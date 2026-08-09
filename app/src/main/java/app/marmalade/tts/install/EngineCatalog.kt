package app.marmalade.tts.install

import androidx.annotation.StringRes
import app.marmalade.tts.R

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
//     │                                       └── displayName, descriptionRes,
//     │                                           downloadSizeBytes,
//     │                                           installedSizeBytes,
//     │                                           licenseSummaryRes
//     │
//     ▼
//   Single HTTP GET → sha256 check → tar.bz2 extract → atomic rename
//
//   UI (Onboarding / EnginesScreen)
//     │
//     └── EngineCatalog.all  ──► render one card per engine, show
//                                 description, size, license summary
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
 * Relative synthesis-speed tier — the hero axis on the engine-select card
 * (the "A3 spec columns" design). [meterFill] is how many of the card's
 * four meter segments render filled, and it doubles as the colour key: the
 * meter is coloured by fill count (4 = green, 3 = light green, 2 = amber,
 * 1 = red — see EngineSpecColumn.speedMeterColor), so a shorter bar is also
 * a hotter colour and a single red tick reads as "slow", never as "one of
 * four good". FASTEST leads (Kitten), then FAST (Kokoro); the amber 2-tick
 * band is reserved for a mid engine between Kokoro and Pocket, HEAVY is the
 * heaviest (Pocket).
 */
enum class SpeedTier(val meterFill: Int) {
    FASTEST(4),
    FAST(3),
    HEAVY(1),
}

/**
 * How an engine's output quality is framed on the card. Deliberately NOT a
 * linear ranking — each engine leads on a different strength, so the "heavy"
 * Pocket engine is "most expressive", never "worst".
 */
enum class QualityTier {
    /** Kitten — good, natural. */
    NATURAL,

    /** Kokoro — best all-round quality + widest language coverage. */
    BEST_OVERALL,

    /** Pocket — most expressive, characterful English. */
    MOST_EXPRESSIVE,
}

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
 * @property descriptionRes    String resource for the one-paragraph pitch
 *                             shown on the install consent card and the
 *                             engine detail screen. Keep it short.
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
 * @property licenseSummaryRes String resource for the one-liner shown on the
 *                             install card, e.g. "Includes GPL-3.0
 *                             components (espeak-ng)."
 * @property taglineRes        String resource for the short one-line pitch
 *                             shown on the spec-column engine card. This is
 *                             the residual prose after the structured
 *                             Speed/Quality/Languages chrome takes over the
 *                             card.
 * @property speedTier         Relative synthesis-speed tier — the hero axis
 *                             on the engine-select card.
 * @property qualityTier       How this engine's output quality is framed (a
 *                             strength, not a ranking).
 * @property languageCodes     BCP-47 codes this engine can speak. Size 1 →
 *                             the card shows the single language's name; size
 *                             > 1 → it shows "<n> languages" with an info
 *                             affordance that lists them. For Kokoro this must
 *                             match the distinct locales in
 *                             [app.marmalade.tts.data.KokoroDirectVoiceCatalog]
 *                             (pinned by EngineCatalogTest).
 */
