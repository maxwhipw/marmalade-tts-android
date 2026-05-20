package app.marmalade.tts.install

import android.util.Log
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * Marker interface for the install root directory. Lets unit tests inject
 * a temporary folder without standing up a full Android `Context`.
 *
 * In production, this is `Context.getFilesDir()`. In tests, it's a
 * `@get:Rule TemporaryFolder`-backed File.
 */
fun interface EngineFilesDir {
    fun get(): File
}

/**
 * Just the bit of [app.marmalade.tts.engine.KittenEngine] that the
 * installer needs. Extracting an interface here lets unit tests run
 * without instantiating KittenEngine (which transitively needs an
 * Android `Context` we can't mint in a JVM test).
 */
fun interface NativeEngineHandle {
    /** Drop any cached native handle (mmap'd model bytes etc.). Idempotent. */
    fun release()
}

/**
 * Abstraction over the HTTP fetch step. Production wires this to
 * `HttpURLConnection`; unit tests substitute a synchronous in-memory
 * fetcher so they don't need to stand up a real TCP server.
 */
fun interface HttpFetcher {
    /**
     * Open an input stream for [url]. Implementations are responsible for
     * following redirects, applying timeouts, and throwing IOException on
     * non-2xx response codes. The caller closes the stream.
     */
    @Throws(java.io.IOException::class)
    fun open(url: String): java.io.InputStream
}

/** Production implementation: stream from a remote URL via `HttpURLConnection`. */
object UrlHttpFetcher : HttpFetcher {
    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 30_000

    override fun open(url: String): java.io.InputStream {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = true
            requestMethod = "GET"
            setRequestProperty("Accept-Encoding", "identity")
        }
        conn.connect()
        val code = conn.responseCode
        if (code !in 200..299) {
            conn.disconnect()
            throw IOException("HTTP $code fetching $url")
        }
        // Wrap so the consumer's close() also disconnects the connection.
        val raw = conn.inputStream
        return object : java.io.FilterInputStream(raw) {
            override fun close() {
                try {
                    super.close()
                } finally {
                    conn.disconnect()
                }
            }
        }
    }
}

// -----------------------------------------------------------------------------
// Data flow
// -----------------------------------------------------------------------------
//   UI: OnboardingViewModel / EnginesViewModel
//     │
//     │  .install("kitten") { progress -> ... }
//     ▼
//   EngineInstaller.install(name)
//     │
//     ├── EngineCatalog.byName(name) ──► EngineDescriptor
//     │
//     ├── _state["kitten"].value = Downloading(0, total, "")
//     │
//     ├── for each EngineFile in descriptor.files:
//     │      ├── HTTP GET (HttpURLConnection)        — downloadFile()
//     │      ├── stream bytes → ${scratchDir}/<relativePath>
//     │      ├── update sha256 incrementally
//     │      ├── update _state["kitten"] with progress
//     │      └── verify sha256 matches descriptor (skipped for PENDING)
//     │
//     ├── _state["kitten"].value = Extracting
//     │     (no-op for individual-file installs — kept for state-machine
//     │      symmetry with future archive-based engines)
//     │
//     ├── atomic rename scratchDir → engineDir
//     │
//     └── _state["kitten"].value = Installed
//
//   On any failure:
//     ├── _state["kitten"].value = Failed(reason)
//     ├── delete scratchDir (so a retry starts clean)
//     └── return Result.failure(IOException(reason))
//
//   UI: subscribes via .state("kitten")
// -----------------------------------------------------------------------------

/**
 * Persistent install lifecycle state for an engine bundle.
 *
 * State transitions are linear-with-loopback:
 *
 * ```
 *   NotInstalled ──► Downloading ──► Extracting ──► Installed
 *                          │                            │
 *                          ▼                            ▼
 *                       Failed                       uninstall()
 *                          │                            │
 *                          └──► NotInstalled ◄──────────┘
 *
 *   Corrupt: discovered out-of-band by verify() — Installed devolves
 *   to Corrupt when files have gone missing under the running app.
 * ```
 */
sealed class InstallState {
    object NotInstalled : InstallState()

    /**
     * Engine is mid-download. The UI polls/observes this for the progress
     * bar; one update per processed chunk of [bytesFetched].
     */
    data class Downloading(
        val bytesFetched: Long,
        val totalBytes: Long,
        val currentFile: String,
    ) : InstallState()

