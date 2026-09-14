package app.marmalade.tts.install

// -----------------------------------------------------------------------------
// Data flow
// -----------------------------------------------------------------------------
//   VoicePackCatalog.all  ──►  VoicePack (id, engine, language, archive)
//     │                             │
//     │                             ├── EngineCatalog: the pack-based engine's
//     │                             │   descriptor borrows the DEFAULT pack's
//     │                             │   archive + sizes, so the Engines-tab
//     │                             │   card shows a real download size and
//     │                             │   "Install" installs that pack.
//     │                             │
//     │                             ├── EngineInstaller.installPack(packId) →
//     │                             │   GET → sha256 → tar extract → atomic
//     │                             │   rename into
//     │                             │   engines/<engine>/packs/<packId>/
//     │                             │
//     │                             └── VitsVoiceCatalog: one VoiceMeta row per
//     │                                 pack, seeded into Room like every other
//     │                                 engine's static voice list.
//     ▼
//   engines/vits-marmalade-v1/packs/<packId>/{model.onnx, model.onnx.json, …}
//     │
//     └── VitsDirectEngine loads the pack's ONNX + config on demand.
// -----------------------------------------------------------------------------

/**
 * One downloadable voice pack for a pack-based engine.
 *
 * A pack is entirely self-contained: a single VITS `model.onnx` plus its
 * `model.onnx.json` config (sample rate, espeak voice, inference scales,
 * phoneme→id map), a MODEL_CARD and a PROVENANCE.md. Nothing about the
 * voice is hardcoded in the app — the engine reads it out of the config —
 * so shipping a new language is a catalog entry plus a release asset, no
 * code change.
 *
 * Why packs at all (vs one monolithic engine archive like Kokoro's): a
 * Piper-class VITS checkpoint is per-voice, ~20 MB. Bundling all languages
 * into one archive would mean a multi-hundred-MB download to get the one
 * voice a user actually speaks.
 *
 * @property id            Stable pack identifier. Doubles as the on-disk
 *                         directory name under `engines/<engine>/packs/`
 *                         and as the voice key in a `<engine>:<packId>`
 *                         voice id, so it must never change once shipped.
 * @property engine        [EngineDescriptor.name] this pack belongs to.
 * @property languageCode  BCP-47 code of the voice (e.g. `"uk-UA"`). The
 *                         pack config carries the same language in Piper's
 *                         underscore form (`uk_UA`); this is the app-facing
 *                         spelling used by `VoiceMeta.languageCode`.
 * @property displayName   User-facing voice label, e.g. "Lada (Ukrainian)".
 * @property qualityTier   Upstream checkpoint quality tier, verbatim from
 *                         the voice's config (`x_low`, `low`, `medium`,
 *                         `high`). Diagnostic/label only — it does not
 *                         change how the pack is run.
 * @property archive       Downloadable tar.gz, verified by sha256 exactly
 *                         like an engine archive.
 * @property installedSizeBytes Sum of the extracted file sizes; drives the
 *                         determinate extraction progress bar.
 * @property licenseNotice Repo-relative path to the long-form third-party
 *                         notice covering this pack's weights + training
 *                         data.
 */
data class VoicePack(
    val id: String,
    val engine: String,
    val languageCode: String,
    val displayName: String,
    val qualityTier: String,
    val archive: EngineArchive,
    val installedSizeBytes: Long,
    val licenseNotice: String,
) {
    init {
        require(id.isNotBlank()) { "voice pack id must not be blank" }
        // The id is a directory name and a voice-id segment — a separator in
        // it would either escape the packs dir or split the voice id wrong.
        require(!id.contains('/') && !id.contains(':')) {
            "voice pack id '$id' must not contain '/' or ':'"
        }
        require(archive.url.isNotBlank()) { "voice pack $id has no archive url" }
        require(archive.sizeBytes > 0L) { "voice pack $id has zero-size archive" }
        require(installedSizeBytes > 0L) { "voice pack $id has zero installed size" }
    }
}

/**
 * Static catalog of downloadable voice packs.
 *
 * Slice A ships one: Ukrainian "Lada" at the `x_low` tier, whose licence
 * chain is permissive end to end (MIT weights from `rhasspy/piper-voices`,
 * Apache-2.0 training data from `egorsmkv/ukrainian-tts-datasets`) and
 * whose inference runs entirely on Marmalade's own direct-ORT VITS path —
 * no Piper runtime code is used or shipped. See
 * `LICENSES/vits-marmalade.md`.
 */
object VoicePackCatalog {

    /**
     * Engine name of the direct-ORT VITS engine. Spelled here rather than
     * referenced from [app.marmalade.tts.engine.vits.VitsDirectEngine] so the
     * install package doesn't depend on the engine package (the installer is
     * unit-tested without an Android `Context`). `VoicePackCatalogTest` pins
     * the two spellings together.
     */
    const val VITS_MARMALADE_ENGINE: String = "vits-marmalade-v1"

    /** Repo-relative long-form notice covering every VITS Marmalade pack. */
    const val VITS_MARMALADE_LICENSE_NOTICE: String = "LICENSES/vits-marmalade.md"

    /**
     * Ukrainian, single speaker "Lada", 16 kHz, `x_low` tier.
     *
     * Extracted layout: `model.onnx` (20,628,813) + `model.onnx.json`
     * (4,186) + `MODEL_CARD` (267) + `PROVENANCE.md` (1,246).
     */
    val UK_LADA_X_LOW: VoicePack = VoicePack(
        id = "uk-lada-x_low",
        engine = VITS_MARMALADE_ENGINE,
        languageCode = "uk-UA",
        displayName = "Lada (Ukrainian)",
        qualityTier = "x_low",
        archive = EngineArchive(
            url = "https://github.com/maxwhipw/marmalade-tts-android-engines/" +
                "releases/download/v24/uk-lada-x_low.tar.gz",
            sha256 = "818722f3e605c8b7f564cfed819466207e42c82e904f9e569240b31929bd3683",
            sizeBytes = 18_717_432L,
            archiveRoot = "uk-lada-x_low/",
        ),
        installedSizeBytes = 20_634_512L,
        licenseNotice = VITS_MARMALADE_LICENSE_NOTICE,
    )

    /** Every voice pack the app knows how to install. Read-only. */
    val all: List<VoicePack> = listOf(UK_LADA_X_LOW)

    /** Lookup by [VoicePack.id]. Null for unknown packs. */
    fun byId(id: String): VoicePack? = all.firstOrNull { it.id == id }

    /** Packs belonging to [engineName], in catalog (display) order. */
    fun forEngine(engineName: String): List<VoicePack> = all.filter { it.engine == engineName }

    /**
     * The pack installed when the user taps Install on the engine card
     * itself (rather than picking a language). Null if the engine has no
     * packs at all, which the catalog never ships.
     */
    fun defaultPackFor(engineName: String): VoicePack? = forEngine(engineName).firstOrNull()
}
