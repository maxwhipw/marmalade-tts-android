package app.marmalade.tts.install

import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import kotlinx.coroutines.test.runTest
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
 * classpath). The trade-off is that we don't exercise the production
 * `HttpURLConnection` implementation — that's fine. The non-trivial
 * logic in `EngineInstaller` is around state, file system, and SHA-256
 * verification, which the fake exercises end-to-end.
 *
 * The installer is tested against synthetic descriptors via the
 * `TestInstaller` wrapper (which exposes the `installViaDescriptor` and
 * `verifyDescriptor` internal helpers as public methods). The
 * production `install(name)` path lookups against `EngineCatalog` are
 * covered by [EngineCatalogTest].
 */
class EngineInstallerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var filesDir: File
    private lateinit var installer: TestInstaller
    private lateinit var fakeEngine: FakeKittenEngine
    private lateinit var fetcher: FakeHttpFetcher

    @Before
    fun setUp() {
        filesDir = tempFolder.newFolder("files")
        fakeEngine = FakeKittenEngine()
        fetcher = FakeHttpFetcher()
        installer = TestInstaller(
            filesDir = EngineFilesDir { filesDir },
            engine = fakeEngine,
            fetcher = fetcher,
        )
    }

    // -- happy path --------------------------------------------------------

    @Test
    fun installDownloadsFilesVerifiesHashAndAtomicallyRenames() = runTest {
        val descriptor = stageBundle(
            files = mapOf(
                "model.fp16.onnx" to "hello model".toByteArray(),
                "tokens.txt" to "abc def".toByteArray(),
                "espeak-ng-data/phontab" to "shared phonemizer table".toByteArray(),
            ),
        )

        val progressEvents = mutableListOf<InstallState.Downloading>()
        val result = installer.install(descriptor) { progress ->
            progressEvents += progress
        }

        assertTrue("install should succeed, got $result", result.isSuccess)
        val engineDir = File(filesDir, "engines/${descriptor.name}")
        assertTrue("engine dir should exist after install", engineDir.isDirectory)
        assertEquals("hello model", File(engineDir, "model.fp16.onnx").readText())
        assertEquals(
            "shared phonemizer table",
            File(engineDir, "espeak-ng-data/phontab").readText(),
        )
        // Scratch dir cleaned up.
        assertFalse(File(filesDir, "engines/${descriptor.name}.tmp").exists())
        // Progress reported at least once per file.
        assertTrue(
            "should have reported progress, got ${progressEvents.size} events",
            progressEvents.size >= descriptor.files.size,
        )
    }

    // -- sha mismatch ------------------------------------------------------

    @Test
    fun shaMismatchFailsAndCleansScratch() = runTest {
        val descriptor = EngineDescriptor(
            name = "ferret",
            displayName = "Ferret",
            description = "test",
            downloadSizeBytes = 11L,
            installedSizeBytes = 11L,
            isRecommended = false,
            files = listOf(
                EngineFile(
                    relativePath = "model.bin",
                    url = "https://test/ferret/model.bin",
                    sha256 = "deadbeef".repeat(8), // wrong hash
                    sizeBytes = 11L,
                ),
            ),
            licenseNotice = "n/a",
            licenseSummary = "n/a",
        )
        fetcher.payloads["https://test/ferret/model.bin"] = "hello model".toByteArray()

        val result = installer.install(descriptor) {}

        assertTrue("expected failure for sha mismatch", result.isFailure)
        val msg = result.exceptionOrNull()?.message ?: ""
        assertTrue("expected SHA mismatch reason in '$msg'", msg.contains("SHA-256 mismatch"))
        assertFalse(File(filesDir, "engines/ferret.tmp").exists())
        assertFalse(File(filesDir, "engines/ferret").exists())
    }

    // -- pending hash is skipped (with warning) ---------------------------

    @Test
    fun pendingShaIsAcceptedAsDeferredVerification() = runTest {
        val descriptor = EngineDescriptor(
            name = "mongoose",
            displayName = "Mongoose",
            description = "test",
            downloadSizeBytes = 4L,
            installedSizeBytes = 4L,
            isRecommended = false,
            files = listOf(
                EngineFile(
                    relativePath = "file.txt",
                    url = "https://test/mongoose/file.txt",
                    sha256 = EngineCatalog.SHA256_PENDING,
                    sizeBytes = 4L,
                ),
            ),
            licenseNotice = "n/a",
            licenseSummary = "n/a",
        )
        fetcher.payloads["https://test/mongoose/file.txt"] = "test".toByteArray()

        val result = installer.install(descriptor) {}

        assertTrue("pending sha should not fail install: $result", result.isSuccess)
    }

    // -- network failure mid-stream ---------------------------------------

    @Test
    fun httpErrorMidStreamCleansUpScratch() = runTest {
        val descriptor = EngineDescriptor(
            name = "weasel",
            displayName = "Weasel",
            description = "test",
            downloadSizeBytes = 10L,
            installedSizeBytes = 10L,
            isRecommended = false,
            files = listOf(
                EngineFile(
                    relativePath = "first.bin",
                    url = "https://test/weasel/first.bin",
                    sha256 = EngineCatalog.SHA256_PENDING,
                    sizeBytes = 5L,
                ),
                EngineFile(
                    relativePath = "second.bin",
                    url = "https://test/weasel/second.bin",
                    sha256 = EngineCatalog.SHA256_PENDING,
                    sizeBytes = 5L,
                ),
            ),
            licenseNotice = "n/a",
            licenseSummary = "n/a",
        )
        fetcher.payloads["https://test/weasel/first.bin"] = "hello".toByteArray()
        // second.bin not registered → fetcher returns 404-style IOException

        val result = installer.install(descriptor) {}

        assertTrue("expected failure, got $result", result.isFailure)
        assertFalse(File(filesDir, "engines/weasel.tmp").exists())
        assertFalse(File(filesDir, "engines/weasel").exists())
    }

    // -- uninstall ---------------------------------------------------------

    @Test
    fun uninstallRemovesEngineDirAndReleasesNativeHandle() = runTest {
        val engineDir = File(filesDir, "engines/kitten")
        engineDir.mkdirs()
        File(engineDir, "model.fp16.onnx").writeText("dummy")

        val result = installer.uninstall("kitten")

        assertTrue("uninstall should succeed, got $result", result.isSuccess)
        assertFalse("engine dir should be removed", engineDir.exists())
        assertTrue("kittenEngine.release() should have been called", fakeEngine.released)
    }

    @Test
    fun uninstallOnNotInstalledEngineIsNoop() = runTest {
        val result = installer.uninstall("kitten")
        assertTrue("uninstall should succeed on absent engine", result.isSuccess)
    }

    // -- verify ------------------------------------------------------------

    @Test
    fun verifyDistinguishesInstalledFromCorruptFromNotInstalled() = runTest {
        val descriptor = EngineDescriptor(
            name = "stoat",
            displayName = "Stoat",
            description = "test",
            downloadSizeBytes = 11L,
            installedSizeBytes = 11L,
            isRecommended = false,
            files = listOf(
                EngineFile(
                    relativePath = "a.bin",
                    url = "https://example.invalid/a.bin",
                    sha256 = EngineCatalog.SHA256_PENDING,
                    sizeBytes = 5L,
                ),
            ),
            licenseNotice = "n/a",
            licenseSummary = "n/a",
        )

        assertEquals(InstallState.NotInstalled, installer.verifyAgainst(descriptor))

        val dir = File(filesDir, "engines/${descriptor.name}")
        dir.mkdirs()
        assertEquals(InstallState.Corrupt, installer.verifyAgainst(descriptor))

        File(dir, "a.bin").writeText("hello")
        assertEquals(InstallState.Installed, installer.verifyAgainst(descriptor))
    }

    // -- idempotent re-install ---------------------------------------------

    @Test
    fun reinstallReplacesPreviousFilesAtomically() = runTest {
        val descriptor = stageBundle(
            files = mapOf("model.fp16.onnx" to "v1".toByteArray()),
        )

        installer.install(descriptor) {}
        assertEquals(
            "v1",
            File(filesDir, "engines/${descriptor.name}/model.fp16.onnx").readText(),
        )

        // Stage v2 with the same URL — swap the bytes + rebuild descriptor
        // with the matching sha256 so the verification passes.
        val v2 = "v2 contents".toByteArray()
        fetcher.payloads[descriptor.files.single().url] = v2
        val v2Descriptor = descriptor.copy(
            files = listOf(descriptor.files.single().copy(
                sha256 = sha256Hex(v2),
                sizeBytes = v2.size.toLong(),
            )),
            downloadSizeBytes = v2.size.toLong(),
            installedSizeBytes = v2.size.toLong(),
        )
        val secondResult = installer.install(v2Descriptor) {}
        assertTrue("reinstall should succeed, got $secondResult", secondResult.isSuccess)

        assertEquals(
            "v2 contents",
            File(filesDir, "engines/${descriptor.name}/model.fp16.onnx").readText(),
        )
    }

    // -- fixture machinery -------------------------------------------------

    /** Register a bundle's bytes with the fake fetcher and return a matching descriptor. */
    private fun stageBundle(files: Map<String, ByteArray>): EngineDescriptor {
        val name = "kitten-test"
        val entries = files.entries.map { (path, bytes) ->
            val url = "https://test/$name/$path"
            fetcher.payloads[url] = bytes
            EngineFile(
                relativePath = path,
                url = url,
                sha256 = sha256Hex(bytes),
                sizeBytes = bytes.size.toLong(),
            )
        }
        return EngineDescriptor(
            name = name,
            displayName = "Kitten (test)",
            description = "test",
            downloadSizeBytes = files.values.sumOf { it.size.toLong() },
            installedSizeBytes = files.values.sumOf { it.size.toLong() },
            isRecommended = true,
            files = entries,
            licenseNotice = "n/a",
            licenseSummary = "n/a",
        )
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
internal class FakeKittenEngine : NativeEngineHandle {
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

private fun sha256Hex(bytes: ByteArray): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
    val sb = StringBuilder(digest.size * 2)
    for (b in digest) {
        val v = b.toInt() and 0xFF
        sb.append("0123456789abcdef"[v ushr 4])
        sb.append("0123456789abcdef"[v and 0x0F])
    }
    return sb.toString()
}
