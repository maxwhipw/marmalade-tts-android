package app.marmalade.tts.engine.vits

/** Shared access to the checked-in real pack config used by the VITS tests. */
internal object VitsTestFixtures {

    private const val UK_LADA_CONFIG = "vits/uk-lada-x_low.model.onnx.json"

    /**
     * The verbatim `model.onnx.json` of the shipped `uk-lada-x_low` pack.
     * Checked in so the tests assert against the real phoneme map and the real
     * inference scales, not a hand-written approximation of them.
     */
    fun ukLadaConfigJson(): String =
        checkNotNull(javaClass.classLoader?.getResourceAsStream(UK_LADA_CONFIG)) {
            "missing test resource $UK_LADA_CONFIG"
        }.use { it.readBytes().toString(Charsets.UTF_8) }

    /** Parsed form of [ukLadaConfigJson]. Needs `org.json` — Robolectric only. */
    fun ukLadaConfig(): VitsPackConfig = VitsPackConfig.parse(ukLadaConfigJson(), UK_LADA_CONFIG)
}
