package app.marmalade.tts.install

/**
 * How good a voice pack actually sounds, on a **linear** scale.
 *
 * Deliberately the opposite shape to [QualityTier]. That enum frames an
 * *engine's* quality non-linearly on purpose — each engine leads on a
 * different strength, so Pocket is "most expressive", never "worst". A voice
 * pack has no such story: the packs all run through the same VITS graph, so
 * the only thing that separates them is how clean the training audio was.
 * Norwegian NVCC was recorded in ordinary meeting rooms and it sounds like it;
 * the Swedish NST voice is a professional actor in a studio. Ranking those
 * honestly is the point, so this scale is ordered and [meterFill] is a rank.
 *
 * Shown wherever a pack or one of its voices is offered — the pack-management
 * rows on the engine's Configure screen carry the meter plus the word. Max's
 * rule: "we ship honestly", so a rough pack says so rather than shipping with
 * a silent asterisk.
 *
 * **Grades are Max's-ear-owned.** They come from listening to the packs, not
 * from the checkpoint's `qualityTier` string (which is upstream's *training*
 * tier — `x_low`/`medium`/`high` — and says nothing about the corpus). An
 * agent must never change a pack's grade: it is a listening judgement, and
 * the only way to revise it is for Max to listen again.
 *
 * @property meterFill How many of the four meter segments render filled, which
 *                     doubles as the colour key exactly like
 *                     [SpeedTier.meterFill] (4 = green, 3 = light green,
 *                     2 = amber, 1 = red — see
 *                     `EngineSpecColumn.meterFillColor`). Sharing the idiom is
 *                     intentional: the user has already learned that a short
 *                     hot bar is a cost.
 */
enum class PackQuality(val meterFill: Int) {
    /** Studio-clean source audio, no audible artefacts. */
    EXCELLENT(4),

    /** Clean and pleasant; minor artefacts a critical listener would notice. */
    GOOD(3),

    /** Understandable and usable, clearly below the good packs. */
    BASIC(2),

    /**
     * Rough source recordings — room noise, inconsistent level. Shipped
     * because something is better than nothing for a language with no other
     * option, and labelled plainly so nobody downloads it expecting studio
     * audio.
     */
    ROUGH(1),
}
