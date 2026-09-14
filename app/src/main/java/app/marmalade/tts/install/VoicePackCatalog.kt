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
 * @property sampleRate    The checkpoint's `audio.sample_rate`, copied here
 *                         for the catalog's `VoiceMeta` row (the picker and
 *                         the system-TTS negotiation read it before any pack
 *                         is loaded). The pack config stays the authority at
 *                         synthesis time. Upstream ships 16 kHz for the
 *                         `x_low`/`low` tiers and 22.05 kHz for
 *                         `medium`/`high`, so this is per-pack, not
 *                         per-engine.
 * @property gender        `"male"` / `"female"` when the corpus documents the
 *                         speaker's gender, else null. Never inferred from a
 *                         name or from the audio — see each pack's
 *                         `PROVENANCE.md`.
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
    val sampleRate: Int,
    val gender: String?,
    val archive: EngineArchive,
    val installedSizeBytes: Long,
    val licenseNotice: String,
) {
    init {
        require(id.isNotBlank()) { "voice pack id must not be blank" }
        require(sampleRate > 0) { "voice pack $id has non-positive sampleRate ($sampleRate)" }
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
 * Every pack's weights are MIT (`rhasspy/piper-voices`) and every corpus
 * behind them is permissive with commercial use stated by the rights holder
 * (Apache-2.0, CC BY 4.0 or CC0) — inference runs entirely on Marmalade's
 * own direct-ORT VITS path, so no Piper runtime code is used or shipped.
 * Per-pack provenance is audited in the `PROVENANCE.md` shipped inside each
 * tarball and summarised in `LICENSES/vits-marmalade.md`.
 *
 * **CC BY 4.0 packs require attribution** (the Icelandic Talrómur voices):
 * the notice lives in `LICENSES/vits-marmalade.md` and in `CREDITS.md`,
 * which is the repo's home for required voice-data attribution. Adding
 * another CC-BY corpus means editing both.
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
     * Where every pack tarball is published. Kept as one constant so a release
     * bump is a single edit and no pack can silently point at an older tag.
     */
    private const val PACK_RELEASE_BASE_URL: String =
        "https://github.com/maxwhipw/marmalade-tts-android-engines/releases/download/v24"

    /** `<packId>.tar.gz` at [PACK_RELEASE_BASE_URL], rooted at `<packId>/`. */
    private fun packArchive(packId: String, sha256: String, sizeBytes: Long) = EngineArchive(
        url = "$PACK_RELEASE_BASE_URL/$packId.tar.gz",
        sha256 = sha256,
        sizeBytes = sizeBytes,
        archiveRoot = "$packId/",
    )

    /**
     * One Talrómur speaker's pack. The four differ only in name, gender and
     * download identity, so the shared fields (language, tier, sample rate)
     * live here rather than being copy-pasted four times where they could
     * drift.
     */
    private fun icelandicPack(
        packId: String,
        displayName: String,
        gender: String,
        sha256: String,
        sizeBytes: Long,
        installedSizeBytes: Long,
    ) = VoicePack(
        id = packId,
        engine = VITS_MARMALADE_ENGINE,
        languageCode = "is-IS",
        displayName = displayName,
        qualityTier = "medium",
        sampleRate = 22_050,
        gender = gender,
        archive = packArchive(packId, sha256, sizeBytes),
        installedSizeBytes = installedSizeBytes,
        licenseNotice = VITS_MARMALADE_LICENSE_NOTICE,
    )

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
        sampleRate = 16_000,
        // The upstream corpus documents no gender for this speaker.
        gender = null,
        archive = packArchive(
            packId = "uk-lada-x_low",
            sha256 = "818722f3e605c8b7f564cfed819466207e42c82e904f9e569240b31929bd3683",
            sizeBytes = 18_717_432L,
        ),
        installedSizeBytes = 20_634_512L,
        licenseNotice = VITS_MARMALADE_LICENSE_NOTICE,
    )

    /**
     * The four Icelandic **Talrómur** voices, 22.05 kHz `medium` tier.
     *
     * One pack per speaker (Talrómur is a multi-speaker corpus but upstream
     * trained a separate single-speaker checkpoint for each), so they behave
     * exactly like `uk-lada-x_low` at runtime. Speaker genders come from the
     * corpus documentation, not from the audio — see each pack's
     * `PROVENANCE.md`.
     *
     * **CC BY 4.0 data — attribution required.** See the class kdoc.
     */
    val IS_BUI_MEDIUM: VoicePack = icelandicPack(
        packId = "is-bui-medium",
        displayName = "Búi (Icelandic)",
        gender = "male",
        sha256 = "9506ac338f0c616b3cfb84f70913a16fb4c2ba23f736e70bce0df597a03fbf3e",
        sizeBytes = 58_611_931L,
        installedSizeBytes = 76_501_223L,
    )

    val IS_SALKA_MEDIUM: VoicePack = icelandicPack(
        packId = "is-salka-medium",
        displayName = "Salka (Icelandic)",
        gender = "female",
        sha256 = "9ec1d85bf3cd745e7e8d515c14c89dff7ac2b66b6a8de0163114b8ad461a6983",
        sizeBytes = 58_670_331L,
        installedSizeBytes = 76_501_227L,
    )

    val IS_STEINN_MEDIUM: VoicePack = icelandicPack(
        packId = "is-steinn-medium",
        displayName = "Steinn (Icelandic)",
        gender = "male",
        sha256 = "8a070d5caafe7b7759ef272ff3fdb35bc0c59a72cf9f062f3e3cac939b853b55",
        sizeBytes = 58_681_543L,
        installedSizeBytes = 76_501_230L,
    )

    val IS_UGLA_MEDIUM: VoicePack = icelandicPack(
        packId = "is-ugla-medium",
        displayName = "Ugla (Icelandic)",
        gender = "female",
        sha256 = "47f7095b3f734306015adf1644c4d47b5d84f44660aa4b564d52cbee5f12bf0a",
        sizeBytes = 58_682_555L,
        installedSizeBytes = 76_501_222L,
    )

    /**
     * Swedish **NST** voice, 22.05 kHz `medium` tier, single speaker.
     *
     * Data is CC0 (rights holder Nasjonalbiblioteket, the National Library of
     * Norway, which inherited the NST corpora): a professional voice actor
     * recorded expressly for TTS product development, so no attribution is
     * required and commercial use is unambiguous. Gender is not documented.
     */
    val SV_NST_MEDIUM: VoicePack = VoicePack(
        id = "sv-nst-medium",
        engine = VITS_MARMALADE_ENGINE,
        languageCode = "sv-SE",
        displayName = "NST (Swedish)",
        qualityTier = "medium",
        sampleRate = 22_050,
        gender = null,
        archive = packArchive(
            packId = "sv-nst-medium",
            sha256 = "24a62e01187811abd1fb293bb5222092169f1d1609309b3693275b6010e5dd79",
            sizeBytes = 58_295_971L,
        ),
        installedSizeBytes = 63_110_311L,
        licenseNotice = VITS_MARMALADE_LICENSE_NOTICE,
    )

    /** Every voice pack the app knows how to install. Read-only. */
    val all: List<VoicePack> = listOf(
        UK_LADA_X_LOW,
        IS_BUI_MEDIUM,
        IS_SALKA_MEDIUM,
        IS_STEINN_MEDIUM,
        IS_UGLA_MEDIUM,
        SV_NST_MEDIUM,
    )

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
