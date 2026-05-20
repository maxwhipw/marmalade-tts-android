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
 * The actual sha256 verification is deferred (see STUBS.md, the
 * `SHA256_PENDING` sentinel) — this test makes sure the schema is
 * consistent (sizes sum, files have URLs, every file has the right
 * structure), not that the hashes are real.
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
    fun kittenHasAllRequiredTopLevelFiles() {
        val kitten = EngineCatalog.byName("kitten")!!
        val paths = kitten.files.map { it.relativePath }.toSet()
        // The four files KittenEngine.isInstalled() looks for.
        assertTrue("missing model.fp16.onnx", paths.contains("model.fp16.onnx"))
        assertTrue("missing voices.bin", paths.contains("voices.bin"))
        assertTrue("missing tokens.txt", paths.contains("tokens.txt"))
        // espeak-ng-data is enumerated as individual files, so confirm at
        // least one entry lives under it (otherwise the manifest is empty
        // and KittenEngine.isInstalled() will reject the install).
        assertTrue(
            "manifest is missing any espeak-ng-data/ entries",
            paths.any { it.startsWith("espeak-ng-data/") },
        )
    }

    @Test
    fun downloadSizeMatchesSumOfFiles() {
        for (engine in EngineCatalog.all) {
            val sum = engine.files.sumOf { it.sizeBytes }
            assertEquals(
                "downloadSizeBytes for ${engine.name} should equal sum of file sizes",
                sum,
                engine.downloadSizeBytes,
            )
        }
    }

    @Test
    fun everyFileHasHttpsUrlAndShaAndNonZeroSize() {
        for (engine in EngineCatalog.all) {
            for (file in engine.files) {
                assertTrue(
                    "url for ${file.relativePath} must be HTTPS, was ${file.url}",
                    file.url.startsWith("https://"),
                )
                assertTrue(
                    "sha256 for ${file.relativePath} must be non-blank",
                    file.sha256.isNotBlank(),
                )
                assertTrue(
                    "sizeBytes for ${file.relativePath} must be > 0",
                    file.sizeBytes > 0L,
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
    fun emptyFilesListIsRejected() {
        // The init block of EngineDescriptor should refuse to construct an
        // engine with no files — protect that invariant.
        try {
            EngineDescriptor(
                name = "empty",
                displayName = "Empty",
                description = "x",
                downloadSizeBytes = 0L,
                installedSizeBytes = 0L,
                isRecommended = false,
                files = emptyList(),
                licenseNotice = "",
                licenseSummary = "",
            )
            throw AssertionError("expected IllegalArgumentException for empty files list")
        } catch (_: IllegalArgumentException) {
            // pass
        }
    }
}
