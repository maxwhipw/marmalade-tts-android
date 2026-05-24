package app.marmalade.tts

import android.app.Application
import app.marmalade.tts.data.KittenMiniVoiceCatalog
import app.marmalade.tts.data.KittenNanoVoiceCatalog
import app.marmalade.tts.data.KokoroV10VoiceCatalog
import app.marmalade.tts.data.KokoroV11VoiceCatalog
import app.marmalade.tts.data.SettingsRepository
import app.marmalade.tts.data.db.VoiceMetaDao
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import javax.inject.Provider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

// -----------------------------------------------------------------------------
// Data flow
// -----------------------------------------------------------------------------
//   onCreate
//     │
//     ├── super.onCreate()  (Hilt populates @Inject fields here)
//     │
//     └── applicationScope.launch
//             │
//             ▼
//        last = settings.catalogVersion.first()
//             │
//             ▼
//        if (last < CATALOG_VERSION) {
//             dao.upsertAll(KittenVoiceCatalog.voices)
//             dao.upsertAll(KokoroVoiceCatalog.voices)
//             settings.setCatalogVersion(CATALOG_VERSION)
//        }
//
//   Pre-v0.1.19 the seed was "upsert if engine's rows are absent". That
//   fixed the fresh-install case but left users stranded when a catalog
//   *expanded* — e.g. v0.1.19's Kokoro 11 → 53 voices for multi-lang
//   wouldn't reach existing installs because Kokoro rows already existed,
//   so the per-engine check skipped the upsert. The catalog-version stamp
//   replaces that with a monotonic gate: any time we ship new/changed
//   catalog rows, bump CATALOG_VERSION and the next cold start picks them
//   up via Room's REPLACE-on-conflict upsert.
// -----------------------------------------------------------------------------

/**
 * Application entry point.
 *
 * Owns the voice-catalog seed: on every cold start, compares the on-disk
 * "last seeded catalog version" against the [CATALOG_VERSION] shipped in
 * this build. If the shipped version is newer, every catalog is re-upserted
 * via Room's REPLACE-on-conflict path, then the stamp is updated.
 *
 * Seeding lives here (not in `RoomDatabase.Callback.onCreate`) because the
 * callback's coroutine was fire-and-forget on a private scope, so
 * `SpeakViewModel.currentVoice` could read the DAO before the seed
 * completed and get a stale null forever (DataStore didn't re-emit). See
 * Blocker #2 + Major #4 in the v0.1 whole-project review.
 *
 * Doing the seed here:
 *  - uses a monotonic CATALOG_VERSION gate that handles both the
 *    fresh-install case and catalog *expansions* (the v0.1.19 multi-lang
 *    Kokoro upgrade is the first such expansion);
 *  - uses an application-scoped [CoroutineScope] tied to this Application
 *    instance (one per process, by definition);
 *  - runs before `SpeakViewModel` is constructed in practice — the seed
 *    coroutine launches in `onCreate`, the first VM is built when
 *    `MainActivity` starts, and even if the suspend points overlap,
 *    `SpeakViewModel.currentVoice` now combines `voiceDao.getAll()` so it
 *    re-resolves once the seed lands.
 */
@HiltAndroidApp
class MarmaladeTtsApplication : Application() {

    /**
     * Provider so the field can be safely held without forcing eager DB
     * construction at field-population time. By the time the seed coroutine
     * suspends + resumes on IO, Hilt has the graph ready; `.get()` triggers
     * Room's `databaseBuilder.build()` which is already off the main thread.
     */
    @Inject
    lateinit var voiceDao: Provider<VoiceMetaDao>

    @Inject
    lateinit var settings: Provider<SettingsRepository>

    /**
     * Application-lifetime scope. SupervisorJob so a seed failure doesn't
     * propagate out of this scope and tear down anything else launched on
     * it. The Application instance lives for the duration of the process,
     * so we don't cancel this scope — there's no later point where doing
     * so would be correct.
     */
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        // Hilt populates @Inject fields during super.onCreate() — must run
        // before any access to `voiceDao` / `settings`.
        super.onCreate()
        applicationScope.launch {
            val dao = voiceDao.get()
            val prefs = settings.get()
            val lastSeeded = prefs.catalogVersion.first()
            if (lastSeeded < CATALOG_VERSION) {
                // REPLACE-on-conflict upsert is idempotent: existing rows
                // get their columns refreshed (e.g. a voice's languageCode
                // flipping from "en-US" to "ja-JP" in the multi-lang
                // expansion) without ever wiping the table.
                dao.upsertAll(KokoroV10VoiceCatalog.voices)
                dao.upsertAll(KokoroV11VoiceCatalog.voices)
                dao.upsertAll(KittenNanoVoiceCatalog.voices)
                dao.upsertAll(KittenMiniVoiceCatalog.voices)
                prefs.setCatalogVersion(CATALOG_VERSION)
            }
        }
    }

    companion object {
        /**
         * Bump every time the shipped voice catalogs change (add/remove
         * voices, change a language code, rename a display name, etc.).
         * Existing installs whose DataStore-stored
         * [SettingsRepository.catalogVersion] is below this number will
         * re-seed on next cold start.
         *
         * History:
         *  - v1: Introduced in v0.1.19 alongside the Kokoro 11 → 53 voice
         *    expansion (multi-lang). The pre-v0.1.19 schema didn't have a
         *    stamp; everyone defaults to 0 on read and so re-seeds on
         *    first launch of v0.1.19.
         */
        const val CATALOG_VERSION: Int = 2
    }
}
