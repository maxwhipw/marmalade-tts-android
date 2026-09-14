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

    private val ENGINE = VoicePackCatalog.VITS_MARMALADE_ENGINE

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
                "kk-issai-high",
                "no-nvcc-medium",
                "uk-ukrainian_tts-medium",
            ),
            VoicePackCatalog.all.map { it.id },
        )
    }

    @Test
    fun aSingleSpeakerPackKeepsItsBarePackIdAsTheVoiceKey() {
        // Non-negotiable: `uk-lada-x_low` already shipped, and user aliases
        // store the voice id. A `#0` suffix would orphan every one of them.
        val lada = VoicePackCatalog.UK_LADA_X_LOW
        assertEquals(1, lada.voices.size)
        assertEquals("uk-lada-x_low", lada.voices.single().voiceKey)
        assertEquals(0, lada.voices.single().sid)
        assertEquals(lada.displayName, lada.voices.single().displayName)
        // …and the suffixed form is NOT a synonym for it.
        assertNull(VoicePackCatalog.voiceByKey(ENGINE, "uk-lada-x_low#0"))
    }

    @Test
    fun aMultiSpeakerPackKeysEachSpeakerByItsSid() {
        val issai = VoicePackCatalog.KK_ISSAI_HIGH
        assertEquals(6, issai.voices.size)
        assertEquals(
            listOf(
                "kk-issai-high#0",
                "kk-issai-high#1",
                "kk-issai-high#2",
                "kk-issai-high#3",
                "kk-issai-high#4",
                "kk-issai-high#5",
            ),
            issai.voices.map { it.voiceKey },
        )
        // Each voice must carry its pack's language + rate, not the default.
        for (voice in issai.voices) {
            assertEquals("kk-KZ", voice.languageCode)
            assertEquals(22_050, voice.sampleRate)
        }
    }

    @Test
    fun voiceKeysRoundTripToTheirPackAndSpeaker() {
        for (voice in VoicePackCatalog.voicesForEngine(ENGINE)) {
            assertEquals(voice, VoicePackCatalog.voiceByKey(ENGINE, voice.voiceKey))
        }
        // The named Kazakh speakers, by the sids the checkpoint actually uses
        // (VitsPackConfigTest pins these against the real config).
        val iseke = VoicePackCatalog.voiceByKey(ENGINE, "kk-issai-high#1")!!
        assertEquals("kk-issai-high", iseke.packId)
        assertEquals(1, iseke.sid)
        assertEquals("Iseke (Kazakh)", iseke.displayName)
        assertEquals("male", iseke.gender)
        assertEquals("Raya (Kazakh)", VoicePackCatalog.voiceByKey(ENGINE, "kk-issai-high#3")!!.displayName)
    }

    @Test
    fun anOutOfRangeOrMalformedSpeakerSuffixResolvesToNothing() {
        // The engine turns null into a hard failure. Silently falling back to
        // speaker 0 would render a different person than the user picked.
        for (bad in listOf(
            "kk-issai-high#6",
            "kk-issai-high#-1",
            "kk-issai-high#",
            "kk-issai-high#01",
            "kk-issai-high",
            "no-nvcc-medium#10",
            "not-a-pack#0",
        )) {
            assertNull("'$bad' must not resolve to a voice", VoicePackCatalog.voiceByKey(ENGINE, bad))
        }
    }

    @Test
    fun everyVoiceKeyIsUniqueAcrossTheWholeCatalog() {
        // Voice keys become Room primary keys (`<engine>:<voiceKey>`), so a
        // collision silently drops one voice at seed time.
        val keys = VoicePackCatalog.all.flatMap { pack -> pack.voices.map { it.voiceKey } }
        assertEquals(keys.size, keys.distinct().size)
    }

    @Test
    fun norwegianSpeakerLabelsDecodeTheirCorpusCode() {
        // The labels are generated from the three-letter code, so this pins the
        // decode table (K/M gender, last two letters dialect area) rather than
        // ten hand-written strings.
        val nvcc = VoicePackCatalog.NO_NVCC_MEDIUM
        assertEquals(10, nvcc.voices.size)
        assertEquals("Norwegian KNN (female, North Norway)", nvcc.voices[0].displayName)
        assertEquals("Norwegian MON (male, East Norway)", nvcc.voices[6].displayName)
        assertEquals("Norwegian KSV (female, Southwest Norway)", nvcc.voices[1].displayName)
        assertEquals("Norwegian MNV (male, Northwest Norway)", nvcc.voices[7].displayName)
        assertEquals("Norwegian KMN (female, Central Norway)", nvcc.voices[8].displayName)
        for (voice in nvcc.voices) {
            val code = voice.displayName.removePrefix("Norwegian ").take(3)
            val expected = if (code.first() == 'K') "female" else "male"
            assertEquals(
                "${voice.voiceKey}: gender must follow the code's first letter",
                expected,
                voice.gender,
            )
            assertTrue(
                "${voice.displayName}: label must spell the gender out",
                voice.displayName.contains("($expected,"),
            )
        }
    }

    @Test
    fun everyVoiceLabelIsDistinctIncludingTheTwoLadas() {
        // Two rows reading "Lada (Ukrainian)" in the picker would be
        // indistinguishable: the same speaker exists in the x_low pack and in
        // the 3-speaker medium one. The better voice keeps the plain name.
        val names = VoicePackCatalog.voicesForEngine(ENGINE).map { it.displayName }
        assertEquals("voice labels must be unique: $names", names.size, names.distinct().size)
        assertEquals(
            "Lada (Ukrainian, small)",
            VoicePackCatalog.voiceByKey(ENGINE, "uk-lada-x_low")!!.displayName,
        )
        assertEquals(
            "Lada (Ukrainian)",
            VoicePackCatalog.voiceByKey(ENGINE, "uk-ukrainian_tts-medium#0")!!.displayName,
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
        // The id is both a directory name and the first part of a
        // "<engine>:<packId>[#<sid>]" voice id — a '/' would escape the packs
        // dir, a ':' would split the voice id in the wrong place, and a '#'
        // would collide with the speaker suffix.
        for (bad in listOf("../evil", "uk:lada", "uk#lada")) {
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
    fun voiceRowsMirrorThePackCatalogsFlattenedVoiceList() {
        // The seeded rows are the catalog's PackVoice list, one per speaker —
        // any divergence means a voice the engine can't resolve or a voice the
        // picker never shows.
        val voices = VoicePackCatalog.voicesForEngine(VitsVoiceCatalog.ENGINE)
        assertEquals(
            voices.map { "${VitsVoiceCatalog.ENGINE}:${it.voiceKey}" },
            VitsVoiceCatalog.voices.map { it.id },
        )
        assertEquals(voices.map { it.languageCode }, VitsVoiceCatalog.voices.map { it.languageCode })
        assertEquals(voices.map { it.sampleRate }, VitsVoiceCatalog.voices.map { it.sampleRate })
        assertEquals(voices.map { it.gender }, VitsVoiceCatalog.voices.map { it.gender })
        assertEquals(voices.map { it.displayName }, VitsVoiceCatalog.voices.map { it.displayName })
        // 6 single-speaker packs + 6 Kazakh + 10 Norwegian + 3 Ukrainian.
        assertEquals(25, VitsVoiceCatalog.voices.size)
        assertTrue(
            "the default voice must be one of the catalog's voices",
            VitsVoiceCatalog.voices.any { it.id == VitsVoiceCatalog.DEFAULT_VOICE_ID },
        )
        assertEquals("${VitsVoiceCatalog.ENGINE}:uk-lada-x_low", VitsVoiceCatalog.DEFAULT_VOICE_ID)
    }

    @Test
    fun seededSortOrderGroupsTheVoicesByLanguage() {
        // The picker sorts a model's voices by sortOrder, so this is what keeps
        // the 25 VITS voices from interleaving languages — the two Ukrainian
        // packs sit at opposite ends of the catalog list and would otherwise
        // bracket every other language.
        val byOrder = VitsVoiceCatalog.voices.sortedBy { it.sortOrder }
        val languageRuns = byOrder.map { it.languageCode }
            .fold(mutableListOf<String>()) { runs, code ->
                if (runs.lastOrNull() != code) runs += code
                runs
            }
        assertEquals(
            "each language must appear as ONE contiguous run: $languageRuns",
            listOf("uk-UA", "is-IS", "sv-SE", "kk-KZ", "nb-NO"),
            languageRuns,
        )
        // Ranks are a dense 0..n-1 permutation — a duplicate would make two
        // rows' relative order depend on the name tiebreak instead.
        assertEquals(
            VitsVoiceCatalog.voices.indices.toList(),
            VitsVoiceCatalog.voices.map { it.sortOrder }.sorted(),
        )
        // Inside a language, catalog order is preserved (x_low Lada before the
        // medium pack's three speakers).
        assertEquals(
            listOf(
                "Lada (Ukrainian, small)",
                "Lada (Ukrainian)",
                "Mykyta (Ukrainian)",
                "Tetiana (Ukrainian)",
            ),
            byOrder.filter { it.languageCode == "uk-UA" }.map { it.displayName },
        )
    }

    @Test
    fun everyPackDeclaresAQualityGrade() {
        // Enforced by the type, but this also pins that no pack silently
        // inherits a default: the grade is a per-pack listening judgement.
        for (pack in VoicePackCatalog.all) {
            assertTrue(
                "${pack.id}: meterFill must be 1..4",
                pack.quality.meterFill in 1..4,
            )
        }
    }
}
