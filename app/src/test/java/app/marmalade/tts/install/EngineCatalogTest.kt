package app.marmalade.tts.install

import androidx.test.core.app.ApplicationProvider
import app.marmalade.tts.R
import app.marmalade.tts.data.KokoroDirectVoiceCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Pins the static catalog data so a stray edit can't silently change
 * what's installable.
 *
 * The actual sha256 verification happens at install time against the
 * single archive bytes — this test only confirms the catalog schema is
 * well-formed (HTTPS URL, lower-case 64-hex sha256, non-zero sizes,
 * GPL disclosure present).
 *
 * Robolectric because the catalog's prose lives in string resources and
 * the GPL-disclosure check reads the resolved text.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EngineCatalogTest {

    private val resources = ApplicationProvider
        .getApplicationContext<android.content.Context>()
        .resources

    @Test
    fun catalogContainsAllVariants() {
        // Order is the display order in onboarding + Settings → Engines.
        // Kitten Direct first (recommended, baked offline default), then
        // Kokoro Direct, then Pocket. The clean-room Pocket diagnostic
        // engine is last because it's developer-only.
        assertEquals(
            listOf(
                "kitten-direct-v0_8",
                "kokoro-direct-v1_0",
                "pocket-tts-en-v2026_04",
                // Developer-only clean-room Pocket (diagnostic; shares the
                // production Pocket bundle payload).
                "pocket-tts-en-v2026_04-dev",
            ),
            EngineCatalog.all.map { it.name },
        )
    }

    @Test
    fun kittenDirectIsRecommended_othersAreNot() {
        // Exactly one recommended engine — the onboarding pre-selection
        // logic reads the boolean per engine; multiple recommendations
        // would over-pre-select on first launch.
        assertEquals(
            "exactly one recommended engine expected",
            1,
            EngineCatalog.all.count { it.isRecommended },
        )
        assertTrue(
            "kitten-direct-v0_8 should be the recommended default (baked, offline-ready)",
            EngineCatalog.byName("kitten-direct-v0_8")!!.isRecommended,
        )
        // The recommended engine must always be visible to non-developers,
        // else fresh release installs would onboard with nothing pre-checked.
        assertTrue(
            "the recommended engine must not be developerOnly",
            EngineCatalog.all.none { it.isRecommended && it.developerOnly },
        )
    }

    @Test
    fun developerOnlyFlagsThePocketDevEngine() {
        // Developer-only = just the clean-room Pocket diagnostic engine now
        // that sherpa-onnx (and its four engines) is gone. The production
        // direct-ORT engines + Pocket stay visible. visibleTo(false) must
        // drop exactly this one.
        assertEquals(
            setOf("pocket-tts-en-v2026_04-dev"),
            EngineCatalog.developerOnlyNames,
        )
        assertEquals(
            "visibleTo(false) drops the one developer-only engine",
            EngineCatalog.all.size - 1,
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
        // GPL disclosure is part of the install consent UX — the Kokoro/
        // Kitten direct engines ship espeak-ng (GPL-3.0) in their bundle, so
        // their licenseSummary must mention "GPL" so install cards reflect
        // it. Pocket runs on Microsoft onnxruntime-android directly and
        // has no GPL components, so it's exempt — and its licenseSummary
        // must explicitly state "no GPL" so users can see the difference.
        // Both Pocket variants (prod + clean-room dev) share the GPL-free payload.
        val pocketEngines = setOf("pocket-tts-en-v2026_04", "pocket-tts-en-v2026_04-dev")
        val gplEngines = EngineCatalog.all.filter { it.name !in pocketEngines }
        for (engine in gplEngines) {
            val summary = resources.getString(engine.licenseSummaryRes)
            assertTrue(
                "${engine.name} license summary should mention GPL — was '$summary'",
                summary.lowercase().contains("gpl"),
            )
        }
        val pocket = EngineCatalog.byName("pocket-tts-en-v2026_04")!!
        val pocketSummary = resources.getString(pocket.licenseSummaryRes)
        assertTrue(
            "pocket license summary should explicitly state it has no GPL — was '$pocketSummary'",
            pocketSummary.lowercase().contains("no gpl"),
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
                descriptionRes = R.string.engine_kitten_desc,
                downloadSizeBytes = 1L,
                installedSizeBytes = 1L,
                isRecommended = false,
                archive = EngineArchive(
                    url = "",
                    sha256 = "0".repeat(64),
                    sizeBytes = 1L,
                ),
                licenseNotice = "",
                licenseSummaryRes = R.string.engine_kitten_license,
                taglineRes = R.string.engine_kitten_tagline,
                speedTier = SpeedTier.FAST,
                qualityTier = QualityTier.NATURAL,
                languageCodes = listOf("en"),
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
                descriptionRes = R.string.engine_kitten_desc,
                downloadSizeBytes = 0L,
                installedSizeBytes = 0L,
                isRecommended = false,
                archive = EngineArchive(
                    url = "https://example.invalid/x.tar.bz2",
                    sha256 = "0".repeat(64),
                    sizeBytes = 0L,
                ),
                licenseNotice = "",
                licenseSummaryRes = R.string.engine_kitten_license,
                taglineRes = R.string.engine_kitten_tagline,
                speedTier = SpeedTier.FAST,
                qualityTier = QualityTier.NATURAL,
                languageCodes = listOf("en"),
            )
            throw AssertionError("expected IllegalArgumentException for zero archive size")
        } catch (_: IllegalArgumentException) {
            // pass
        }
    }

    // -- A3 spec-column data ------------------------------------------------

    @Test
    fun kokoroLanguageCodesMatchVoiceCatalog() {
        // The languages the Kokoro card advertises (and its info dialog lists)
        // must be exactly the distinct locales its voice catalog exposes — so
        // "9 languages" can never drift from what the engine actually speaks.
        // American + British English count separately, which is why it's 9.
        val fromVoices = KokoroDirectVoiceCatalog.voices
            .map { it.languageCode }
            .toSet()
        val fromDescriptor = EngineCatalog.byName("kokoro-direct-v1_0")!!.languageCodes
        assertEquals(
            "Kokoro descriptor languageCodes must match the voice catalog's distinct locales",
            fromVoices,
            fromDescriptor.toSet(),
        )
        assertEquals(
            "no duplicate language codes on the Kokoro descriptor",
            fromDescriptor.size,
            fromDescriptor.toSet().size,
        )
        assertEquals("Kokoro should advertise 9 languages", 9, fromDescriptor.size)
    }

    @Test
    fun speedTiersMatchTheDesign() {
        // The A3 spec-column card leads with speed; pin the tier per engine so
        // a stray edit can't silently demote the fastest default.
        assertEquals(SpeedTier.FASTEST, EngineCatalog.byName("kitten-direct-v0_8")!!.speedTier)
        assertEquals(SpeedTier.FAST, EngineCatalog.byName("kokoro-direct-v1_0")!!.speedTier)
        assertEquals(SpeedTier.HEAVY, EngineCatalog.byName("pocket-tts-en-v2026_04")!!.speedTier)
    }

    @Test
    fun singleLanguageEnginesHaveExactlyOneCode() {
        // Kitten + Pocket are English-only; their card shows the language name,
        // not a count, so they must carry exactly one code.
        for (name in listOf("kitten-direct-v0_8", "pocket-tts-en-v2026_04")) {
            assertEquals(
                "$name should advertise exactly one language",
                1,
                EngineCatalog.byName(name)!!.languageCodes.size,
            )
        }
    }
}
