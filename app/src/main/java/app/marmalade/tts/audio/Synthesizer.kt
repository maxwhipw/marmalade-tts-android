package app.marmalade.tts.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import app.marmalade.tts.data.KittenMiniVoiceCatalog
import app.marmalade.tts.data.KittenNanoVoiceCatalog
import app.marmalade.tts.data.KokoroV10VoiceCatalog
import app.marmalade.tts.data.KokoroV11VoiceCatalog
import app.marmalade.tts.data.PocketDevVoiceCatalog
import app.marmalade.tts.data.PocketEtVoiceCatalog
import app.marmalade.tts.data.PocketVoiceCatalog
import app.marmalade.tts.data.SettingsRepository
import app.marmalade.tts.engine.KittenMiniEngine
import app.marmalade.tts.engine.KittenNanoEngine
import app.marmalade.tts.engine.KokoroV10Engine
import app.marmalade.tts.engine.KokoroV11Engine
import app.marmalade.tts.engine.PocketDevEngine
import app.marmalade.tts.engine.PocketEngine
import app.marmalade.tts.engine.PocketExecuTorchDevEngine
import app.marmalade.tts.engine.kitten.KittenDirectEngine
import app.marmalade.tts.engine.kitten.KittenDirectMiniEngine
import app.marmalade.tts.engine.kokoro.KokoroDirectEngine
import app.marmalade.tts.data.KittenDirectVoiceCatalog
import app.marmalade.tts.data.KittenDirectMiniVoiceCatalog
import app.marmalade.tts.data.KokoroDirectVoiceCatalog
import app.marmalade.tts.engine.SynthAudio
import app.marmalade.tts.engine.TtsEngine
import app.marmalade.tts.preprocessing.EmojiProsody
import app.marmalade.tts.preprocessing.Emotion
import app.marmalade.tts.preprocessing.Preprocessor
import app.marmalade.tts.preprocessing.ProsodyApplier
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// -----------------------------------------------------------------------------
// Data flow
// -----------------------------------------------------------------------------
//   ViewModel.speak(text, voiceId, speed, effectBlocks)
//     │
//     ▼
//   Synthesizer.speak(text, voiceId, speed, effectBlocks)
//     │
//     ├── engineNameFor(voiceId) ──► "kokoro" | "kitten"
//     │     (split on ':' — voice IDs use "<engine>:<voiceName>")
//     │
//     ├── settings.enabledRules(engineName).first() ──► Set<String>
//     ├── Preprocessor.apply(text, enabled)         ──► normalized text
//     │     (HTML decode, currency, numbers-to-words, etc. per user's
//     │      Settings → Text preprocessing toggles)
//     │
//     ├── synthesizeForEngine(engineName, ...)
//     │     ├── "kokoro" → KokoroEngine.synthesize(...) ──► SynthAudio
//     │     └── "kitten" → KittenEngine.synthesize(...) ──► SynthAudio
//     │     (both CPU-bound; engines hop onto Dispatchers.Default)
//     │
//     ├── EffectChain.applyChain(pcm, sampleRate, effectBlocks)
//     │     (pure-Kotlin DSP; an empty chain returns the input unchanged)
//     │
//     ├── build AudioTrack (PCM_16BIT, mono, sampleRate)
//     │
//     ├── AudioTrack.play()
//     │
//     ├── AudioTrack.write(pcm)                  ── blocks until queued
//     │
//     ├── busy-wait for playback head to catch up to write head
//     │     (so callers know when audio actually finishes, not just queued)
//     │
//     └── stop + release AudioTrack
//
//   Errors:
//     UnsupportedOperationException (engine assets missing)
//        ──► Result.failure(SynthesizerException.ModelMissing)
//     Anything else
//        ──► Result.failure(SynthesizerException.SynthesisFailed(cause))
// -----------------------------------------------------------------------------

/** Typed errors so callers can switch on the failure mode without parsing strings. */
sealed class SynthesizerException(message: String, cause: Throwable? = null) :
    Exception(message, cause) {

    /** Engine assets haven't been downloaded yet. UI routes user to Engines screen. */
    object ModelMissing : SynthesizerException("TTS engine not installed")

    /** Anything else — JNI failure, invalid voice ID, AudioTrack init, etc. */
    class SynthesisFailed(cause: Throwable) :
        SynthesizerException(cause.message ?: "synthesis failed", cause)
}

