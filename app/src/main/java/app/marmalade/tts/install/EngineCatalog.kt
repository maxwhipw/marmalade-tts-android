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
 * Static catalog of installable engines. Kitten is the only entry in v0.1.
 *
 * Future engines (piper, kokoro, pocket) join this list as their bundles
 * are characterised. The list order is also the display order in the
 * onboarding wizard and Settings → Engines screen.
 */
object EngineCatalog {

    /**
     * Sum of unpacked Kitten file sizes. Carried over from the v0.1.0–
     * v0.1.2 per-file catalog where this figure was computed as
     * `(KITTEN_TOP_LEVEL + KITTEN_ESPEAK_DATA).sumOf { it.sizeBytes }`.
     * That came out to roughly 42 MB on-disk; the precise byte count
     * documented here is the pre-refactor sum so the "installed size"
     * copy in the UI stays accurate.
     *
     * Refresh procedure when bumping the bundle:
     *   tar -xjf kitten-nano-en-v0_1-fp16.tar.bz2
     *   find kitten-nano-en-v0_1-fp16 -type f -exec stat -c %s {} + | \
     *       awk '{s+=$1} END {print s}'
     */
    private const val KITTEN_INSTALLED_SIZE_BYTES: Long = 42_318_953L

    /** Kitten TTS — small (~42 MB on-disk), 8 English voices, runs on every device. */
    private val KITTEN: EngineDescriptor = EngineDescriptor(
        name = "kitten",
        displayName = "Kitten TTS",
        description = "Small, fast English TTS with 8 voices. Recommended starter engine — " +
            "runs offline on every device and downloads in under a minute.",
        downloadSizeBytes = 26_855_312L,
        installedSizeBytes = KITTEN_INSTALLED_SIZE_BYTES,
        isRecommended = true,
        archive = EngineArchive(
            url = "https://github.com/maxwhipw/marmalade-tts-android-engines/releases/download/v2/kitten-nano-en-v0_1-fp16.tar.bz2",
            sha256 = "f35dac93754fe2ac97c66e1f468311d0d2130f7f0f5a89bfa1197e09a0cbdec5",
            sizeBytes = 26_855_312L,
            // The upstream Sherpa-ONNX tarball wraps everything in a
            // top-level directory of this name. The installer strips it
            // during extraction so the on-device layout is flat under
            // ${filesDir}/engines/kitten/.
            archiveRoot = "kitten-nano-en-v0_1-fp16/",
        ),
        licenseNotice = "LICENSES/kitten-tts.md",
        licenseSummary = "Includes GPL-3.0 components (espeak-ng phonemizer).",
    )

    /** Every engine the app knows how to install. Read-only. */
    val all: List<EngineDescriptor> = listOf(KITTEN)

    /** Lookup by [EngineDescriptor.name]. Returns null for unknown engines. */
    fun byName(name: String): EngineDescriptor? = all.firstOrNull { it.name == name }
}
