package app.marmalade.tts.install

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the static catalog data so a stray edit can't silently change
 * what's installable.
 *
 * The actual sha256 verification happens at install time against the
 * single archive bytes — this test only confirms the catalog schema is
 * well-formed (HTTPS URL, lower-case 64-hex sha256, non-zero sizes,
 * GPL disclosure present).
 */
class EngineCatalogTest {

    @Test
    fun catalogContainsAllVariants() {
        // Order is the display order in onboarding + Settings → Engines.
        // Kokoro v1.0 first (recommended default), then v1.1 (Mandarin
        // specialist), then KokoroDirect (perf port). Kitten family next
        // in the same order. Pocket last because it's the alpha-quality
        // non-sherpa engine.
        assertEquals(
            listOf(
                "kokoro-v1_0",
                "kokoro-v1_1",
                "kokoro-direct-v1_0",
                "kitten-nano-v0_8",
                "kitten-mini-v0_8",
                "kitten-direct-v0_8",
                "kitten-direct-mini-v0_8",
                "pocket-tts-en-v2026_04",
            ),
            EngineCatalog.all.map { it.name },
        )
    }

    @Test
    fun kokoroDirectIsRecommended_othersAreNot() {
        // Exactly one recommended engine — the onboarding pre-selection
        // logic reads the boolean per engine; multiple recommendations
        // would over-pre-select on first launch. The recommendation moved
        // from the sherpa kokoro-v1_0 to kokoro-direct-v1_0 in
        // v0.3.0-alpha.10.Z when the sherpa engines became developer-only.
        assertEquals(
            "exactly one recommended engine expected",
            1,
            EngineCatalog.all.count { it.isRecommended },
        )
        assertTrue(
            "kokoro-direct-v1_0 should be the recommended default",
            EngineCatalog.byName("kokoro-direct-v1_0")!!.isRecommended,
        )
        // The recommended engine must always be visible to non-developers,
        // else fresh release installs would onboard with nothing pre-checked.
        assertTrue(
            "the recommended engine must not be developerOnly",
            EngineCatalog.all.none { it.isRecommended && it.developerOnly },
        )
    }

    @Test
    fun developerOnlyFlagsExactlyTheSherpaEngines() {
        // The four sherpa-onnx engines are gated as developer-only; the
        // direct-ORT engines + Pocket are production. visibleTo(false) must
        // drop exactly the sherpa four.
        assertEquals(
            setOf("kokoro-v1_0", "kokoro-v1_1", "kitten-nano-v0_8", "kitten-mini-v0_8"),
            EngineCatalog.developerOnlyNames,
        )
        assertEquals(
            "visibleTo(false) drops the four sherpa engines",
            EngineCatalog.all.size - 4,
            EngineCatalog.visibleTo(showDeveloper = false).size,
        )
        // visibleTo(true) keeps every engine but sorts the developer-only
        // ones to the end — so it's the same set, ordered production-first.
        val visibleAll = EngineCatalog.visibleTo(showDeveloper = true)
        assertEquals(
            "visibleTo(true) contains the whole catalog",
            EngineCatalog.all.toSet(),
            visibleAll.toSet(),
        )
        val firstDeveloperIdx = visibleAll.indexOfFirst { it.developerOnly }
        assertTrue(
            "developer engines must sort after all production engines",
            firstDeveloperIdx == -1 || visibleAll.drop(firstDeveloperIdx).all { it.developerOnly },
        )
    }

    @Test
    fun engineNameMatchesEngineKey() {
        // Engine identifier must match the directory name the engine class
        // uses (filesDir/engines/<name>). Catching a rename here saves us
        // from a silent install-vs-load mismatch.
        for (engine in EngineCatalog.all) {
            assertEquals(engine.name, EngineCatalog.byName(engine.name)!!.name)
        }
    }

    @Test
    fun everyArchiveUrlIsHttps() {
        for (engine in EngineCatalog.all) {
            assertTrue(
                "archive url for ${engine.name} must be HTTPS, was '${engine.archive.url}'",
                engine.archive.url.startsWith("https://"),
            )
        }
    }

    @Test
    fun everyArchiveShaIs64HexLowercase() {
        for (engine in EngineCatalog.all) {
            val sha = engine.archive.sha256
            assertEquals(
                "${engine.name}: sha256 must be 64 hex chars, was '$sha'",
                64,
                sha.length,
            )
            assertTrue(
                "${engine.name}: sha256 must be lowercase hex, was '$sha'",
                sha.all { it in '0'..'9' || it in 'a'..'f' },
            )
        }
    }

    @Test
    fun everyArchiveSizeIsPositiveAndReasonable() {
        for (engine in EngineCatalog.all) {
            // > 1 MB guards against a "0" or sentinel size slipping in;
            // we don't pin the upper bound because bundle refreshes change it.
            assertTrue(
                "${engine.name}: archive.sizeBytes must be > 1 MB, was ${engine.archive.sizeBytes}",
                engine.archive.sizeBytes > 1L * 1024L * 1024L,
            )
        }
    }

