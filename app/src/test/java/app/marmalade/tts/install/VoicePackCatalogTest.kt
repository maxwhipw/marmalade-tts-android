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
    fun catalogContainsExactlyTheShippedPacks() {
        assertEquals(
            listOf(
                "uk-lada-x_low",
                "is-bui-medium",
                "is-salka-medium",
                "is-steinn-medium",
                "is-ugla-medium",
                "sv-nst-medium",
            ),
            VoicePackCatalog.all.map { it.id },
        )
    }

    @Test
    fun packIdsAreUnique() {
        // Two packs sharing an id would collide in the packs/ directory, so the
        // second one's download would silently overwrite the first's model.
        val ids = VoicePackCatalog.all.map { it.id }
        assertEquals(ids.size, ids.distinct().size)
    }

    @Test
    fun everyPackDeclaresALanguageSampleRateAndNonBlankIdentity() {
        for (pack in VoicePackCatalog.all) {
            // BCP-47 "xx-YY" — the app-facing spelling VoiceMeta.languageCode
            // wants, not upstream's "xx_YY".
            assertTrue(
                "${pack.id}: languageCode '${pack.languageCode}' must be BCP-47 xx-YY",
                pack.languageCode.matches(Regex("[a-z]{2}-[A-Z]{2}")),
            )
            assertTrue("${pack.id}: blank displayName", pack.displayName.isNotBlank())
            assertTrue("${pack.id}: blank qualityTier", pack.qualityTier.isNotBlank())
            assertTrue("${pack.id}: blank sha256", pack.archive.sha256.isNotBlank())
            // The two tiers upstream publishes: x_low/low render at 16 kHz,
            // medium/high at 22.05 kHz. A mismatch means the seeded VoiceMeta
            // row lies to the system-TTS sample-rate negotiation.
            val expectedRate = if (pack.qualityTier in setOf("x_low", "low")) 16_000 else 22_050
            assertEquals(
                "${pack.id}: ${pack.qualityTier} tier should be $expectedRate Hz",
                expectedRate,
                pack.sampleRate,
            )
            assertTrue(
                "${pack.id}: gender must be male/female/null, was '${pack.gender}'",
                pack.gender in setOf(null, "male", "female"),
            )
        }
    }

    @Test
    fun everyArchiveIsTheSameReleaseTagAndNamedAfterItsPack() {
        // A stray tag in one URL is the one failure mode a sha256 can't catch
        // early: the download succeeds against the wrong asset and fails
        // verification after tens of megabytes.
        for (pack in VoicePackCatalog.all) {
            assertEquals(
                "${pack.id}: archive url must be <base>/<packId>.tar.gz",
                "https://github.com/maxwhipw/marmalade-tts-android-engines/releases/" +
                    "download/v24/${pack.id}.tar.gz",
                pack.archive.url,
            )
            assertEquals("${pack.id}/", pack.archive.archiveRoot)
        }
    }

    @Test
    fun everyShaIsDistinct() {
        // Identical hashes across two packs means a copy-paste that would make
        // one language's download install the other language's voice.
        val shas = VoicePackCatalog.all.map { it.archive.sha256 }
        assertEquals(shas.size, shas.distinct().size)
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
        assertEquals(
            VoicePackCatalog.forEngine(VitsVoiceCatalog.ENGINE).map { it.sampleRate },
            VitsVoiceCatalog.voices.map { it.sampleRate },
        )
        assertEquals(
            VoicePackCatalog.forEngine(VitsVoiceCatalog.ENGINE).map { it.gender },
            VitsVoiceCatalog.voices.map { it.gender },
        )
        assertTrue(
            "the default voice must be one of the catalog's voices",
            VitsVoiceCatalog.voices.any { it.id == VitsVoiceCatalog.DEFAULT_VOICE_ID },
        )
    }
}
