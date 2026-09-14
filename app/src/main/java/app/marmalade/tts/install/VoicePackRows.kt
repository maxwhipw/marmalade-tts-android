package app.marmalade.tts.install

// -----------------------------------------------------------------------------
// Data flow
// -----------------------------------------------------------------------------
//   VoicePackCatalog.forEngine(engine)   ──┐
//   Map<packId, InstallState> (installer)  ├──► voicePackGroups(...)
//                                          │      one VoicePackLanguageGroup
//                                          │      per language, rows inside
//                                          │      → EngineDetailScreen's
//                                          │        "Voice packs" section
//                                          └──► voicePackSummary(...)
//                                                 "9 voice packs · 6 languages
//                                                  · 2 of 9 installed" on the
//                                                 engine card + section header
// -----------------------------------------------------------------------------
//
// Pure derivation, no Android and no I/O, so the grouping / progress / action
// rules are unit-testable without a device. The ViewModel's only job is to
// collect the per-pack state flows and hand the map in.
// -----------------------------------------------------------------------------

/**
 * True when the files are on disk and work.
 *
 * [InstallState.Outdated] counts: it means the archive was re-published since
 * the install, not that the installed copy is broken — the UI offers an update
 * and the voices keep working in the meantime. Hiding those voices from the
 * picker would be a worse lie than listing them.
 */
val InstallState.isUsableOnDisk: Boolean
    get() = this is InstallState.Installed || this is InstallState.Outdated

/**
 * One pack as the pack-management list renders it: the catalog entry plus its
 * current on-disk state, with the display decisions derived rather than
 * re-made at each call site.
 */
data class VoicePackRow(
    val pack: VoicePack,
    val state: InstallState,
) {
    /** Selectable voices the pack contributes — one per speaker. */
    val voiceCount: Int get() = pack.voices.size

    /** True while a download/extract is in flight: no buttons, show progress. */
    val isBusy: Boolean
        get() = state is InstallState.Downloading || state is InstallState.Extracting

    /** True when the pack's voices can actually be spoken right now. */
    val isUsable: Boolean get() = state.isUsableOnDisk

    /**
     * Determinate progress in 0f..1f while [isBusy], else null.
     *
     * Null for a zero/unknown total instead of 0f: an indeterminate bar is
     * honest about not knowing, a 0% determinate bar looks stalled.
     */
    val progress: Float?
        get() = when (val s = state) {
            is InstallState.Downloading -> fraction(s.bytesFetched, s.totalBytes)
            is InstallState.Extracting -> fraction(s.bytesExtracted, s.totalBytes)
            else -> null
        }

    /** What the row's single action button does, or null while [isBusy]. */
    val action: VoicePackAction?
        get() = when (state) {
            InstallState.NotInstalled -> VoicePackAction.INSTALL
            InstallState.Installed -> VoicePackAction.UNINSTALL
            is InstallState.Failed -> VoicePackAction.RETRY
            InstallState.Corrupt -> VoicePackAction.REINSTALL
            is InstallState.Outdated -> VoicePackAction.UPDATE
            is InstallState.Downloading, is InstallState.Extracting -> null
        }

    /** The failure text to show under the row, or null when there is none. */
    val failureReason: String?
        get() = (state as? InstallState.Failed)?.reason

    private fun fraction(done: Long, total: Long): Float? =
        if (total > 0L) (done.toFloat() / total.toFloat()).coerceIn(0f, 1f) else null
}

/** The one action a pack row offers, given its state. */
enum class VoicePackAction {
    INSTALL,
    UNINSTALL,
    RETRY,
    REINSTALL,
    UPDATE,
}

/**
 * Packs of one language, which is how the list is grouped: a user looking for
 * Icelandic wants the four Talrómur speakers together, and the ten Norwegian
 * voices are one decision, not ten scattered rows.
 */
data class VoicePackLanguageGroup(
    val languageCode: String,
    val rows: List<VoicePackRow>,
) {
    /** Voices across every pack of this language. */
    val voiceCount: Int get() = rows.sumOf { it.voiceCount }

    /** Packs of this language already on disk. */
    val installedCount: Int get() = rows.count { it.isUsable }
}

/**
 * Aggregate counts for the engine card's one-line pack summary.
 *
 * [installedCount] is what keeps the line honest: the card used to imply the
 * engine "has" 9 packs when tapping Install downloaded exactly one of them.
 */
data class VoicePackSummary(
    val packCount: Int,
    val languageCount: Int,
    val installedCount: Int,
)

/**
 * Group [engineName]'s catalog packs by language, preserving catalog order for
 * both the groups (a language sorts where its first pack sits) and the rows
 * inside them. Catalog order is curated, so sorting alphabetically here would
 * throw away the ordering decision the catalog already made.
 *
 * A pack with no entry in [states] renders as [InstallState.NotInstalled] —
 * that is the correct pre-probe reading, and it means the list draws
 * immediately instead of waiting for nine flows to emit.
 */
fun voicePackGroups(
    engineName: String,
    states: Map<String, InstallState>,
): List<VoicePackLanguageGroup> =
    VoicePackCatalog.forEngine(engineName)
        .map { pack -> VoicePackRow(pack, states[pack.id] ?: InstallState.NotInstalled) }
        .groupBy { it.pack.languageCode }
        .map { (language, rows) -> VoicePackLanguageGroup(language, rows) }

/** Counts behind the engine card's "N voice packs · M languages" line. */
fun voicePackSummary(
    engineName: String,
    states: Map<String, InstallState>,
): VoicePackSummary {
    val packs = VoicePackCatalog.forEngine(engineName)
    return VoicePackSummary(
        packCount = packs.size,
        languageCount = packs.map { it.languageCode }.distinct().size,
        installedCount = packs.count { pack -> states[pack.id]?.isUsableOnDisk == true },
    )
}
