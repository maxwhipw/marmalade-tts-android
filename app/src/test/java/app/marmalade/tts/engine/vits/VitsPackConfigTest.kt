package app.marmalade.tts.engine.vits

import app.marmalade.tts.install.VoicePackCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Pins the `model.onnx.json` parse against the REAL config of the shipped
 * `uk-lada-x_low` pack (checked in at
 * `app/src/test/resources/vits/uk-lada-x_low.model.onnx.json`).
 *
 * Worth testing: every number here is fed straight into the model's `scales`
 * tensor or decides the output sample rate, and a silently wrong field yields
 * audio that plays at the wrong speed or pitch rather than an error.
 *
 * Robolectric because [VitsPackConfig] parses with `org.json`, which throws
 * "stub!" on a plain JVM.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VitsPackConfigTest {

    @Test
    fun parsesTheRealUkLadaPackConfig() {
        val config = VitsPackConfig.parse(VitsTestFixtures.ukLadaConfigJson())

        assertEquals(16_000, config.sampleRate)
        assertEquals("uk", config.espeakVoice)
        assertEquals(0.667f, config.noiseScale, 1e-6f)
        assertEquals(1.0f, config.lengthScale, 1e-6f)
        assertEquals(0.8f, config.noiseW, 1e-6f)
        assertEquals(1, config.numSpeakers)
        assertFalse("single-speaker pack must not request a sid input", config.isMultiSpeaker)
        assertEquals("uk_UA", config.languageCode)
        assertEquals("lada", config.dataset)
        assertEquals(130, config.phonemeIdMap.size)
        // The three wrapping markers the encoder needs, by value.
        assertEquals(listOf(1), config.phonemeIdMap["^"])
        assertEquals(listOf(0), config.phonemeIdMap["_"])
        assertEquals(listOf(2), config.phonemeIdMap["$"])
    }

    @Test
    fun aSingleSpeakerConfigHasNoSpeakerIdMap() {
        assertEquals(emptyMap<String, Int>(), VitsTestFixtures.ukLadaConfig().speakerIdMap)
    }

    @Test
    fun parsesTheRealKazakhMultiSpeakerConfig() {
        val config = VitsTestFixtures.config("kk-issai-high")

        assertEquals(22_050, config.sampleRate)
        assertEquals("kk", config.espeakVoice)
        assertEquals(6, config.numSpeakers)
        assertTrue("a 6-speaker pack must request a sid input", config.isMultiSpeaker)
        // The sids are NOT in key order — reading them off the map rather than
        // assuming an ordering is the whole point of parsing this field.
        assertEquals(
            mapOf(
                "ISSAI_KazakhTTS2_M2" to 0,
                "ISSAI_KazakhTTS_M1_Iseke" to 1,
                "ISSAI_KazakhTTS2_F3" to 2,
                "ISSAI_KazakhTTS_F1_Raya" to 3,
                "ISSAI_KazakhTTS2_F1" to 4,
                "ISSAI_KazakhTTS2_F2" to 5,
            ),
            config.speakerIdMap,
        )
    }

    @Test
    fun parsesTheRealNorwegianMultiSpeakerConfig() {
        val config = VitsTestFixtures.config("no-nvcc-medium")

        assertEquals(22_050, config.sampleRate)
        assertEquals("nb", config.espeakVoice)
        assertEquals(10, config.numSpeakers)
        assertEquals(
            mapOf(
                "KNN" to 0,
                "KSV" to 1,
                "MMN" to 2,
                "KON" to 3,
                "MNN" to 4,
                "MSV" to 5,
                "MON" to 6,
                "MNV" to 7,
                "KMN" to 8,
                "KNV" to 9,
            ),
            config.speakerIdMap,
        )
    }

    @Test
    fun theCatalogsSpeakerSidsMatchTheRealCheckpoints() {
        // The catalog's curated speaker list and the downloaded checkpoint are
        // separate artefacts. A wrong sid here renders a different speaker than
        // the label promises, with no error anywhere — this is the only check
        // that can catch it without a device.
        for (packId in listOf("kk-issai-high", "no-nvcc-medium", "uk-ukrainian_tts-medium")) {
            val config = VitsTestFixtures.config(packId)
            val pack = checkNotNull(VoicePackCatalog.byId(packId)) { "no catalog pack $packId" }
            assertEquals(
                "$packId: catalog declares ${pack.speakers.size} speakers, " +
                    "checkpoint has ${config.numSpeakers}",
                config.numSpeakers,
                pack.speakers.size,
            )
            assertEquals(
                "$packId: catalog sids must be exactly the checkpoint's speaker_id_map values",
                config.speakerIdMap.values.sorted(),
                pack.speakers.map { it.sid }.sorted(),
            )
        }
    }

    @Test
    fun everyMultiSpeakerCatalogPackHasACheckedInConfigAndViceVersa() {
        // Guards the test above from silently covering nothing: a new
        // multi-speaker pack without a fixture would skip the sid check.
        val declared = VoicePackCatalog.forEngine(VoicePackCatalog.VITS_MARMALADE_ENGINE)
            .filter { it.speakers.isNotEmpty() }
            .map { it.id }
        assertEquals(
            listOf("kk-issai-high", "no-nvcc-medium", "uk-ukrainian_tts-medium"),
            declared,
        )
    }

    @Test
    fun phonemeTypeDefaultsToEspeakAndIsReadWhenPresent() {
        // uk-lada's config has no `phoneme_type` at all (pre-1.0 exporter),
        // no-nvcc spells "espeak" out, and uk-ukrainian_tts is the grapheme one.
        assertEquals(VitsPhonemeType.ESPEAK, VitsTestFixtures.ukLadaConfig().phonemeType)
        assertEquals(
            VitsPhonemeType.ESPEAK,
            VitsTestFixtures.config("no-nvcc-medium").phonemeType,
        )
        assertEquals(
            VitsPhonemeType.TEXT,
            VitsTestFixtures.config("uk-ukrainian_tts-medium").phonemeType,
        )
    }

    @Test
    fun parsesTheRealGraphemeUkrainianConfig() {
        val config = VitsTestFixtures.config("uk-ukrainian_tts-medium")

        assertEquals(22_050, config.sampleRate)
        assertEquals(3, config.numSpeakers)
        assertEquals(mapOf("lada" to 0, "mykyta" to 1, "tetiana" to 2), config.speakerIdMap)
        // A grapheme map is small: Ukrainian letters + punctuation, no IPA.
        assertEquals(49, config.phonemeIdMap.size)
        assertTrue(
            "a grapheme map must carry punctuation as its own entries",
            config.phonemeIdMap.containsKey("!") && config.phonemeIdMap.containsKey(","),
        )
    }

    @Test
    fun anUnknownPhonemeTypeIsRejectedRatherThanDefaulted() {
        // Defaulting to espeak would feed IPA ids into a table they don't
        // belong to: no error, pure noise.
        val doctored = VitsTestFixtures.ukLadaConfigJson()
            .replace("\"dataset\"", "\"phoneme_type\": \"runes\",\n  \"dataset\"")
        val error = runCatching { VitsPackConfig.parse(doctored, origin = "packs/w") }
            .exceptionOrNull()
        assertTrue("expected IllegalStateException, got $error", error is IllegalStateException)
        assertTrue(
            "message should name the offending value, was '${error?.message}'",
            error?.message?.contains("runes") == true,
        )
    }

    @Test
    fun malformedJsonFailsWithTheOffendingOrigin() {
        val error = runCatching { VitsPackConfig.parse("{not json", origin = "packs/x") }
            .exceptionOrNull()
        assertTrue("expected IllegalStateException, got $error", error is IllegalStateException)
        assertTrue(
            "message should name the origin, was '${error?.message}'",
            error?.message?.contains("packs/x") == true,
        )
    }

    @Test
    fun aConfigMissingTheWrappingMarkersIsRejected() {
        // A phoneme map without `_` would silently encode without pad
        // interspersal — audio would come out as noise, so this must fail loud.
        val stripped = VitsTestFixtures.ukLadaConfigJson()
            .replace("\"_\": [\n      0\n    ],", "")
        val error = runCatching { VitsPackConfig.parse(stripped, origin = "packs/y") }
            .exceptionOrNull()
        assertTrue("expected IllegalStateException, got $error", error is IllegalStateException)
    }

    @Test
    fun aConfigMissingARequiredSectionIsRejected() {
        val error = runCatching {
            VitsPackConfig.parse("""{"audio": {"sample_rate": 16000}}""", origin = "packs/z")
        }.exceptionOrNull()
        assertTrue("expected IllegalStateException, got $error", error is IllegalStateException)
    }
}