/**
 * Surface area that the ViewModels actually consume from [Synthesizer].
 *
 * Extracted so JVM unit tests can substitute a fake without standing up
 * the real [KittenEngine] (which needs an Android Context for asset
 * access and a Sherpa-ONNX JNI handle).
 */
interface SpeechPlayer {
    /**
     * Synthesize [text] using [voiceId], optionally rate-scaled by [speed]
     * (1.0 = native pace) and shaped by the [effectBlocks] chain (empty =
     * dry). Returns when audio has finished playing (or been cancelled).
     *
     * Defaults are provided so existing callers that don't care about
     * speed / effect can keep calling `speak(text, voiceId)`.
     */
    suspend fun speak(
        text: String,
        voiceId: String,
        speed: Float = 1.0f,
        effectBlocks: List<EffectBlock> = emptyList(),
        /**
         * Optional espeak language override (e.g. `"en-us"`, `"ja"`,
         * `"cmn"`). When null, KokoroDirect picks the voice's natural
         * language; KittenDirect stays on its load-time default. Other
         * engines ignore.
         */
        phonemizationLanguage: String? = null,
    ): Result<Unit>
    fun cancel()

    /**
     * Eagerly load the engine that backs [voiceId] so a subsequent
     * [speak] call doesn't pay model-load + warmup latency on the user's
     * critical path. Idempotent + non-throwing — a failure logs and
     * returns; the first real `speak` will surface the error.
     *
     * The Speak screen calls this on voice-selection so navigating to
     * Speak with voice X selected starts a background pre-load.
     */
    suspend fun preload(voiceId: String)
}

/**
 * Single playback pipeline shared by both ViewModels.
 *
 * Why a separate class instead of inlining into each ViewModel:
 * - The "synthesise → play via AudioTrack → wait for playback to drain"
 *   sequence is identical for the main Speak button and the voice-picker
 *   Preview button. Bug fixes need to land in one place.
 * - The TTS engine itself is a `@Singleton`, so it's natural to colocate
 *   the audio I/O around it in another `@Singleton`.
 *
 * Thread model:
 * - [speak] is `suspend`; it runs synthesis on `Dispatchers.Default` (via
 *   the engine) and AudioTrack I/O on `Dispatchers.IO`. Returns when
 *   playback has fully drained — not just when the buffer is queued.
 * - [cancel] flips a volatile flag and stops the active AudioTrack. Safe
 *   from any thread.
 *
 * v0.1 design choice: one playback at a time. If a second speak() comes in
 * while the first is active, the caller should call cancel() first. We
 * don't queue; we don't pre-empt; the UI gates the button instead.
 */
