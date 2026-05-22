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
    fun kittenIsTheOnlyEngineInV01() {
        assertEquals(listOf("kitten"), EngineCatalog.all.map { it.name })
    }

    @Test
    fun kittenIsRecommendedAndMatchesEngineKey() {
        val kitten = EngineCatalog.byName("kitten")!!
        assertTrue(kitten.isRecommended)
        // Engine identifier must match the directory name KittenEngine uses.
        // Catching a rename here saves us from a silent install-vs-load
        // mismatch (`isInstalled()` looks at filesDir/engines/kitten).
        assertEquals("kitten", kitten.name)
    }

    @Test
    fun kittenArchiveUrlIsHttps() {
        val kitten = EngineCatalog.byName("kitten")!!
        assertTrue(
            "archive url must be HTTPS, was '${kitten.archive.url}'",
            kitten.archive.url.startsWith("https://"),
        )
    }

    @Test
    fun kittenArchiveShaIs64HexLowercase() {
        val kitten = EngineCatalog.byName("kitten")!!
        val sha = kitten.archive.sha256
        assertEquals("sha256 must be 64 hex chars, was '$sha'", 64, sha.length)
        assertTrue(
            "sha256 must be lowercase hex, was '$sha'",
            sha.all { it in '0'..'9' || it in 'a'..'f' },
        )
    }

    @Test
    fun kittenArchiveSizeIsPositiveAndReasonable() {
        val kitten = EngineCatalog.byName("kitten")!!
        // > 1 MB guards against a "0" or sentinel size slipping in;
        // we don't pin the upper bound because bundle refreshes change it.
        assertTrue(
            "archive.sizeBytes must be > 1 MB, was ${kitten.archive.sizeBytes}",
            kitten.archive.sizeBytes > 1L * 1024L * 1024L,
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
    fun kittenArchiveRootEndsWithSlash() {
        // archiveRoot's contract is "directory prefix to strip" — if it
        // doesn't end with "/" the prefix match will eat partial filenames.
        // Empty string is allowed (means "no stripping").
        val kitten = EngineCatalog.byName("kitten")!!
        val root = kitten.archive.archiveRoot
        if (root.isNotEmpty()) {
            assertTrue(
                "archiveRoot must end with '/', was '$root'",
                root.endsWith("/"),
            )
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
        // GPL disclosure is part of the install consent UX — it must show
        // up in the catalog string so the UI cards reflect it.
        val kitten = EngineCatalog.byName("kitten")!!
        val haystack = kitten.licenseSummary.lowercase()
        assertTrue(
            "kitten.licenseSummary should mention GPL — was '${kitten.licenseSummary}'",
            haystack.contains("gpl"),
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
