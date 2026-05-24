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

    // Unpacked sizes (sum of file sizes after tar extraction). Compute via
    //   find <extracted-dir> -type f -exec stat -c %s {} + | awk '{s+=$1} END {print s}'
    private const val KOKORO_V1_0_INSTALLED_SIZE_BYTES: Long = 400_786_089L
    private const val KOKORO_V1_1_INSTALLED_SIZE_BYTES: Long = 426_654_376L
    private const val KITTEN_NANO_INSTALLED_SIZE_BYTES: Long = 78_049_671L
    private const val KITTEN_MINI_INSTALLED_SIZE_BYTES: Long = 99_550_582L

    /**
     * Kokoro v1.0 multi-lang fp32 (`kokoro-multi-lang-v1_0`). 53 voices
     * across 9 languages — American + British English, Spanish, French,
     * Hindi, Italian, Japanese, Brazilian Portuguese, Mandarin.
     *
     * Recommended default since v0.2.0 split the Kokoro engine family.
     * v1.0 has noticeably better English audio quality than v1.1 per
     * pre-ship A/B; v1.1 ships alongside for users who want its 100
     * Mandarin voices.
     */
    private val KOKORO_V1_0: EngineDescriptor = EngineDescriptor(
        name = "kokoro-v1_0",
        displayName = "Kokoro v1.0",
        description = "53 voices across 9 languages. Recommended for " +
            "English-primary use — best audio quality in the Kokoro family. " +
            "Covers American + British English, Spanish, French, Hindi, Italian, " +
            "Japanese, Brazilian Portuguese, and Mandarin.",
        downloadSizeBytes = 349_418_188L,
        installedSizeBytes = KOKORO_V1_0_INSTALLED_SIZE_BYTES,
        isRecommended = true,
        archive = EngineArchive(
            url = "https://github.com/maxwhipw/marmalade-tts-android-engines/releases/download/v6/kokoro-multi-lang-v1_0.tar.bz2",
            sha256 = "c133d26353d776da730870dac7da07dbfc9a5e3bc80cc5e8e83ab6e823be7046",
            sizeBytes = 349_418_188L,
            archiveRoot = "kokoro-multi-lang-v1_0/",
        ),
        licenseNotice = "LICENSES/kokoro-tts.md",
        licenseSummary = "Apache-2.0 model + GPL-3.0 espeak-ng phonemizer. " +
            "53 voices across 9 languages — recommended default.",
    )

    /**
     * Kokoro v1.1 multi-lang fp32 (`kokoro-multi-lang-v1_1`). 103 voices —
     * only 3 English (af_maple, af_sol, bf_vale) and 100 Mandarin
     * (zf_001..zm_100). Mandarin-specialist variant; English audio
     * quality is lower than v1.0 per pre-ship A/B, so v1.0 stays the
     * recommended default for English-primary use.
     *
     * Installs alongside v1.0 — they're independent engines with disjoint
     * voice IDs. Users opt into v1.1 if they want the Mandarin catalog.
     */
    private val KOKORO_V1_1: EngineDescriptor = EngineDescriptor(
        name = "kokoro-v1_1",
        displayName = "Kokoro v1.1 (Mandarin)",
        description = "100 Mandarin voices plus 3 English (af_maple, af_sol, " +
            "bf_vale). Install for Mandarin TTS — v1.0 has better English " +
            "audio quality, so install both if you need both languages.",
        downloadSizeBytes = 364_816_464L,
        installedSizeBytes = KOKORO_V1_1_INSTALLED_SIZE_BYTES,
        isRecommended = false,
        archive = EngineArchive(
            url = "https://github.com/maxwhipw/marmalade-tts-android-engines/releases/download/v8/kokoro-multi-lang-v1_1.tar.bz2",
            sha256 = "a3f4c73d043860e3fd2e5b06f36795eb81de0fc8e8de6df703245edddd87dbad",
            sizeBytes = 364_816_464L,
            archiveRoot = "kokoro-multi-lang-v1_1/",
        ),
        licenseNotice = "LICENSES/kokoro-tts.md",
        licenseSummary = "Apache-2.0 model + GPL-3.0 espeak-ng phonemizer. " +
            "103 voices (3 English + 100 Mandarin) — Mandarin specialist.",
    )

    /**
     * Kitten Nano v0.8 fp32 (`kitten-nano-en-v0_8-fp32`). 15M-parameter
     * English model, 8 voices, ~61 MB compressed. The lightweight
     * default Kitten variant. v0.1.22 swapped from int8 to fp32 due to
     * audible quantisation artifacts in the int8 build.
     */
    private val KITTEN_NANO: EngineDescriptor = EngineDescriptor(
        name = "kitten-nano-v0_8",
        displayName = "Kitten Nano (v0.8)",
        description = "Small, fast English TTS — 8 voices, 15M parameters. " +
            "The lightweight alternative to Kokoro. Kitten Mini ships " +
            "alongside as a quality upgrade at roughly the same download size.",
        downloadSizeBytes = 63_815_222L,
        installedSizeBytes = KITTEN_NANO_INSTALLED_SIZE_BYTES,
        isRecommended = false,
        archive = EngineArchive(
            url = "https://github.com/maxwhipw/marmalade-tts-android-engines/releases/download/v7/kitten-nano-en-v0_8-fp32.tar.bz2",
            sha256 = "16092117bfe591ddcd58d078e1454603b8e1caea46f85653b2c2efae76bd883e",
            sizeBytes = 63_815_222L,
            archiveRoot = "kitten-nano-en-v0_8-fp32/",
        ),
        licenseNotice = "LICENSES/kitten-tts.md",
        licenseSummary = "Includes GPL-3.0 components (espeak-ng phonemizer).",
    )

    /**
     * Kitten Mini v0.8 (`kitten-mini-en-v0_8`). 80M-parameter English
     * model with upstream's deliberate mixed-precision quantisation
     * (fp32 + fp16 + selective int8/uint8 — NOT blanket dynamic int8).
     * ~5.3x more parameters than nano; marginal but audible quality lift
     * per the pre-ship A/B. Same compressed bundle size as nano (~64 MB).
     */
    private val KITTEN_MINI: EngineDescriptor = EngineDescriptor(
        name = "kitten-mini-v0_8",
        displayName = "Kitten Mini (v0.8)",
        description = "Larger English TTS — 8 voices, 80M parameters. " +
            "Same voice names as Kitten Nano but a fundamentally larger " +
            "model with marginally better audio.",
        downloadSizeBytes = 67_547_594L,
        installedSizeBytes = KITTEN_MINI_INSTALLED_SIZE_BYTES,
        isRecommended = false,
        archive = EngineArchive(
            url = "https://github.com/maxwhipw/marmalade-tts-android-engines/releases/download/v8/kitten-mini-en-v0_8.tar.bz2",
            sha256 = "518f9b130320f690d5b5476df77bde4215fca67773cda16710318e5081234b9d",
            sizeBytes = 67_547_594L,
            archiveRoot = "kitten-mini-en-v0_8/",
        ),
        licenseNotice = "LICENSES/kitten-tts.md",
        licenseSummary = "Includes GPL-3.0 components (espeak-ng phonemizer).",
    )

    /**
     * Every engine the app knows how to install. Read-only.
     *
     * Order is the display order: Kokoro family first (recommended
     * default = v1.0), then Kitten family (lighter alternatives).
     */
    val all: List<EngineDescriptor> = listOf(
        KOKORO_V1_0,
        KOKORO_V1_1,
        KITTEN_NANO,
        KITTEN_MINI,
    )

    /** Lookup by [EngineDescriptor.name]. Returns null for unknown engines. */
    fun byName(name: String): EngineDescriptor? = all.firstOrNull { it.name == name }
}
