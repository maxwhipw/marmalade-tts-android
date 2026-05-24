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
    private lateinit var fakeEngine: FakeKittenNanoEngine
    private lateinit var fetcher: FakeHttpFetcher

    @Before
    fun setUp() {
        filesDir = tempFolder.newFolder("files")
        fakeEngine = FakeKittenNanoEngine()
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
        assertTrue(File(engineDir, "model.fp16.onnx").length() >= 1L * 1024L * 1024L)
        assertEquals("voices payload", File(engineDir, "voices.bin").readText())
        assertEquals("phoneme tokens", File(engineDir, "tokens.txt").readText())
        assertTrue(File(engineDir, "espeak-ng-data").isDirectory)
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
        assertFalse(File(filesDir, "engines/evil.archive.tmp").exists())
    }

    // -- uninstall ---------------------------------------------------------

    @Test
    fun uninstallRemovesEngineDirAndReleasesNativeHandle() = runTest {
        val engineDir = File(filesDir, "engines/kitten")
        engineDir.mkdirs()
        File(engineDir, "model.fp16.onnx").writeText("dummy")

        val result = installer.uninstall("kitten-nano-v0_8")

        assertTrue("uninstall should succeed, got $result", result.isSuccess)
        assertFalse("engine dir should be removed", engineDir.exists())
        assertTrue("kittenEngine.release() should have been called", fakeEngine.released)
    }

    @Test
    fun uninstallOnNotInstalledEngineIsNoop() = runTest {
        val result = installer.uninstall("kitten-nano-v0_8")
        assertTrue("uninstall should succeed on absent engine", result.isSuccess)
    }

    // -- verify ------------------------------------------------------------

    @Test
    fun verifyDistinguishesInstalledFromCorruptFromNotInstalled() = runTest {
        // verifyLayout is keyed to the Kitten payload shape (model.fp16.onnx
        // + voices.bin + tokens.txt + espeak-ng-data/ with > 100 entries),
        // so this test uses the kitten name + manually stages the layout.
        val descriptor = engineDescriptor(
            name = "kitten-nano-v0_8",
            url = "https://test/kitten.tar.bz2",
            archiveBytes = ByteArray(0), // unused — we only call verify
        )

        assertEquals(InstallState.NotInstalled, installer.verifyAgainst(descriptor))

        val dir = File(filesDir, "engines/${descriptor.name}")
        dir.mkdirs()
        assertEquals(
            "empty dir should be Corrupt",
            InstallState.Corrupt,
            installer.verifyAgainst(descriptor),
        )

        // Build the full expected layout: oversize model, the two small
        // files, and an espeak-ng-data/ dir with > 100 entries.
        File(dir, "model.fp16.onnx").writeBytes(ByteArray(2 * 1024 * 1024) { 0x42 })
        File(dir, "voices.bin").writeText("voices")
        File(dir, "tokens.txt").writeText("tokens")
        val dataDir = File(dir, "espeak-ng-data").apply { mkdirs() }
        for (i in 0 until 120) {
            File(dataDir, "entry_$i").writeText("$i")
        }
        assertEquals(InstallState.Installed, installer.verifyAgainst(descriptor))
    }

    @Test
    fun verifyReturnsCorruptIfModelTooSmall() = runTest {
        val descriptor = engineDescriptor(
            name = "kitten-nano-v0_8",
            url = "https://test/kitten.tar.bz2",
            archiveBytes = ByteArray(0),
        )

        val dir = File(filesDir, "engines/${descriptor.name}").apply { mkdirs() }
        File(dir, "model.fp16.onnx").writeText("too small") // < 1 MB
        File(dir, "voices.bin").writeText("voices")
        File(dir, "tokens.txt").writeText("tokens")
        val dataDir = File(dir, "espeak-ng-data").apply { mkdirs() }
        for (i in 0 until 120) {
            File(dataDir, "entry_$i").writeText("$i")
        }

        assertEquals(InstallState.Corrupt, installer.verifyAgainst(descriptor))
    }

    // -- idempotent re-install ---------------------------------------------

    @Test
    fun reinstallReplacesPreviousFilesAtomically() = runTest {
        // First install.
        val v1Layout = KITTEN_LAYOUT.toMutableMap().apply {
            this["voices.bin"] = "v1".toByteArray()
        }
        val v1Descriptor = stageBundle(v1Layout)
        val first = installer.install(v1Descriptor) {}
        assertTrue("first install should succeed, got $first", first.isSuccess)
        assertEquals(
            "v1",
            File(filesDir, "engines/${v1Descriptor.name}/voices.bin").readText(),
        )

        // Second install with new content at the same URL.
        val v2Layout = KITTEN_LAYOUT.toMutableMap().apply {
            this["voices.bin"] = "v2 fresh".toByteArray()
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
            File(filesDir, "engines/${v1Descriptor.name}/voices.bin").readText(),
        )
        // Native handle was released before the reinstall touched the
        // existing engine dir.
        assertTrue(
            "reinstall should release the native handle before deleting model files",
            fakeEngine.released,
        )
    }

    // -- fixture machinery -------------------------------------------------

    /**
     * Register a bundle's bytes with the fake fetcher and return a matching
     * descriptor. Uses the standard Kitten archive-root convention.
     */
    private fun stageBundle(files: Map<String, ByteArray>): EngineDescriptor {
        val archive = buildArchive(files, archiveRootName = ARCHIVE_ROOT)
        val descriptor = engineDescriptor(
            name = "kitten-test",
            url = "https://test/kitten-test/bundle.tar.bz2",
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
        // Wrapper directory name used by the production Kitten archive.
        // The installer strips this prefix during extraction; the test
        // archives mirror the same layout.
        private const val ARCHIVE_ROOT = "kitten-nano-en-v0_1-fp16/"

        /**
         * Synthetic Kitten payload large enough to pass the post-install
         * verification (model > 1 MB, > 100 espeak-ng-data entries).
         */
        private val KITTEN_LAYOUT: Map<String, ByteArray> = buildMap {
            // 1.5 MB so it clears the 1 MB MIN_MODEL_BYTES floor with room
            // to spare. Bytes don't matter — the installer only checks the
            // archive's sha, not the model's contents.
            put("model.fp16.onnx", ByteArray(1_500_000) { (it and 0xFF).toByte() })
            put("voices.bin", "voices payload".toByteArray())
            put("tokens.txt", "phoneme tokens".toByteArray())
            // > 100 entries under espeak-ng-data/ to clear MIN_ESPEAK_ENTRIES.
            for (i in 0 until 110) {
                put("espeak-ng-data/entry_$i", "dict-$i".toByteArray())
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
internal class FakeKittenNanoEngine : NativeEngineHandle {
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
 * it — mirrors the upstream Sherpa-ONNX tarball's `kitten-nano-en-v0_1-fp16/`
 * wrapper directory.
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
