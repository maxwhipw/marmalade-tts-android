package app.marmalade.tts.engine.vits

/** Shared access to the checked-in real pack config used by the VITS tests. */
internal object VitsTestFixtures {

    private const val UK_LADA_CONFIG = "vits/uk-lada-x_low.model.onnx.json"

    /**
     * The verbatim `model.onnx.json` of the shipped `uk-lada-x_low` pack.
     * Checked in so the tests assert against the real phoneme map and the real
     * inference scales, not a hand-written approximation of them.
     */
    fun ukLadaConfigJson(): String = configJson(UK_LADA_CONFIG)

    /** Parsed form of [ukLadaConfigJson]. Needs `org.json` — Robolectric only. */
    fun ukLadaConfig(): VitsPackConfig = VitsPackConfig.parse(ukLadaConfigJson(), UK_LADA_CONFIG)

    /**
     * The verbatim `model.onnx.json` of any checked-in pack, by pack id. The
     * multi-speaker packs are here for the `speaker_id_map` assertions: the
     * catalog pins curated display names against numeric sids, and the only
     * way to know those sids are right is to read the real checkpoint config.
     */
    fun configJson(packId: String): String = readResource(
        if (packId.endsWith(".json")) packId else "vits/$packId.model.onnx.json",
    )

    /** Parsed form of [configJson]. Needs `org.json` — Robolectric only. */
    fun config(packId: String): VitsPackConfig =
        VitsPackConfig.parse(configJson(packId), origin = packId)

    private fun readResource(path: String): String =
        checkNotNull(javaClass.classLoader?.getResourceAsStream(path)) {
            "missing test resource $path"
        }.use { it.readBytes().toString(Charsets.UTF_8) }
}
