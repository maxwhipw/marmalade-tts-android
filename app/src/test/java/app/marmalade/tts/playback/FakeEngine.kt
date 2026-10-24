package app.marmalade.tts.playback

import app.marmalade.tts.engine.EngineNotInstalledException
import app.marmalade.tts.engine.LivePlaybackRate
import app.marmalade.tts.engine.SentenceGaps
import app.marmalade.tts.engine.SynthAudio
import app.marmalade.tts.engine.TtsEngine
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * A scriptable engine on the virtual clock (§5.1). Text is cut into sentence
 * chunks; each costs `fixedMsPerChunk + tokens × msPerToken × rtfScale(now)`
 * of render time and yields `tokens × audioMsPerToken` of audio. A chunk over
 * [tokenCap] is re-split and every piece emitted as its own chunk, like
 * Kokoro's over-cap path.
 *
 * Models the two engine behaviours the Narrator must survive:
 *  - a **[synthLock]** held per chunk (Kokoro-like) or for the whole
 *    utterance (Pocket-like, `PocketEngine.kt:682`), shareable with a fake
 *    system-TTS caller;
 *  - a **non-abortable** mode: a chunk in flight ignores cancellation until
 *    it ends (the pre-9c941cb class — ORT ran the abandoned chunk to the end).
 */
