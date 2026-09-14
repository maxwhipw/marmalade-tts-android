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
//     │                                 PackVoice (a pack contributes one voice
//     │                                 per speaker), seeded into Room like
//     │                                 every other engine's static voice list.
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
 *                         change how the pack is run. NOT the user-facing
 *                         grade: it describes the training recipe, not how
 *                         the result sounds. See [quality].
 * @property quality       User-facing audio-quality grade, shown wherever the
 *                         pack or one of its voices is offered. A listening
 *                         judgement owned by Max — see [PackQuality]; agents
 *                         must not change a pack's grade.
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
 *                         `PROVENANCE.md`. Ignored when [speakers] is
 *                         non-empty (each speaker carries its own).
 * @property speakers      Empty for a single-speaker checkpoint, which
 *                         contributes exactly one voice keyed by the bare
 *                         pack id. For a multi-speaker checkpoint, one entry
 *                         per speaker the catalog exposes — see [PackSpeaker]
 *                         and [voices].
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
    val quality: PackQuality,
    val sampleRate: Int,
    val gender: String?,
    val archive: EngineArchive,
    val installedSizeBytes: Long,
    val licenseNotice: String,
    val speakers: List<PackSpeaker> = emptyList(),
) {
    init {
        require(id.isNotBlank()) { "voice pack id must not be blank" }
        require(sampleRate > 0) { "voice pack $id has non-positive sampleRate ($sampleRate)" }
        // The id is a directory name and a voice-id segment — a separator in
        // it would either escape the packs dir or split the voice id wrong.
        require(!id.contains('/') && !id.contains(':')) {
            "voice pack id '$id' must not contain '/' or ':'"
        }
        // '#' separates the pack id from the speaker index in a voice key.
        require(!id.contains(SPEAKER_SEPARATOR)) {
            "voice pack id '$id' must not contain '$SPEAKER_SEPARATOR'"
        }
        require(archive.url.isNotBlank()) { "voice pack $id has no archive url" }
        require(archive.sizeBytes > 0L) { "voice pack $id has zero-size archive" }
        require(installedSizeBytes > 0L) { "voice pack $id has zero installed size" }
        require(speakers.map { it.sid }.distinct().size == speakers.size) {
            "voice pack $id declares a duplicate speaker sid"
        }
    }

    /**
     * Every selectable voice this pack contributes, in declaration order.
     *
     * A single-speaker pack yields one voice whose key is the bare pack id —
     * which is what keeps the already-shipped `uk-lada-x_low` voice id (and
     * any user alias pointing at it) working unchanged. A multi-speaker pack
     * yields one voice per declared speaker, keyed `<packId>#<sid>`.
     */
    val voices: List<PackVoice> = if (speakers.isEmpty()) {
        listOf(
            PackVoice(
                packId = id,
                voiceKey = id,
                sid = 0,
                displayName = displayName,
                languageCode = languageCode,
                sampleRate = sampleRate,
                gender = gender,
                quality = quality,
            ),
        )
    } else {
        speakers.map { speaker ->
            PackVoice(
                packId = id,
                voiceKey = "$id$SPEAKER_SEPARATOR${speaker.sid}",
                sid = speaker.sid,
                displayName = speaker.displayName,
                languageCode = languageCode,
                sampleRate = sampleRate,
                gender = speaker.gender,
                quality = quality,
            )
        }
    }

    companion object {
        /** Separates a pack id from a speaker index in a voice key. */
        const val SPEAKER_SEPARATOR: Char = '#'
    }
}

/**
 * One speaker of a multi-speaker checkpoint, as the catalog declares it.
 *
 * @property sid         The model's own speaker index, taken from the
 *                       checkpoint's `speaker_id_map` — never guessed from the
 *                       order the names appear in. Fed to the graph's `sid`
 *                       input verbatim.
 * @property displayName Curated, user-facing. The upstream keys are corpus
 *                       identifiers (`ISSAI_KazakhTTS2_F3`, `KSV`) and are not
 *                       shown to anyone.
 * @property gender      `"male"` / `"female"` where the corpus documents it
 *                       (these keys encode it), else null.
 */
data class PackSpeaker(
    val sid: Int,
    val displayName: String,
    val gender: String? = null,
) {
    init {
        require(sid >= 0) { "speaker sid must not be negative ($sid)" }
        require(displayName.isNotBlank()) { "speaker $sid has a blank displayName" }
    }
}

