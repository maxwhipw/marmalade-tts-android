package app.marmalade.tts

import android.app.Application
import app.marmalade.tts.data.KittenVoiceCatalog
import app.marmalade.tts.data.db.VoiceMetaDao
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import javax.inject.Provider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
//        voiceDao.count() == 0 ?
//             │            │
//             │ yes        │ no
//             ▼            ▼
//        upsertAll(...) (no-op, already seeded)
// -----------------------------------------------------------------------------

/**
 * Application entry point.
 *
 * Owns the one-shot voice-catalog seed: on first launch (or any cold start
 * where the `voice_meta` table is empty), the Kitten voice catalog is
 * inserted so `SpeakViewModel.currentVoice` can resolve immediately.
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
 *  - is gated on `count() == 0` so it's idempotent across cold starts and
 *    survives a destructive migration without duplicating rows;
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
            if (dao.count() == 0) {
                dao.upsertAll(KittenVoiceCatalog.voices)
            }
        }
    }
}
