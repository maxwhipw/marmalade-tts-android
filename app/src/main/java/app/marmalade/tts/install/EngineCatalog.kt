package app.marmalade.tts.install

// -----------------------------------------------------------------------------
// Data flow
// -----------------------------------------------------------------------------
//   EngineInstaller.install(name)
//     │
//     ├── EngineCatalog.byName(name)  ──► EngineDescriptor
//     │                                       │
//     │                                       ├── files: List<EngineFile>
//     │                                       │     (relativePath, url, sha256, size)
//     │                                       │
//     │                                       └── displayName, description,
//     │                                           downloadSizeBytes, licenseSummary
//     │
//     ▼
//   For each EngineFile: HTTP GET → sha256 check → write to scratch dir
//
//   UI (Onboarding / EnginesScreen)
//     │
//     └── EngineCatalog.all  ──► render one card per engine, show
//                                 description, size, licenseSummary
// -----------------------------------------------------------------------------

/**
 * Description of a single file that makes up an installable engine bundle.
 *
 * The installer downloads each file independently (concurrent or serial),
 * verifies it against [sha256], and stages it under
 * `${filesDir}/engines/<engine>.tmp/<relativePath>` before atomic-renaming
 * the temp dir to `${filesDir}/engines/<engine>/` once every file is
 * accounted for.
 *
 * @property relativePath  Path relative to the engine's install directory,
 *                         e.g. `"model.fp16.onnx"` or
 *                         `"espeak-ng-data/intonations"`. Forward slashes
 *                         only; resolved via `java.io.File(parent, path)`.
 * @property url           Absolute HTTPS URL the installer GETs.
 * @property sha256        Hex-encoded SHA-256 of the file's bytes, lower
 *                         case. May be the sentinel value
 *                         [EngineCatalog.SHA256_PENDING] for files whose
 *                         hashes haven't been pinned yet — see STUBS.md.
 * @property sizeBytes     Expected file size in bytes (for progress UI).
 */
data class EngineFile(
    val relativePath: String,
    val url: String,
    val sha256: String,
    val sizeBytes: Long,
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
 * @property downloadSizeBytes Sum of all [files] sizes — informs the
 *                             "this will download ~42 MB" copy.
 * @property installedSizeBytes Approximate on-disk size after extraction
 *                             (for option-3-style file-by-file installs
 *                             this equals [downloadSizeBytes]; for archive
 *                             installs it would be larger).
 * @property isRecommended     True for the engine pre-checked in the
 *                             onboarding wizard. v0.1 only ships Kitten,
 *                             which is the recommended default.
 * @property files             Files to download, in any order. Empty list
 *                             would describe a no-op engine; not allowed.
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
    val files: List<EngineFile>,
    val licenseNotice: String,
    val licenseSummary: String,
) {
    init {
        require(name.isNotBlank()) { "engine name must not be blank" }
        require(files.isNotEmpty()) { "engine $name has no files; would never install" }
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
     * Sentinel hash value indicating "we don't have the real sha256 pinned
     * yet — verify softly with a logged warning instead of rejecting." The
     * installer treats this as a deferred-verification marker. Once the
     * real hash is known (e.g. after running
     * `scripts/generate-kitten-manifest.py`), replace the placeholder.
     *
     * Documented in STUBS.md so reviewers don't mistake this for a missing
     * verification step.
     */
    const val SHA256_PENDING: String = "PENDING_VERIFICATION"

    /**
     * Base URL for the GitHub-Releases-hosted Kitten engine bundle on the
     * dedicated `marmalade-tts-android-engines` repo. The URL pattern is
     * `<base>/<flatAssetName>` — GitHub release-asset filenames are flat,
     * so directory separators in [EngineFile.relativePath] are replaced
     * with `__` to produce the asset name.
     *
     * v0.1.0 / v0.1.1 of the app pinned a Hugging Face mirror that the
     * upstream maintainer later removed (returned HTTP 401). The dedicated
     * mirror puts us in control of the URL contract — see the engines
     * repo's README for the licensing rationale (GPL-3.0 as a whole
     * because espeak-ng-data is GPL-3.0).
     *
     * Pulled out into a constant so the generator script + the static
     * catalog can stay in sync.
     */
    private const val KITTEN_BASE: String =
        "https://github.com/maxwhipw/marmalade-tts-android-engines/releases/download/v1"

    /**
     * Top-level Kitten files (everything outside `espeak-ng-data/`).
     *
     * Sizes + sha256 captured from the Sherpa-ONNX
     * `kitten-nano-en-v0_1-fp16.tar.bz2` bundle dated 2026-05-12; verified
     * byte-identical to the engines-repo mirror at refresh time.
     * Refresh via the procedure documented in
     * `scripts/generate-kitten-manifest.py`.
     */
    private val KITTEN_TOP_LEVEL: List<EngineFile> = listOf(
        EngineFile(
            relativePath = "model.fp16.onnx",
            url = "$KITTEN_BASE/model.fp16.onnx",
            sha256 = "6b42d25df767db408d95738b464f02168a9cfb76367c1b2b9e90095485981407",
            sizeBytes = 23_848_586L,
        ),
        EngineFile(
            relativePath = "voices.bin",
            url = "$KITTEN_BASE/voices.bin",
            sha256 = "138cf3a7afd0ebf1f9d6fb72f49e960ef8405252eaff5d130cf3fba1b038a741",
            sizeBytes = 8_192L,
        ),
        EngineFile(
            relativePath = "tokens.txt",
            url = "$KITTEN_BASE/tokens.txt",
            sha256 = "934a4188addc7665dd3410256bb622169242357fbb99d840d9351209b486dabb",
            sizeBytes = 1_064L,
        ),
    )

    /**
     * espeak-ng-data manifest — all 355 phonemizer data files. Generated by
     * [scripts/generate-kitten-manifest.py] against a locally-extracted
     * bundle. Each entry carries its real sha256 + size so the installer
     * verifies every file independently.
     *
     * The installer treats this as "all entries are independently
     * downloadable" — keeping each file as its own URL avoids needing a
     * bzip2 decoder (bzip2 isn't in Java stdlib and adding it requires a
     * gradle dependency that we cannot add — see project STUBS.md).
     */
    private val KITTEN_ESPEAK_DATA: List<EngineFile> = KittenEspeakDataManifest.FILES

    /** Kitten TTS — small (~42 MB on-disk), 8 English voices, runs on every device. */
    private val KITTEN: EngineDescriptor = EngineDescriptor(
        name = "kitten",
        displayName = "Kitten TTS",
        description = "Small, fast English TTS with 8 voices. Recommended starter engine — " +
            "runs offline on every device and downloads in under a minute.",
        downloadSizeBytes = (KITTEN_TOP_LEVEL + KITTEN_ESPEAK_DATA).sumOf { it.sizeBytes },
        installedSizeBytes = (KITTEN_TOP_LEVEL + KITTEN_ESPEAK_DATA).sumOf { it.sizeBytes },
        isRecommended = true,
        files = KITTEN_TOP_LEVEL + KITTEN_ESPEAK_DATA,
        licenseNotice = "LICENSES/kitten-tts.md",
        licenseSummary = "Includes GPL-3.0 components (espeak-ng phonemizer).",
    )

    /** Every engine the app knows how to install. Read-only. */
    val all: List<EngineDescriptor> = listOf(KITTEN)

    /** Lookup by [EngineDescriptor.name]. Returns null for unknown engines. */
    fun byName(name: String): EngineDescriptor? = all.firstOrNull { it.name == name }
}
