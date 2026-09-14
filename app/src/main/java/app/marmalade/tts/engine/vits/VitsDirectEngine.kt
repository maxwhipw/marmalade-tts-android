package app.marmalade.tts.engine.vits

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.os.Build
import android.util.Log
import app.marmalade.tts.audio.TextChunker
import app.marmalade.tts.data.SettingsRepository
import app.marmalade.tts.engine.EngineNotInstalledException
import app.marmalade.tts.engine.SynthAudio
import app.marmalade.tts.engine.TtsEngine
import app.marmalade.tts.install.VoicePackCatalog
import app.marmalade.tts.lang.LangDetector
import app.marmalade.tts.perf.CpuClusterDetector
import app.marmalade.tts.phonemizer.EnPhonemeFixups
import app.marmalade.tts.phonemizer.EspeakPhonemizer
import app.marmalade.tts.phonemizer.SharedEspeakData
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

// -----------------------------------------------------------------------------
// Data flow
// -----------------------------------------------------------------------------
//   synthesize(text, voiceId = "vits-marmalade-v1:<packId>", speed, lang?)
//     │
//     ├── packFor(voiceId) ────────► VoicePack  (unknown id → hard failure)
//     │
//     ├── ensurePackLoaded(pack) ──► LoadedPack
//     │        ├── VitsPackConfig.load(packs/<id>/model.onnx.json)
//     │        │      (sample rate, espeak voice, scales, phoneme_id_map)
//     │        └── OrtSession(packs/<id>/model.onnx)   [XNNPACK EP]
//     │
//     ├── VitsPhonemeIds.splitClauses(text)   → [(clause text, terminator)]
//     │        └── per clause: EspeakPhonemizer.phonemize(clause, lang)  → IPA
//     │
//     ├── VitsPhonemeIds.encode(phonemized clauses, config.phonemeIdMap)
//     │        → int64 ids  ^ _ (p _)* $
//     │
//     ├── OrtSession.run:
//     │        input         int64 [1, T]  ids
//     │        input_lengths int64 [1]     T
//     │        scales        f32   [3]     [noise_scale, length_scale, noise_w]
//     │        sid           int64 [1]     only when num_speakers > 1
//     │        → output[0]   f32   [...,N] audio, leading 1-dims squeezed
//     │
//     └── float → PCM16 (×32767, clipped) → SynthAudio(pcm, config.sampleRate)
//
//   Speed is NOT applied here (supportsNativeSpeed = false): the services
//   time-stretch the rendered audio, and length_scale stays at the config's
//   trained default. No loudness normalization either — deliberately unlike
//   the Piper runtime, which peak-normalizes every utterance.
// -----------------------------------------------------------------------------
//
// VitsDirectEngine — "VITS Marmalade"
//
// Marmalade's own inference path for Piper-class single-speaker VITS
// checkpoints, on `com.microsoft.onnxruntime:onnxruntime-android`. NO Piper
// runtime code is used or shipped: the maintained fork (OHF-Voice/piper1-gpl)
// is GPL-3.0. The tensor contract was read off the checkpoint itself and the
// id-mapping semantics reimplemented in [VitsPhonemeIds]; see that file.
//
// Voices arrive as downloadable packs, one self-contained checkpoint each:
//
//   ${filesDir}/engines/vits-marmalade-v1/packs/<packId>/
//       model.onnx        — VITS generator
//       model.onnx.json   — sample rate, espeak voice, scales, phoneme ids
//       MODEL_CARD        — upstream card (licence trail)
//       PROVENANCE.md     — our weights/data/licence audit
//
// Residency discipline mirrors KokoroDirect: sessions build against locals,
// the `env` marker publishes LAST so a concurrent [isLoaded] can never see a
// half-built engine, and [release] nulls the marker FIRST. Unlike the
// single-model engines, several packs may be resident at once — each is its
// own ORT session, and [release] closes all of them.
//
// ORT-Android footguns observed (see PocketEngine's header for the full list):
// input tensors are created per call and never reused after the Result closes,
// and no output is pinned (pinning requires pinning the FULL output set).
// -----------------------------------------------------------------------------

