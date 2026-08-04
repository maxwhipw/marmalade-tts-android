package app.marmalade.tts.install

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import kotlinx.coroutines.test.runTest
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * JVM unit tests for [EngineInstaller].
 *
 * Drives the installer against an in-memory [FakeHttpFetcher] (no real
 * sockets — `com.sun.net.httpserver` isn't on the Android unit test
 * classpath). The fetcher serves synthetic tar.bz2 archives built with
 * commons-compress, which is also the library the installer uses for
 * extraction, so any compat mismatch surfaces here.
 *
 * The installer is exercised via the `TestInstaller` wrapper (which
 * exposes the `installViaDescriptor` and `verifyDescriptor` internal
 * helpers as public methods). The production `install(name)` path
 * lookups against `EngineCatalog` are covered by [EngineCatalogTest].
 */
class EngineInstallerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var filesDir: File
    private lateinit var installer: TestInstaller
    private lateinit var fakeEngine: FakeNativeEngineHandle
    private lateinit var fetcher: FakeHttpFetcher

    @Before
    fun setUp() {
        filesDir = tempFolder.newFolder("files")
        fakeEngine = FakeNativeEngineHandle()
        fetcher = FakeHttpFetcher()
        installer = TestInstaller(
            filesDir = EngineFilesDir { filesDir },
            engine = fakeEngine,
            fetcher = fetcher,
        )
    }

    // -- happy path --------------------------------------------------------

    @Test
    fun installDownloadsArchiveVerifiesShaExtractsAndAtomicallyRenames() = runTest {
        val descriptor = stageBundle(KITTEN_LAYOUT)

        val progressEvents = mutableListOf<InstallState.Downloading>()
        val result = installer.install(descriptor) { progress ->
            progressEvents += progress
        }

        assertTrue("install should succeed, got $result", result.isSuccess)
        val engineDir = File(filesDir, "engines/${descriptor.name}")
        assertTrue("engine dir should exist after install", engineDir.isDirectory)
        // Spot-check a few extracted files.
        assertTrue(File(engineDir, "kitten.onnx").length() >= 1L * 1024L * 1024L)
        assertEquals("voice-bella", File(engineDir, "voices/bella.bin").readText())
        assertTrue(File(engineDir, "phonemizer/arm64-v8a/libttsespeak.so").isFile)
        assertTrue(File(engineDir, "phonemizer/espeak-ng-data").isDirectory)
        // Scratch dir + archive scratch cleaned up.
        assertFalse(File(filesDir, "engines/${descriptor.name}.tmp").exists())
        assertFalse(File(filesDir, "engines/${descriptor.name}.archive.tmp").exists())
        // Progress reported at least once.
        assertTrue(
            "should have reported progress, got ${progressEvents.size} events",
            progressEvents.isNotEmpty(),
        )
        // Final progress emission should equal totalBytes (so the UI bar
        // hits 100% before flipping to Extracting).
        val last = progressEvents.last()
        assertEquals(last.totalBytes, last.bytesFetched)
    }

    // -- sha mismatch ------------------------------------------------------

    @Test
    fun shaMismatchFailsAndCleansScratch() = runTest {
        val archive = buildArchive(KITTEN_LAYOUT, archiveRootName = ARCHIVE_ROOT)
        val descriptor = engineDescriptor(
            name = "ferret",
            url = "https://test/ferret.tar.bz2",
            archiveBytes = archive,
            // Wrong hash — bytes are valid but the catalog claims a
            // different sha256.
            shaOverride = "deadbeef".repeat(8),
        )
        fetcher.payloads[descriptor.archive.url] = archive

        val result = installer.install(descriptor) {}

        assertTrue("expected failure for sha mismatch", result.isFailure)
        val msg = result.exceptionOrNull()?.message ?: ""
        assertTrue("expected SHA mismatch reason in '$msg'", msg.contains("SHA-256 mismatch"))
        assertFalse(File(filesDir, "engines/ferret.tmp").exists())
        assertFalse(File(filesDir, "engines/ferret").exists())
        assertFalse(File(filesDir, "engines/ferret.archive.tmp").exists())
    }

    // -- network failure mid-stream ---------------------------------------

    @Test
    fun httpErrorCleansUpScratch() = runTest {
        val archive = buildArchive(KITTEN_LAYOUT, archiveRootName = ARCHIVE_ROOT)
        val descriptor = engineDescriptor(
            name = "weasel",
            url = "https://test/weasel.tar.bz2",
            archiveBytes = archive,
        )
        // Don't register the URL with the fetcher — it'll throw IOException,
        // simulating a 404 or transport error.

        val result = installer.install(descriptor) {}

        assertTrue("expected failure, got $result", result.isFailure)
        assertFalse(File(filesDir, "engines/weasel.tmp").exists())
        assertFalse(File(filesDir, "engines/weasel").exists())
        assertFalse(File(filesDir, "engines/weasel.archive.tmp").exists())
    }

    // -- zip-slip protection ----------------------------------------------

    @Test
    fun zipSlipEntryIsRejected() = runTest {
        // Build an archive whose entry name climbs out of the engine
        // directory. Use no archive root so the malicious path is the
        // entry's full name post-strip.
        val maliciousLayout = mapOf(
            "../../../../etc/pwned" to "haha".toByteArray(),
        )
        val archive = buildArchive(maliciousLayout, archiveRootName = "")
        val descriptor = engineDescriptor(
            name = "evil",
            url = "https://test/evil.tar.bz2",
            archiveBytes = archive,
            archiveRoot = "",
        )
        fetcher.payloads[descriptor.archive.url] = archive

        val result = installer.install(descriptor) {}

        assertTrue("zip-slip should fail install, got $result", result.isFailure)
        val msg = result.exceptionOrNull()?.message ?: ""
        assertTrue(
            "expected escape-detection in '$msg'",
            msg.contains("escapes destination", ignoreCase = true),
        )
        assertFalse(File(filesDir, "engines/evil").exists())
        assertFalse(File(filesDir, "engines/evil.tmp").exists())
        // The partial archive is intentionally RETAINED on a non-SHA failure
        // so a later attempt can resume the download (only a SHA mismatch wipes
        // it — see EngineInstaller.installViaDescriptor's catch). Zip-slip
        // happens during extraction, after a clean download, so the archive
        // scratch is kept by design.
        assertTrue(File(filesDir, "engines/evil.archive.tmp").exists())
    }

    // -- uninstall ---------------------------------------------------------

    @Test
    fun uninstallRemovesEngineDirAndReleasesNativeHandle() = runTest {
        // The installer derives the on-disk dir from the engine NAME
        // (engineDirFor → engines/<name>), so the dir must match the name we
        // uninstall.
        val engineDir = File(filesDir, "engines/kitten-direct-v0_8")
        engineDir.mkdirs()
        File(engineDir, "kitten.onnx").writeText("dummy")

        val result = installer.uninstall("kitten-direct-v0_8")

        assertTrue("uninstall should succeed, got $result", result.isSuccess)
        assertFalse("engine dir should be removed", engineDir.exists())
        assertTrue("engineHandle.release() should have been called", fakeEngine.released)
    }

    @Test
    fun uninstallOnNotInstalledEngineIsNoop() = runTest {
        val result = installer.uninstall("kitten-direct-v0_8")
        assertTrue("uninstall should succeed on absent engine", result.isSuccess)
    }

    // -- verify ------------------------------------------------------------

    @Test
    fun verifyDistinguishesInstalledFromCorruptFromNotInstalled() = runTest {
        // verifyLayout dispatches on the engine name; kitten-direct-v0_8 routes
        // to verifyKittenDirectLayout (kitten.onnx + phonemizer/<abi>/libttsespeak.so
        // + phonemizer/espeak-ng-data/ with > 100 entries + voices/<name>.bin for the
        // 8 voices), so this test uses that name + manually stages the layout.
        val descriptor = engineDescriptor(
            name = "kitten-direct-v0_8",
            url = "https://test/kitten-direct.tar.bz2",
            archiveBytes = ByteArray(0), // unused — we only call verify
        )

        assertEquals(InstallState.NotInstalled, installer.verifyAgainst(descriptor))

        val dir = File(filesDir, "engines/${descriptor.name}")
        dir.mkdirs()
        // A bare install_meta-less dir bootstraps a meta then fails the layout
        // check → Corrupt.
        assertEquals(
            "empty dir should be Corrupt",
            InstallState.Corrupt,
            installer.verifyAgainst(descriptor),
        )

        // Build the full expected KittenDirect layout: oversize model, the
        // phonemizer lib + espeak-ng-data/ dir with > 100 entries, and the 8
        // per-voice bins.
        stageKittenDirectLayout(dir)
        assertEquals(InstallState.Installed, installer.verifyAgainst(descriptor))
    }

    @Test
    fun verifyReturnsCorruptIfModelTooSmall() = runTest {
        val descriptor = engineDescriptor(
            name = "kitten-direct-v0_8",
            url = "https://test/kitten-direct.tar.bz2",
            archiveBytes = ByteArray(0),
        )

        val dir = File(filesDir, "engines/${descriptor.name}").apply { mkdirs() }
        stageKittenDirectLayout(dir)
        // Overwrite the model with a sub-1-MB file — should flip to Corrupt.
        File(dir, "kitten.onnx").writeText("too small") // < 1 MB

        assertEquals(InstallState.Corrupt, installer.verifyAgainst(descriptor))
    }

    /** Write a complete KittenDirect on-disk layout into [dir]. */
    private fun stageKittenDirectLayout(dir: File) {
        File(dir, "kitten.onnx").writeBytes(ByteArray(2 * 1024 * 1024) { 0x42 })
        File(dir, "phonemizer/arm64-v8a").mkdirs()
        File(dir, "phonemizer/arm64-v8a/libttsespeak.so").writeText("elf")
        val dataDir = File(dir, "phonemizer/espeak-ng-data").apply { mkdirs() }
        // The core files English phonemization needs — verifyKittenDirectLayout
        // checks for these specifically (so the baked English-only tree passes),
        // not a raw entry count.
        for (f in listOf("phondata", "phonindex", "phontab", "intonations", "en_dict")) {
            File(dataDir, f).writeText(f)
        }
        File(dataDir, "lang").mkdirs()
        File(dataDir, "lang/en").writeText("en")
        File(dataDir, "voices").mkdirs()
        File(dataDir, "voices/f1").writeText("f1")
        val voicesDir = File(dir, "voices").apply { mkdirs() }
        for (name in listOf("bella", "jasper", "luna", "bruno", "rosie", "hugo", "kiki", "leo")) {
            File(voicesDir, "$name.bin").writeText("voice-$name")
        }
    }

    // -- idempotent re-install ---------------------------------------------

    @Test
    fun reinstallReplacesPreviousFilesAtomically() = runTest {
        // First install.
        val v1Layout = KITTEN_LAYOUT.toMutableMap().apply {
            this["voices/bella.bin"] = "v1".toByteArray()
        }
        val v1Descriptor = stageBundle(v1Layout)
        val first = installer.install(v1Descriptor) {}
        assertTrue("first install should succeed, got $first", first.isSuccess)
        assertEquals(
            "v1",
            File(filesDir, "engines/${v1Descriptor.name}/voices/bella.bin").readText(),
        )

        // Second install with new content at the same URL.
        val v2Layout = KITTEN_LAYOUT.toMutableMap().apply {
            this["voices/bella.bin"] = "v2 fresh".toByteArray()
        }
        val v2Archive = buildArchive(v2Layout, archiveRootName = ARCHIVE_ROOT)
        fetcher.payloads[v1Descriptor.archive.url] = v2Archive
        val v2Descriptor = v1Descriptor.copy(
            archive = v1Descriptor.archive.copy(
                sha256 = sha256Hex(v2Archive),
                sizeBytes = v2Archive.size.toLong(),
            ),
            downloadSizeBytes = v2Archive.size.toLong(),
        )

        val second = installer.install(v2Descriptor) {}
        assertTrue("reinstall should succeed, got $second", second.isSuccess)

        assertEquals(
            "v2 fresh",
            File(filesDir, "engines/${v1Descriptor.name}/voices/bella.bin").readText(),
        )
        // Native handle was released before the reinstall touched the
        // existing engine dir.
        assertTrue(
            "reinstall should release the native handle before deleting model files",
            fakeEngine.released,
        )
    }

    @Test
    fun leftoverFullyDownloadedArchiveInstallsWithoutRefetching() = runTest {
        // A previous attempt downloaded the whole archive, then failed after
        // the download (e.g. disk full during extraction — the installer
        // keeps the archive for resume). A resume request for
        // `bytes=<totalBytes>-` would be HTTP 416 from a real server; the
        // installer must skip the network entirely. Prove it by NOT
        // registering the URL — any fetch attempt throws. kitten-direct
        // name so post-install layout verification routes to
        // verifyKittenDirectLayout (same convention as stageBundle).
        val archive = buildArchive(KITTEN_LAYOUT, archiveRootName = ARCHIVE_ROOT)
        val descriptor = engineDescriptor(
            name = "kitten-direct-v0_8",
            url = "https://test/kitten-direct/bundle.tar.bz2",
            archiveBytes = archive,
        )
        val archiveTmp = File(filesDir, "engines/${descriptor.name}.archive.tmp")
        archiveTmp.parentFile!!.mkdirs()
        archiveTmp.writeBytes(archive)

        val result = installer.install(descriptor) {}

        assertTrue("install from leftover archive should succeed, got $result", result.isSuccess)
        assertTrue(File(filesDir, "engines/${descriptor.name}/kitten.onnx").isFile)
        assertFalse("archive scratch should be consumed", archiveTmp.exists())
    }

    @Test
    fun failedUpdateKeepsExistingEngineIntact() = runTest {
        val v1Descriptor = stageBundle(KITTEN_LAYOUT)
        val first = installer.install(v1Descriptor) {}
        assertTrue("first install should succeed, got $first", first.isSuccess)

        // Update attempt whose download fails (URL no longer registered).
        fetcher.payloads.remove(v1Descriptor.archive.url)
        val second = installer.install(v1Descriptor) {}
        assertTrue("expected update failure, got $second", second.isFailure)

        // The previously-working engine must survive the failed update.
        val engineDir = File(filesDir, "engines/${v1Descriptor.name}")
        assertTrue("engine dir should survive a failed update", engineDir.isDirectory)
        assertEquals("voice-bella", File(engineDir, "voices/bella.bin").readText())
        assertEquals(InstallState.Installed, installer.verifyAgainst(v1Descriptor))
    }

    // -- fixture machinery -------------------------------------------------

    /**
     * Register a bundle's bytes with the fake fetcher and return a matching
     * descriptor. Uses the KittenDirect engine name + archive-root convention
     * so the post-install verify routes to verifyKittenDirectLayout.
     */
    private fun stageBundle(files: Map<String, ByteArray>): EngineDescriptor {
        val archive = buildArchive(files, archiveRootName = ARCHIVE_ROOT)
        val descriptor = engineDescriptor(
            name = "kitten-direct-v0_8",
            url = "https://test/kitten-direct/bundle.tar.bz2",
            archiveBytes = archive,
        )
        fetcher.payloads[descriptor.archive.url] = archive
        return descriptor
    }

    private fun engineDescriptor(
        name: String,
        url: String,
        archiveBytes: ByteArray,
        shaOverride: String? = null,
        archiveRoot: String = ARCHIVE_ROOT,
    ): EngineDescriptor = EngineDescriptor(
        name = name,
        displayName = name,
        description = "test",
        downloadSizeBytes = archiveBytes.size.toLong().coerceAtLeast(1L),
        installedSizeBytes = (archiveBytes.size.toLong() * 2L).coerceAtLeast(2L),
        isRecommended = false,
        archive = EngineArchive(
            url = url,
            sha256 = shaOverride ?: sha256Hex(archiveBytes),
            sizeBytes = archiveBytes.size.toLong().coerceAtLeast(1L),
            archiveRoot = archiveRoot,
        ),
        licenseNotice = "n/a",
        licenseSummary = "n/a",
    )

    companion object {
        // Wrapper directory name used by the production Kitten Direct archive.
        // The installer strips this prefix during extraction; the test
        // archives mirror the same layout.
        private const val ARCHIVE_ROOT = "kitten-direct-v0_8/"

        // The 8 KittenDirect voices (lowercased displayNames) the installer's
        // verifyKittenDirectLayout requires under voices/.
        private val KITTEN_DIRECT_VOICES =
            listOf("bella", "jasper", "luna", "bruno", "rosie", "hugo", "kiki", "leo")

        /**
         * Synthetic KittenDirect payload large enough to pass the post-install
         * verification: kitten.onnx > 1 MB, an espeak phonemizer lib under a
         * per-ABI dir, > 100 espeak-ng-data entries, and the 8 per-voice bins.
         */
        private val KITTEN_LAYOUT: Map<String, ByteArray> = buildMap {
            // 1.5 MB so it clears the 1 MB MIN_MODEL_BYTES floor with room
            // to spare. Bytes don't matter — the installer only checks the
            // archive's sha, not the model's contents.
            put("kitten.onnx", ByteArray(1_500_000) { (it and 0xFF).toByte() })
            put("phonemizer/arm64-v8a/libttsespeak.so", "elf".toByteArray())
            // The core espeak files English phonemization needs — checked
            // specifically by verifyKittenDirectLayout (so the baked English-
            // only tree passes) rather than a raw entry count.
            for (f in listOf("phondata", "phonindex", "phontab", "intonations", "en_dict")) {
                put("phonemizer/espeak-ng-data/$f", f.toByteArray())
            }
            put("phonemizer/espeak-ng-data/lang/en", "en".toByteArray())
            put("phonemizer/espeak-ng-data/voices/f1", "f1".toByteArray())
            for (name in KITTEN_DIRECT_VOICES) {
                put("voices/$name.bin", "voice-$name".toByteArray())
            }
        }
    }
}

