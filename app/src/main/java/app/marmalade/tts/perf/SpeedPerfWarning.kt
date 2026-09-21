package app.marmalade.tts.perf

import app.marmalade.tts.data.db.VoiceAlias

// -----------------------------------------------------------------------------
// The speed-up performance warning — RTF-aware, not a static threshold.
//
// The slider itself is uncapped (0.5–2.0). The job here is only to decide
// WHEN to tell the user that the engine they've picked probably can't render
// fast enough on this device to keep up with the rate they're asking for.
//
// An engine keeps up at effective speed S roughly while `RTF × S < 1`; we
// warn at 0.8 to leave headroom for thermal throttling and background load.
// The RTF used is, in order of preference:
//   1. a rolling MEASURED warm RTF for the engine on this device
//      (SettingsRepository.engineRtf, fed by the synth service);
//   2. EngineRecommender's PROBE-derived prediction for the engine;
//   3. neither → the old static rule (effective speed past
//      VoiceAlias.SPEED_PERF_WARNING_THRESHOLD), so a device we know nothing
//      about still gets the pre-RTF behaviour.
//
// Design record: docs/UX-NOTES.md, addendum 4 (decided at the lab).
// -----------------------------------------------------------------------------

/** Pure trigger logic for the speed-up performance warning. */
object SpeedPerfWarning {

    /**
     * Warn once the effective-speed-adjusted RTF crosses this. 0.8 rather
     * than 1.0: the ~0.2 headroom absorbs thermal throttling and background
     * load, and the failure it guards (playback outrunning synthesis) is a
     * gradual one — better to warn a little early than a beat too late.
     */
    const val EFFECTIVE_RTF_THRESHOLD: Double = 0.8

    /**
     * Whether to show the warning for an engine asked to run at
     * [effectiveSpeed] (slider value in the alias editor; chip × alias speed
     * in the reader).
     *
     * [measuredRtf] wins over [predictedRtf]; when both are null we fall back
     * to the static speed threshold so a device with no probe and no
     * measurements still behaves as it did before this landed.
     */
    fun shouldWarn(
        measuredRtf: Double?,
        predictedRtf: Double?,
        effectiveSpeed: Float,
    ): Boolean {
        val rtf = measuredRtf ?: predictedRtf
        return if (rtf != null) {
            rtf * effectiveSpeed > EFFECTIVE_RTF_THRESHOLD
        } else {
            effectiveSpeed > VoiceAlias.SPEED_PERF_WARNING_THRESHOLD
        }
    }
}

/** Rolling-average math for the per-engine measured RTF store. */
object RollingRtf {

    /**
     * Weight of the newest sample in the exponential moving average. 0.3 is
     * deliberately boring: recent enough to track a device warming up or
     * throttling within a handful of utterances, smooth enough that one odd
     * measurement (a GC pause, a backgrounded app stealing a core) doesn't
     * flip the warning on its own.
     */
    const val ALPHA: Double = 0.3

    /**
     * Fold [sample] into the running average. The first sample (null
     * [previous]) becomes the average outright; after that it's a standard
     * EMA. Cheap and allocation-free.
     */
    fun update(previous: Double?, sample: Double): Double =
        if (previous == null) sample else previous + ALPHA * (sample - previous)
}