private const val TAG = "VitsDirectEngine"

/**
 * Sample rate reported before any pack has been loaded. Every pack shipped so
 * far is 16 kHz; the real value comes from the pack config the moment one
 * loads, and [SynthAudio] always carries the rate that actually rendered it.
 */
private const val FALLBACK_SAMPLE_RATE = 16_000

/** Logcat tag shared with the other engines' streaming diagnostics. */
private const val PERF_TAG = "StreamPerf"

/**
 * Soft floor for the chunker's tiny-sentence merge pass — same 80 chars
 * Kokoro/Kitten use (sherpa's 50-token threshold in source characters).
 */
private const val MIN_CHARS_PER_CHUNK = 80

@Singleton
class VitsDirectEngine @Inject constructor(
    @ApplicationContext private val ctx: Context,
    private val settings: SettingsRepository,
    private val sharedEspeak: SharedEspeakData,
) : TtsEngine {

    override val engineName: String = ENGINE_NAME

    /**
     * The rate of the most recently loaded pack, or [FALLBACK_SAMPLE_RATE]
     * before the first load. Per-pack rather than per-engine because packs are
     * independent checkpoints (upstream ships 16 kHz `x_low`/`low` and 22.05
     * kHz `medium`/`high` tiers), so callers that need the exact rate for a
     * specific voice should read it off the returned [SynthAudio].
     */
    override val sampleRate: Int get() = lastSampleRate

    /** Same class as Kokoro/Kitten — see [TtsEngine.maxInputChars]. */
    override val maxInputChars: Int = 400

    /**
     * The graph's `length_scale` could stretch time, but user speed is applied
     * downstream as a Tempo effect for every engine now (see
     * [TtsEngine.supportsNativeSpeed]); `length_scale` stays at the trained
     * default from the pack config.
     */
    override val supportsNativeSpeed: Boolean = false

    private val engineDir: File get() = File(ctx.filesDir, "engines/$ENGINE_NAME")
    private val packsDir: File get() = File(engineDir, PACKS_DIR_NAME)

    private val loadLock = Mutex()
    private val synthLock = Mutex()

    // -- live state (null / empty while not loaded) ---------------------------

    /**
     * Publish-last "loaded" marker, exactly as in KokoroDirectEngine: set only
     * after at least one pack session and the phonemizer are usable, nulled
     * first on release. A plain volatile read answers [isLoaded] with no lock.
     */
    @Volatile private var env: OrtEnvironment? = null

    private var phonemizer: EspeakPhonemizer? = null

    /**
     * Resident pack sessions, keyed by pack id. Writes happen under
     * [loadLock]; the fast-path read in [ensureLoadedSuspending] is lock-free,
     * hence a concurrent map rather than a plain one.
     */
    private val loaded = java.util.concurrent.ConcurrentHashMap<String, LoadedPack>()

    @Volatile private var lastSampleRate: Int = FALLBACK_SAMPLE_RATE

    /** A pack that is resident right now: its config plus its ORT session. */
    private class LoadedPack(val config: VitsPackConfig, val session: OrtSession)

    // -- install state --------------------------------------------------------

    /**
     * True when at least one catalog pack is present and structurally intact.
     * Cheap — two `File` probes per candidate pack, no parsing.
     */
    override fun isInstalled(): Boolean = installedPackIds().isNotEmpty()

    override fun isLoaded(): Boolean = env != null

    override fun ensureModelLoaded() {
        if (env != null) return
        kotlinx.coroutines.runBlocking { ensureLoadedSuspending(packId = null) }
    }

    override suspend fun synthesize(
        text: String,
        voiceId: String,
        speed: Float,
        phonemizationLanguage: String?,
    ): SynthAudio = withContext(Dispatchers.Default) {
        val parts = ArrayList<ShortArray>()
        var rate = sampleRate
        synthesizeStream(text, voiceId, speed, phonemizationLanguage).collect {
            parts.add(it.pcm)
            rate = it.sampleRate
        }
        if (parts.isEmpty()) return@withContext SynthAudio(ShortArray(0), rate)
        val merged = ShortArray(parts.sumOf { it.size })
        var pos = 0
        for (p in parts) {
            System.arraycopy(p, 0, merged, pos, p.size)
            pos += p.size
        }
        SynthAudio(pcm = merged, sampleRate = rate)
    }

    /**
     * Chunk-per-emission streaming. The synthesis pipeline hands engines the
     * WHOLE utterance (`runSynthesisPipeline` does no splitting), so chunking
     * is the engine's own job — same as Kokoro/Kitten, and with the same
     * discipline: never split mid-word, split only on sentence-end punctuation
     * and newlines, merge runs of tiny sentences up to [MIN_CHARS_PER_CHUNK]
     * (first chunk exempt, so a short opening sentence starts speaking
     * immediately) and cap at [maxInputChars].
     *
     * One ORT run per chunk, with the chunk's clauses concatenated into a
     * single id sequence — the shape the golden reference was verified in.
     */
    override fun synthesizeStream(
        text: String,
        voiceId: String,
        speed: Float,
        phonemizationLanguage: String?,
    ): kotlinx.coroutines.flow.Flow<SynthAudio> = kotlinx.coroutines.flow.channelFlow {
        val packId = packIdFrom(voiceId)
        val pack = ensureLoadedSuspending(packId)
        val lang = phonemizationLanguage?.takeIf { it != LangDetector.AUTO }
            ?: pack.config.espeakVoice
        // Warm-up only: phonemize(text, lang) re-asserts the voice atomically
        // per call, because espeak's active voice is process-global and a
        // concurrent synth on another engine can flip it between chunks.
        phonemizer?.setVoice(lang)
        val chunks = TextChunker.chunk(
            text = text,
            maxChars = maxInputChars,
            packSentences = false,
            sentenceOnly = true,
            allowWordSplits = false,
            minChars = MIN_CHARS_PER_CHUNK,
            minCharsExemptFirst = true,
        )
        for ((idx, chunk) in chunks.withIndex()) {
            val clauses = VitsPhonemeIds.splitClauses(chunk)
            if (clauses.isEmpty()) continue
            val startNs = System.nanoTime()
            // Phonemize + infer under the synth lock: the clause loop must not
            // interleave with another utterance on this engine, and an ORT
            // session is not reentrant.
            val audio = synthLock.withLock { renderUtterance(pack, clauses, lang) }
            if (audio.pcm.isEmpty()) continue
            val inferMs = (System.nanoTime() - startNs) / 1_000_000
            val audioMs = audio.pcm.size * 1000L / audio.sampleRate
            Log.d(
                PERF_TAG,
                "vits pack=$packId chunk=$idx/${chunks.size} infer=${inferMs}ms " +
                    "audio=${audioMs}ms rtf=${if (audioMs > 0) inferMs.toDouble() / audioMs else Double.NaN} " +
                    "textLen=${chunk.length}",
            )
            send(audio)
        }
    }.flowOn(Dispatchers.Default)

    override fun release() {
        // synthLock excludes an in-flight inference (closing a session
        // mid-run() is a native SIGSEGV); loadLock excludes a concurrent load.
        // Only place both are held, so the order can't deadlock.
        kotlinx.coroutines.runBlocking {
            synthLock.withLock { loadLock.withLock { releaseInternal() } }
        }
    }

    // -- load -----------------------------------------------------------------

    /**
     * Ensure [packId] is resident and return it. `null` means "whichever pack
     * the engine defaults to" — the first installed catalog pack — which is
     * what [ensureModelLoaded] and the warm-up paths ask for.
     *
     * @throws EngineNotInstalledException when no pack (or not that pack) is
     *   on disk.
     * @throws IllegalStateException when a present pack fails to load.
     */
    private suspend fun ensureLoadedSuspending(packId: String?): LoadedPack {
        val resolved = packId ?: defaultInstalledPackId()
        loaded[resolved]?.let { return it }
        loadLock.withLock {
            loaded[resolved]?.let { return it }
            val packDir = File(packsDir, resolved)
            if (!isPackUsable(packDir)) throw EngineNotInstalledException(ENGINE_NAME)
            val manualThreads = settings.intraOpThreads.firstOrNull()
            val threadCount = manualThreads ?: CpuClusterDetector.detectPerfCoreCount()
            val t0 = System.currentTimeMillis()
            try {
                val pack = loadPack(resolved, packDir, threadCount)
                loaded[resolved] = pack
                lastSampleRate = pack.config.sampleRate
                Log.i(
                    TAG,
                    "loaded pack $resolved (${pack.config.dataset}, " +
                        "${pack.config.languageCode}, ${pack.config.sampleRate} Hz, " +
                        "espeak=${pack.config.espeakVoice}) in " +
                        "${System.currentTimeMillis() - t0} ms",
                )
                return pack
            } catch (t: Throwable) {
                // A pack that failed to load must not stay half-registered;
                // everything else resident keeps working.
                loaded.remove(resolved)
                throw IllegalStateException(
                    "VITS pack '$resolved' failed to load: ${t.message}",
                    t,
                )
            }
        }
    }

    /**
     * Build one pack's session + config and (first time only) open espeak.
     * Caller holds [loadLock].
     */
    private fun loadPack(packId: String, packDir: File, intraOpThreads: Int): LoadedPack {
        val ort = OrtEnvironment.getEnvironment()
        val config = VitsPackConfig.load(File(packDir, CONFIG_FILE))
        val session = createSession(
            ort,
            buildSessionOptions(intraOpThreads),
            File(packDir, MODEL_FILE),
        )
        if (phonemizer == null) {
            // libespeak-ng.so is compiled into the APK and the data is the
            // app-level shared full-language tree — same as every other
            // espeak-backed engine. See phonemizer/SharedEspeakData.kt.
            val espeak = EspeakPhonemizer(
                libPath = EspeakPhonemizer.APK_LIB_NAME,
                dataPath = sharedEspeak.ensure().absolutePath,
                voice = config.espeakVoice,
                // Only fires for English voices (no VITS pack ships English
                // yet). When one does, its fixup replacement wants an A/B of
                // its own — see EnPhonemeFixups.
                fixupModel = EnPhonemeFixups.Model.KOKORO,
            )
            val rate = espeak.open()
            if (rate < 0) {
                runCatching { session.close() }
                throw IllegalStateException("espeak failed to open (status=$rate)")
            }
            phonemizer = espeak
            Log.i(TAG, "espeak version=${espeak.version()}")
        }
        // Publish the marker only once a pack is fully usable.
        env = ort
        return LoadedPack(config, session)
    }

    private fun buildSessionOptions(intraOpThreads: Int): OrtSession.SessionOptions =
        OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(intraOpThreads)
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            setMemoryPatternOptimization(true)
            addConfigEntry("session.intra_op.allow_spinning", "0")
            // fp32 graphs — XNNPACK is the right EP here (the quantized-model
            // SIGSEGV that forced Kokoro onto the CPU EP doesn't apply; packs
            // ship upstream's fp32 checkpoints).
            try {
                addXnnpack(mapOf("intra_op_num_threads" to intraOpThreads.toString()))
                Log.i(TAG, "XNNPACK EP enabled (intraOpThreads=$intraOpThreads)")
            } catch (t: Throwable) {
                Log.w(TAG, "XNNPACK EP unavailable; CPU EP only", t)
            }
        }

    /**
     * On arm64 ORT mmaps the model by path (cheap). On 32-bit ARM we hand it
     * the bytes: misaligned weight offsets in the mapped file trip SIGBUS on
     * ARMv7. Same workaround as PocketEngine + the other direct engines.
     */
    private fun createSession(
        ort: OrtEnvironment,
        opts: OrtSession.SessionOptions,
        file: File,
    ): OrtSession {
        val primary = Build.SUPPORTED_ABIS.firstOrNull() ?: ""
        return if (primary == "armeabi-v7a" || primary == "armeabi") {
            ort.createSession(file.readBytes(), opts)
        } else {
            ort.createSession(file.absolutePath, opts)
        }
    }

    private fun releaseInternal() {
        env = null // marker first — a racing isLoaded() must report false
        for ((id, pack) in loaded) {
            try {
                pack.session.close()
            } catch (t: Throwable) {
                Log.w(TAG, "closing session for pack $id failed", t)
            }
        }
        loaded.clear()
        try {
            phonemizer?.close()
        } catch (t: Throwable) {
            Log.w(TAG, "closing espeak failed", t)
        }
        phonemizer = null
    }

    // -- inference ------------------------------------------------------------

    /** Caller holds [synthLock]. */
    private fun renderUtterance(
        pack: LoadedPack,
        clauses: List<VitsClause>,
        lang: String,
    ): SynthAudio {
        val phon = phonemizer ?: error("phonemizer missing")
        val phonemized = clauses.map { clause ->
            VitsClause(phon.phonemize(clause.text, lang), clause.terminator)
        }
        val encoded = VitsPhonemeIds.encode(phonemized, pack.config.phonemeIdMap)
        if (encoded.missing.isNotEmpty()) {
            Log.w(
                TAG,
                "skipped ${encoded.missing.values.sum()} phoneme(s) absent from the " +
                    "pack's map: ${VitsPhonemeIds.describeMissing(encoded.missing)}",
            )
        }
        val pcm = runInference(pack, encoded.ids)
        return SynthAudio(pcm = pcm, sampleRate = pack.config.sampleRate)
    }

    /**
     * One ORT run. Input tensors are created per call and closed in a
     * `finally` after the Result is consumed — reusing an input tensor across
     * runs is invalid once the Result that saw it has been closed, and nothing
     * is pinned because pinning demands the full output set.
     */
    private fun runInference(pack: LoadedPack, ids: IntArray): ShortArray {
        val ort = env ?: error("engine not loaded")
        // Ids are only the two markers — nothing to say.
        if (ids.size <= 2) return ShortArray(0)

        val config = pack.config
        val inputTensor = directLongTensor(ort, longArrayOf(1, ids.size.toLong())) { buf ->
            for (id in ids) buf.put(id.toLong())
        }
        val lengthsTensor = directLongTensor(ort, longArrayOf(1)) { buf ->
            buf.put(ids.size.toLong())
        }
        val scalesTensor = directFloatTensor(ort, longArrayOf(3)) { buf ->
            buf.put(config.noiseScale)
            buf.put(config.lengthScale)
            buf.put(config.noiseW)
        }
        // Single-speaker packs have no `sid` input at all — feeding one would
        // fail the run. Multi-speaker packs take speaker 0 until the catalog
        // models per-speaker voices.
        val sidTensor = if (config.isMultiSpeaker) {
            directLongTensor(ort, longArrayOf(1)) { buf -> buf.put(0L) }
        } else {
            null
        }

        try {
            val inputs = buildMap<String, OnnxTensor> {
                put(INPUT_IDS, inputTensor)
                put(INPUT_LENGTHS, lengthsTensor)
                put(INPUT_SCALES, scalesTensor)
                sidTensor?.let { put(INPUT_SID, it) }
            }
            val results = pack.session.run(inputs)
            try {
                return floatToPcm16(squeezeWaveform(results[0].value))
            } finally {
                results.close()
            }
        } finally {
            inputTensor.close()
            lengthsTensor.close()
            scalesTensor.close()
            sidTensor?.close()
        }
    }

    /**
     * ORT hands back the output with its leading batch/channel dims intact
     * (`[1, 1, N]` for these graphs), surfacing as nested `Array<*>`. Walk in
     * until the flat float row.
     */
    private fun squeezeWaveform(value: Any?): FloatArray = when (value) {
        is FloatArray -> value
        is Array<*> -> squeezeWaveform(value.firstOrNull())
        else -> {
            Log.w(TAG, "unexpected model output type ${value?.javaClass?.name}")
            FloatArray(0)
        }
    }

    /**
     * float → PCM16. No loudness normalization, deliberately: the Piper
     * runtime peak-normalizes each utterance, which makes a quiet sentence as
     * loud as a shouted one and fights the app's own effect chain. Marmalade
     * keeps the model's own levels.
     */
    private fun floatToPcm16(samples: FloatArray): ShortArray {
        val out = ShortArray(samples.size)
        for (i in samples.indices) {
            out[i] = (samples[i].coerceIn(-1.0f, 1.0f) * 32767.0f).toInt().toShort()
        }
        return out
    }

    private inline fun directLongTensor(
        ort: OrtEnvironment,
        shape: LongArray,
        fill: (java.nio.LongBuffer) -> Unit,
    ): OnnxTensor {
        val count = shape.fold(1L) { acc, d -> acc * d }.toInt().coerceAtLeast(1)
        val buf = ByteBuffer.allocateDirect(count * 8).order(ByteOrder.nativeOrder()).asLongBuffer()
        fill(buf)
        buf.rewind()
        return OnnxTensor.createTensor(ort, buf, shape)
    }

    private inline fun directFloatTensor(
        ort: OrtEnvironment,
        shape: LongArray,
        fill: (java.nio.FloatBuffer) -> Unit,
    ): OnnxTensor {
        val count = shape.fold(1L) { acc, d -> acc * d }.toInt().coerceAtLeast(1)
        val buf = ByteBuffer.allocateDirect(count * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        fill(buf)
        buf.rewind()
        return OnnxTensor.createTensor(ort, buf, shape)
    }

    // -- pack resolution ------------------------------------------------------

    /**
     * Pack id from a `<engine>:<packId>` voice id (a bare pack id is accepted
     * too — the benchmark screen and dev callers pass one).
     *
     * @throws IllegalArgumentException naming the known packs when the id
     *   isn't one of them. Silently substituting another voice would mean a
     *   user hears a language they didn't pick, with nothing in the logs.
     */
    private fun packIdFrom(voiceId: String): String {
        val candidate = voiceId.substringAfter(':', voiceId)
        val known = VoicePackCatalog.forEngine(ENGINE_NAME).map { it.id }
        if (candidate !in known) {
            throw IllegalArgumentException(
                "Unknown $ENGINE_NAME voice '$voiceId'. Known packs: ${known.joinToString()}",
            )
        }
        return candidate
    }

    /**
     * Pack used when no voice was named. First *installed* catalog pack, so a
     * user who only downloaded the second language still gets a working
     * warm-up.
     *
     * @throws EngineNotInstalledException when nothing is installed.
     */
    private fun defaultInstalledPackId(): String =
        installedPackIds().firstOrNull() ?: throw EngineNotInstalledException(ENGINE_NAME)

    /** Ids of catalog packs present on disk, in catalog order. */
    fun installedPackIds(): List<String> =
        VoicePackCatalog.forEngine(ENGINE_NAME)
            .map { it.id }
            .filter { isPackUsable(File(packsDir, it)) }

    private fun isPackUsable(packDir: File): Boolean {
        if (!packDir.isDirectory) return false
        val model = File(packDir, MODEL_FILE)
        if (!model.isFile || model.length() == 0L) return false
        val config = File(packDir, CONFIG_FILE)
        return config.isFile && config.length() > 0L
    }

    companion object {
        /** Must match [VoicePackCatalog.VITS_MARMALADE_ENGINE]. */
        const val ENGINE_NAME = VoicePackCatalog.VITS_MARMALADE_ENGINE

        private const val PACKS_DIR_NAME = "packs"
        private const val MODEL_FILE = "model.onnx"
        private const val CONFIG_FILE = "model.onnx.json"

        // Graph input names, read off the real checkpoint (2026-09-13).
        private const val INPUT_IDS = "input"
        private const val INPUT_LENGTHS = "input_lengths"
        private const val INPUT_SCALES = "scales"
        private const val INPUT_SID = "sid"
    }
}