/**
 * One selectable voice: a (pack, speaker) pair flattened into the shape the
 * voice catalog and the engine both want.
 *
 * @property voiceKey The second half of the app's `<engine>:<voiceKey>` voice
 *                    id — the bare pack id for a single-speaker pack,
 *                    `<packId>#<sid>` for one speaker of a multi-speaker pack.
 * @property quality  The owning pack's grade, copied down so a voice row can
 *                    show it without a second lookup. Per-pack, not per
 *                    speaker: one checkpoint, one corpus, one recording setup.
 */
data class PackVoice(
    val packId: String,
    val voiceKey: String,
    val sid: Int,
    val displayName: String,
    val languageCode: String,
    val sampleRate: Int,
    val gender: String?,
    val quality: PackQuality,
)

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
 * **The [PackQuality] grades in here are provisional** until Max's ear-lab
 * pass recalibrates them, and they are his to set either way: they are a
 * listening judgement about the source audio, not something derivable from the
 * checkpoint metadata. Never "fix" a grade to match a pack's [qualityTier].
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
        // All four Talrómur speakers were recorded in the same studio session
        // and grade the same, so the grade lives in the shared helper.
        quality = PackQuality.GOOD,
        sampleRate = 22_050,
        gender = gender,
        archive = packArchive(packId, sha256, sizeBytes),
        installedSizeBytes = installedSizeBytes,
        licenseNotice = VITS_MARMALADE_LICENSE_NOTICE,
    )

    /**
     * One NVCC speaker, labelled from its own three-letter corpus code so the
     * ten labels cannot drift out of step with the codes they describe.
     *
     * @param code `K`/`M` for female/male, then the two-letter dialect area.
     */
    private fun norwegianSpeaker(sid: Int, code: String): PackSpeaker {
        val gender = when (code.first()) {
            'K' -> "female" // kvinne
            'M' -> "male" // mann
            else -> error("NVCC speaker code '$code' must start with K or M")
        }
        val area = when (code.drop(1)) {
            "ON" -> "East Norway"
            "SV" -> "Southwest Norway"
            "NV" -> "Northwest Norway"
            "MN" -> "Central Norway"
            "NN" -> "North Norway"
            else -> error("unknown NVCC dialect area in speaker code '$code'")
        }
        return PackSpeaker(
            sid = sid,
            displayName = "Norwegian $code ($gender, $area)",
            gender = gender,
        )
    }

    /**
     * Ukrainian, single speaker "Lada", 16 kHz, `x_low` tier.
     *
     * Extracted layout: `model.onnx` (20,628,813) + `model.onnx.json`
     * (4,186) + `MODEL_CARD` (267) + `PROVENANCE.md` (1,246).
     *
     * Labelled "…, small": [UK_UKRAINIAN_TTS_MEDIUM] has a Lada too, from the
     * same corpus family at a higher tier, and the two must be tellable apart
     * in the picker. The better voice keeps the plain name; this one says what
     * it is. The pack **id** is unchanged — user aliases point at it.
     */
    val UK_LADA_X_LOW: VoicePack = VoicePack(
        id = "uk-lada-x_low",
        engine = VITS_MARMALADE_ENGINE,
        languageCode = "uk-UA",
        displayName = "Lada (Ukrainian, small)",
        qualityTier = "x_low",
        // The `x_low` checkpoint at 16 kHz: intelligible, plainly thinner than
        // the medium Ukrainian pack that shipped alongside it.
        quality = PackQuality.BASIC,
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
        quality = PackQuality.GOOD,
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

    /**
     * Kazakh **ISSAI** pack, 22.05 kHz `high` tier, **6 speakers**.
     *
     * Two are named in the corpus (KazakhTTS v1's M1 "Iseke" and F1 "Raya");
     * the other four are KazakhTTS2 speakers identified only by a key whose
     * letter gives the gender and whose number is that speaker's index within
     * their gender (`F3` = the third female). The display names follow that:
     * "Kazakh voice 2 (male)" is `ISSAI_KazakhTTS2_M2`. A number therefore
     * appears twice across genders (M2 and F2), which is why the gender is
     * part of the label rather than only of [PackSpeaker.gender].
     *
     * Sids are the checkpoint's own `speaker_id_map` values, which are NOT in
     * key order — `VitsPackConfigTest` pins these against the real config.
     *
     * **CC BY 4.0 data — attribution required** (KazakhTTS / KazakhTTS2,
     * ISSAI, Nazarbayev University). See `LICENSES/vits-marmalade.md`.
     */
    val KK_ISSAI_HIGH: VoicePack = VoicePack(
        id = "kk-issai-high",
        engine = VITS_MARMALADE_ENGINE,
        languageCode = "kk-KZ",
        displayName = "Kazakh (ISSAI)",
        qualityTier = "high",
        quality = PackQuality.GOOD,
        sampleRate = 22_050,
        gender = null,
        archive = packArchive(
            packId = "kk-issai-high",
            sha256 = "be6063fadb2d0789cc27f009b257b89e1303935379fd9d24c69a504d247cf46e",
            sizeBytes = 118_825_492L,
        ),
        installedSizeBytes = 127_870_459L,
        licenseNotice = VITS_MARMALADE_LICENSE_NOTICE,
        speakers = listOf(
            // ISSAI_KazakhTTS2_M2
            PackSpeaker(sid = 0, displayName = "Kazakh voice 2 (male)", gender = "male"),
            // ISSAI_KazakhTTS_M1_Iseke
            PackSpeaker(sid = 1, displayName = "Iseke (Kazakh)", gender = "male"),
            // ISSAI_KazakhTTS2_F3
            PackSpeaker(sid = 2, displayName = "Kazakh voice 3 (female)", gender = "female"),
            // ISSAI_KazakhTTS_F1_Raya
            PackSpeaker(sid = 3, displayName = "Raya (Kazakh)", gender = "female"),
            // ISSAI_KazakhTTS2_F1
            PackSpeaker(sid = 4, displayName = "Kazakh voice 1 (female)", gender = "female"),
            // ISSAI_KazakhTTS2_F2
            PackSpeaker(sid = 5, displayName = "Kazakh voice 2 (female)", gender = "female"),
        ),
    )

    /**
     * Norwegian Bokmål **NVCC** pack, 22.05 kHz `medium` tier, **10 speakers**.
     *
     * Upstream keys are three-letter pseudonyms: first letter is the gender
     * (K = *kvinne*, female; M = *mann*, male) and the last two are the
     * speaker's dialect area (ON = East, SV = Southwest, NV = Northwest,
     * MN = Central, NN = North). The labels keep the code so a listener can
     * map a voice back to the corpus, and spell out both facts in English.
     *
     * Language code is `nb-NO`: the config says `no_NO` and espeak's voice is
     * `nb`, and Bokmål is what the corpus is. Data is CC0 (Språkbanken /
     * National Library of Norway) — no attribution required.
     *
     * Recording caveat from the corpus documentation, carried in the pack's
     * `PROVENANCE.md`: these were recorded in ordinary meeting rooms, not a
     * studio, so some voices carry room noise. Developer-only until Max's ear
     * lab picks the keepers.
     */
    val NO_NVCC_MEDIUM: VoicePack = VoicePack(
        id = "no-nvcc-medium",
        engine = VITS_MARMALADE_ENGINE,
        languageCode = "nb-NO",
        displayName = "Norwegian (NVCC)",
        qualityTier = "medium",
        // Ships anyway: Max's call is that something beats nothing for a
        // language with no cleanly-licensed alternative, as long as the label
        // says plainly what it is.
        quality = PackQuality.ROUGH,
        sampleRate = 22_050,
        gender = null,
        archive = packArchive(
            packId = "no-nvcc-medium",
            sha256 = "77df9edbda0417e9178f63c7516a637f28e76a782cbde1bc4885a8dbae1408ed",
            sizeBytes = 71_295_587L,
        ),
        installedSizeBytes = 76_777_139L,
        licenseNotice = VITS_MARMALADE_LICENSE_NOTICE,
        speakers = listOf(
            norwegianSpeaker(sid = 0, code = "KNN"),
            norwegianSpeaker(sid = 1, code = "KSV"),
            norwegianSpeaker(sid = 2, code = "MMN"),
            norwegianSpeaker(sid = 3, code = "KON"),
            norwegianSpeaker(sid = 4, code = "MNN"),
            norwegianSpeaker(sid = 5, code = "MSV"),
            norwegianSpeaker(sid = 6, code = "MON"),
            norwegianSpeaker(sid = 7, code = "MNV"),
            norwegianSpeaker(sid = 8, code = "KMN"),
            norwegianSpeaker(sid = 9, code = "KNV"),
        ),
    )

    /**
     * Ukrainian **ukrainian_tts** pack, 22.05 kHz `medium` tier, **3 speakers**
     * — and the first **grapheme** pack: its config says
     * `phoneme_type: "text"`, so the engine skips espeak entirely and feeds the
     * model characters (see [app.marmalade.tts.engine.vits.VitsPhonemeIds]).
     *
     * Same corpus family as [UK_LADA_X_LOW] (egorsmkv, Apache-2.0) at a higher
     * tier, which is why its Lada gets the plain label and the x_low one is
     * marked "small".
     *
     * The upstream model card misattributes the training data to
     * OHF-Voice/voice-datasets, which has no Ukrainian entry; the real source
     * is `egorsmkv/ukrainian-tts-datasets` (verified 2026-09-13, recorded in
     * the pack's `PROVENANCE.md`). The GPLv3 `robinhad/ukrainian-tts` project
     * does **not** attach: that is a separate ESPnet codebase and this VITS
     * checkpoint was trained from scratch on the datasets.
     */
    val UK_UKRAINIAN_TTS_MEDIUM: VoicePack = VoicePack(
        id = "uk-ukrainian_tts-medium",
        engine = VITS_MARMALADE_ENGINE,
        languageCode = "uk-UA",
        displayName = "Ukrainian (ukrainian_tts)",
        qualityTier = "medium",
        quality = PackQuality.GOOD,
        sampleRate = 22_050,
        gender = null,
        archive = packArchive(
            packId = "uk-ukrainian_tts-medium",
            sha256 = "00b28714d7a665121d47e499416ae108a4407154512d5887b29b839406d2af4d",
            sizeBytes = 71_144_912L,
        ),
        installedSizeBytes = 76_739_354L,
        licenseNotice = VITS_MARMALADE_LICENSE_NOTICE,
        // speaker_id_map: {"lada": 0, "mykyta": 1, "tetiana": 2}. Genders are
        // null: the corpus documents the speakers' names but not their gender,
        // and guessing from a given name is exactly what this field doesn't do
        // (the x_low Lada is null for the same reason).
        speakers = listOf(
            PackSpeaker(sid = 0, displayName = "Lada (Ukrainian)"),
            PackSpeaker(sid = 1, displayName = "Mykyta (Ukrainian)"),
            PackSpeaker(sid = 2, displayName = "Tetiana (Ukrainian)"),
        ),
    )

    /** Every voice pack the app knows how to install. Read-only. */
    val all: List<VoicePack> = listOf(
        UK_LADA_X_LOW,
        IS_BUI_MEDIUM,
        IS_SALKA_MEDIUM,
        IS_STEINN_MEDIUM,
        IS_UGLA_MEDIUM,
        SV_NST_MEDIUM,
        KK_ISSAI_HIGH,
        NO_NVCC_MEDIUM,
        UK_UKRAINIAN_TTS_MEDIUM,
    )

    /** Lookup by [VoicePack.id]. Null for unknown packs. */
    fun byId(id: String): VoicePack? = all.firstOrNull { it.id == id }

    /** Packs belonging to [engineName], in catalog (display) order. */
    fun forEngine(engineName: String): List<VoicePack> = all.filter { it.engine == engineName }

    /**
     * Every selectable voice of [engineName]'s packs, pack order then declared
     * speaker order. This — not [forEngine] — is the voice list: a
     * multi-speaker pack contributes several voices.
     */
    fun voicesForEngine(engineName: String): List<PackVoice> =
        forEngine(engineName).flatMap { it.voices }

    /**
     * Resolve a voice key (`<packId>` or `<packId>#<sid>`) to its voice.
     * Null when no pack of [engineName] declares it — which the engine turns
     * into a hard failure rather than substituting another speaker.
     */
    fun voiceByKey(engineName: String, voiceKey: String): PackVoice? =
        voicesForEngine(engineName).firstOrNull { it.voiceKey == voiceKey }

    /**
     * The pack installed when the user taps Install on the engine card
     * itself (rather than picking a language). Null if the engine has no
     * packs at all, which the catalog never ships.
     */
    fun defaultPackFor(engineName: String): VoicePack? = forEngine(engineName).firstOrNull()
}
