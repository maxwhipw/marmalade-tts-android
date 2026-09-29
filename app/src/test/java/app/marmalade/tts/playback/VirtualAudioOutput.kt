package app.marmalade.tts.playback

import app.marmalade.tts.audio.StreamingEffectChain
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * The harness's writer (§5.1): the real [StreamingEffectChain] (live Tempo
 * included) per session, consumed at the output rate in [sliceMs] ticks on
 * the test scheduler's virtual clock.
 *
 * Commands queue in submission order and are applied at the next tick, like
 * the C2 writer thread between slices: stream items join the track's queue,
 * controls act at once. Chunk identity travels as SIDE-BAND frame marks
 * through the chain — a mark sits at the post-chain frame where that chunk's
 * output starts (or ends), so ChunkStarted is measured after Tempo, within
 * one input slice. PCM values are never used as tags (Tempo would mangle them).
 */
class VirtualAudioOutput(
    scope: CoroutineScope,
    private val clock: () -> Long,
    private val sliceMs: Long = 10,
) : AudioOutput {

    /** One audible slice of one track: what the listener hears in [atMs, atMs + sliceMs). */
    data class Heard(
        val atMs: Long,
        val track: Track,
        val tag: ChunkTag?,
        val audibleMs: Double,
        val silentMs: Double,
        /** The heard tag's epoch is below its session's flushed minimum: must never happen. */
        val stale: Boolean,
    )

    val timeline = ArrayList<Heard>()
    val events = ArrayList<Pair<Long, OutputEvent>>()
    val commands = ArrayList<Pair<Long, OutputCommand>>()

    /** Track rebuilds on a sample-rate change, with the time they happened. */
    val rebuilds = ArrayList<Pair<Long, Int>>()

    /** Items dropped because their epoch was below the flushed minimum. */
    var staleDropped = 0
        private set

    private var sink: (OutputEvent) -> Unit = {}
    private val pending = ArrayDeque<OutputCommand>()
    private val tracks = HashMap<Track, TrackState>()

    private class Mark(val frame: Long, val event: OutputEvent)

    private class TrackState {
        var playing = false
        val queue = ArrayDeque<OutputCommand>()
        val minEpoch = HashMap<Long, Int>()
        val tempos = HashMap<Long, Float>()
        var rate = 0
        var chain: StreamingEffectChain? = null
        var chainSession = -1L
        var writeOffset = 0

        /** Post-chain FIFO: produced frames not yet heard. */
        val fifo = ArrayDeque<ShortArray>()
        var fifoHead = 0
        var produced = 0L
        var consumed = 0L
        val marks = ArrayList<Mark>()
        var audible: ChunkTag? = null

        fun available(): Long = produced - consumed

        fun clear() {
            queue.clear()
            chain = null
            chainSession = -1
            writeOffset = 0
            fifo.clear()
            fifoHead = 0
            consumed = produced
            marks.clear()
            audible = null
        }
    }

    init {
        scope.launch {
            while (isActive) {
                tick()
                delay(sliceMs)
            }
        }
    }

    override fun attach(sink: (OutputEvent) -> Unit) {
        this.sink = sink
    }

    override fun submit(command: OutputCommand) {
        commands += clock() to command
        pending.addLast(command)
    }

    /** Deliver an event as if the writer had reported it (stale-event scenarios). */
    fun inject(event: OutputEvent) = sink(event)

    fun isPlaying(track: Track): Boolean = tracks[track]?.playing == true

    /** Output frames queued (pre- and post-chain) — nothing should linger after a stop. */
    fun queuedItems(track: Track): Int = tracks[track]?.let { it.queue.size + it.fifo.size } ?: 0

    private fun tick() {
        while (pending.isNotEmpty()) apply(pending.removeFirst())
        for ((track, t) in tracks.entries.sortedBy { it.key.ordinal }) {
            if (!t.playing) continue
            val rate = if (t.rate > 0) t.rate else 24_000
            val need = (rate * sliceMs / 1000).toLong()
            while (t.available() < need && t.queue.isNotEmpty()) feed(track, t)
            val take = minOf(need, t.available())
            val startTag = t.audible
            consume(track, t, take)
            val audibleMs = take * 1000.0 / rate
            val tag = t.audible ?: startTag
            val stale = take > 0 && tag != null && tag.epoch < (t.minEpoch[tag.sessionId] ?: 0)
            timeline += Heard(clock(), track, tag, audibleMs, sliceMs - audibleMs, stale)
        }
    }

    private fun apply(c: OutputCommand) {
        val t = tracks.getOrPut(c.track) { TrackState() }
        when (c) {
            is OutputCommand.Begin, is OutputCommand.Write, is OutputCommand.End -> {
                if (isStale(t, c)) staleDropped++ else t.queue.addLast(c)
            }
            is OutputCommand.Play -> t.playing = true
            is OutputCommand.Pause -> t.playing = false
            is OutputCommand.Flush -> {
                t.minEpoch[c.sessionId] = maxOf(t.minEpoch[c.sessionId] ?: 0, c.minEpoch)
                t.clear()
            }
            is OutputCommand.SetTempo -> {
                t.tempos[c.sessionId] = c.tempo
                if (t.chainSession == c.sessionId) t.chain?.setLiveTempo(c.tempo)
            }
            is OutputCommand.Release -> {
                t.clear()
                tracks.remove(c.track)
            }
        }
    }

    private fun isStale(t: TrackState, c: OutputCommand): Boolean {
        val (session, epoch) = when (c) {
            is OutputCommand.Begin -> c.sessionId to c.epoch
            is OutputCommand.Write -> c.tag.sessionId to c.tag.epoch
            is OutputCommand.End -> c.sessionId to c.epoch
            else -> return false
        }
        return epoch < (t.minEpoch[session] ?: 0)
    }

    /** Run one input slice (or one stream item) through the chain into the FIFO. */
    private fun feed(track: Track, t: TrackState) {
        when (val item = t.queue.first()) {
            is OutputCommand.Begin -> {
                t.queue.removeFirst()
                if (t.rate != 0 && t.rate != item.sampleRate) rebuilds += clock() to item.sampleRate
                t.rate = item.sampleRate
                val tempo = t.tempos[item.sessionId] ?: item.tempo
                t.tempos[item.sessionId] = tempo
                t.chain = StreamingEffectChain(item.effectBlocks, item.sampleRate, liveTempo = tempo)
                t.chainSession = item.sessionId
            }
            is OutputCommand.Write -> {
                val chain = t.chain ?: StreamingEffectChain(emptyList(), t.rate, liveTempo = 1f).also { t.chain = it }
                val slice = (t.rate * sliceMs / 1000).toInt().coerceAtLeast(1)
                val from = t.writeOffset
                val to = minOf(item.pcm.size, from + slice)
                if (from == 0) t.marks += Mark(t.produced, OutputEvent.ChunkStarted(track, item.tag))
                append(t, chain.process(item.pcm.copyOfRange(from, to)))
                t.writeOffset = to
                if (to >= item.pcm.size) {
                    t.marks += Mark(t.produced, OutputEvent.ChunkFinished(track, item.tag))
                    t.queue.removeFirst()
                    t.writeOffset = 0
                }
            }
            is OutputCommand.End -> {
                t.queue.removeFirst()
                t.chain?.let { append(t, it.flush()) }
                t.chain = null
                t.chainSession = -1
                t.marks += Mark(t.produced, OutputEvent.SessionEnded(track, item.sessionId, item.epoch))
            }
            else -> t.queue.removeFirst()
        }
    }

    private fun append(t: TrackState, pcm: ShortArray) {
        if (pcm.isEmpty()) return
        t.fifo.addLast(pcm)
        t.produced += pcm.size
    }

    private fun consume(track: Track, t: TrackState, frames: Long) {
        var left = frames
        while (left > 0 && t.fifo.isNotEmpty()) {
            val head = t.fifo.first()
            val n = minOf(left, (head.size - t.fifoHead).toLong()).toInt()
            t.fifoHead += n
            left -= n
            if (t.fifoHead >= head.size) {
                t.fifo.removeFirst()
                t.fifoHead = 0
            }
        }
        t.consumed += frames - left
        // A start mark fires once a frame at or after it is heard; an end mark
        // once every frame before it is.
        val due = t.marks.filter {
            when (it.event) {
                is OutputEvent.ChunkStarted -> it.frame < t.consumed
                else -> it.frame <= t.consumed
            }
        }
        if (due.isEmpty()) return
        t.marks.removeAll(due.toSet())
        for (m in due) {
            if (m.event is OutputEvent.ChunkStarted) t.audible = (m.event as OutputEvent.ChunkStarted).tag
            events += clock() to m.event
            sink(m.event)
        }
    }
}
