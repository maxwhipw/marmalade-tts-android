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
 *               For Kitten Direct this is `"kitten-direct-v0_8/"` (the
 *               directory name the bundle's tarball uses).
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
 * @property developerOnly     True for diagnostic engines (currently the
 *                             Pocket clean-reference build) that aren't
 *                             meant for normal users. Hidden from the
 *                             user-facing engine lists (manage screen,
 *                             onboarding, alias + voice pickers) unless the
 *                             "show developer engines" setting is on — kept
 *                             in the catalog so existing aliases that point
 *                             at them still resolve + synthesize. Routing
 *                             ([byName]) never filters on this flag.
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
    val developerOnly: Boolean = false,
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
 * Ships Kokoro Direct (recommended default), Kitten Direct + Kitten Direct
 * Mini, and Pocket TTS — all running on `onnxruntime-android` directly. The
 * list order is also the display order in the onboarding wizard and
 * Settings → Engines screen.
 */
object EngineCatalog {

    // Unpacked sizes (sum of file sizes after tar extraction). Compute via
    //   find <extracted-dir> -type f -exec stat -c %s {} + | awk '{s+=$1} END {print s}'
    private const val KITTEN_DIRECT_INSTALLED_SIZE_BYTES: Long = 72_862_079L
    private const val KITTEN_DIRECT_MINI_INSTALLED_SIZE_BYTES: Long = 94_363_897L
    private const val KOKORO_DIRECT_INSTALLED_SIZE_BYTES: Long = 482_162_181L
    // v21 bundle: 6 commercial-safe voices (cosette/jean dropped — CC-BY-NC-4.0).
    private const val POCKET_TTS_INSTALLED_SIZE_BYTES: Long = 217_288_756L
    // The clean-reference dev engine still pins the older 8-voice v10 archive
    // (~1.6 MB larger); it's developer-only, so the small estimate drift is
    // immaterial.
    private const val POCKET_TTS_DEV_INSTALLED_SIZE_BYTES: Long = POCKET_TTS_INSTALLED_SIZE_BYTES

    /**
     * Kitten Direct v0.8 (`kitten-direct-v0_8`) — the 15M-param KittenML
     * acoustic model, 8 voices, run directly on
     * `onnxruntime-android`. Phonemization is espeak-ng
     * (GPL-3.0-or-later), compiled from source into the APK as
     * libespeak-ng.so and dlopen'd at runtime by the MIT JNI shim; the
     * bundle supplies the espeak-ng-data dictionaries. (An earlier design
     * used a BSD-3 OpenPhonemizer ONNX to avoid GPL entirely; it was
     * dropped because IPA-convention mismatches degraded quality.)
     */
    private val KITTEN_DIRECT: EngineDescriptor = EngineDescriptor(
        name = "kitten-direct-v0_8",
        displayName = "Kitten Nano (v0.8)",
        description = "Small and fast — the lightest download and the quickest " +
            "to start speaking. English only, 8 voices. A good pick when you want " +
            "speed and a small footprint over the widest language coverage. Runs " +
            "fully on your device with a bundled espeak-ng phonemizer.",
        downloadSizeBytes = 61_007_823L,
        installedSizeBytes = KITTEN_DIRECT_INSTALLED_SIZE_BYTES,
        isRecommended = false,
        archive = EngineArchive(
            // v16: same bundle as v14, recompressed as tar.gz for ~5× faster
            // on-device install (native zlib vs pure-Java bzip2). Installer
            // detects format from magic bytes; v14 .tar.bz2 still works for
            // anyone with cached URLs.
            url = "https://github.com/maxwhipw/marmalade-tts-android-engines/releases/download/v16/kitten-direct-v0_8.tar.gz",
            sha256 = "f31b9f2ca81c53099e7f0490fa1f531bb6749a6d5de3c0f8e000cf29cab70073",
            sizeBytes = 61_007_823L,
            archiveRoot = "kitten-direct-v0_8/",
        ),
        licenseNotice = "LICENSES/kitten-direct.md",
        licenseSummary = "Apache-2.0 model; phonemized by the app\u2019s built-in espeak-ng (GPL-3.0-or-later).",
    )

    /**
     * Kitten Direct Mini v0.8 (`kitten-direct-mini-v0_8`) — the 80M-parameter
     * KittenML model on the same direct-ORT path as [KITTEN_DIRECT] (nano,
     * 15M). Larger model, marginally better audio; same 8 voices, same
     * espeak-ng phonemizer.
     *
     * Speed prior differs from nano: Mini is correctly paced at speed=1.0,
     * so [KittenDirectMiniEngine] applies no compensation — see its kdoc.
     */
    private val KITTEN_DIRECT_MINI: EngineDescriptor = EngineDescriptor(
        name = "kitten-direct-mini-v0_8",
        displayName = "Kitten Mini (v0.8)",
        description = "A step up in quality from Kitten Nano while staying fast " +
            "and light — the same 8 English voices with a larger model, and still " +
            "a small download. Runs fully on your device with a bundled espeak-ng " +
            "phonemizer. (80M-parameter model vs Kitten Nano's 15M.)",
        downloadSizeBytes = 65_470_846L,
        installedSizeBytes = KITTEN_DIRECT_MINI_INSTALLED_SIZE_BYTES,
        isRecommended = false,
        archive = EngineArchive(
            url = "https://github.com/maxwhipw/marmalade-tts-android-engines/releases/download/v20/kitten-direct-mini-v0_8.tar.gz",
            sha256 = "6d2c75719f8752a90a2832bcad53c2592dad7a095e0c93020a6f9f8d2b66274a",
            sizeBytes = 65_470_846L,
            archiveRoot = "kitten-direct-mini-v0_8/",
        ),
        licenseNotice = "LICENSES/kitten-direct.md",
        licenseSummary = "Apache-2.0 model; phonemized by the app\u2019s built-in espeak-ng (GPL-3.0-or-later).",
    )

    /**
     * Kokoro Direct v1.0 (`kokoro-direct-v1_0`) — the 53-voice
     * multi-language KokoroML v1.0 acoustic model. The inference runs
     * on `onnxruntime-android` directly with the same Pocket-style
     * optimizations as KittenDirect (XNNPACK EP, thread autodetect,
     * direct ByteBuffers, etc.). Espeak-ng (GPL-3.0-or-later) is compiled
     * from source into the APK and dlopen'd at runtime by a tiny MIT
     * JNI shim; the bundle supplies espeak-ng-data.
     *
     * v17 added `lexicon-zh.txt` (sherpa's pre-baked misaki+pypinyin Han→IPA
     * table) for Mandarin. v18 adds `openjtalk_dic/` (Open JTalk's
     * naist-jdic, BSD-3, ~103 MB extracted) for Japanese — kanji→kana reading
     * + cutlet-style IPA conversion, the G2P Kokoro v1.0 was trained with.
     * espeak now only handles the European languages it does well
     * (en/es/fr/it/hi/pt); zh and ja have dedicated bundled pipelines.
     */
    private val KOKORO_DIRECT: EngineDescriptor = EngineDescriptor(
        name = "kokoro-direct-v1_0",
        displayName = "Kokoro (v1.0)",
        description = "Best all-round quality with the widest language support — " +
            "53 voices across 9 languages, including English, Spanish, French, " +
            "Italian, Hindi, Portuguese, Japanese, and Mandarin. Recommended for " +
            "most people. Runs fully on your device; Japanese and Mandarin use " +
            "bundled pronunciation data and the other languages use espeak-ng, " +
            "all loaded at runtime.",
        downloadSizeBytes = 360_529_858L,
        installedSizeBytes = KOKORO_DIRECT_INSTALLED_SIZE_BYTES,
        isRecommended = true,
        archive = EngineArchive(
            // v18: adds openjtalk_dic/ (~24 MB compressed) for Japanese G2P via
            // the Open JTalk frontend + cutlet IPA conversion. Otherwise the
            // same payload as v17 (which added lexicon-zh.txt for Mandarin).
            url = "https://github.com/maxwhipw/marmalade-tts-android-engines/releases/download/v18/kokoro-direct-v1_0.tar.gz",
            sha256 = "30493e0a054fb54158db155fa109d3e1d5e5a1ea44919238a4ea988887cd1de0",
            sizeBytes = 360_529_858L,
            archiveRoot = "kokoro-direct-v1_0/",
        ),
        licenseNotice = "LICENSES/kokoro-direct.md",
        licenseSummary = "Apache-2.0 model + BSD-3 Open JTalk dictionary (Japanese); " +
            "European langs phonemized by the app\u2019s built-in espeak-ng (GPL-3.0-or-later).",
    )

    /**
     * Pocket TTS English v2026_04 (`pocket-tts-en-v2026_04-mixed`). Kyutai
     * Labs' Latent Space Diffusion model, run on Microsoft
     * `onnxruntime-android` directly (no sherpa-onnx in this path). 6
     * commercial-safe predefined voices (the upstream 8 minus `cosette`
     * and `jean`, which are CC-BY-NC-4.0; see below).
     *
     * Selective quantization per upstream
     * [PR #147](https://github.com/kyutai-labs/pocket-tts/pull/147):
     * int8 only the transformer (`flow_lm_main`), keep the audio decoder
     * (`mimi_decoder`), encoder (`mimi_encoder`), flow head (`flow_lm_flow`)
     * and text conditioner at fp32. v9 of this bundle blanket-quantized
     * everything and the result was audibly tinny; the mixed variant is the
     * quality fix. The v21 bundle re-rolls the v10 mixed model with the two
     * non-commercial voices removed.
     *
     * Licensing: the Kyutai model **code** is MIT (not Apache-2.0). Each
     * predefined voice carries its own data license — the 6 we ship are
     * CC0 (`javert`, `marius`) or CC-BY-4.0 (`alba`, `azelma`, `eponine`,
     * `fantine`); attribution for the CC-BY voices is in CREDITS.md. No
     * espeak dependency — Pocket phonemizes upstream of the ONNX export, so
     * this is the only catalog entry without GPL contamination.
     */
    private val POCKET_TTS_EN: EngineDescriptor = EngineDescriptor(
        name = "pocket-tts-en-v2026_04",
        displayName = "Pocket TTS (English, 2026-04)",
        description = "The most expressive English voices — 6 built-in voices. " +
            "English only and heavier to run than Kitten, so best on capable " +
            "phones. Kyutai Labs' model in a mixed-precision build for clean output.",
        downloadSizeBytes = 97_291_178L,
        installedSizeBytes = POCKET_TTS_INSTALLED_SIZE_BYTES,
        isRecommended = false,
        archive = EngineArchive(
            url = "https://github.com/maxwhipw/marmalade-tts-android-engines/releases/download/v21/pocket-tts-en-v2026_04-mixed.tar.bz2",
            sha256 = "3a3ed2810afbae1f963f4f4e3ebde767c2d3c7c622cc544f23170f0ca755b452",
            sizeBytes = 97_291_178L,
            archiveRoot = "pocket-tts-en/",
        ),
        licenseNotice = "LICENSES/pocket-tts.md",
        licenseSummary = "MIT model code + MIT runtime (onnxruntime-android); " +
            "voices CC0 / CC-BY-4.0. No GPL components.",
    )

    /**
     * Pocket TTS Clean Reference (`pocket-tts-en-v2026_04-dev`) — the
     * same bundle as [POCKET_TTS_EN], installed to its own directory,
     * driven by [app.marmalade.tts.engine.PocketDevEngine] which is a
     * deliberately perf-free port of upstream `pocket_tts/models/
     * tts_model.py`. Used to A/B against the production [POCKET_TTS_EN]
     * inference path for diagnostics (the chunk-start artefact saga,
     * P-AC). Developer-only so regular users aren't tempted to install
     * a slower diagnostic engine; the two installs live side-by-side
     * and can be installed/uninstalled independently.
     */
    private val POCKET_TTS_EN_DEV: EngineDescriptor = EngineDescriptor(
        name = "pocket-tts-en-v2026_04-dev",
        displayName = "Pocket TTS — Clean Reference",
        description = "Diagnostic clean-room build of the Pocket TTS pipeline " +
            "— same model + voices as the regular Pocket engine, but running " +
            "through a deliberately perf-optimization-free Kotlin port of the " +
            "upstream Python inference loop. Used to isolate which production " +
            "optimization causes audio artefacts. Slower than the regular " +
            "Pocket engine; install both side-by-side to A/B them.",
        downloadSizeBytes = 98_264_623L,
        installedSizeBytes = POCKET_TTS_DEV_INSTALLED_SIZE_BYTES,
        isRecommended = false,
        developerOnly = true,
        archive = EngineArchive(
            url = "https://github.com/maxwhipw/marmalade-tts-android-engines/releases/download/v10/pocket-tts-en-v2026_04-mixed.tar.bz2",
            sha256 = "d4faf0e09e2c0f3f0f97221670e193893e5aa8f17e3812fb54ed3ef13fffc2f1",
            sizeBytes = 98_264_623L,
            archiveRoot = "pocket-tts-en/",
        ),
        licenseNotice = "LICENSES/pocket-tts.md",
        licenseSummary = "MIT model code + MIT runtime; voices CC0 / CC-BY-4.0. " +
            "Diagnostic build — same bundle as Pocket TTS.",
    )

    /**
     * Every engine the app knows how to install. Read-only.
     *
     * This is the canonical catalog order (grouped by family). User-facing
     * *display* order comes from [visibleTo], which sorts production engines
     * ahead of the developer-only ones.
     */
    val all: List<EngineDescriptor> = listOf(
        KOKORO_DIRECT,
        KITTEN_DIRECT,
        KITTEN_DIRECT_MINI,
        POCKET_TTS_EN,
        POCKET_TTS_EN_DEV,
    )

    /** Lookup by [EngineDescriptor.name]. Returns null for unknown engines. */
    fun byName(name: String): EngineDescriptor? = all.firstOrNull { it.name == name }

    /** Names of the [EngineDescriptor.developerOnly] (diagnostic) engines. */
    val developerOnlyNames: Set<String> =
        all.filter { it.developerOnly }.mapTo(mutableSetOf()) { it.name }

    /**
     * The catalog filtered + ordered for user-facing lists. When
     * [showDeveloper] is false, [developerOnly] engines are dropped; when
     * true they're included but sorted *after* the production engines (a
     * stable sort, so each group keeps its [all] order). Routing must keep
     * using [byName] — never this — so aliases on a hidden engine still
     * resolve.
     */
    fun visibleTo(showDeveloper: Boolean): List<EngineDescriptor> =
        (if (showDeveloper) all else all.filter { !it.developerOnly })
            .sortedBy { it.developerOnly }
}