// -- shared test doubles --------------------------------------------------

/**
 * Test-only wrapper that exposes the internal `installViaDescriptor` /
 * `verifyDescriptor` helpers as public methods.
 */
internal class TestInstaller(
    filesDir: EngineFilesDir,
    engine: NativeEngineHandle,
    fetcher: HttpFetcher,
) : EngineInstaller(filesDir, engine, fetcher) {

    suspend fun install(
        descriptor: EngineDescriptor,
        onProgress: (InstallState.Downloading) -> Unit,
    ): Result<Unit> = installViaDescriptor(descriptor, onProgress)

    suspend fun verifyAgainst(descriptor: EngineDescriptor): InstallState =
        verifyDescriptor(descriptor)
}

/** Native-handle double that just records whether `release()` was called. */
internal class FakeNativeEngineHandle : NativeEngineHandle {
    var released: Boolean = false
        private set

    override fun release() {
        released = true
    }
}

/**
 * In-memory HTTP fetcher. Tests register bytes for a URL; unregistered
 * URLs throw `IOException` (mirroring the 404 path in the real fetcher).
 */
internal class FakeHttpFetcher : HttpFetcher {
    val payloads: MutableMap<String, ByteArray> = mutableMapOf()

    override fun open(url: String): InputStream {
        val payload = payloads[url]
            ?: throw IOException("HTTP 404 fetching $url")
        return ByteArrayInputStream(payload)
    }
}

