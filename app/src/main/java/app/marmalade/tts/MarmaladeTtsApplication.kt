package app.marmalade.tts

import android.app.Application
import app.marmalade.tts.data.KittenMiniVoiceCatalog
import app.marmalade.tts.data.KittenNanoVoiceCatalog
import app.marmalade.tts.data.KokoroV10VoiceCatalog
import app.marmalade.tts.data.KokoroV11VoiceCatalog
import app.marmalade.tts.data.PocketDevVoiceCatalog
import app.marmalade.tts.data.PocketVoiceCatalog
import app.marmalade.tts.data.KittenDirectVoiceCatalog
import app.marmalade.tts.data.KittenDirectMiniVoiceCatalog
import app.marmalade.tts.data.KokoroDirectVoiceCatalog
import app.marmalade.tts.data.BuiltinEffects
import app.marmalade.tts.data.SettingsRepository
import app.marmalade.tts.data.db.EffectDao
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

    @Inject
    lateinit var effectDao: Provider<EffectDao>

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
                dao.upsertAll(KokoroDirectVoiceCatalog.voices)
                dao.upsertAll(KittenNanoVoiceCatalog.voices)
                dao.upsertAll(KittenMiniVoiceCatalog.voices)
                dao.upsertAll(KittenDirectVoiceCatalog.voices)
                dao.upsertAll(KittenDirectMiniVoiceCatalog.voices)
                dao.upsertAll(PocketVoiceCatalog.voices)
                dao.upsertAll(PocketDevVoiceCatalog.voices)
                // Built-in effects. REPLACE-on-conflict refreshes them on each
                // bump; user-created effects (other ids) are untouched, and
                // built-ins are read-only in the UI so this can't clobber user
                // edits. Prune drops built-ins removed from the catalog (e.g.
                // Whisper/Slow deep/Fast high in v9) so they don't linger on
                // upgrades.
                effectDao.get().upsertAll(BuiltinEffects.seedRows)
                effectDao.get().pruneBuiltinsNotIn(BuiltinEffects.seedIds)
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
         *  - v2: v0.2.0 — Kokoro + Kitten split into v1.0/v1.1 and Nano/Mini
         *    engines with new IDs (`kokoro-v1_0`, `kitten-mini-v0_8`, …).
         *  - v3: v0.3.0-alpha.1 — added Pocket TTS catalog (8 voices,
         *    `pocket-tts-en-v2026_04:<name>`).
         *  - v4: v0.3.0-alpha.10 — added Kokoro Direct catalog (53 voices,
         *    `kokoro-direct-v1_0:<name>`).
         *  - v7: v0.3.0-alpha.11 — seed the built-in effects (Cave/Robot/
         *    Telephone) into the new `effect` table (E-C). (v5/v6 were the
         *    Kitten Direct + Kitten Direct Mini catalog bumps.)
         *  - v8: v0.3.0-alpha.11 — effects rebuilt to mirror the CLI's sox
         *    presets (E-I): Cave/Robot/Telephone recipes changed + 7 presets
         *    added (Chipmunk/Deep/Whisper/Stadium/Megaphone/Slow deep/Fast
         *    high). REPLACE-on-conflict refreshes the built-in rows.
         *  - v9: v0.3.0-alpha.11 — preset refinement: Robot pitch −300→−100,
         *    Megaphone vol 2.0→1.5, and Whisper/Slow deep/Fast high removed
         *    (pruneBuiltinsNotIn drops the stale rows on upgrade).
         *  - v10: v0.3.0-alpha.11 — 11 curated voice stackups (Broadcaster/
         *    Podcast/Trailer/Audiobook/Walkie-talkie/Vintage radio/Intercom/
         *    Underwater/Ethereal/Alien/Demon) + 3 Android-only ones (Cyborg/
         *    8-bit/Glitch) using the new Bitcrush/RingMod blocks.
         *  - v11: v0.3.0-alpha.11 — Dragon preset added; Ghost renamed to
         *    Ethereal (new id, old `builtin:ghost` pruned); Vol makeup added to
         *    8-bit/Underwater/Glitch.
         *  - v12: v0.3.0-alpha.11 — Demon removed (pruned); Vintage radio
         *    redesigned for a more authentic AM-radio feel (narrower band,
         *    heavier saturation + compression, deeper wobble, cabinet reverb).
         *  - v13: v0.3.0-alpha.11 — Vintage radio further differentiated from
         *    Walkie-talkie: wow-and-flutter chorus, lighter leveling
         *    compression, slower deeper tremolo throb, bigger cabinet reverb.
         *  - v14: v0.3.0-alpha.11 — Vintage radio rewritten to match the
         *    canonical Audacity AM Radio preset (HP 400 / LP 4k / +12 dB
         *    mid honk @ 1 kHz). Removed chorus — references confirm wow-and-
         *    flutter is a tape/turntable cue, not a radio cue.
         *  - v15: v0.3.0-alpha.11 — Sardonic preset added (Android-only, uses
         *    Bitcrush + RingMod): deadpan synthetic AI voice with multi-voice
         *    chorus doubling + slight pitch up + clean-room reverb.
         *  - v16: v0.3.0-alpha.11 — Monotone block added (YIN pitch detect +
         *    dynamic shifter); Sardonic recipe now uses it for true flat-pitch
         *    deadpan instead of static pitch + chorus alone.
         *  - v17: v0.3.0-alpha.11 — Sardonic renamed to AI (id `builtin:ai`,
         *    old `builtin:sardonic` pruned), plus Pitch(+200) after Monotone
         *    for an octave-ish lift. Robot gets Vol(0.7) makeup to tame
         *    overdrive loudness.
         *  - v18: v0.3.0-alpha.11 — AI's post-Monotone Pitch bumped 200→300
         *    cents; Monotone smoothing tuned (glide 200 ms + 30-cent
         *    hysteresis) to suppress turntable-wow on intra-utterance pitch
         *    variations.
         *  - v19: v0.3.0-alpha.11 — Monotone2 (phase-vocoder shifter) added,
         *    plus a new Synth preset mirroring AI but using Monotone2. The
         *    Monotone preset is intentionally kept untouched for A/B.
         *  - v20: v0.3.0-alpha.11 — Monotone2 + Synth removed after the A/B
         *    (the original Monotone-based AI was preferred). `builtin:synth`
         *    is pruned and the Monotone2 block + Fft DSP retired.
         *  - v22: v0.3.0-alpha.11 — added Pocket ExecuTorch dev catalog (8
         *    voices, `pocket-tts-en-v2026_04-et:<name>`) for the on-device
         *    ExecuTorch-vs-ORT RTF A/B.
         *  - v23: 1.0.0-beta.1 — removed the Pocket ExecuTorch dev catalog;
         *    the ExecuTorch experiment was dropped from the app (preserved on
         *    the `experimental/executorch` branch). Pre-existing ET voice rows
         *    are inert — the engine is gone — and harmlessly linger until a
         *    reinstall; we don't prune them.
         */
        const val CATALOG_VERSION: Int = 23
    }
}