class FakeEngine(
    override val engineName: String,
    private val clock: () -> Long,
    override val sampleRate: Int = 24_000,
) : TtsEngine {

    enum class LockMode { PerChunk, WholeUtterance }

    data class Render(
        val text: String,
        val chunk: Int,
        val startMs: Long,
        var endMs: Long = -1,
        var aborted: Boolean = false,
    )

    var msPerToken = 24.0
    var fixedMsPerChunk = 0.0
    var audioMsPerToken = 55.0
    var tokensPerChar = 1.1
    var tokenCap = 500
    var rtfScale: (nowMs: Long) -> Double = { 1.0 }
    var abortable = true
    var lockMode = LockMode.PerChunk
    var installed = true
    var failWhen: (text: String) -> Throwable? = { null }

    /**
     * Render ahead into an internal buffer like the real engines' channelFlow
     * (64 chunks): while the collector waits, chunks keep rendering, so the
     * one collected after a wait arrives at once.
     */
    var internalBuffer = false

    /**
     * Emit each sentence chunk as this many slices, like the streaming
     * engines (KittenDirect, KokoroDirect streaming): only the first
     * slice of a chunk is a [SynthAudio.resumePoint]. Render time is spread
     * evenly over the slices.
     */
    var slicesPerChunk = 1
    val synthLock = Mutex()

    /** Share of each slice's render reported as [SynthAudio.extraRenderMs] (chunk mode's extra calls). */
    var extraRenderShare = 0.0

    /** The live playback rate ([LivePlaybackRate]) each slice saw, in order; null without one. */
    val liveRates = ArrayList<Float?>()

    val renders = ArrayList<Render>()

    /** Most chunks rendering at the same instant (must stay ≤ 1). */
    var maxConcurrent = 0
        private set
    private var concurrent = 0

    /** Every voiceId/language/speed the engine was asked for, in order. */
    val requests = ArrayList<Triple<String, String?, Float>>()

    /** The expressiveness of each request, in the same order as [requests]. */
    val expressivenessRequests = ArrayList<Float?>()

    /** Each render's pause between sentences, in request order (#96). */
    val sentencePauseRequests = ArrayList<Int>()

    fun rendersOf(text: String): List<Render> = renders.filter { it.text == text }

    fun completedRendersOf(text: String): List<Render> = renders.filter { it.text == text && !it.aborted }

    override fun isInstalled(): Boolean = installed
    override fun isLoaded(): Boolean = true
    override fun ensureModelLoaded() {
        if (!installed) throw EngineNotInstalledException(engineName)
    }
    override fun release() = Unit

    override suspend fun synthesize(
        text: String,
        voiceId: String,
        speed: Float,
        phonemizationLanguage: String?,
        expressiveness: Float?,
        sentencePauseMs: Int,
    ): SynthAudio = throw UnsupportedOperationException("stream only")

    override fun synthesizeStream(
        text: String,
        voiceId: String,
        speed: Float,
        phonemizationLanguage: String?,
        playbackRate: Float,
        expressiveness: Float?,
        sentencePauseMs: Int,
    ): Flow<SynthAudio> = stream(text, voiceId, speed, phonemizationLanguage, sentencePauseMs)
        .onStart {
            expressivenessRequests += expressiveness
            sentencePauseRequests += sentencePauseMs
        }
        // Default capacity (64), like the engines' channelFlow, so the
        // renderer's own buffer(n) fuses with it the same way.
        .let { if (internalBuffer) it.buffer() else it }

    /** Like Pocket: [sentencePauseMs] of added silence after each sentence chunk but the last (#96). */
    private fun stream(text: String, voiceId: String, speed: Float, phonemizationLanguage: String?, sentencePauseMs: Int = 0) = flow {
        requests += Triple(voiceId, phonemizationLanguage, speed)
        val pieces = chunksOf(text)
        if (lockMode == LockMode.WholeUtterance) {
            synthLock.withLock { for (p in pieces) for (i in 0 until slicesPerChunk) emit(renderOne(p, text, i)) }
        } else {
            for ((pi, p) in pieces.withIndex()) {
                val pause = if (sentencePauseMs > 0 && pi < pieces.lastIndex) SentenceGaps.samples(sentencePauseMs, sampleRate) else 0
                for (i in 0 until slicesPerChunk) {
                    val audio = synthLock.withLock { renderOne(p, text, i) }
                    emit(audio)
                }
                if (pause > 0) emit(SentenceGaps.silence(sentencePauseMs, sampleRate))
            }
        }
    }

    /** Sentence chunks, each re-split while over the token cap (nothing dropped). */
    fun chunksOf(text: String): List<String> {
        val sentences = text.split(Regex("(?<=[.!?。！？])\\s*")).map { it.trim() }.filter { it.isNotEmpty() }
        val out = ArrayList<String>()
        for (s in sentences) splitToCap(s, out)
        return out
    }

    private fun splitToCap(s: String, out: MutableList<String>) {
        if (tokens(s) <= tokenCap || s.length < 2) {
            out += s
            return
        }
        val mid = s.length / 2
        splitToCap(s.substring(0, mid), out)
        splitToCap(s.substring(mid), out)
    }

    private fun tokens(s: String): Int = (s.length * tokensPerChar).roundToInt().coerceAtLeast(1)

    /** One slice ([slice] of [slicesPerChunk]) of [piece]. */
    private suspend fun renderOne(piece: String, whole: String, slice: Int): SynthAudio {
        failWhen(whole)?.let { throw it }
        val t = tokens(piece)
        val renderMs = ((fixedMsPerChunk + t * msPerToken) * rtfScale(clock()) / slicesPerChunk).roundToInt().toLong()
        val r = Render(whole, renders.count { it.text == whole }, clock())
        renders += r
        concurrent++
        maxConcurrent = maxOf(maxConcurrent, concurrent)
        try {
            if (abortable) delay(renderMs) else withContext(NonCancellable) { delay(renderMs) }
            currentCoroutineContext().ensureActive()
        } catch (e: CancellationException) {
            r.aborted = true
            throw e
        } finally {
            concurrent--
            r.endMs = clock()
        }
        liveRates += currentCoroutineContext()[LivePlaybackRate]?.rate?.value
        val samples = (t * audioMsPerToken * sampleRate / 1000 / slicesPerChunk).roundToInt()
        return SynthAudio(
            ShortArray(samples) { (it % 200 - 100).toShort() }, sampleRate, resumePoint = slice == 0,
            extraRenderMs = (renderMs * extraRenderShare).roundToInt().toLong(),
        )
    }
}
