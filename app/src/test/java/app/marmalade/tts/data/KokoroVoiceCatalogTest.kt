package app.marmalade.tts.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests the static Kokoro voice seed used to populate Room on first
 * launch. If the voice list or its ordering drifts, the speaker indices
 * in `KokoroEngine.SPEAKER_ID_BY_NAME` will silently desync from
 * Sherpa-ONNX's `voices.bin` — none of which surfaces as a build error,
 * so the catalog itself is worth pinning with assertions.
 *
 * v0.1.19 expanded the catalog from 11 (English only) to 53 voices
 * across 9 languages; these tests are the source-of-truth pin against
 * `scripts/kokoro/v1.0/generate_voices_bin.py` in the sherpa-onnx repo.
 */
class KokoroVoiceCatalogTest {

    @Test
    fun seedsExpectedFiftyThreeVoicesInVoicesBinOrder() {
        // Order MUST match Sherpa-ONNX's kokoro v1.0 multi-lang
        // `voices.bin` packing (see id2speaker in
        // scripts/kokoro/v1.0/generate_voices_bin.py). Both the catalog
        // and the engine's SPEAKER_ID_BY_NAME depend on this order — if
        // it changes, that map must change in lockstep.
        val names = KokoroVoiceCatalog.voices.map { it.displayName }
        assertEquals(
            listOf(
                // American female
                "af_alloy", "af_aoede", "af_bella", "af_heart", "af_jessica",
                "af_kore", "af_nicole", "af_nova", "af_river", "af_sarah", "af_sky",
                // American male
                "am_adam", "am_echo", "am_eric", "am_fenrir", "am_liam",
                "am_michael", "am_onyx", "am_puck", "am_santa",
                // British female
                "bf_alice", "bf_emma", "bf_isabella", "bf_lily",
                // British male
                "bm_daniel", "bm_fable", "bm_george", "bm_lewis",
                // Spanish
                "ef_dora", "em_alex",
                // French
                "ff_siwis",
                // Hindi
                "hf_alpha", "hf_beta", "hm_omega", "hm_psi",
                // Italian
                "if_sara", "im_nicola",
                // Japanese
                "jf_alpha", "jf_gongitsune", "jf_nezumi", "jf_tebukuro", "jm_kumo",
                // Brazilian Portuguese
                "pf_dora", "pm_alex", "pm_santa",
                // Mandarin female
                "zf_xiaobei", "zf_xiaoni", "zf_xiaoxiao", "zf_xiaoyi",
                // Mandarin male
                "zm_yunjian", "zm_yunxi", "zm_yunxia", "zm_yunyang",
            ),
            names,
        )
    }

    @Test
    fun voiceIdsUseEngineColonDisplayNameConvention() {
        for (voice in KokoroVoiceCatalog.voices) {
            assertEquals(
                "voice id mismatch for ${voice.displayName}",
                "kokoro:${voice.displayName}",
                voice.id,
            )
        }
    }

    @Test
    fun voicesShareEngineAndSampleRate() {
        for (voice in KokoroVoiceCatalog.voices) {
            assertEquals(24000, voice.sampleRate)
            assertEquals("kokoro", voice.engine)
        }
    }

    @Test
    fun languageCodesMatchKokoroPrefixConvention() {
        // First letter of the voice key encodes its natural language.
        // Pin the prefix → BCP-47 mapping so silent drift between catalog
        // expansions and the languageFor helper can't ship.
        val expected: Map<String, String> = mapOf(
            // prefix character → BCP-47 code
            "a" to "en-US", "b" to "en-GB",
            "e" to "es-ES", "f" to "fr-FR",
            "h" to "hi-IN", "i" to "it-IT",
            "j" to "ja-JP", "p" to "pt-BR",
            "z" to "zh-CN",
        )
        for (voice in KokoroVoiceCatalog.voices) {
            val prefix = voice.displayName.first().toString()
            val expectedLang = expected[prefix]
                ?: error("Unknown prefix '$prefix' for voice ${voice.displayName}")
            assertEquals(
                "languageCode mismatch for ${voice.displayName}",
                expectedLang,
                voice.languageCode,
            )
        }
    }

