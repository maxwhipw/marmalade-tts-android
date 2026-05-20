package app.marmalade.tts.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests the static voice seed used to populate Room on first launch. If any
 * of these go wrong the system TTS picker shows the wrong voices, or — worse
 * — voice IDs don't round-trip through Android's `voiceName` field. None of
 * those would surface as a build error, so the catalog itself is worth
 * pinning with assertions.
 */
class KittenVoiceCatalogTest {

    @Test
    fun seedsExpectedEightVoices() {
        val names = KittenVoiceCatalog.voices.map { it.displayName }
        assertEquals(
            listOf("Bella", "Jasper", "Luna", "Bruno", "Rosie", "Hugo", "Kiki", "Leo"),
            names,
        )
    }

    @Test
    fun voiceIdsUseEngineColonDisplayNameConvention() {
        for (voice in KittenVoiceCatalog.voices) {
            assertEquals(
                "voice id mismatch for ${voice.displayName}",
                "kitten:${voice.displayName}",
                voice.id,
            )
        }
    }

    @Test
    fun allVoicesAreEnUsAtNativeSampleRate() {
        for (voice in KittenVoiceCatalog.voices) {
            assertEquals("en-US", voice.languageCode)
            assertEquals(24000, voice.sampleRate)
            assertEquals("kitten", voice.engine)
        }
    }

    @Test
    fun genderIsAlwaysFemaleOrMale() {
        for (voice in KittenVoiceCatalog.voices) {
            assertNotNull("gender missing for ${voice.displayName}", voice.gender)
            assertTrue(
                "unexpected gender '${voice.gender}' for ${voice.displayName}",
                voice.gender == "female" || voice.gender == "male",
            )
        }
    }

    @Test
    fun defaultVoiceIdIsBella() {
        // Bella is the documented default — UI agent, CHANGELOG, and
        // README all reference it by name.
        assertEquals("kitten:Bella", KittenVoiceCatalog.DEFAULT_VOICE_ID)
        assertNotNull(KittenVoiceCatalog.voices.firstOrNull { it.id == KittenVoiceCatalog.DEFAULT_VOICE_ID })
    }

    @Test
    fun nothingShipsAsInstalledOutOfTheBox() {
        // Until KittenEngine.ensureModelLoaded() succeeds, no voice should
        // claim to be installed. Voice picker filters depend on this flag.
        for (voice in KittenVoiceCatalog.voices) {
            assertEquals(
                "voice ${voice.displayName} should not start installed",
                false,
                voice.isInstalled,
            )
        }
    }

    @Test
    fun voiceIdHelperMatchesEntries() {
        // The helper is what callers should use; verify it produces the
        // same IDs the seed list does.
        for (voice in KittenVoiceCatalog.voices) {
            assertEquals(voice.id, KittenVoiceCatalog.voiceId(voice.displayName))
        }
    }
}
