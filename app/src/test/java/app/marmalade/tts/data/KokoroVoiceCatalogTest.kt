package app.marmalade.tts.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests the static Kokoro voice seed used to populate Room on first
 * launch. If the voice list or its ordering drifts the speaker indices
 * in `KokoroEngine.SPEAKER_ID_BY_NAME` will silently desync from
 * Sherpa-ONNX's `voices.bin` — none of which surfaces as a build error,
 * so the catalog itself is worth pinning with assertions.
 */
class KokoroVoiceCatalogTest {

    @Test
    fun seedsExpectedElevenVoicesInVoicesBinOrder() {
        // Order matches Sherpa-ONNX's kokoro v0.19 voices.bin packing
        // (alphabetical by upstream voice key). Both the catalog and
        // the engine's SPEAKER_ID_BY_NAME depend on this order — if it
        // changes, that map must change in lockstep.
        val names = KokoroVoiceCatalog.voices.map { it.displayName }
        assertEquals(
            listOf(
                "af",
                "af_bella",
                "af_nicole",
                "af_sarah",
                "af_sky",
                "am_adam",
                "am_michael",
                "bf_emma",
                "bf_isabella",
                "bm_george",
                "bm_lewis",
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
    fun allVoicesAreEnUsAtNativeSampleRate() {
        for (voice in KokoroVoiceCatalog.voices) {
            assertEquals("en-US", voice.languageCode)
            assertEquals(24000, voice.sampleRate)
            assertEquals("kokoro", voice.engine)
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
        assertEquals("female", KokoroVoiceCatalog.genderFor("af"))
        assertEquals("female", KokoroVoiceCatalog.genderFor("af_bella"))
        assertEquals("male", KokoroVoiceCatalog.genderFor("bm_lewis"))
        assertNull("malformed key should not infer a gender",
            KokoroVoiceCatalog.genderFor("x"))
        // Keys that don't follow the [region][gender]_… convention return
        // null — the catalog never produces these, but the helper should
        // not invent a guess for unknown shapes.
        assertNull(KokoroVoiceCatalog.genderFor("ab_unknown"))
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
    fun voiceCountMatchesExpectedKokoroV019Set() {
        // Pin the count alongside the order test — a slip that
        // duplicates a name would still pass the order test if both
        // rows happened to compare equal, but the count would change.
        assertEquals(11, KokoroVoiceCatalog.voices.size)
    }
}
