package app.marmalade.tts.install

import app.marmalade.tts.data.VitsVoiceCatalog
import app.marmalade.tts.engine.vits.VitsDirectEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the voice-pack catalog the same way [EngineCatalogTest] pins engines:
 * a stray edit to a URL, hash or size is the difference between a working
 * install and a download that fails after 18 MB, and none of it is covered by
 * a compile check.
 *
 * Plain JVM — no resources, no `org.json`.
 */
class VoicePackCatalogTest {

    @Test
    fun catalogContainsTheSliceAPack() {
        assertEquals(listOf("uk-lada-x_low"), VoicePackCatalog.all.map { it.id })
    }

    @Test
    fun everyPackTargetsAKnownEngine() {
        for (pack in VoicePackCatalog.all) {
            assertNotNull(
                "pack ${pack.id} targets unknown engine '${pack.engine}'",
                EngineCatalog.byName(pack.engine),
            )
        }
    }

    @Test
    fun theEngineNameIsSpelledTheSameEverywhere() {
        // Three copies of this string exist by design (installer package,
        // engine package, voice catalog) because the install layer must not
        // depend on the engine layer. They must agree or voices route nowhere.
        assertEquals(VoicePackCatalog.VITS_MARMALADE_ENGINE, VitsDirectEngine.ENGINE_NAME)
        assertEquals(VoicePackCatalog.VITS_MARMALADE_ENGINE, VitsVoiceCatalog.ENGINE)
    }

    @Test
    fun everyArchiveUrlIsHttpsAndEveryShaIs64HexLowercase() {
        for (pack in VoicePackCatalog.all) {
            assertTrue(
                "pack ${pack.id} archive url must be HTTPS, was '${pack.archive.url}'",
                pack.archive.url.startsWith("https://"),
            )
            val sha = pack.archive.sha256
            assertEquals("${pack.id}: sha256 must be 64 hex chars, was '$sha'", 64, sha.length)
            assertTrue(
                "${pack.id}: sha256 must be lowercase hex, was '$sha'",
                sha.all { it in '0'..'9' || it in 'a'..'f' },
            )
        }
    }

    @Test
    fun installedSizeExceedsDownloadSize() {
        // The archives are gzip-compressed, so an installedSizeBytes at or
        // below the wire size means someone copied the wrong number — and the
        // extraction progress bar would freeze short of (or race past) 100%.
        for (pack in VoicePackCatalog.all) {
            assertTrue(
                "${pack.id}: installedSizeBytes (${pack.installedSizeBytes}) should exceed " +
                    "archive size (${pack.archive.sizeBytes})",
                pack.installedSizeBytes > pack.archive.sizeBytes,
            )
        }
    }

    @Test
    fun everyArchiveRootEndsWithSlash() {
        for (pack in VoicePackCatalog.all) {
            val root = pack.archive.archiveRoot
            if (root.isNotEmpty()) {
                assertTrue("${pack.id}: archiveRoot must end with '/', was '$root'", root.endsWith("/"))
            }
        }
    }

    @Test
    fun lookupsRoundtripAndTheDefaultPackIsTheEngineDescriptorsOne() {
        for (pack in VoicePackCatalog.all) {
            assertEquals(pack, VoicePackCatalog.byId(pack.id))
        }
        assertNull(VoicePackCatalog.byId("not-a-pack"))

        val engine = EngineCatalog.byName(VoicePackCatalog.VITS_MARMALADE_ENGINE)!!
        assertEquals(
            VoicePackCatalog.defaultPackFor(engine.name)!!.id,
            engine.defaultPackId,
        )
    }

    @Test
    fun aPackIdWithAPathOrVoiceSeparatorIsRejected() {
        // The id is both a directory name and the second half of a
        // "<engine>:<packId>" voice id — a '/' would escape the packs dir and
        // a ':' would split the voice id in the wrong place.
        for (bad in listOf("../evil", "uk:lada")) {
            val error = runCatching {
                VoicePackCatalog.UK_LADA_X_LOW.copy(id = bad)
            }.exceptionOrNull()
            assertTrue(
                "expected IllegalArgumentException for id '$bad', got $error",
                error is IllegalArgumentException,
            )
        }
    }

    @Test
    fun voiceRowsMirrorThePackCatalog() {
        // The voice list IS the pack list for this engine (one single-speaker
        // checkpoint per pack), so any divergence means a voice the engine
        // can't resolve or a pack the picker never shows.
        assertEquals(
            VoicePackCatalog.forEngine(VitsVoiceCatalog.ENGINE).map { "${VitsVoiceCatalog.ENGINE}:${it.id}" },
            VitsVoiceCatalog.voices.map { it.id },
        )
        assertEquals(
            VoicePackCatalog.forEngine(VitsVoiceCatalog.ENGINE).map { it.languageCode },
            VitsVoiceCatalog.voices.map { it.languageCode },
        )
        assertTrue(
            "the default voice must be one of the catalog's voices",
            VitsVoiceCatalog.voices.any { it.id == VitsVoiceCatalog.DEFAULT_VOICE_ID },
        )
    }
}
