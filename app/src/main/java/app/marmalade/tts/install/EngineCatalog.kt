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
 * @property developerOnly     True for the legacy sherpa-onnx engines that
 *                             the direct-ORT engines superseded. Hidden from
 *                             the user-facing engine lists (manage screen,
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
    private const val KITTEN_DIRECT_INSTALLED_SIZE_BYTES: Long = 72_862_079L
    private const val KITTEN_DIRECT_MINI_INSTALLED_SIZE_BYTES: Long = 94_363_897L
    private const val KOKORO_DIRECT_INSTALLED_SIZE_BYTES: Long = 482_162_181L
    private const val POCKET_TTS_INSTALLED_SIZE_BYTES: Long = 218_888_871L
    private const val POCKET_TTS_DEV_INSTALLED_SIZE_BYTES: Long = POCKET_TTS_INSTALLED_SIZE_BYTES

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
        description = "Legacy engine, kept for comparison — superseded by Kokoro " +
            "Direct, which has the same 53 voices and 9 languages but runs faster " +
            "and switches language correctly. Use Kokoro Direct instead. (Runs on " +
            "the older sherpa-onnx engine.)",
        downloadSizeBytes = 349_418_188L,
        installedSizeBytes = KOKORO_V1_0_INSTALLED_SIZE_BYTES,
        isRecommended = false,
        developerOnly = true,
        archive = EngineArchive(
            url = "https://github.com/maxwhipw/marmalade-tts-android-engines/releases/download/v6/kokoro-multi-lang-v1_0.tar.bz2",
            sha256 = "c133d26353d776da730870dac7da07dbfc9a5e3bc80cc5e8e83ab6e823be7046",
            sizeBytes = 349_418_188L,
            archiveRoot = "kokoro-multi-lang-v1_0/",
        ),
        licenseNotice = "LICENSES/kokoro-tts.md",
        licenseSummary = "Apache-2.0 model + GPL-3.0 espeak-ng phonemizer. " +
            "53 voices across 9 languages — legacy engine, use Kokoro Direct.",
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
        description = "Legacy Mandarin-focused engine, kept for comparison — 100 " +
            "Mandarin voices plus 3 English. Superseded by Kokoro Direct for most " +
            "uses. (Runs on the older sherpa-onnx engine.)",
        downloadSizeBytes = 364_816_464L,
        installedSizeBytes = KOKORO_V1_1_INSTALLED_SIZE_BYTES,
        isRecommended = false,
        developerOnly = true,
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
        description = "Legacy small English engine, kept for comparison — " +
            "superseded by Kitten Direct, which runs the same model faster. " +
            "Use Kitten Direct instead. (Runs on the older sherpa-onnx engine.)",
        downloadSizeBytes = 63_815_222L,
        installedSizeBytes = KITTEN_NANO_INSTALLED_SIZE_BYTES,
        isRecommended = false,
        developerOnly = true,
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
        description = "Legacy English engine, kept for comparison — superseded by " +
            "Kitten Direct Mini, which runs the same larger model faster. Use " +
            "Kitten Direct Mini instead. (Runs on the older sherpa-onnx engine.)",
        downloadSizeBytes = 67_547_594L,
        installedSizeBytes = KITTEN_MINI_INSTALLED_SIZE_BYTES,
        isRecommended = false,
        developerOnly = true,
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
     * Kitten Direct v0.8 (`kitten-direct-v0_8`) — Kitten TTS without
     * sherpa-onnx. Same 15M-param KittenML acoustic model as
     * [KITTEN_NANO] / [KITTEN_MINI], same 8 voices — but the GPL-3.0
     * espeak-ng phonemizer that the sherpa engines compile in is
     * replaced with the BSD-3 OpenPhonemizer running directly on
     * `onnxruntime-android`.
     *
     * Bundle size is bigger than the sherpa-Kitten Nano bundle (109 MiB
     * vs 61 MiB) because the phonemizer ONNX (~59 MiB) now ships inside
     * the asset pack instead of being statically linked into the
     * sherpa-onnx native library. The trade-off is no GPL components
     * anywhere in the runtime or assets.
     */
    private val KITTEN_DIRECT: EngineDescriptor = EngineDescriptor(
        name = "kitten-direct-v0_8",
        displayName = "Kitten Direct (v0.8)",
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
        licenseSummary = "Apache-2.0 model + GPL-3.0 espeak-ng phonemizer (in bundle, not APK).",
    )

    /**
     * Kitten Direct Mini v0.8 (`kitten-direct-mini-v0_8`) — the 80M-parameter
     * KittenML model on the same direct-ORT path as [KITTEN_DIRECT] (nano,
     * 15M). Larger model, marginally better audio; same 8 voices, same
     * espeak-ng phonemizer. The direct-ORT replacement for the sherpa
     * [KITTEN_MINI] engine.
     *
     * Speed prior differs from nano: Mini is correctly paced at speed=1.0
     * (sherpa metadata reports all-1.0 priors), so [KittenDirectMiniEngine]
     * applies no compensation — see its kdoc.
     */
    private val KITTEN_DIRECT_MINI: EngineDescriptor = EngineDescriptor(
        name = "kitten-direct-mini-v0_8",
        displayName = "Kitten Direct Mini (v0.8)",
        description = "A step up in quality from Kitten Direct while staying fast " +
            "and light — the same 8 English voices with a larger model, and still " +
            "a small download. Runs fully on your device with a bundled espeak-ng " +
            "phonemizer. (80M-parameter model vs Kitten Direct's 15M.)",
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
        licenseSummary = "Apache-2.0 model + GPL-3.0 espeak-ng phonemizer (in bundle, not APK).",
    )

    /**
     * Kokoro Direct v1.0 (`kokoro-direct-v1_0`) — Kokoro v1.0 without
     * sherpa-onnx. Same 53-voice multi-language KokoroML acoustic model
     * as [KOKORO_V1_0], same speaker ordering — but the inference runs
     * on `onnxruntime-android` directly with the same Pocket-style
     * optimizations as KittenDirect (XNNPACK EP, thread autodetect,
     * direct ByteBuffers, etc.). Espeak-ng (GPL-3.0) ships in the
     * bundle and is dlopen'd at runtime by a tiny MIT JNI shim — no
     * GPL code enters the APK.
     *
     * v17 added `lexicon-zh.txt` (sherpa's pre-baked misaki+pypinyin Han→IPA
     * table) for Mandarin. v18 adds `openjtalk_dic/` (Open JTalk's
     * naist-jdic, BSD-3, ~103 MB extracted) for Japanese — kanji→kana reading
     * + cutlet-style IPA conversion, the G2P Kokoro v1.0 was trained with.
     * espeak (GPL-3.0) now only handles the European languages it does well
     * (en/es/fr/it/hi/pt); zh and ja have dedicated bundled pipelines.
     */
    private val KOKORO_DIRECT: EngineDescriptor = EngineDescriptor(
        name = "kokoro-direct-v1_0",
        displayName = "Kokoro Direct (v1.0)",
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
        licenseSummary = "Apache-2.0 model + GPL-3.0 espeak-ng (European langs) + " +
            "BSD-3 Open JTalk (Japanese), all in bundle, not APK.",
    )

    /**
     * Pocket TTS English v2026_04 (`pocket-tts-en-v2026_04-mixed`). Kyutai
     * Labs' Latent Space Diffusion model, run on Microsoft
     * `onnxruntime-android` directly (no sherpa-onnx in this path). 8
     * predefined voices plus user-cloned voices via the `mimi_encoder`
     * pipeline.
     *
     * Selective quantization per upstream
     * [PR #147](https://github.com/kyutai-labs/pocket-tts/pull/147):
     * int8 only the transformer (`flow_lm_main`), keep the audio decoder
     * (`mimi_decoder`), encoder (`mimi_encoder`), flow head (`flow_lm_flow`)
     * and text conditioner at fp32. v9 of this bundle blanket-quantized
     * everything and the result was audibly tinny; the mixed variant
     * (v10, this entry) is the quality fix.
     *
     * Apache-2.0 throughout: model weights, runtime, and the tokenizer.
     * No espeak dependency — Pocket does its own phonemization upstream
     * of the ONNX export, so this is the only catalog entry whose
     * `licenseSummary` doesn't flag GPL contamination.
     */
    private val POCKET_TTS_EN: EngineDescriptor = EngineDescriptor(
        name = "pocket-tts-en-v2026_04",
        displayName = "Pocket TTS (English, 2026-04)",
        description = "The most expressive English voices, and the only engine " +
            "that can clone a voice from your own audio — 8 built-in voices. " +
            "English only and heavier to run than Kitten, so best on capable " +
            "phones. Kyutai Labs' model in a mixed-precision build for clean output.",
        downloadSizeBytes = 98_264_623L,
        installedSizeBytes = POCKET_TTS_INSTALLED_SIZE_BYTES,
        isRecommended = false,
        archive = EngineArchive(
            url = "https://github.com/maxwhipw/marmalade-tts-android-engines/releases/download/v10/pocket-tts-en-v2026_04-mixed.tar.bz2",
            sha256 = "d4faf0e09e2c0f3f0f97221670e193893e5aa8f17e3812fb54ed3ef13fffc2f1",
            sizeBytes = 98_264_623L,
            archiveRoot = "pocket-tts-en/",
        ),
        licenseNotice = "LICENSES/pocket-tts.md",
        licenseSummary = "Apache-2.0 model + MIT runtime (onnxruntime-android). " +
            "No GPL components.",
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
        licenseSummary = "Apache-2.0 model + MIT runtime. Diagnostic build — same " +
            "bundle as Pocket TTS.",
    )

    /**
     * Every engine the app knows how to install. Read-only.
     *
     * This is the canonical catalog order (grouped by family). User-facing
     * *display* order comes from [visibleTo], which sorts production engines
     * ahead of the developer-only ones.
     */
    val all: List<EngineDescriptor> = listOf(
        KOKORO_V1_0,
        KOKORO_V1_1,
        KOKORO_DIRECT,
        KITTEN_NANO,
        KITTEN_MINI,
        KITTEN_DIRECT,
        KITTEN_DIRECT_MINI,
        POCKET_TTS_EN,
        POCKET_TTS_EN_DEV,
    )

    /** Lookup by [EngineDescriptor.name]. Returns null for unknown engines. */
    fun byName(name: String): EngineDescriptor? = all.firstOrNull { it.name == name }

    /** Names of the [EngineDescriptor.developerOnly] (legacy sherpa) engines. */
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
