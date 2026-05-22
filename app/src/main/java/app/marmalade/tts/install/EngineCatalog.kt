package app.marmalade.tts.install

// -----------------------------------------------------------------------------
// Data flow
// -----------------------------------------------------------------------------
//   EngineInstaller.install(name)
//     │
//     ├── EngineCatalog.byName(name)  ──► EngineDescriptor
//     │                                       │
//     │                                       ├── archive: EngineArchive
//     │                                       │     (url, sha256, sizeBytes,
//     │                                       │      archiveRoot)
//     │                                       │
//     │                                       └── displayName, description,
//     │                                           downloadSizeBytes,
//     │                                           installedSizeBytes,
//     │                                           licenseSummary
//     │
//     ▼
//   Single HTTP GET → sha256 check → tar.bz2 extract → atomic rename
//
//   UI (Onboarding / EnginesScreen)
//     │
//     └── EngineCatalog.all  ──► render one card per engine, show
//                                 description, size, licenseSummary
// -----------------------------------------------------------------------------

/**
 * Archive that makes up an engine bundle. The installer downloads it,
 * verifies [sha256], and extracts it into `${filesDir}/engines/<name>.tmp/`
 * before atomic-renaming to `${filesDir}/engines/<name>/`.
 *
 * @property url Absolute HTTPS URL to a tar.bz2 archive whose top-level
 *               directory (named per [archiveRoot]) contains the engine
 *               payload. The installer flattens the top-level directory
 *               during extraction so the on-device layout is
 *               `${filesDir}/engines/<name>/<file>` regardless of the
 *               archive's wrapper-dir name.
 * @property sha256 Hex-encoded SHA-256 of the archive bytes, lower case.
 * @property sizeBytes Expected archive size in bytes (for progress UI).
 * @property archiveRoot Name of the wrapper directory inside the archive
 *               to strip during extraction, with trailing `/`. Empty
 *               string means "extract entries as-is, no stripping" —
 *               use that for future archives that aren't wrapper-dir'd.
 *               For Kitten v0.1 this is `"kitten-nano-en-v0_1-fp16/"`
 *               (the directory name Sherpa-ONNX's tarball uses).
 */
data class EngineArchive(
    val url: String,
    val sha256: String,
    val sizeBytes: Long,
    val archiveRoot: String = "",
)

/**
 * Description of a downloadable engine bundle.
 *
 * Static metadata only — no I/O. The installer + UI both consume this; it
 * is the single source of truth for "what does the user have to download
 * to use engine X."
 *
 * @property name              Engine identifier (matches `VoiceMeta.engine`
 *                             and the directory under `${filesDir}/engines/`).
 *                             Stable across versions — part of the public
 *                             surface that aliases reference.
 * @property displayName       User-facing label (e.g. "Kitten TTS").
 * @property description       One-paragraph pitch for the engine, shown on
 *                             the install consent card. Keep it short.
 * @property downloadSizeBytes Compressed archive size on the wire — informs
 *                             the "this will download ~26 MB" copy. Equals
 *                             [archive.sizeBytes] by convention.
 * @property installedSizeBytes Approximate on-disk size after extraction.
 *                             Larger than [downloadSizeBytes] because the
 *                             archive is tar.bz2-compressed.
 * @property isRecommended     True for the engine pre-checked in the
 *                             onboarding wizard. v0.1 only ships Kitten,
 *                             which is the recommended default.
 * @property archive           Single downloadable archive that contains
 *                             every file in the engine bundle.
 * @property licenseNotice     Path inside the APK to the long-form notice
 *                             shown on the license expand panel.
 * @property licenseSummary    One-liner shown on the install card, e.g.
 *                             "Includes GPL-3.0 components (espeak-ng)."
 */
data class EngineDescriptor(
    val name: String,
    val displayName: String,
    val description: String,
    val downloadSizeBytes: Long,
    val installedSizeBytes: Long,
    val isRecommended: Boolean,
    val archive: EngineArchive,
    val licenseNotice: String,
    val licenseSummary: String,
) {
    init {
        require(name.isNotBlank()) { "engine name must not be blank" }
        require(archive.url.isNotBlank()) { "engine $name has no archive url" }
        require(archive.sizeBytes > 0L) { "engine $name has zero-size archive" }
    }
}

/**
 * Static catalog of installable engines.
 *
 * v0.1.9 ships both Kokoro (recommended default) and Kitten. Future engines
 * (piper, pocket) join this list as their bundles are characterised. The
 * list order is also the display order in the onboarding wizard and
 * Settings → Engines screen.
 */
object EngineCatalog {

    /**
     * Sum of unpacked Kokoro v0.19 int8 file sizes. Compute with:
     *   tar -xjf kokoro-int8-en-v0_19.tar.bz2
     *   find kokoro-int8-en-v0_19 -type f -exec stat -c %s {} + | \
     *       awk '{s+=$1} END {print s}'
     */
    private const val KOKORO_INSTALLED_SIZE_BYTES: Long = 157_947_103L