    @Test
    fun kokoroPointsAtV6FpThirtyTwoMultiLangRelease() {
        // v0.1.20 upgraded the Kokoro bundle from v5 (int8-v1.0 — the
        // unblessed power-user export that produced tinny audio) to v6
        // (fp32 `kokoro-multi-lang-v1_0`). Catching a URL typo here saves
        // a real install failure (404) on the first launch after upgrade.
        val kokoro = EngineCatalog.byName("kokoro-v1_0")!!
        assertTrue(
            "kokoro must reference the v6 engines-repo release, was '${kokoro.archive.url}'",
            kokoro.archive.url.contains("/releases/download/v6/"),
        )
        assertTrue(
            "kokoro archive should be the fp32 kokoro-multi-lang-v1_0 bundle",
            kokoro.archive.url.endsWith("kokoro-multi-lang-v1_0.tar.bz2"),
        )
    }

    @Test
    fun downloadSizeMatchesArchiveSize() {
        // The "download size" the UI shows the user is just the archive's
        // wire size — they're not allowed to drift. (installedSizeBytes is
        // bigger because it's the unpacked total.)
        for (engine in EngineCatalog.all) {
            assertEquals(
                "downloadSizeBytes for ${engine.name} should equal archive.sizeBytes",
                engine.archive.sizeBytes,
                engine.downloadSizeBytes,
            )
        }
    }

    @Test
    fun installedSizeIsLargerThanDownloadSize() {
        // Tar.bz2 is compressed — extracted total should always exceed
        // archive size. Sanity check that catches accidentally setting
        // installedSizeBytes to archive.sizeBytes.
        for (engine in EngineCatalog.all) {
            assertTrue(
                "${engine.name}: installedSizeBytes (${engine.installedSizeBytes}) " +
                    "should exceed downloadSizeBytes (${engine.downloadSizeBytes})",
                engine.installedSizeBytes > engine.downloadSizeBytes,
            )
        }
    }

    @Test
    fun everyArchiveRootEndsWithSlash() {
        // archiveRoot's contract is "directory prefix to strip" — if it
        // doesn't end with "/" the prefix match will eat partial filenames.
        // Empty string is allowed (means "no stripping").
        for (engine in EngineCatalog.all) {
            val root = engine.archive.archiveRoot
            if (root.isNotEmpty()) {
                assertTrue(
                    "${engine.name}: archiveRoot must end with '/', was '$root'",
                    root.endsWith("/"),
                )
            }
        }
    }

    @Test
    fun byNameLookupRoundtripsAndReturnsNullForUnknown() {
        for (engine in EngineCatalog.all) {
            assertNotNull(EngineCatalog.byName(engine.name))
        }
        assertNull(EngineCatalog.byName("not-an-engine"))
    }

    @Test
    fun licenseSummaryFlagsGplComponent() {
        // GPL disclosure is part of the install consent UX — sherpa-onnx
        // engines pull in espeak-ng-data (GPL-3.0) via Sherpa-ONNX, so
        // their licenseSummary must mention "GPL" so install cards reflect
        // it. Pocket runs on Microsoft onnxruntime-android directly and
        // has no GPL components, so it's exempt — and its licenseSummary
        // must explicitly state "no GPL" so users can see the difference.
        val sherpaEngines = EngineCatalog.all.filter { it.name != "pocket-tts-en-v2026_04" }
        for (engine in sherpaEngines) {
            val haystack = engine.licenseSummary.lowercase()
            assertTrue(
                "${engine.name}.licenseSummary should mention GPL — was '${engine.licenseSummary}'",
                haystack.contains("gpl"),
            )
        }
        val pocket = EngineCatalog.byName("pocket-tts-en-v2026_04")!!
        assertTrue(
            "pocket licenseSummary should explicitly state it has no GPL — was '${pocket.licenseSummary}'",
            pocket.licenseSummary.lowercase().contains("no gpl"),
        )
    }

    @Test
    fun emptyArchiveUrlIsRejected() {
        // The init block of EngineDescriptor should refuse to construct an
        // engine with no archive URL — protect that invariant.
        try {
            EngineDescriptor(
                name = "empty",
                displayName = "Empty",
                description = "x",
                downloadSizeBytes = 1L,
                installedSizeBytes = 1L,
                isRecommended = false,
                archive = EngineArchive(
                    url = "",
                    sha256 = "0".repeat(64),
                    sizeBytes = 1L,
                ),
                licenseNotice = "",
                licenseSummary = "",
            )
            throw AssertionError("expected IllegalArgumentException for empty archive url")
        } catch (_: IllegalArgumentException) {
            // pass
        }
    }

    @Test
    fun zeroSizeArchiveIsRejected() {
        try {
            EngineDescriptor(
                name = "tiny",
                displayName = "Tiny",
                description = "x",
                downloadSizeBytes = 0L,
                installedSizeBytes = 0L,
                isRecommended = false,
                archive = EngineArchive(
                    url = "https://example.invalid/x.tar.bz2",
                    sha256 = "0".repeat(64),
                    sizeBytes = 0L,
                ),
                licenseNotice = "",
                licenseSummary = "",
            )
            throw AssertionError("expected IllegalArgumentException for zero archive size")
        } catch (_: IllegalArgumentException) {
            // pass
        }
    }
}