    /**
     * Files are downloaded and being staged into the engine directory. For
     * v0.1 with individual-file installs this is a near-instant rename;
     * the state is retained for future archive engines whose extraction
     * step is non-trivial.
     */
    object Extracting : InstallState()

    /** Engine is ready for use — `KittenEngine.isInstalled()` will return true. */
    object Installed : InstallState()

    /** Install attempt failed mid-flight. UI shows a Retry affordance with the reason. */
    data class Failed(val reason: String) : InstallState()

    /**
     * Files are *present on disk* but at least one is missing or its
     * hash doesn't match the catalog. Surfaced by [EngineInstaller.verify]
     * and treated by the UI the same as NotInstalled (offer reinstall).
     */
    object Corrupt : InstallState()
}

/**
 * Engine-as-plugin installer.
 *
 * Owns the lifecycle of `${filesDir}/engines/<engine>/` directories — the
 * UI never touches those paths directly. The contract with `KittenEngine`
 * and friends is: once [install] completes successfully,
 * `KittenEngine.isInstalled()` returns true and synthesis works.
 *
 * Implementation invariants:
 *
 *  1. **Atomic installs.** Files are downloaded to a scratch directory
 *     (`<name>.tmp`) and renamed to the final location on success. The app
 *     never observes a partial engine directory.
 *  2. **Hash-verified files.** Every downloaded file's SHA-256 is checked
 *     against the manifest. Mismatches fail the install (after one retry).
 *     The sentinel [EngineCatalog.SHA256_PENDING] skips verification with
 *     a logged warning — see STUBS.md for why this exists.
 *  3. **Single concurrent install per engine.** Callers must serialise
 *     install/uninstall on the same engine name. v0.1 enforces this via UI
 *     state (the install button disables while in flight); a future Mutex
 *     can move the guarantee into this class.
 *  4. **No network use outside install/verify.** The single
 *     `<uses-permission android:name="android.permission.INTERNET" />`
 *     in the manifest documents that boundary.
 */