    @Test
    fun genderIsAlwaysFemaleOrMaleAndMatchesPrefix() {
        // The "_f_" / "_m_" character at index 1 is the canonical
        // gender signal in upstream Kokoro voice keys. Verify the seed
        // matches that convention so neither side can silently drift.
        for (voice in KokoroVoiceCatalog.voices) {
            assertNotNull("gender missing for ${voice.displayName}", voice.gender)
            assertTrue(
                "unexpected gender '${voice.gender}' for ${voice.displayName}",
                voice.gender == "female" || voice.gender == "male",
            )
            val derived = KokoroVoiceCatalog.genderFor(voice.displayName)
            assertEquals(
                "gender derived from key ${voice.displayName} should match seed",
                voice.gender,
                derived,
            )
        }
    }

    @Test
    fun genderForHelperHandlesKnownAndUnknownKeys() {
        assertEquals("female", KokoroVoiceCatalog.genderFor("af_bella"))
        assertEquals("male", KokoroVoiceCatalog.genderFor("bm_lewis"))
        assertEquals("female", KokoroVoiceCatalog.genderFor("jf_alpha"))
        assertEquals("male", KokoroVoiceCatalog.genderFor("zm_yunjian"))
        assertNull("malformed key should not infer a gender",
            KokoroVoiceCatalog.genderFor("x"))
        // Keys that don't follow the [region][gender]_… convention return
        // null — the catalog never produces these, but the helper should
        // not invent a guess for unknown shapes.
        assertNull(KokoroVoiceCatalog.genderFor("ab_unknown"))
    }

    @Test
    fun languageForHelperHandlesKnownAndUnknownKeys() {
        assertEquals("en-US", KokoroVoiceCatalog.languageFor("af_bella"))
        assertEquals("en-GB", KokoroVoiceCatalog.languageFor("bm_lewis"))
        assertEquals("ja-JP", KokoroVoiceCatalog.languageFor("jf_alpha"))
        assertEquals("zh-CN", KokoroVoiceCatalog.languageFor("zm_yunjian"))
        assertEquals("es-ES", KokoroVoiceCatalog.languageFor("ef_dora"))
        assertEquals("fr-FR", KokoroVoiceCatalog.languageFor("ff_siwis"))
        assertEquals("hi-IN", KokoroVoiceCatalog.languageFor("hf_alpha"))
        assertEquals("it-IT", KokoroVoiceCatalog.languageFor("if_sara"))
        assertEquals("pt-BR", KokoroVoiceCatalog.languageFor("pf_dora"))
        assertNull("empty key returns null", KokoroVoiceCatalog.languageFor(""))
        assertNull("unknown prefix returns null", KokoroVoiceCatalog.languageFor("xy_test"))
    }

    @Test
    fun defaultVoiceIdIsAfBella() {
        assertEquals("kokoro:af_bella", KokoroVoiceCatalog.DEFAULT_VOICE_ID)
        assertNotNull(
            KokoroVoiceCatalog.voices.firstOrNull {
                it.id == KokoroVoiceCatalog.DEFAULT_VOICE_ID
            },
        )
    }

    @Test
    fun nothingShipsAsInstalledOutOfTheBox() {
        // Until KokoroEngine.ensureModelLoaded() succeeds, no voice should
        // claim to be installed. The voice picker's install-gate filter
        // depends on this.
        for (voice in KokoroVoiceCatalog.voices) {
            assertEquals(
                "voice ${voice.displayName} should not start installed",
                false,
                voice.isInstalled,
            )
        }
    }

    @Test
    fun voiceIdHelperMatchesEntries() {
        for (voice in KokoroVoiceCatalog.voices) {
            assertEquals(voice.id, KokoroVoiceCatalog.voiceId(voice.displayName))
        }
    }

    @Test
    fun voiceCountMatchesExpectedKokoroV1Set() {
        // Pin the count alongside the order test — a slip that duplicates
        // a name would still pass the order test if both rows happened to
        // compare equal, but the count would change.
        assertEquals(53, KokoroVoiceCatalog.voices.size)
    }
}
