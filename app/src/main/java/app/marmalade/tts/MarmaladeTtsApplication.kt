package app.marmalade.tts

import android.app.Application
import app.marmalade.tts.data.KittenVoiceCatalog
import app.marmalade.tts.data.KokoroVoiceCatalog
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
//        for each catalog (KittenVoiceCatalog, KokoroVoiceCatalog):
//             │
//             ▼
//        dao.getByEngine(<engine>).first().isEmpty() ?
//             │              │
//             │ yes          │ no
//             ▼              ▼
//        upsertAll(catalog)  (no-op, already seeded)
//
//   The per-engine check (rather than `count() == 0`) handles the upgrade
//   path from v0.1.8 (Kitten-only) to v0.1.9+ (Kitten + Kokoro): users who
//   already have Kitten voices in the DB still get Kokoro voices inserted
//   on next launch.
// -----------------------------------------------------------------------------

/**
 * Application entry point.
 *
 * Owns the one-shot voice-catalog seed: on first launch (or any cold start
 * where a catalog's voices aren't yet present), the matching voice list
 * is inserted so `SpeakViewModel.currentVoice` can resolve immediately.
 *
 * Seeding moved here from a `RoomDatabase.Callback.onCreate` lambda (see
 * Blocker #2 + Major #4 in the v0.1 whole-project review):
 *
 *  - The callback's coroutine was fire-and-forget on a private scope, so
 *    `SpeakViewModel.currentVoice` could read the DAO before the seed
 *    completed and get a stale null forever (DataStore didn't re-emit).
 *  - The scope captured by the anonymous callback was process-lived and
 *    never cancelled — latent leak.
 *
 * Doing the seed here:
 *  - is gated per-engine on `getByEngine(<engine>).first().isEmpty()` so
 *    it's idempotent across cold starts AND survives the v0.1.8 → v0.1.9
 *    upgrade (users with Kitten already seeded still get Kokoro inserted);
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
        // before any access to `voiceDao`.
        super.onCreate()
        applicationScope.launch {
            val dao = voiceDao.get()
            // Per-engine seeding: each catalog only writes its rows if
            // the DB is missing every voice for that engine. Idempotent
            // on cold start, and handles the v0.1.8 → v0.1.9 upgrade
            // (Kitten present, Kokoro absent) without duplicating Kitten.
            if (dao.getByEngine(KittenVoiceCatalog.ENGINE).first().isEmpty()) {
                dao.upsertAll(KittenVoiceCatalog.voices)
            }
            if (dao.getByEngine(KokoroVoiceCatalog.ENGINE).first().isEmpty()) {
                dao.upsertAll(KokoroVoiceCatalog.voices)
            }
        }
    }
}