    /**
     * Sum of unpacked Kitten v0.8 int8 file sizes. Compute with:
     *   tar -xjf kitten-nano-en-v0_8-int8.tar.bz2
     *   find kitten-nano-en-v0_8-int8 -type f -exec stat -c %s {} + | \
     *       awk '{s+=$1} END {print s}'
     */
    private const val KITTEN_INSTALLED_SIZE_BYTES: Long = 45_652_547L

    /**
     * Kokoro TTS — Sherpa-ONNX kokoro-int8-en v0.19 port. ~98 MB compressed
     * download, ~151 MB on-disk, 11 English voices (American + British,
     * male + female).
     *
     * Recommended default starting v0.1.9: Kokoro sounds meaningfully
     * better than Kitten at the cost of a ~3x larger download. The Kokoro
     * model itself is Apache-2.0; the espeak-ng phonemizer it shares with
     * Kitten is GPL-3.0 — the install card shows that disclosure before
     * the user opts in.
     */
    private val KOKORO: EngineDescriptor = EngineDescriptor(
        name = "kokoro",
        displayName = "Kokoro TTS",
        description = "Higher-quality English TTS with 11 voices (American + British). " +
            "Sounds noticeably more natural than Kitten — recommended if you have the " +
            "storage for ~100 MB.",
        downloadSizeBytes = 103_248_205L,
        installedSizeBytes = KOKORO_INSTALLED_SIZE_BYTES,
        isRecommended = true,
        archive = EngineArchive(
            url = "https://github.com/maxwhipw/marmalade-tts-android-engines/releases/download/v4/kokoro-int8-en-v0_19.tar.bz2",
            sha256 = "c9f0dd393615805b0bab050c340834d5e684e732aec91c0e860cd30e982c08bd",
            sizeBytes = 103_248_205L,
            // Sherpa-ONNX's tarball wraps everything in a top-level
            // directory of this name. The installer strips it during
            // extraction so the on-device layout is flat under
            // ${filesDir}/engines/kokoro/.
            archiveRoot = "kokoro-int8-en-v0_19/",
        ),
        licenseNotice = "LICENSES/kokoro-tts.md",
        licenseSummary = "Apache-2.0 model + GPL-3.0 espeak-ng phonemizer. " +
            "Higher-quality alternative to Kitten.",
    )

    /**
     * Kitten TTS — Sherpa-ONNX nano-en v0.8 int8 port. ~31 MB compressed
     * download, ~44 MB on-disk, 8 English voices.
     *
     * v0.1.0–v0.1.3 shipped the older v0.1 fp16 port; v0.1.4 upgrades to
     * v0.8 int8 (same revision the marmalade-tts CLI uses on Linux). The
     * model filename also changed from `model.fp16.onnx` to
     * `model.int8.onnx` — [KittenEngine] reads from the new path.
     *
     * Demoted from `isRecommended = true` in v0.1.9 when Kokoro joined the
     * catalog. Kitten stays available as the smaller-footprint option.
     */
    private val KITTEN: EngineDescriptor = EngineDescriptor(
        name = "kitten",
        displayName = "Kitten TTS",
        description = "Small, fast English TTS with 8 voices. Downloads in under a minute " +
            "and fits in ~45 MB on-disk — the lightweight alternative to Kokoro.",
        downloadSizeBytes = 31_220_690L,
        installedSizeBytes = KITTEN_INSTALLED_SIZE_BYTES,
        isRecommended = false,
        archive = EngineArchive(
            url = "https://github.com/maxwhipw/marmalade-tts-android-engines/releases/download/v3/kitten-nano-en-v0_8-int8.tar.bz2",
            sha256 = "6fa5be852612ce761094ba74ee6123b4fc4acfefa79bf64dc63acae4a83af2fd",
            sizeBytes = 31_220_690L,
            // Sherpa-ONNX's tarball wraps everything in a top-level
            // directory of this name. The installer strips it during
            // extraction so the on-device layout is flat under
            // ${filesDir}/engines/kitten/.
            archiveRoot = "kitten-nano-en-v0_8-int8/",
        ),
        licenseNotice = "LICENSES/kitten-tts.md",
        licenseSummary = "Includes GPL-3.0 components (espeak-ng phonemizer).",
    )

    /**
     * Every engine the app knows how to install. Read-only.
     *
     * Order is the display order (onboarding cards + Settings → Engines).
     * Kokoro first because it is the recommended default; Kitten second
     * as the lightweight alternative.
     */
    val all: List<EngineDescriptor> = listOf(KOKORO, KITTEN)

    /** Lookup by [EngineDescriptor.name]. Returns null for unknown engines. */
    fun byName(name: String): EngineDescriptor? = all.firstOrNull { it.name == name }
}
