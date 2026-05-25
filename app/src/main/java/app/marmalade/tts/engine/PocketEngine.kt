package app.marmalade.tts.engine

import android.content.Context
import app.marmalade.tts.data.PocketVoiceCatalog
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Pocket TTS engine — the first non-sherpa engine in the catalog.
 *
 * v0.3.0-alpha.1 ships **scaffolding only**. The engine appears in the
 * Engines screen, installs cleanly via the standard tar.bz2 pipeline,
 * and registers its 8 voices in `voice_meta`. Synthesis is intentionally
 * unimplemented at this stage — calling [synthesize] throws
 * `NotImplementedError`, and the UI's Speak button is gated on the
 * existing `EngineNotInstalledException` path for engines whose bundle
 * isn't on disk, so attempting to play a Pocket voice surfaces a
 * graceful failure rather than a JNI crash.
 *
 * What's coming in alpha.2: the LSD inference loop (5-session ORT
 * pipeline, three-phase flow_lm priming, Euler-integrated flow matching,
 * mimi_decoder PCM output). What's coming in v0.3.0: voice cloning UX
 * + the `mimi_encoder` pipeline that turns user-supplied audio into
 * `[numFrames, 1024]` embeddings.
 *
 * Why a separate engine class (not a SherpaEngine subclass):
 *  - Pocket isn't a sherpa-onnx pipeline. It uses
 *    `com.microsoft.onnxruntime:onnxruntime-android` directly to drive
 *    a 5-graph model (text_conditioner + flow_lm_main + flow_lm_flow +
 *    mimi_encoder + mimi_decoder). The sherpa-onnx `OfflineTts` API
 *    can't express this.
 *  - It has voice cloning (no sherpa engine does), so the lifecycle
 *    includes a per-voice on-first-use encoding step that doesn't
 *    fit the SherpaEngine shape.
 *
 * The shared parent surface is [TtsEngine] — same engineName / sampleRate
 * / isInstalled / ensureModelLoaded / synthesize / release contract that
 * Synthesizer + the two TTS services already route through.
 */
@Singleton
open class PocketEngine @Inject constructor(
    @ApplicationContext private val ctx: Context,
) : TtsEngine {

    override val engineName: String = ENGINE_NAME

    /**
     * 24 kHz per `bundle.json#sample_rate`. Returned even before the
     * model is loaded because the system-TTS callback path needs to
     * declare a sample rate before any synthesis happens (same reason
     * SherpaEngine has its `defaultSampleRate` fallback).
     */
    override val sampleRate: Int = PocketVoiceCatalog.SAMPLE_RATE

    private val engineDir: File get() = File(ctx.filesDir, "engines/$ENGINE_NAME")

    /**
     * Cheap on-disk structural check — does the engine directory contain
     * every file we need to attempt loading? The installer renames the
     * extraction tmpdir into place atomically, so partial state shouldn't
     * surface here, but a corrupted install gets caught and surfaced as
     * "not installed" rather than a crash mid-load.
     */
    override fun isInstalled(): Boolean {
        if (!engineDir.isDirectory) return false
        for (name in REQUIRED_FILES) {
            if (!File(engineDir, name).isFile) return false
        }
        // voices/ subdir must hold every predefined-voice reference WAV.
        val voicesDir = File(engineDir, VOICES_DIR)
        if (!voicesDir.isDirectory) return false
        for (voice in PocketVoiceCatalog.voices) {
            if (!File(voicesDir, "${voice.displayName}.wav").isFile) return false
        }
        return true
    }

    /**
     * No-op in alpha.1 beyond an installed-or-not check. alpha.2 will
     * lazily load the 5 ORT sessions here (with the ARMv7 byte-array
     * guard per NekoSpeak ADR-005) and parse the state manifests from
     * bundle.json.
     */
    override fun ensureModelLoaded() {
        if (!isInstalled()) {
            throw EngineNotInstalledException(ENGINE_NAME)
        }
        // Real session loading lands in alpha.2.
    }

    /**
     * Synthesis is not implemented yet — alpha.2 will port the LSD
     * inference loop here. Throws `NotImplementedError` so callers see
     * a clear stack-traced "this engine isn't ready" failure rather
     * than silent empty audio.
     */
    override suspend fun synthesize(
        text: String,
        voiceId: String,
        speed: Float,
    ): SynthAudio {
        throw NotImplementedError(
            "Pocket TTS synthesis is not implemented yet — landing in v0.3.0-alpha.2.",
        )
    }

    /**
     * No native resources to release in alpha.1. alpha.2 will close the
     * 5 ORT sessions + the OrtEnvironment here. Idempotent + thread-safe
     * by contract.
     */
    override fun release() {
        // No sessions to close yet.
    }

    companion object {
        /**
         * Matches the catalog name + the install directory under
         * `${filesDir}/engines/`. The `v2026_04` suffix follows the
         * version-in-name convention established in v0.2.0
         * (`kokoro-v1_0`, `kitten-mini-v0_8`) — Pocket upstream tags
         * monthly model releases, so `v2026_04` is the April 2026 export
         * date of the bundle we're shipping.
         */
        const val ENGINE_NAME = "pocket-tts-en-v2026_04"

        /**
         * Required files at the root of the engine directory. The
         * `voices/` subdir's contents are checked separately against
         * [PocketVoiceCatalog.voices].
         */
        private val REQUIRED_FILES = listOf(
            "flow_lm_main_int8.onnx",
            "flow_lm_flow_int8.onnx",
            "mimi_encoder_int8.onnx",
            "mimi_decoder_int8.onnx",
            "text_conditioner_int8.onnx",
            "tokenizer.model",
            "bos_before_voice.npy",
            "bundle.json",
        )

        private const val VOICES_DIR = "voices"
    }
}
