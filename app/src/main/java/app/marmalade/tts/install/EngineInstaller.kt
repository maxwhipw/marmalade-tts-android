package app.marmalade.tts.install

import android.util.Log
import java.io.BufferedInputStream
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
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream

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
//     ├── EngineCatalog.byName(name) ──► EngineDescriptor (carries EngineArchive)
//     │
//     ├── _state["kitten"].value = Downloading(0, archive.sizeBytes, "archive")
//     │
//     ├── Stream archive bytes:
//     │      ├── HTTP GET via HttpFetcher → ${engineDir}.archive.tmp
//     │      ├── update sha256 incrementally
//     │      ├── emit Downloading progress every ~1% / ~256 KB
//     │      └── reject on sha256 mismatch with archive.sha256
//     │
//     ├── _state["kitten"].value = Extracting
//     │
//     ├── Open archive.tmp → BZip2CompressorInputStream → TarArchiveInputStream:
//     │      ├── for each entry:
//     │      │     ├── strip archive.archiveRoot prefix
//     │      │     ├── canonical-path check (zip-slip protection)
//     │      │     ├── skip directories
//     │      │     └── stream bytes into ${scratchDir}/<relPath>
//     │      └── delete archive.tmp
//     │
//     ├── atomic rename scratchDir → engineDir
//     │
//     ├── verifyDescriptor() — confirms required files are present + non-zero
//     │     (does NOT re-hash; the archive sha256 already proved bytes are correct)
//     │
//     └── _state["kitten"].value = Installed
//
//   On any failure:
//     ├── _state["kitten"].value = Failed(reason)
//     ├── delete archive.tmp + scratchDir (so a retry starts clean)
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
     * bar. One emission per ~1% of the archive or per ~256 KB transferred,
     * whichever is coarser, so the Flow isn't flooded.
     */
    data class Downloading(
        val bytesFetched: Long,
        val totalBytes: Long,
        val currentFile: String,
    ) : InstallState()

    /**
     * Archive is downloaded + sha256-verified and is now being decompressed
     * + untarred into the engine directory. For Kitten's 27 MB tar.bz2 this
     * runs in ~1-3 seconds; Kokoro's multi-lang 125 MB bundle is closer to
     * 10-15 seconds and used to sit on an indeterminate spinner that
     * looked stuck — v0.1.20 added byte-level extraction progress.
     *
     * [bytesExtracted] is the cumulative size of files written to disk
     * during the unpack so far; [totalBytes] is the *estimated* unpacked
     * size from `EngineDescriptor.installedSizeBytes`. The estimate is
     * cheap and accurate (we control the bundle layout); UI bars divide
     * the two for a determinate progress fraction.
     */
    data class Extracting(
        val bytesExtracted: Long,
        val totalBytes: Long,
    ) : InstallState()

    /** Engine is ready for use — `KittenEngine.isInstalled()` will return true. */
    object Installed : InstallState()

    /** Install attempt failed mid-flight. UI shows a Retry affordance with the reason. */
    data class Failed(val reason: String) : InstallState()

    /**
     * Files are *present on disk* but the post-install sanity check
     * couldn't find the required top-level files. Surfaced by
     * [EngineInstaller.verify] and treated by the UI the same as
     * NotInstalled (offer reinstall).
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
 *  1. **Atomic installs.** The archive is downloaded to
 *     `<name>.archive.tmp`, extracted into a scratch directory
 *     (`<name>.tmp`), and the scratch dir is renamed to the final
 *     location on success. The app never observes a partial engine
 *     directory.
 *  2. **Hash-verified archive.** The archive's SHA-256 is checked against
 *     the manifest. Mismatch fails the install and deletes the bad bytes.
 *     Because the archive is a single sealed bundle, per-file hashes are
 *     redundant — proving the archive bytes are correct proves every
 *     extracted file's bytes are correct.
 *  3. **Zip-slip protection.** Each archive entry's normalized path is
 *     checked to make sure it stays inside the scratch directory.
 *  4. **Single concurrent install per engine.** Callers must serialise
 *     install/uninstall on the same engine name. v0.1 enforces this via UI
 *     state (the install button disables while in flight); a future Mutex
 *     can move the guarantee into this class.
 *  5. **No network use outside install/verify.** The single
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
     *  - directory present but files missing → [InstallState.Corrupt]
     *
     * Multiple subscribers share the same StateFlow — this is how the
     * Onboarding screen and the Engines screen stay in sync if they're
     * both open (e.g. via system back).
     */
    fun state(engineName: String): Flow<InstallState> = stateFlow(engineName).asStateFlow()

    /**
     * Install [engineName] from the catalog.
     *
     * Downloads the engine's single tar.bz2 archive, verifies its SHA-256,
     * decompresses + untars it into the engine directory. Reports progress
     * through [onProgress] and updates the per-engine state flow.
     *
     * Re-running install on an already-installed engine is idempotent:
     * the existing engine directory is removed first so the new install
     * starts clean.
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
        val finalDir = engineDirFor(engineName)
        val scratchDir = scratchDirFor(engineName)
        val archiveTmp = archiveTmpFor(engineName)

        // Clean up any leftover scratch / partial archive from a previous
        // failed attempt, and drop any existing final dir so we never
        // overlay a partial install on top of an existing one.
        if (archiveTmp.exists()) archiveTmp.delete()
        if (scratchDir.exists()) scratchDir.deleteRecursively()
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
        archiveTmp.parentFile?.mkdirs()

        try {
            val archive = descriptor.archive
            val totalBytes = archive.sizeBytes

            // 1. Download the archive while computing SHA-256 and emitting
            // throttled progress updates.
            sf.value = InstallState.Downloading(
                bytesFetched = 0L,
                totalBytes = totalBytes,
                currentFile = ARCHIVE_PROGRESS_LABEL,
            )

            Log.d(TAG, "Downloading ${archive.url} → ${archiveTmp.absolutePath}")
            val fetchedSha = downloadArchive(archive.url, archiveTmp, totalBytes) { fetched ->
                val update = InstallState.Downloading(
                    bytesFetched = fetched,
                    totalBytes = totalBytes,
                    currentFile = ARCHIVE_PROGRESS_LABEL,
                )
                sf.value = update
                onProgress(update)
            }

            // 2. Verify archive hash.
            if (!fetchedSha.equals(archive.sha256, ignoreCase = true)) {
                throw IOException(
                    "SHA-256 mismatch for archive ${descriptor.name}: " +
                        "expected ${archive.sha256}, got $fetchedSha",
                )
            }

            // 3. Extract. Emit byte-level progress against the descriptor's
            // declared installed size — the UI shows a determinate bar for
            // the unpack phase instead of a stuck indeterminate spinner.
            val totalUnpackedBytes = descriptor.installedSizeBytes
            sf.value = InstallState.Extracting(
                bytesExtracted = 0L,
                totalBytes = totalUnpackedBytes,
            )
            extractArchive(archiveTmp, scratchDir, archive.archiveRoot) { bytes ->
                sf.value = InstallState.Extracting(
                    bytesExtracted = bytes,
                    totalBytes = totalUnpackedBytes,
                )
            }

            // 4. Delete the archive scratch file — it's served its purpose.
            archiveTmp.delete()

            // 5. Atomic rename. Scratch dir and final dir share the same
            // parent (${filesDir}/engines/), so rename is just an inode flip
            // — no cross-filesystem fallback needed.
            if (!scratchDir.renameTo(finalDir)) {
                throw IOException(
                    "Could not rename ${scratchDir.absolutePath} to ${finalDir.absolutePath}",
                )
            }

            // 6. Post-install sanity check. The archive sha already proved
            // the bytes are correct, so this just confirms the extraction
            // produced the expected top-level layout — defensive against
            // a malformed bundle slipping through.
            val verified = verifyLayout(descriptor, finalDir)
            if (verified is InstallState.Corrupt) {
                // Extracted shape doesn't match expectations. Tear down so a
                // retry has a clean slate.
                finalDir.deleteRecursively()
                throw IOException(
                    "Post-install verification failed for ${descriptor.name}: " +
                        "extracted layout missing required files",
                )
            }

            sf.value = InstallState.Installed
            Result.success(Unit)
        } catch (t: Throwable) {
            Log.w(TAG, "Install of $engineName failed", t)
            // Clean up scratch + archive on failure so the next attempt
            // starts fresh.
            if (archiveTmp.exists()) archiveTmp.delete()
            if (scratchDir.exists()) scratchDir.deleteRecursively()
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
            // disappears immediately. The injected NativeEngineHandle
            // releases all four sherpa-onnx engines; release() is
            // idempotent on engines that aren't currently loaded.
            kittenEngine.release()

            val dir = engineDirFor(engineName)
            if (dir.exists() && !dir.deleteRecursively()) {
                throw IOException("Could not delete ${dir.absolutePath}")
            }
            // Also clean any stale scratch dir + partial archive lying around.
            val scratch = scratchDirFor(engineName)
            if (scratch.exists()) scratch.deleteRecursively()
            val archiveTmp = archiveTmpFor(engineName)
            if (archiveTmp.exists()) archiveTmp.delete()

            stateFlow(engineName).value = InstallState.NotInstalled
            Result.success(Unit)
        } catch (t: Throwable) {
            Log.w(TAG, "Uninstall of $engineName failed", t)
            Result.failure(if (t is IOException) t else IOException(t))
        }
    }

    /**
     * Inspect the on-disk engine bundle and return the matching state.
     * Cheap — does not re-hash files, only checks presence of the well-
     * known top-level layout. Used by the UI to populate the Engines
     * screen on first composition.
     */
    open suspend fun verify(engineName: String): InstallState {
        val descriptor = EngineCatalog.byName(engineName)
            ?: return InstallState.NotInstalled
        return verifyDescriptor(descriptor)
    }

    /**
     * Test-friendly verify that operates against a caller-supplied
     * descriptor. Production code paths must go through [verify].
     *
     * For the current single-engine (Kitten) catalog this delegates to
     * [verifyLayout] which encodes the Kitten payload's required files
     * (model.fp16.onnx + voices.bin + tokens.txt + espeak-ng-data/).
     */
    internal suspend fun verifyDescriptor(descriptor: EngineDescriptor): InstallState =
        withContext(Dispatchers.IO) {
            val engineName = descriptor.name
            val dir = engineDirFor(engineName)
            val computed = if (!dir.isDirectory) {
                InstallState.NotInstalled
            } else {
                verifyLayout(descriptor, dir)
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

    private fun archiveTmpFor(engineName: String): File =
        File(filesDir.get(), "engines/$engineName.archive.tmp")

    /**
     * Stream the archive at [url] to [target] via [httpFetcher], computing
     * SHA-256 incrementally and emitting throttled byte-count updates via
     * [onProgress].
     *
     * Progress is throttled to one emission per ~1% of [totalBytes] (or per
     * ~256 KB, whichever is coarser) so the StateFlow consumer isn't
     * flooded with thousands of updates per second on fast connections.
     *
     * @return hex-encoded SHA-256 of the downloaded bytes (lowercase).
     */
    private fun downloadArchive(
        url: String,
        target: File,
        totalBytes: Long,
        onProgress: (Long) -> Unit,
    ): String {
        val digest = MessageDigest.getInstance("SHA-256")
        var bytesSoFar = 0L
        var bytesSinceEmit = 0L
        // Emit at the larger of 1% of total or 256 KB. For a 27 MB archive
        // that's ~270 KB → ~100 updates total, which is plenty granular for
        // a progress bar without flooding the Flow.
        val emitThreshold = maxOf(totalBytes / 100L, PROGRESS_MIN_BYTES)
        httpFetcher.open(url).use { input ->
            target.outputStream().use { output ->
                val buf = ByteArray(BUFFER_SIZE)
                while (true) {
                    val read = input.read(buf)
                    if (read == -1) break
                    output.write(buf, 0, read)
                    digest.update(buf, 0, read)
                    bytesSoFar += read
                    bytesSinceEmit += read
                    if (bytesSinceEmit >= emitThreshold) {
                        onProgress(bytesSoFar)
                        bytesSinceEmit = 0L
                    }
                }
            }
        }
        // Final emission so the bar reaches 100% before we flip to Extracting.
        onProgress(bytesSoFar)
        return digest.digest().toHex()
    }

    /**
     * Extract a tar.bz2 [archiveFile] into [destDir]. Strips the leading
     * [archiveRoot] prefix from each entry path (so an archive containing
     * `kitten-nano-en-v0_1-fp16/model.fp16.onnx` produces
     * `${destDir}/model.fp16.onnx`).
     *
     * Skips directory entries (parent dirs are mkdirs'd implicitly).
     * Rejects entries whose normalized path would escape [destDir]
     * (zip-slip protection).
     */
    private fun extractArchive(
        archiveFile: File,
        destDir: File,
        archiveRoot: String,
        onProgress: (bytesExtracted: Long) -> Unit = {},
    ) {
        val destCanonical = destDir.canonicalPath
        // Throttle progress emissions to ~every 1 MB so the StateFlow isn't
        // flooded during the unpack of a 125 MB Kokoro bundle. The download
        // path uses the same shape.
        var bytesWritten = 0L
        var bytesSinceLastEmit = 0L
        val emitThreshold = 1024L * 1024L
        BufferedInputStream(archiveFile.inputStream()).use { fileIn ->
            BZip2CompressorInputStream(fileIn).use { bzIn ->
                TarArchiveInputStream(bzIn).use { tarIn ->
                    while (true) {
                        val entry = tarIn.nextEntry ?: break
                        if (entry.isDirectory) continue
                        val rawName = entry.name
                        // Strip the wrapper directory if it matches; otherwise
                        // keep the entry path as-is. Empty archiveRoot means
                        // "no stripping".
                        val relPath = when {
                            archiveRoot.isEmpty() -> rawName
                            rawName.startsWith(archiveRoot) -> rawName.removePrefix(archiveRoot)
                            else -> rawName
                        }
                        if (relPath.isEmpty()) continue

                        val outFile = File(destDir, relPath)
                        val outCanonical = outFile.canonicalPath
                        // Zip-slip: refuse any entry whose canonical path
                        // lands outside destDir. catches `../../etc/passwd`-
                        // style escapes in adversarial archives.
                        if (!outCanonical.startsWith(destCanonical + File.separator) &&
                            outCanonical != destCanonical
                        ) {
                            throw IOException(
                                "Tar entry escapes destination directory: $rawName " +
                                    "→ $outCanonical (dest=$destCanonical)",
                            )
                        }
                        outFile.parentFile?.mkdirs()
                        outFile.outputStream().use { out ->
                            val buf = ByteArray(BUFFER_SIZE)
                            while (true) {
                                val read = tarIn.read(buf)
                                if (read == -1) break
                                out.write(buf, 0, read)
                                bytesWritten += read
                                bytesSinceLastEmit += read
                                if (bytesSinceLastEmit >= emitThreshold) {
                                    onProgress(bytesWritten)
                                    bytesSinceLastEmit = 0L
                                }
                            }
                        }
                    }
                }
            }
        }
        // Final emission so the UI bar lands at the actual extracted total
        // before we transition to Installed (or, when the bundle's actual
        // unpacked size differs slightly from the descriptor's declared
        // installedSizeBytes, so the bar doesn't freeze short of 100%).
        onProgress(bytesWritten)
    }

    /**
     * Confirm the on-disk layout matches the Kitten engine's expected
     * top-level shape. Used by [installViaDescriptor]'s post-extract
     * sanity check and by [verifyDescriptor]'s startup probe.
     *
     * Does not re-hash files — for an archive install, the upstream
     * archive's sha256 (verified at download time) already proves the
     * extracted bytes are correct. This is a structural check only:
     *
     *  - Some `model*.onnx` file present and > 1 MB (catches truncated
     *    extract). Filename varies across upstream Kitten revisions —
     *    v0.1 ships `model.fp16.onnx`, v0.8 ships `model.int8.onnx`,
     *    others might ship `model.fp32.onnx`. Matching by glob keeps
     *    the installer agnostic to which revision is in the bundle.
     *  - `voices.bin` present and non-empty
     *  - `tokens.txt` present and non-empty
     *  - `espeak-ng-data/` is a directory with > 100 entries
     *    (Kitten's bundle has ~355; the threshold is a sanity floor that
     *    catches "extraction halfway through" without pinning the count)
     */
    /**
     * Dispatch the post-extract structural check based on the engine
     * family. Sherpa-onnx engines (Kokoro v1.*, Kitten *) all share the
     * `model*.onnx` + `voices.bin` + `tokens.txt` + `espeak-ng-data/`
     * shape; Pocket has a completely different layout (5 graphs,
     * sentencepiece tokenizer, npy BOS embedding, no espeak). Adding a
     * new engine family means adding a branch here.
     */
    private fun verifyLayout(descriptor: EngineDescriptor, dir: File): InstallState {
        return if (descriptor.name == "pocket-tts-en-v2026_04") {
            verifyPocketLayout(dir)
        } else {
            verifySherpaLayout(dir)
        }
    }

    /** Sherpa-onnx layout (Kokoro + Kitten). */
    private fun verifySherpaLayout(dir: File): InstallState {
        val modelCandidates = dir.listFiles { f -> f.isFile && f.name.startsWith("model") && f.name.endsWith(".onnx") }
        val model = modelCandidates?.firstOrNull()
        val voices = File(dir, "voices.bin")
        val tokens = File(dir, "tokens.txt")
        val espeak = File(dir, "espeak-ng-data")

        if (model == null || model.length() < MIN_MODEL_BYTES) return InstallState.Corrupt
        if (!voices.isFile || voices.length() == 0L) return InstallState.Corrupt
        if (!tokens.isFile || tokens.length() == 0L) return InstallState.Corrupt
        if (!espeak.isDirectory) return InstallState.Corrupt
        val entryCount = espeak.list()?.size ?: 0
        if (entryCount < MIN_ESPEAK_ENTRIES) return InstallState.Corrupt
        return InstallState.Installed
    }

    /**
     * Pocket TTS layout: 5 ONNX graphs + tokenizer.model +
     * bos_before_voice.npy + bundle.json + voices/ subdir with one WAV
     * per predefined voice. Voice cloning lands files under
     * cloned_voices/ later; their absence at install time is fine.
     */
    private fun verifyPocketLayout(dir: File): InstallState {
        val requiredFiles = listOf(
            "flow_lm_main_int8.onnx",
            "flow_lm_flow_int8.onnx",
            "mimi_encoder_int8.onnx",
            "mimi_decoder_int8.onnx",
            "text_conditioner_int8.onnx",
            "tokenizer.model",
            "bos_before_voice.npy",
            "bundle.json",
        )
        for (name in requiredFiles) {
            val f = File(dir, name)
            if (!f.isFile || f.length() == 0L) return InstallState.Corrupt
        }
        // At least one ONNX file should be appreciably-sized — guards against
        // a truncated extraction where the headers landed but the body
        // didn't (sherpa's MIN_MODEL_BYTES analogue).
        val mainOnnx = File(dir, "flow_lm_main_int8.onnx")
        if (mainOnnx.length() < MIN_MODEL_BYTES) return InstallState.Corrupt

        val voicesDir = File(dir, "voices")
        if (!voicesDir.isDirectory) return InstallState.Corrupt
        // We need at least one voice WAV; check the eight Kyutai predefined
        // names match what the catalog promises (PocketVoiceCatalog.voices
        // is the source of truth, but we can't depend on it from the
        // installer module without widening the dependency graph — eight
        // hardcoded names here mirror it).
        val expectedVoices = listOf("alba", "azelma", "cosette", "eponine", "fantine", "javert", "jean", "marius")
        for (name in expectedVoices) {
            val wav = File(voicesDir, "$name.wav")
            if (!wav.isFile || wav.length() == 0L) return InstallState.Corrupt
        }
        return InstallState.Installed
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

        // Minimum emit interval for download progress (256 KB). Throttles
        // the StateFlow at the high end when 1% of total is < 256 KB.
        private const val PROGRESS_MIN_BYTES: Long = 256L * 1024L

        // Sanity floors for the post-install layout check. Tuned to catch
        // truncated extractions without pinning to the exact upstream
        // numbers (which would force a code change every bundle bump).
        private const val MIN_MODEL_BYTES: Long = 1L * 1024L * 1024L
        private const val MIN_ESPEAK_ENTRIES: Int = 100

        // String shown in the per-engine progress UI while the archive is
        // downloading. v0.1's per-file labels are gone with the per-file
        // catalog — a single archive download is the whole download phase.
        private const val ARCHIVE_PROGRESS_LABEL: String = "archive"
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
