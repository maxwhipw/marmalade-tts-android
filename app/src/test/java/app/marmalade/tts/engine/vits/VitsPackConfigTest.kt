package app.marmalade.tts.engine.vits

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