@Singleton
class Synthesizer @Inject constructor(
    private val kittenNano: KittenNanoEngine,
    private val kittenMini: KittenMiniEngine,
    private val kittenDirect: KittenDirectEngine,
    private val kittenDirectMini: KittenDirectMiniEngine,
    private val kokoroV10: KokoroV10Engine,
    private val kokoroV11: KokoroV11Engine,
    private val kokoroDirect: KokoroDirectEngine,
    private val pocket: PocketEngine,
    private val pocketDev: PocketDevEngine,
    private val pocketEt: PocketExecuTorchDevEngine,
    private val preprocessor: Preprocessor,
    private val settings: SettingsRepository,
    private val keepaliveCoordinator: app.marmalade.tts.service.KeepaliveCoordinator,
) : SpeechPlayer {

    @Volatile
    private var currentTrack: AudioTrack? = null

    @Volatile
    private var cancelled: Boolean = false

    /**
     * Job of the in-flight [speak] call's coroutineScope. Captured so
     * [cancel] can interrupt the streaming Flow's upstream (the engine's
     * AR loop). Plain `cancelled` flag was sufficient for the
     * non-streaming path because `playPcm`'s tight loop polled it; the
     * streaming flow suspends inside `OrtSession.run` for ~100 ms at a
     * time, so we need real coroutine cancellation to break it
     * promptly.
     */
    @Volatile
    private var currentJob: Job? = null

    /**
     * Synthesize [text] using [voiceId], play it, and return when playback
     * has fully drained.
     *
     * Returns:
     *   Result.success(Unit)              — played to completion (or cancelled)
     *   Result.failure(ModelMissing)      — engine assets not yet bundled
     *   Result.failure(SynthesisFailed)   — anything else
     */
    override suspend fun speak(
        text: String,
        voiceId: String,
        speed: Float,
        effectBlocks: List<EffectBlock>,
        phonemizationLanguage: String?,
    ): Result<Unit> = coroutineScope {
        cancelled = false
        currentJob = coroutineContext[Job]
        // P-K — refresh keepalive while the UI is still in the foreground.
        // Doing this BEFORE starting the (possibly long) synth means the
        // FGS start hits Android's "started from foreground" window even
        // if the user backgrounds the app mid-synth.
        keepaliveCoordinator.onSynthCompleted()
        try {
            val engineName = engineNameFor(voiceId)
            val enabled = settings.enabledRules(engineName).first()

            // Streaming eligibility. Effects now run ON the streaming path:
            // [StreamingEffectChain] carries each block's DSP state (reverb
            // ring buffers, filter memory, LFO phase) across chunk seams, so an
            // effect no longer forfeits the time-to-first-audio win. The only
            // remaining batched trigger is non-neutral emotion — ProsodyApplier
            // shapes the whole PCM and has no streaming form yet.
            val hint = EmojiProsody.detect(text)
            val canStream = hint.emotion == Emotion.Neutral

            if (canStream) {
                speakStreaming(text, voiceId, speed, effectBlocks, engineName, enabled, phonemizationLanguage)
            } else {
                speakBatched(text, voiceId, speed, effectBlocks, engineName, enabled, phonemizationLanguage)
            }
        } finally {
            currentJob = null
        }
    }

    /**
     * Streaming path. Pre-processes input, then hands the full text to
     * the engine's `synthesizeStream` Flow — engines are responsible
     * for their own input-length handling (sherpa-onnx splits per
     * sentence internally via `generateWithCallback`; Pocket chunks via
     * its internal `TextChunker` call inside `synthesizeStream`).
     *
     * Pipelining between producer (synth) and consumer (AudioTrack)
     * runs through a 2-slot `Channel<SynthAudio>`:
     *
     *   producer coroutine ──► Channel<SynthAudio> (capacity 2) ──► consumer
     *      ▲                                                          │
     *      │ synth chunk N+1 while chunk N plays                     │
     *      │ blocks on send() when channel is full                   │
     *      └──── backpressure ────────────────────────────────────────┘
     *
     * Channel capacity 2 keeps RAM bounded while giving the producer
     * a one-chunk head start: by the time chunk N finishes playing,
     * chunk N+1 is already in the channel waiting.
     *
     * Cancellation propagates via the captured Job: cancel() cancels
     * the producer + consumer, the channel closes, AudioTrack stops.
     */
    private suspend fun speakStreaming(
        text: String,
        voiceId: String,
        speed: Float,
        effectBlocks: List<EffectBlock>,
        engineName: String,
        enabledRules: Set<String>,
        phonemizationLanguage: String? = null,
    ): Result<Unit> {
        if (text.isBlank()) return Result.success(Unit)
        val preprocessed = preprocessor.apply(text, enabledRules)
        val stripped = EmojiProsody.stripEmojis(preprocessed)
        if (stripped.isBlank()) return Result.success(Unit)
        Log.d(TAG, "Streaming: $engineName (${stripped.length} chars, ${effectBlocks.size} effect blocks)")

        return try {
            coroutineScope {
                val audioChannel = Channel<SynthAudio>(capacity = 2)

                // Producer: engine emits audio chunks → effect chain → channel.
                // The chain is built lazily on the first chunk (we need its
                // sampleRate for filter coefficients / reverb delays). An empty
                // chain is a pass-through, so the dry path adds no work.
                val producer = launch(Dispatchers.Default) {
                    try {
                        var chain: StreamingEffectChain? = null
                        var sr = 0
                        streamForEngine(engineName, stripped, voiceId, speed, phonemizationLanguage)
                            .collect { audio ->
                                val c = chain ?: StreamingEffectChain(effectBlocks, audio.sampleRate)
                                    .also { chain = it; sr = audio.sampleRate }
                                audioChannel.send(SynthAudio(c.process(audio.pcm), audio.sampleRate))
                            }
                        // Drain the effect tail (e.g. reverb ring-out) as a final chunk.
                        chain?.flush()?.takeIf { it.isNotEmpty() }?.let { tail ->
                            audioChannel.send(SynthAudio(tail, sr))
                        }
                    } finally {
                        audioChannel.close()
                    }
                }

                // Consumer: AudioTrack writes (runs in parallel with producer).
                playFromChannel(audioChannel)
                producer.join()
            }
            Result.success(Unit)
        } catch (_: CancellationException) {
            Result.success(Unit)
        } catch (_: UnsupportedOperationException) {
            Result.failure(SynthesizerException.ModelMissing)
        } catch (t: Throwable) {
            Log.w(TAG, "Streaming synthesis failed", t)
            Result.failure(SynthesizerException.SynthesisFailed(t))
        }
    }

    /**
     * Batched path. Used when post-synth shaping is requested
     * (a non-empty effect chain or prosody emotion ≠ Neutral).
     *
     * Collects the engine's stream (engines that need chunking handle
     * it internally — see `PocketEngine.synthesizeStream` and
     * `SherpaEngine.synthesizeStream`). All emitted chunks are
     * concatenated into one PCM buffer, then shaping runs on the
     * combined buffer. That keeps reverb tails / vibrato phase
     * continuous across natural chunk boundaries — applying these
     * per-chunk would clip the tail and reset oscillator phase between
     * chunks.
     *
     * No pipelining: shaping needs the full PCM before playback
     * starts. Cost: zero parallelism on the effect path. Acceptable
     * trade — the common case is the empty (dry) chain which goes
     * through speakStreaming.
     */
    private suspend fun speakBatched(
        text: String,
        voiceId: String,
        speed: Float,
        effectBlocks: List<EffectBlock>,
        engineName: String,
        enabledRules: Set<String>,
        phonemizationLanguage: String? = null,
    ): Result<Unit> {
        if (text.isBlank()) return Result.success(Unit)

        val hint = EmojiProsody.detect(text)
        val preprocessed = preprocessor.apply(text, enabledRules)
        val stripped = EmojiProsody.stripEmojis(preprocessed)
        if (stripped.isBlank()) return Result.success(Unit)
        Log.d(TAG, "Batched: $engineName (${stripped.length} chars, ${effectBlocks.size} effect blocks, emotion=${hint.emotion})")

        // Collect the engine's stream into one PCM buffer. Engines
        // self-chunk; we just concat their emissions.
        val pieces = ArrayList<ShortArray>()
        var sampleRate = 0
        try {
            streamForEngine(engineName, stripped, voiceId, speed).collect { audio ->
                if (cancelled) throw CancellationException("user cancel")
                pieces.add(audio.pcm)
                sampleRate = audio.sampleRate
            }
        } catch (_: CancellationException) {
            return Result.success(Unit)
        } catch (_: UnsupportedOperationException) {
            return Result.failure(SynthesizerException.ModelMissing)
        } catch (t: Throwable) {
            Log.w(TAG, "Synthesis failed", t)
            return Result.failure(SynthesizerException.SynthesisFailed(t))
        }
        if (pieces.isEmpty()) return Result.success(Unit)

        // Concat raw PCM.
        val totalLen = pieces.sumOf { it.size }
        val combined = ShortArray(totalLen)
        var pos = 0
        for (p in pieces) {
            System.arraycopy(p, 0, combined, pos, p.size)
            pos += p.size
        }

        val emotionShaped = ProsodyApplier.apply(combined, sampleRate, hint.emotion)
        val final = EffectChain.applyChain(emotionShaped, sampleRate, effectBlocks)

        return try {
            playPcm(final, sampleRate)
            Result.success(Unit)
        } catch (_: CancellationException) {
            Result.success(Unit)
        } catch (t: Throwable) {
            Log.w(TAG, "Playback failed", t)
            Result.failure(SynthesizerException.SynthesisFailed(t))
        }
    }

    /**
     * Stop any in-flight playback. Safe to call from any thread and any
     * state — no-op if nothing is playing.
     *
     * For the streaming path: also cancels the engine-side AR loop via
     * the captured coroutine job. Without this, the loop would keep
     * generating frames into a Flow that nobody's collecting, holding
     * the engine's synthLock until natural completion.
     */
    override suspend fun preload(voiceId: String): Unit = withContext(Dispatchers.IO) {
        try {
            engineFor(engineNameFor(voiceId)).ensureModelLoaded()
        } catch (t: Throwable) {
            // Pre-load is best-effort. ModelMissing is the common case
            // (user hasn't downloaded the engine yet) — surface only on
            // explicit speak().
            Log.d(TAG, "preload($voiceId) skipped: ${t.message}")
        }
    }

    override fun cancel() {
        cancelled = true
        currentJob?.cancel()
        val track = currentTrack ?: return
        try {
            if (track.playState != AudioTrack.PLAYSTATE_STOPPED) {
                track.pause()
                track.flush()
                track.stop()
            }
        } catch (_: IllegalStateException) {
            // Track already in a terminal state — nothing to do.
        }
    }

    // -- private --------------------------------------------------------------

    /**
     * Engine name embedded in [voiceId] (everything before the first `:`).
     * Falls back to the Kokoro v1.0 default for malformed inputs.
     */
    private fun engineNameFor(voiceId: String): String {
        val sep = voiceId.indexOf(':')
        if (sep <= 0) return KokoroV10VoiceCatalog.ENGINE
        val name = voiceId.substring(0, sep)
        return when (name) {
            KokoroV10VoiceCatalog.ENGINE,
            KokoroV11VoiceCatalog.ENGINE,
            KokoroDirectVoiceCatalog.ENGINE,
            KittenNanoVoiceCatalog.ENGINE,
            KittenMiniVoiceCatalog.ENGINE,
            KittenDirectVoiceCatalog.ENGINE,
            KittenDirectMiniVoiceCatalog.ENGINE,
            PocketVoiceCatalog.ENGINE,
            PocketDevVoiceCatalog.ENGINE,
            PocketEtVoiceCatalog.ENGINE -> name
            else -> KokoroV10VoiceCatalog.ENGINE
        }
    }

    /** TtsEngine handle for an engine name. Used for the maxInputChars lookup. */
    private fun engineFor(engineName: String): TtsEngine = when (engineName) {
        KokoroV10VoiceCatalog.ENGINE -> kokoroV10
        KokoroV11VoiceCatalog.ENGINE -> kokoroV11
        KokoroDirectVoiceCatalog.ENGINE -> kokoroDirect
        KittenNanoVoiceCatalog.ENGINE -> kittenNano
        KittenMiniVoiceCatalog.ENGINE -> kittenMini
        KittenDirectVoiceCatalog.ENGINE -> kittenDirect
        KittenDirectMiniVoiceCatalog.ENGINE -> kittenDirectMini
        PocketVoiceCatalog.ENGINE -> pocket
        PocketDevVoiceCatalog.ENGINE -> pocketDev
        PocketEtVoiceCatalog.ENGINE -> pocketEt
        else -> kokoroV10
    }

    /** Route synthesis to the right engine handle. */
    private suspend fun synthesizeForEngine(
        engineName: String,
        text: String,
        voiceId: String,
        speed: Float,
        phonemizationLanguage: String? = null,
    ): SynthAudio = when (engineName) {
        KokoroV10VoiceCatalog.ENGINE -> kokoroV10.synthesize(text, voiceId, speed, phonemizationLanguage)
        KokoroV11VoiceCatalog.ENGINE -> kokoroV11.synthesize(text, voiceId, speed, phonemizationLanguage)
        KokoroDirectVoiceCatalog.ENGINE -> kokoroDirect.synthesize(text, voiceId, speed, phonemizationLanguage)
        KittenNanoVoiceCatalog.ENGINE -> kittenNano.synthesize(text, voiceId, speed, phonemizationLanguage)
        KittenMiniVoiceCatalog.ENGINE -> kittenMini.synthesize(text, voiceId, speed, phonemizationLanguage)
        KittenDirectVoiceCatalog.ENGINE -> kittenDirect.synthesize(text, voiceId, speed, phonemizationLanguage)
        KittenDirectMiniVoiceCatalog.ENGINE -> kittenDirectMini.synthesize(text, voiceId, speed, phonemizationLanguage)
        PocketVoiceCatalog.ENGINE -> pocket.synthesize(text, voiceId, speed, phonemizationLanguage)
        PocketDevVoiceCatalog.ENGINE -> pocketDev.synthesize(text, voiceId, speed, phonemizationLanguage)
        PocketEtVoiceCatalog.ENGINE -> pocketEt.synthesize(text, voiceId, speed, phonemizationLanguage)
        // Defensive: engineNameFor already narrows to known values, but
        // the exhaustive `when` keeps the compiler honest.
        else -> kokoroV10.synthesize(text, voiceId, speed, phonemizationLanguage)
    }

    /**
     * Streaming dispatch counterpart to [synthesizeForEngine]. Returns
     * a Flow that emits one or more SynthAudio chunks. Pocket overrides
     * the default to produce multiple chunks (time-to-first-audio win);
     * sherpa engines inherit the default single-element flow.
     */
    private fun streamForEngine(
        engineName: String,
        text: String,
        voiceId: String,
        speed: Float,
        phonemizationLanguage: String? = null,
    ): Flow<SynthAudio> = when (engineName) {
        KokoroV10VoiceCatalog.ENGINE -> kokoroV10.synthesizeStream(text, voiceId, speed, phonemizationLanguage)
        KokoroV11VoiceCatalog.ENGINE -> kokoroV11.synthesizeStream(text, voiceId, speed, phonemizationLanguage)
        KokoroDirectVoiceCatalog.ENGINE -> kokoroDirect.synthesizeStream(text, voiceId, speed, phonemizationLanguage)
        KittenNanoVoiceCatalog.ENGINE -> kittenNano.synthesizeStream(text, voiceId, speed, phonemizationLanguage)
        KittenMiniVoiceCatalog.ENGINE -> kittenMini.synthesizeStream(text, voiceId, speed, phonemizationLanguage)
        KittenDirectVoiceCatalog.ENGINE -> kittenDirect.synthesizeStream(text, voiceId, speed, phonemizationLanguage)
        KittenDirectMiniVoiceCatalog.ENGINE -> kittenDirectMini.synthesizeStream(text, voiceId, speed, phonemizationLanguage)
        PocketVoiceCatalog.ENGINE -> pocket.synthesizeStream(text, voiceId, speed, phonemizationLanguage)
        PocketDevVoiceCatalog.ENGINE -> pocketDev.synthesizeStream(text, voiceId, speed, phonemizationLanguage)
        PocketEtVoiceCatalog.ENGINE -> pocketEt.synthesizeStream(text, voiceId, speed, phonemizationLanguage)
        else -> kokoroV10.synthesizeStream(text, voiceId, speed, phonemizationLanguage)
    }

    /**
     * Consume audio chunks from a [ReceiveChannel], allocating an
     * AudioTrack on the first chunk (we need the chunk's sampleRate to
     * configure it) and writing each subsequent chunk into the same
     * track. AudioTrack MODE_STREAM handles seam-free playback across
     * writes.
     *
     * Channel-based (rather than Flow-based) so the producer side can
     * run on its own coroutine — natural backpressure when the channel
     * fills, natural producer-pause when AudioTrack's write blocks.
     *
     * Drains after the channel closes — returns when the playback head
     * catches the write head. Same drain pattern as [playPcm].
     */
    private suspend fun playFromChannel(channel: ReceiveChannel<SynthAudio>) =
        withContext(Dispatchers.IO) {
            var track: AudioTrack? = null
            var totalWritten = 0
            // P-A diagnostic: underrun detection. If playbackHeadPosition has
            // already caught up to totalWritten when the next chunk arrives,
            // the user just heard silence — the producer didn't keep up. Logs
            // chunk idx, audio duration of this chunk, and `underrun=true|false`.
            var chunkIdx = 0
            try {
                for (chunk in channel) {
                    if (cancelled) throw CancellationException("user cancel")
                    val underrun = track?.let { it.playbackHeadPosition >= totalWritten && totalWritten > 0 } ?: false
                    val chunkMs = chunk.pcm.size * 1000L / chunk.sampleRate
                    Log.d(PERF_TAG, "consumer chunk=$chunkIdx audio=${chunkMs}ms underrun=$underrun written=$totalWritten head=${track?.playbackHeadPosition ?: 0}")
                    chunkIdx++
                    if (track == null) {
                        val sr = chunk.sampleRate
                        // ~1.5 s of headroom (sr × 2 bytes/sample × 1.5
                        // = sr × 3, but coerceAtLeast wants a Short
                        // count, so sr × 3 / 2 short samples). Larger
                        // than the 250 ms we had before — small per-
                        // chunk pacing jitter (gen briefly stalls,
                        // dispatcher hiccup, GC pause) doesn't drain
                        // the buffer to empty and produce an audible
                        // gap. Memory cost is ~72 KB at 24 kHz —
                        // negligible.
                        val minBuf = AudioTrack.getMinBufferSize(
                            sr,
                            AudioFormat.CHANNEL_OUT_MONO,
                            AudioFormat.ENCODING_PCM_16BIT,
                        ).coerceAtLeast(sr * 3 /* ~1.5 s of int16 samples = sr × 2 bytes × 1.5 s */)
                        val t = AudioTrack.Builder()
                            .setAudioAttributes(
                                AudioAttributes.Builder()
                                    .setUsage(AudioAttributes.USAGE_MEDIA)
                                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                    .build(),
                            )
                            .setAudioFormat(
                                AudioFormat.Builder()
                                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                    .setSampleRate(sr)
                                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                                    .build(),
                            )
                            .setBufferSizeInBytes(minBuf)
                            .setTransferMode(AudioTrack.MODE_STREAM)
                            .build()
                        track = t
                        currentTrack = t
                        t.play()
                    }
                    val t = track!!
                    val pcm = chunk.pcm
                    var off = 0
                    while (off < pcm.size && !cancelled) {
                        val n = t.write(
                            pcm,
                            off,
                            pcm.size - off,
                            AudioTrack.WRITE_BLOCKING,
                        )
                        if (n <= 0) {
                            Log.w(TAG, "AudioTrack.write returned $n; aborting stream")
                            break
                        }
                        off += n
                    }
                    totalWritten += off
                }
                // Drain — block until the head plays through the last write.
                val t = track ?: return@withContext
                while (!cancelled) {
                    val pos = t.playbackHeadPosition
                    if (pos >= totalWritten) break
                    try {
                        Thread.sleep(10L)
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                        break
                    }
                }
            } finally {
                track?.let { t ->
                    try {
                        t.pause()
                        t.flush()
                        t.stop()
                    } catch (_: IllegalStateException) {
                        // Already in a terminal state — ignore.
                    }
                    t.release()
                    if (currentTrack === t) currentTrack = null
                }
            }
        }

    /**
     * Allocate an AudioTrack at [sampleRate] (mono PCM16), write the full
     * buffer, and block until the playback head has caught up with the
     * write head. Releases the track in `finally`.
     *
     * Why busy-wait on the playback head and not e.g. `setNotificationMarkerPosition`:
     * the marker callback runs on a Handler thread we don't control, so
     * pairing it with `suspendCancellableCoroutine` is more wiring than
     * a sub-100ms sleep loop is worth for v0.1. The loop also responds
     * promptly to [cancel] via the volatile flag.
     */
    private suspend fun playPcm(pcm: ShortArray, sampleRate: Int) = withContext(Dispatchers.IO) {
        // Aim for ~250 ms of headroom — responsive cancel, no constant
        // back-pressure stalls on the write loop. Sizing this to the entire
        // PCM payload defeats MODE_STREAM and wastes memory on long inputs.
        val minBuf = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        ).coerceAtLeast(sampleRate * 2 / 4 /* shorts → bytes, 250 ms */)

        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setBufferSizeInBytes(minBuf)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        currentTrack = track
        try {
            track.play()
            // Single write; AudioTrack handles internal chunking. write()
            // returns the number of shorts written; for streaming mode we
            // loop until everything is queued or playback is cancelled.
            var written = 0
            while (written < pcm.size && !cancelled) {
                val remaining = pcm.size - written
                val n = track.write(pcm, written, remaining, AudioTrack.WRITE_BLOCKING)
                if (n <= 0) {
                    Log.w(TAG, "AudioTrack.write returned $n; aborting")
                    break
                }
                written += n
            }

            // Block until the playback head reaches the end of what we wrote.
            // 10 ms granularity keeps the cancel path responsive without
            // pinning the IO thread.
            while (!cancelled) {
                val pos = track.playbackHeadPosition
                if (pos >= written) break
                try {
                    Thread.sleep(10L)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
            }
        } finally {
            try {
                track.pause()
                track.flush()
                track.stop()
            } catch (_: IllegalStateException) {
                // Already stopped — ignore.
            }
            track.release()
            if (currentTrack === track) {
                currentTrack = null
            }
        }
    }

    companion object {
        private const val TAG = "Synthesizer"
        /** Logcat tag for the P-A streaming perf diagnostic — `adb logcat -s StreamPerf`. */
        private const val PERF_TAG = "StreamPerf"
    }
}