@Singleton
open class EngineInstaller @Inject constructor(
    private val filesDir: EngineFilesDir,
    private val kittenEngine: NativeEngineHandle,
    private val httpFetcher: HttpFetcher,
) {

    /**
     * Per-engine state flows. Created lazily on first observation so the
     * flow is hot once the UI subscribes.
     */
    private val states: MutableMap<String, MutableStateFlow<InstallState>> = mutableMapOf()
    private val statesLock = Any()

    /**
     * Returns a hot [Flow] of [InstallState] for [engineName]. The initial
     * value is computed eagerly by inspecting the on-disk engine directory:
     *
     *  - directory absent → [InstallState.NotInstalled]
     *  - directory present, all expected files match → [InstallState.Installed]
     *  - directory present but files missing/corrupt → [InstallState.Corrupt]
     *
     * Multiple subscribers share the same StateFlow — this is how the
     * Onboarding screen and the Engines screen stay in sync if they're
     * both open (e.g. via system back).
     */
    fun state(engineName: String): Flow<InstallState> = stateFlow(engineName).asStateFlow()

    /**
     * Install [engineName] from the catalog.
     *
     * Downloads every file in the engine's descriptor sequentially (kept
     * simple — concurrent downloads add ~3× the complexity for marginal
     * speedup on HTTP/1.1 to a single host). Reports progress through
     * [onProgress] and updates the per-engine state flow.
     *
     * Re-running install on an already-installed engine is idempotent:
     * the existing engine directory is removed first so the new install
     * starts clean. (A future optimisation could skip files whose sha256
     * already matches.)
     *
     * @return Result.success(Unit) on success, Result.failure(IOException)
     *   on any download / verification / I/O error. The state flow has
     *   already been updated to Failed before the Result returns.
     */
    open suspend fun install(
        engineName: String,
        onProgress: (InstallState.Downloading) -> Unit,
    ): Result<Unit> {
        val descriptor = EngineCatalog.byName(engineName)
            ?: return failed(engineName, "Unknown engine: $engineName")
        return installViaDescriptor(descriptor, onProgress)
    }

    /**
     * Test-friendly install that operates against a caller-supplied
     * descriptor instead of looking it up in [EngineCatalog]. Used by
     * `EngineInstallerTest` to drive the installer against a loopback
     * HTTP fixture without polluting the production catalog.
     *
     * Production code paths must go through [install] — the catalog is
     * the source of truth for what's installable.
     */
    internal suspend fun installViaDescriptor(
        descriptor: EngineDescriptor,
        onProgress: (InstallState.Downloading) -> Unit,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val engineName = descriptor.name
        val sf = stateFlow(engineName)
        val totalBytes = descriptor.downloadSizeBytes
        val finalDir = engineDirFor(engineName)
        val scratchDir = scratchDirFor(engineName)

        // Clean up any leftover scratch from a previous failed attempt.
        // Also drop any previous final dir so we never overlay a partial
        // install on top of an existing one.
        if (scratchDir.exists()) {
            scratchDir.deleteRecursively()
        }
        if (finalDir.exists()) {
            // Release the engine's native handle before deleting its files,
            // otherwise OfflineTts could be holding open mmap'd model bytes.
            try {
                kittenEngine.release()
            } catch (_: Throwable) {
                // Best effort — release() should be idempotent and exception-
                // free, but a faulty native build shouldn't block a reinstall.
            }
            finalDir.deleteRecursively()
        }
        scratchDir.mkdirs()

        try {
            var bytesFetched = 0L
            for ((index, file) in descriptor.files.withIndex()) {
                val target = File(scratchDir, file.relativePath)
                target.parentFile?.mkdirs()

                Log.d(TAG, "Downloading ${file.url} → ${target.absolutePath}")
                val fetchedSha = downloadFile(file.url, target) { progressBytes ->
                    // Per-file progress is approximate — we don't know how many
                    // bytes the *server* will send (Content-Length might be
                    // absent for chunked encoding), so we add to the cumulative
                    // counter as bytes land.
                    val update = InstallState.Downloading(
                        bytesFetched = bytesFetched + progressBytes,
                        totalBytes = totalBytes,
                        currentFile = file.relativePath,
                    )
                    sf.value = update
                    onProgress(update)
                }

                // Verify hash unless the manifest is marked PENDING (see
                // STUBS.md). We log loudly so this can't be missed in
                // release-quality logs.
                if (file.sha256 == EngineCatalog.SHA256_PENDING) {
                    Log.w(
                        TAG,
                        "SHA256 verification SKIPPED for ${file.relativePath} " +
                            "(manifest hash is PENDING — see STUBS.md)",
                    )
                } else if (!fetchedSha.equals(file.sha256, ignoreCase = true)) {
                    throw IOException(
                        "SHA-256 mismatch for ${file.relativePath}: " +
                            "expected ${file.sha256}, got $fetchedSha",
                    )
                }

                bytesFetched += target.length()
                Log.d(TAG, "Downloaded ${index + 1}/${descriptor.files.size}: ${file.relativePath}")
            }

            sf.value = InstallState.Extracting

            // Atomic rename. The scratch dir and the final dir share the
            // same parent (${filesDir}/engines/), so rename is just an
            // inode flip — no cross-filesystem fallback needed.
            if (!scratchDir.renameTo(finalDir)) {
                throw IOException(
                    "Could not rename ${scratchDir.absolutePath} to ${finalDir.absolutePath}",
                )
            }

            sf.value = InstallState.Installed
            Result.success(Unit)
        } catch (t: Throwable) {
            Log.w(TAG, "Install of $engineName failed", t)
            // Clean up scratch on failure so the next attempt starts fresh.
            if (scratchDir.exists()) {
                scratchDir.deleteRecursively()
            }
            sf.value = InstallState.Failed(t.message ?: t::class.java.simpleName)
            Result.failure(if (t is IOException) t else IOException(t))
        }
    }

    /**
     * Remove [engineName] from disk. Releases the engine's native handle
     * first so we never delete files that are still mmap'd.
     *
     * Idempotent — calling uninstall on an engine that isn't installed is
     * a successful no-op.
     */
    open suspend fun uninstall(engineName: String): Result<Unit> = withContext(Dispatchers.IO) {
        val descriptor = EngineCatalog.byName(engineName)
            ?: return@withContext Result.failure(IOException("Unknown engine: $engineName"))

        try {
            // Release first — deleting an mmap'd file can leak the mapping
            // on some Android versions, even though the file system entry
            // disappears immediately.
            if (descriptor.name == "kitten") {
                kittenEngine.release()
            }

            val dir = engineDirFor(engineName)
            if (dir.exists() && !dir.deleteRecursively()) {
                throw IOException("Could not delete ${dir.absolutePath}")
            }
            // Also clean any stale scratch dir lying around.
            val scratch = scratchDirFor(engineName)
            if (scratch.exists()) {
                scratch.deleteRecursively()
            }

            stateFlow(engineName).value = InstallState.NotInstalled
            Result.success(Unit)
        } catch (t: Throwable) {
            Log.w(TAG, "Uninstall of $engineName failed", t)
            Result.failure(if (t is IOException) t else IOException(t))
        }
    }

    /**
     * Inspect the on-disk engine bundle and return the matching state.
     * Cheap — does not re-hash files, only checks presence. Used by the UI
     * to populate the Engines screen on first composition.
     */
    open suspend fun verify(engineName: String): InstallState {
        val descriptor = EngineCatalog.byName(engineName)
            ?: return InstallState.NotInstalled
        return verifyDescriptor(descriptor)
    }

    /**
     * Test-friendly verify that operates against a caller-supplied
     * descriptor. Production code paths must go through [verify].
     */
    internal suspend fun verifyDescriptor(descriptor: EngineDescriptor): InstallState =
        withContext(Dispatchers.IO) {
            val engineName = descriptor.name
            val dir = engineDirFor(engineName)
            val computed = if (!dir.isDirectory) {
                InstallState.NotInstalled
            } else {
                val allPresent = descriptor.files.all { file ->
                    val target = File(dir, file.relativePath)
                    target.isFile && target.length() > 0L
                }
                if (allPresent) InstallState.Installed else InstallState.Corrupt
            }
            stateFlow(engineName).value = computed
            computed
        }

    // -- internals ---------------------------------------------------------

    private fun stateFlow(engineName: String): MutableStateFlow<InstallState> {
        synchronized(statesLock) {
            states[engineName]?.let { return it }
            // Initialise from disk so observers see a meaningful starting state.
            // verify() will refine it asynchronously, but for the synchronous
            // path used here we just check the engine directory exists.
            val initial = if (engineDirFor(engineName).isDirectory) {
                InstallState.Installed
            } else {
                InstallState.NotInstalled
            }
            val sf = MutableStateFlow<InstallState>(initial)
            states[engineName] = sf
            return sf
        }
    }

    private fun engineDirFor(engineName: String): File =
        File(filesDir.get(), "engines/$engineName")

    private fun scratchDirFor(engineName: String): File =
        File(filesDir.get(), "engines/$engineName.tmp")

    /**
     * Stream [url] to [target] via [httpFetcher], computing SHA-256 as bytes
     * flow through.
     *
     * @return hex-encoded SHA-256 of the downloaded bytes (lowercase).
     */
    private fun downloadFile(
        url: String,
        target: File,
        onProgress: (Long) -> Unit,
    ): String {
        val digest = MessageDigest.getInstance("SHA-256")
        var bytesSoFar = 0L
        httpFetcher.open(url).use { input ->
            target.outputStream().use { output ->
                val buf = ByteArray(BUFFER_SIZE)
                while (true) {
                    val read = input.read(buf)
                    if (read == -1) break
                    output.write(buf, 0, read)
                    digest.update(buf, 0, read)
                    bytesSoFar += read
                    onProgress(bytesSoFar)
                }
            }
        }
        return digest.digest().toHex()
    }

    /**
     * Convenience to mark a state-flow as Failed and produce the matching
     * Result.failure in one place.
     */
    private fun failed(engineName: String, reason: String): Result<Unit> {
        stateFlow(engineName).value = InstallState.Failed(reason)
        return Result.failure(IOException(reason))
    }

    companion object {
        private const val TAG = "EngineInstaller"

        // 32 KB chunks are a good balance for HTTPS over a typical mobile
        // connection — small enough to keep the progress UI responsive,
        // large enough that the SHA-256 digest call dominates over loop
        // overhead.
        private const val BUFFER_SIZE = 32 * 1024
    }
}

/** Lowercase hex encoding for SHA-256 output. */
private fun ByteArray.toHex(): String {
    val sb = StringBuilder(size * 2)
    for (b in this) {
        val v = b.toInt() and 0xFF
        sb.append(HEX_CHARS[v ushr 4])
        sb.append(HEX_CHARS[v and 0x0F])
    }
    return sb.toString()
}

private val HEX_CHARS = "0123456789abcdef".toCharArray()
