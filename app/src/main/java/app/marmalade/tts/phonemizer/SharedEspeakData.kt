package app.marmalade.tts.phonemizer

import android.content.Context
import android.content.res.AssetManager
import android.util.Log
import app.marmalade.tts.BuildConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The app-level shared espeak-ng-data — one full-language tree serving every
 * espeak engine in the process.
 *
 * Why shared: espeak is ONE process-global instance behind every
 * [EspeakPhonemizer] and `espeak_Initialize` takes exactly one data path, so
 * whichever engine opened espeak first used to pick the data for everyone.
 * With per-bundle copies that was a real defect — the baked Kitten seed
 * shipped English-only data, and if Kitten opened first, Kokoro's
 * es/fr/hi/it/pt voices silently phonemized as English. One superset tree,
 * generated at build from the pinned submodule (see tools/espeak-hostgen and
 * the `generateEspeakData` Gradle task) and shipped in APK assets, removes
 * the ordering hazard entirely — and keeps the data exactly in step with the
 * APK's compiled libespeak-ng version, which bundle-shipped copies couldn't
 * guarantee.
 *
 * [ensure] seeds `assets/espeak/espeak-ng-data` → `filesDir/espeak-ng-data`
 * once per [dataVersion] and returns the seeded dir. Deliberately blocking:
 * the only callers are the engines' `doLoad` bodies, which already run on
 * load threads (`runBlocking` under their loadLock) — a suspend surface here
 * would buy nothing. The copy is ~19 MB of small files, a one-time cost per
 * app version.
 */
@Singleton
class SharedEspeakData internal constructor(
    /** Where the seeded tree lives (`filesDir/espeak-ng-data`). */
    private val targetDir: File,
    /**
     * Stamp written after a successful seed; a mismatch re-seeds. Bound to
     * the app versionCode: the data only changes when the pinned submodule
     * (or the hostgen) changes, and those land in releases. Dev caveat: a
     * data change WITHOUT a versionCode bump keeps the old seed on an
     * already-installed debug build — uninstall or bump to refresh.
     */
    private val dataVersion: String,
    /** Copies the APK's espeak asset tree into the given dir. Seam for JVM tests. */
    private val copyAssets: (File) -> Unit,
) {

    @Inject
    constructor(@ApplicationContext ctx: Context) : this(
        targetDir = File(ctx.filesDir, TARGET_DIR_NAME),
        dataVersion = BuildConfig.VERSION_CODE.toString(),
        copyAssets = { dst -> copyAssetTree(ctx.assets, ASSET_ROOT, dst) },
    )

    /**
     * Serialises concurrent seeds (both engines cold-load in parallel: the
     * P-D preload and a routed synth can race). A JVM monitor, not a
     * coroutine Mutex, because every caller is already on a blocking load
     * thread.
     */
    private val lock = Any()

    /**
     * Returns the seeded espeak-ng-data dir, seeding or re-seeding first if
     * the version marker is absent or stale. Throws [IOException] when the
     * seed can't be produced — callers (engine `doLoad`) already surface
     * load failures with context.
     */
    fun ensure(): File = synchronized(lock) {
        val marker = File(targetDir, MARKER_NAME)
        if (marker.isFile && marker.readText() == dataVersion && isComplete(targetDir)) {
            return targetDir
        }
        Log.i(TAG, "seeding espeak-ng-data v$dataVersion to $targetDir")
        if (targetDir.exists()) targetDir.deleteRecursively()
        copyAssets(targetDir)
        if (!isComplete(targetDir)) {
            targetDir.deleteRecursively()
            throw IOException("seeded espeak-ng-data at $targetDir is incomplete")
        }
        // Marker written LAST: a crash mid-copy leaves no (or a stale)
        // marker, so the next ensure() re-seeds from scratch.
        marker.writeText(dataVersion)
        targetDir
    }

    companion object {
        private const val TAG = "SharedEspeakData"

        /** Asset path of the build-generated full data set (generateEspeakData). */
        private const val ASSET_ROOT = "espeak/espeak-ng-data"

        private const val TARGET_DIR_NAME = "espeak-ng-data"

        /** Dot-file so the marker stays out of espeak's own directory scans. */
        private const val MARKER_NAME = ".seed-version"

        /**
         * Files a usable seed must contain: the phoneme tables plus one dict
         * per language Kokoro actually feeds through espeak. Doubling as the
         * post-copy integrity check and the fast-path staleness probe — a
         * partially deleted tree re-seeds even with a matching marker.
         */
        private val REQUIRED_FILES = listOf(
            "phondata", "phonindex", "phontab", "intonations",
            "en_dict", "es_dict", "fr_dict", "hi_dict", "it_dict", "pt_dict",
        )

        private fun isComplete(dir: File): Boolean =
            REQUIRED_FILES.all { File(dir, it).isFile } &&
                File(dir, "lang").isDirectory &&
                File(dir, "voices").isDirectory

        /**
         * Recursively copy an APK asset subtree. `AssetManager.list()` returns
         * child names for a directory and an empty array for a file (APKs
         * carry no empty directories), so an empty list means "copy this
         * asset as a file" — same convention EngineInstaller.copyAssetTree
         * relies on.
         */
        private fun copyAssetTree(assets: AssetManager, assetPath: String, dest: File) {
            val children = assets.list(assetPath) ?: emptyArray()
            if (children.isEmpty()) {
                dest.parentFile?.mkdirs()
                assets.open(assetPath).use { input ->
                    dest.outputStream().use { input.copyTo(it) }
                }
            } else {
                dest.mkdirs()
                for (child in children) {
                    copyAssetTree(assets, "$assetPath/$child", File(dest, child))
                }
            }
        }
    }
}
