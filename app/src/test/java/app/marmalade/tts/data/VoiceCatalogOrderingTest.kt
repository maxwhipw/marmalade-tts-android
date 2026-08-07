package app.marmalade.tts.data

import app.marmalade.tts.data.db.VoiceMeta
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Contracts introduced with `sortOrder` + pretty Kokoro names
 * (CATALOG_VERSION 32): ids keep the raw voice key while displayName is
 * cosmetic, and both catalogs carry an explicit curated order.
 */
class VoiceCatalogOrderingTest {

    @Test
    fun `kokoro displayName is flagged but id keeps the raw key`() {
        val bella = KokoroDirectVoiceCatalog.voices.first { it.id.endsWith(":af_bella") }
        assertEquals("🇺🇸 Bella", bella.displayName)
        assertEquals("kokoro-direct-v1_0:af_bella", bella.id)

        val kumo = KokoroDirectVoiceCatalog.voices.first { it.id.endsWith(":jm_kumo") }
        assertEquals("🇯🇵 Kumo", kumo.displayName)
    }

    @Test
    fun `speakerIdFor resolves raw keys to voices_bin rows`() {
        assertEquals(2, KokoroDirectVoiceCatalog.speakerIdFor("af_bella"))
        assertEquals(41, KokoroDirectVoiceCatalog.speakerIdFor("jm_kumo"))
        assertEquals(-1, KokoroDirectVoiceCatalog.speakerIdFor("🇺🇸 Bella"))
    }

    @Test
    fun `kokoro sortOrder equals speaker index`() {
        KokoroDirectVoiceCatalog.voices.forEachIndexed { i, v ->
            assertEquals(v.id, i, v.sortOrder)
        }
    }

    @Test
    fun `kitten voices are in the curated best-first order`() {
        assertEquals(
            listOf("Rosie", "Bruno", "Kiki", "Hugo", "Bella", "Jasper", "Luna", "Leo"),
            KittenDirectVoiceCatalog.voices.sortedBy { it.sortOrder }.map { it.displayName },
        )
    }

    @Test
    fun `spokenName strips the flag for speech`() {
        val flagged = KokoroDirectVoiceCatalog.voices.first { it.id.endsWith(":af_bella") }
        assertEquals("Bella", flagged.spokenName)

        val plain = VoiceMeta("kitten:Bella", "kitten", "Bella", "en-US", 24000, "female")
        assertEquals("Bella", plain.spokenName)
    }
}
