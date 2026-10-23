package app.marmalade.tts.service

import android.content.Context
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.SystemClock
import android.util.Log
import app.marmalade.tts.playback.AudioOutput
import app.marmalade.tts.playback.ChunkTag
import app.marmalade.tts.playback.OutputCommand
import app.marmalade.tts.playback.OutputEvent
import app.marmalade.tts.playback.PcmTrack
import app.marmalade.tts.playback.PcmTrackFactory
import app.marmalade.tts.playback.Track
import app.marmalade.tts.playback.TrackWriter
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The Narrator's audio sink on the device (step C2): ONE writer thread,
 * `narrator-writer`, is the only caller of the app's in-app AudioTracks. It
 * runs a [TrackWriter] per [Track] (the article/one-shot track and the
 * interrupt track — at most two AudioTracks) and applies submitted commands
 * between their slices. [submit] never blocks.
 *
 * Playback is continuous: one track for every block of a session, and across
 * sessions at the same rate — no drain, rebuild or chain restart at a
 * paragraph. See [TrackWriter] for the rules (epochs, per-session chains, the
 * padded tail, the parked-track watchdog).
 *
 * The thread sleeps while nothing can change (paused, idle); every heard
 * chunk re-arms the wake lock.
 */
@Singleton
class AudioTrackOutput internal constructor(
    private val onChunkHeard: () -> Unit,
    factory: PcmTrackFactory,
) : AudioOutput {

    @Inject constructor(wakeLock: NarratorWakeLock, @ApplicationContext context: Context) :
        this(wakeLock::onChunk, PcmTrackFactory { NarratorCapture.wrap(AndroidPcmTrack(it), context.filesDir) })

    @Volatile private var sink: (OutputEvent) -> Unit = {}

    private val inbox = LinkedBlockingQueue<OutputCommand>()

    private val writers: Map<Track, TrackWriter> = Track.entries.associateWith { track ->
        TrackWriter(
            track = track,
            factory = factory,
            clock = SystemClock::elapsedRealtime,
            emit = ::emit,
            log = { Log.d(TAG, it) },
        )
    }

    private val thread: Thread by lazy {
        Thread(::loop, "narrator-writer").apply {
            isDaemon = true
            start()
        }
    }

    override fun attach(sink: (OutputEvent) -> Unit) {
        this.sink = sink
    }

    override fun submit(command: OutputCommand) {
        inbox.put(command)
        thread // started on first use
    }

    private fun loop() {
        var wait = TrackWriter.UNTIL_COMMAND
        while (true) {
            var c = when (wait) {
                0L -> inbox.poll()
                TrackWriter.UNTIL_COMMAND -> inbox.take()
                else -> inbox.poll(wait, TimeUnit.MILLISECONDS)
            }
            while (c != null) {
                val command = c
                guarded(command.track) { apply(command) }
                c = inbox.poll()
            }
            wait = TrackWriter.UNTIL_COMMAND
            for ((track, w) in writers) wait = minOf(wait, guarded(track) { step() } ?: 0L)
        }
    }

    /** Never let the writer die — the Narrator would wait for events that can't come. Fail what was playing and go on. */
    private inline fun <T> guarded(track: Track, block: TrackWriter.() -> T): T? {
        val w = writers.getValue(track)
        return try {
            w.block()
        } catch (t: Throwable) {
            Log.e(TAG, "$track writer error", t)
            try {
                w.failTrack(t.message ?: t.javaClass.simpleName)
            } catch (t2: Throwable) {
                Log.e(TAG, "$track writer could not fail its track", t2)
            }
            null
        }
    }

    private fun emit(event: OutputEvent) {
        sink(event)
        if (event is OutputEvent.ChunkStarted) onChunkHeard()
    }

    companion object {
        private const val TAG = "AudioTrackOutput"

        /**
         * ~250 ms of 16-bit mono PCM, in bytes, as a whole number of frames.
         * AudioTrack rejects a buffer size that isn't a multiple of the frame
         * size ("Invalid audio buffer size"), and `sampleRate * 2 / 4` is odd
         * at 22 050 Hz — the rate of the medium/high VITS packs.
         */
        internal fun quarterSecondBufferBytes(sampleRate: Int): Int =
            (sampleRate / 4) * BYTES_PER_FRAME_PCM16_MONO

        private const val BYTES_PER_FRAME_PCM16_MONO = 2
    }
}

/**
 * Android's AudioTrack as a [PcmTrack]: mono PCM16 in streaming mode, ~250 ms
 * of buffer (small enough that pause and speed changes feel immediate), the
 * speech attributes, non-blocking writes. Throws from the constructor when
 * the device refuses the format.
 */
internal class AndroidPcmTrack(override val sampleRate: Int) : PcmTrack {

    private val track: AudioTrack = AudioTrack.Builder()
        .setAudioAttributes(AudioFocusPort.SPEECH_ATTRIBUTES)
        .setAudioFormat(
            AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(sampleRate)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build(),
        )
        .setBufferSizeInBytes(
            AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
                .coerceAtLeast(AudioTrackOutput.quarterSecondBufferBytes(sampleRate)),
        )
        .setTransferMode(AudioTrack.MODE_STREAM)
        .build()

    override val capacityFrames: Int = track.bufferCapacityInFrames

    /** The 32-bit head, unwrapped: frames played since built or flushed. */
    private var lastRaw = 0L
    private var head = 0L

    override fun write(pcm: ShortArray, offset: Int, size: Int, tag: ChunkTag?): Int =
        track.write(pcm, offset, size, AudioTrack.WRITE_NON_BLOCKING)

    override fun head(): Long {
        val raw = track.playbackHeadPosition.toLong() and 0xFFFF_FFFFL
        val delta = (raw - lastRaw) and 0xFFFF_FFFFL
        // A small retrograde step (a timestamp correction) reads as a huge
        // forward one: ignore it rather than jump.
        if (delta < 0x8000_0000L) {
            head += delta
            lastRaw = raw
        }
        return head
    }

    override fun play() = track.play()

    override fun pause() = track.pause()

    override fun flush() {
        track.flush()
        lastRaw = 0
        head = 0
    }

    override fun release() = track.release()
}