data class EngineDescriptor(
    val name: String,
    val displayName: String,
    @StringRes val descriptionRes: Int,
    val downloadSizeBytes: Long,
    val installedSizeBytes: Long,
    val isRecommended: Boolean,
    val archive: EngineArchive,
    val licenseNotice: String,
    @StringRes val licenseSummaryRes: Int,
    @StringRes val taglineRes: Int,
    val speedTier: SpeedTier,
    val qualityTier: QualityTier,
    val languageCodes: List<String>,
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
 * Ships Kitten Direct (recommended default, baked offline), Kokoro Direct,
 * and Pocket TTS — all running on `onnxruntime-android` directly. The list
 * order is also the display order in the onboarding wizard and Engines tab.
 */
object EngineCatalog {

    // Unpacked sizes (sum of file sizes after tar extraction). Compute via
    //   find <extracted-dir> -type f -exec stat -c %s {} + | awk '{s+=$1} END {print s}'
    private const val KITTEN_DIRECT_INSTALLED_SIZE_BYTES: Long = 78_417_260L
    private const val KOKORO_DIRECT_INSTALLED_SIZE_BYTES: Long = 306_030_873L
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
     * espeak-ng-data is the app-level shared tree (SharedEspeakData) —
     * bundle copies are ignored legacy. (An earlier design
     * used a BSD-3 OpenPhonemizer ONNX to avoid GPL entirely; it was
     * dropped because IPA-convention mismatches degraded quality.)
     */
    private val KITTEN_DIRECT: EngineDescriptor = EngineDescriptor(
        name = "kitten-direct-v0_8",
        displayName = "Kitten Nano (v0.8)",
        descriptionRes = R.string.engine_kitten_desc,
        downloadSizeBytes = 64_218_626L,
        installedSizeBytes = KITTEN_DIRECT_INSTALLED_SIZE_BYTES,
        // Recommended + onboarding-preselected default: it's baked into the
        // APK (works instantly + offline on first run) and the fastest engine
        // on any device. Kokoro/Pocket are optional higher-quality downloads.
        isRecommended = true,
        archive = EngineArchive(
            // v22: legacy libttsespeak.so removed (bundles carry no executable
            // code at rest) and espeak-ng-data rebuilt from the 1.52.0 tag —
            // the same tag compiled into the APK. tar.gz since v16 (~5× faster
            // on-device install than bzip2).
            url = "https://github.com/maxwhipw/marmalade-tts-android-engines/releases/download/v22/kitten-direct-v0_8.tar.gz",
            sha256 = "591e1e163f7804c9673c8d2b63d6eb5f43bb2f2b620580af84dd32c55b283199",
            sizeBytes = 64_218_626L,
            archiveRoot = "kitten-direct-v0_8/",
        ),
        licenseNotice = "LICENSES/kitten-direct.md",
        licenseSummaryRes = R.string.engine_kitten_license,
        taglineRes = R.string.engine_kitten_tagline,
        speedTier = SpeedTier.FASTEST,
        qualityTier = QualityTier.NATURAL,
        languageCodes = listOf("en"),
    )

    /**
     * Kokoro Direct v1.0 (`kokoro-direct-v1_0`) — the 53-voice
     * multi-language KokoroML v1.0 acoustic model. The inference runs
     * on `onnxruntime-android` directly with the same Pocket-style
     * optimizations as KittenDirect (XNNPACK EP, thread autodetect,
     * direct ByteBuffers, etc.). Espeak-ng (GPL-3.0-or-later) is compiled
     * from source into the APK and dlopen'd at runtime by a tiny MIT
     * JNI shim; the espeak-ng-data is the app-level shared tree
     * (SharedEspeakData) — bundle copies are ignored legacy.
     *
     * v17 added `lexicon-zh.txt` (sherpa's pre-baked misaki+pypinyin Han→IPA
     * table) for Mandarin. v18 adds `openjtalk_dic/` (Open JTalk's
     * naist-jdic, BSD-3, ~103 MB extracted) for Japanese — kanji→kana reading
     * + cutlet-style IPA conversion, the G2P Kokoro v1.0 was trained with.
     * espeak now only handles the European languages it does well
     * (en/es/fr/it/hi/pt); zh and ja have dedicated bundled pipelines.
     *
     * v23 swaps the fp32 model for a selectively-quantized static-QDQ
     * int8 build of the SAME graph (per-channel, 118-node exclusion list
     * covering the vocoder's boundary convs, the ALBERT text encoder, and
     * the last-stage resblock convs; LSTMs stay fp32). Max ear-verified
     * lossless vs fp32 (2026-08-01); 150 MB model vs 326 MB, and measured
     * faster than fp32 on both x86 and the Pixel 8a. Recipe + sweep
     * tooling: scratch/kokoro-quant-experiments (REPORT.md).
     */
    private val KOKORO_DIRECT: EngineDescriptor = EngineDescriptor(
        name = "kokoro-direct-v1_0",
        displayName = "Kokoro (v1.0)",
        descriptionRes = R.string.engine_kokoro_desc,
        downloadSizeBytes = 194_353_174L,
        installedSizeBytes = KOKORO_DIRECT_INSTALLED_SIZE_BYTES,
        // No longer the default — Kitten is baked + recommended. Kokoro stays
        // the top optional download for best quality + widest language support.
        isRecommended = false,
        archive = EngineArchive(
            // v23: selectively-quantized QDQ int8 model (see class doc above).
            // v22: legacy libttsespeak.so removed; espeak-ng-data rebuilt from
            // the 1.52.0 tag (see KITTEN_DIRECT). v18 added openjtalk_dic/
            // (Japanese G2P); v17 added lexicon-zh.txt (Mandarin).
            url = "https://github.com/maxwhipw/marmalade-tts-android-engines/releases/download/v23/kokoro-direct-v1_0.tar.gz",
            sha256 = "8e8752c9ec937dd2842510b65a195ee48f2e2ddc7a2c69a478d281fefdd64770",
            sizeBytes = 194_353_174L,
            archiveRoot = "kokoro-direct-v1_0/",
        ),
        licenseNotice = "LICENSES/kokoro-direct.md",
        licenseSummaryRes = R.string.engine_kokoro_license,
        taglineRes = R.string.engine_kokoro_tagline,
        speedTier = SpeedTier.FAST,
        qualityTier = QualityTier.BEST_OVERALL,
        // The nine distinct locales KokoroDirectVoiceCatalog exposes. American
        // and British English count separately, which is why it's 9 and not 8.
        // Ordered for the info dialog: English (US/UK), then Spanish, French,
        // Italian, Hindi, Portuguese (Brazil), Japanese, Mandarin. Pinned to
        // the voice catalog by EngineCatalogTest.
        languageCodes = listOf(
            "en-US", "en-GB", "es-ES", "fr-FR", "it-IT", "hi-IN", "pt-BR", "ja-JP", "zh-CN",
        ),
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
        descriptionRes = R.string.engine_pocket_desc,
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
        licenseSummaryRes = R.string.engine_pocket_license,
        taglineRes = R.string.engine_pocket_tagline,
        speedTier = SpeedTier.HEAVY,
        qualityTier = QualityTier.MOST_EXPRESSIVE,
        languageCodes = listOf("en"),
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
        descriptionRes = R.string.engine_pocket_dev_desc,
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
        licenseSummaryRes = R.string.engine_pocket_dev_license,
        taglineRes = R.string.engine_pocket_dev_tagline,
        speedTier = SpeedTier.HEAVY,
        qualityTier = QualityTier.MOST_EXPRESSIVE,
        languageCodes = listOf("en"),
    )

    /**
     * Every engine the app knows how to install. Read-only.
     *
     * This is the canonical catalog order (grouped by family). User-facing
     * *display* order comes from [visibleTo], which sorts production engines
     * ahead of the developer-only ones.
     */
    val all: List<EngineDescriptor> = listOf(
        KITTEN_DIRECT,
        KOKORO_DIRECT,
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