/**
 * Build a tar.bz2 archive in-memory from a {entry-name -> bytes} map.
 *
 * If [archiveRootName] is non-empty, every entry name is prefixed with
 * it — mirrors the production bundle's `kitten-direct-v0_8/` wrapper
 * directory.
 */
internal fun buildArchive(
    files: Map<String, ByteArray>,
    archiveRootName: String,
): ByteArray {
    val out = ByteArrayOutputStream()
    BZip2CompressorOutputStream(out).use { bzOut ->
        TarArchiveOutputStream(bzOut).use { tarOut ->
            tarOut.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
            for ((name, bytes) in files) {
                val entryName = if (archiveRootName.isNotEmpty()) {
                    archiveRootName + name
                } else {
                    name
                }
                val entry = TarArchiveEntry(entryName)
                entry.size = bytes.size.toLong()
                tarOut.putArchiveEntry(entry)
                tarOut.write(bytes)
                tarOut.closeArchiveEntry()
            }
            tarOut.finish()
        }
    }
    return out.toByteArray()
}

internal fun sha256Hex(bytes: ByteArray): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
    val sb = StringBuilder(digest.size * 2)
    for (b in digest) {
        val v = b.toInt() and 0xFF
        sb.append("0123456789abcdef"[v ushr 4])
        sb.append("0123456789abcdef"[v and 0x0F])
    }
    return sb.toString()
}
