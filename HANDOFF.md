# HANDOFF — Cloud providers + nav restructure, 2026-07-24 (branch api-engine)

## Branch state

**All cloud-API work now lives on branch `api-engine`** (Max's call,
2026-07-24: keep it off main until the design is proven). `main` was
reset to `eec6d34` (pre-API) and force-aligned on Forgejo; github main
never had the API commits. Merge `api-engine` → main only when Max says
the feature is done and makes sense.

## This session (2026-07-24) — three lettered features, all committed

- **A `2170dc8` nav restructure**: tabs are now Speak / Aliases /
  Effects / Engines / Settings. Voices left the bottom bar → detail
  route `voices?engine={e}` (from Speak, or engine-scoped from
  EngineDetailScreen's new "Browse voices" row). Engines is a tab again
  (Build wrench back; Effects → Star). Settings lost "Manage engines".
- **B `08230bd` cloud engine card**: the Venice key UI moved from
  Settings to a "Cloud voices" card on the Engines tab — Configure
  where local engines have Install; Voices button appears when
  configured.
- **C `ce5e317` providers as data + live discovery**:
  - `app/src/main/assets/cloud-providers.json` (+ same file committed
    to `~/coding/marmalade-tts-android-engines`, commit `551c6a2`,
    **NOT pushed** — needs Max's all-clear; until it's pushed the
    remote refresh 404s harmlessly and the bundled asset rules).
  - `data/cloud/CloudProviders.kt` (parsing), `CloudProviderStore.kt`
    (asset/remote/discovery merge, filesDir/cloud/ caches, owns the
    engine's Room rows via `VoiceMetaDao.replaceEngine`).
  - Voice ids: `cloud-api-v1:<provider>:<model>:<voice>`; legacy
    2-part ids resolve to venice/tts-kokoro. Keys per provider
    (`cloud_api_key_<id>`, legacy key reads as Venice).
  - `CloudApiScreen` (Engines tab → card → Configure): per-provider
    key dialogs + "Refresh voices"; provider list refreshes from the
    engines repo on open.
  - Venice `/models?type=tts` is public and carries per-model voice
    lists; qwen3 models excluded via modelExclude (return MP3).
  - Ships Venice (Kokoro 54) + OpenAI (gpt-4o-mini-tts, tts-1)
    descriptors. OpenAI voice list is from training knowledge —
    verify before relying on it (it's remotely fixable data).

Verified: `assembleFdroidDebug` + full `testFdroidDebugUnitTest` green
(21 cloud-related tests across CloudApiEngineTest,
CloudApiVoiceCatalogTest, CloudProvidersTest, VoiceMetaDaoTest).

## NOT yet done

- **On-device verify** of everything above (blocked on wireless-ADB
  port from Max). Plan: install debug APK from `api-engine`, check the
  new nav, configure the Venice key via the card (legacy key from the
  earlier dev build should carry over as venice), confirm voices
  appear/refresh, speak, alias + per-app route on a cloud voice.
  Venice key lives on marmalade in
  `~/.config/marmalade-tts-cli` config (`engines.api.api_key`).
- Push `cloud-providers.json` in the engines repo to github (Max's
  all-clear required).
- Deferred (deliberate): local-engine fallback on network failure;
  per-model sample-rate handling (everything pinned 24 kHz, loud
  error otherwise); MP3 decode via MediaCodec for wav-ignoring models.
- Untouched: the other session's uncommitted Momo-font/license files
  (LICENSES/fonts.md, NOTICE.md, LicenseCatalog.kt,
  KittenDirectEngine.kt, SpeakScreen.kt, Type.kt + momo_trust font).

# Previous HANDOFF — Cloud API engine, 2026-07-19

## This session (2026-07-19, afternoon) — Cloud API engine

Commit `d48b5ee`: new **Cloud API engine** — hosted Venice tts-kokoro
over an OpenAI-compatible `/audio/speech` HTTP call. Ported from the
CLI's `~/coding/marmalade-tts-cli/marmalade_tts/engines/api.py` (which
was built + live-verified the same day; see that repo's HANDOFF.md).

- **Engine**: `engine/api/CloudApiEngine.kt` — real `synthesizeStream`
  (WAV header parsed from the response, PCM emitted as bytes arrive;
  `"streaming": true` gives ~0.6 s first-byte on Venice). HTTP via a
  `CloudSpeechHttp` seam (HttpURLConnection prod, fake in tests).
- **Catalog**: `data/CloudApiVoiceCatalog.kt`, ENGINE
  `cloud-api-v1`, 54 voices, CATALOG_VERSION 24→25. NOT in
  EngineCatalog — "installed" = Venice API key configured
  (`SettingsRepository.cloudApiKey`; Settings → "Cloud API engine"
  section has the key dialog).
- **Wiring**: Synthesizer + MarmaladeTtsService when-branches,
  CheckVoiceDataActivity, VoicePicker/Alias VMs treat key-set as
  installed; alias editor engine picker now uses `EngineOption`
  (name+displayName) instead of EngineDescriptor.
- **Tests**: 12 new in `app/src/test/.../engine/api/`; full
  `testFdroidDebugUnitTest` suite green; `assembleFdroidDebug` builds.
- **NOT yet verified on device.** Blocked on wireless-ADB port from
  Max. On-device plan: install debug APK, paste the Venice key
  (Settings → Cloud API engine), pick a `cloud-api-v1` voice, speak,
  watch logcat for the synth path; then an alias + per-app route on the
  cloud voice. Key on marmalade in `~/.config/marmalade-tts/config.yaml`
  (`engines.api.api_key`, $2/day limit).
- Deferred (deliberate): automatic local-engine fallback when the
  network/API fails — today it errors like any engine failure. Also
  model selection (pinned `tts-kokoro`) and base-URL override.
- Untouched: the other session's uncommitted Momo-font/license files
  (still in the tree, still uncommitted).

# HANDOFF — alias-screen routing redesign, 2026-07-24

## This session (2026-07-24) — per-app routing moves onto the alias cards

Branch: **main**. One commit: **`ff15c57`** — not pushed (github is the
authoritative remote and public; needs Max's explicit all-clear).

Design came from a blind Fable-5-vs-Opus-5 design-lab bake-off; Max
picked Opus's "routing strip on the alias card" direction and added two
refinements. Labs + write-ups are at
`~/coding/scratch/design-lab-alias-routing/` (proposal-a = Opus, served
on `http://100.99.77.61:8600/`).

What shipped:

- **`AliasScreen.kt`** — alias rows became cards. Each carries a routing
  strip ("Used by 2 apps" + up to 4 app icons) that opens a picker sheet
  scoped to that alias. Non-primary aliases with no routes get a dashed
  "Route apps to X" invitation instead. The **primary** card states the
  fallback rule for the first time anywhere: "…and everything you haven't
  routed".
- **No persistent edit/trash icons** (Max's call). Tapping the card opens
  the editor, now a `ModalBottomSheet`, with **Delete inside it** behind
  the pre-existing confirm dialog.
- **`AppMappingsScreen` → `AppRoutingSheet`**, **`AppMappingsViewModel` →
  `AppRoutingViewModel`** (git-tracked renames). The VM inverts the
  app-first table into the alias-first view: `saveRouting()` diffs the
  sheet's tick set against that alias's saved rows — upsert additions
  (PK-replace = the "steal"), delete removals, never touch another
  alias's rows.
- **Deleted**: Settings → "Per-app voices" row, `Routes.AppMappings`,
  `SettingsViewModel.appMappingCount`.
- **`InstalledAppsProvider`** seam (impl `PackageManagerAppsProvider`,
  bound in `AppModule`) so the routing diff is unit-testable without a
  PackageManager.

Data + money paths are untouched: no schema change, no migration,
`app_alias_mapping`/DAO/`TtsRouter` unchanged. The Pro gate moved with
the feature and kept its rule — **ticking is gated, un-ticking is free**
so a refunded user can still clean up (`AppRoutingViewModel.toggle`,
with a defense-in-depth re-check in `saveRouting`).

- **Verified**: `:app:testFdroidDebugUnitTest` + `:app:testPlayDebugUnitTest`
  green (308 tests, 9 new in `AppRoutingViewModelTest`);
  `:app:assembleFdroidDebug` builds.
- **NOT verified on device** — no Android device was attached this
  session (`adb devices` empty). Nothing here has been eyeballed
  running. That is the top next task.
- Docs synced: CHANGELOG (Unreleased), REPO-MAP, CLAUDE.md, and
  PAYWALL-PLAN (trip-wire section + manual test matrix).

> ⚠️ **Stashed work — read before switching branches.** This session
> started on `api-engine`, which had uncommitted font/licence work in the
> tree (Momo Trust Display + Type.kt / SpeakScreen / KittenDirectEngine /
> LicenseCatalog / NOTICE / LICENSES). To work on main cleanly it was
> parked, not discarded:
> `stash@{0}` — "WIP font bundling (Momo Trust Display) + licenses —
> parked by Claude 2026-07-24". Restore with
> `git checkout api-engine && git stash pop`. It is the same in-flight
> work the 2026-07-18 entry below flagged as "not mine".

## Previous session (2026-07-19) — TTFA fixes

Max reported ~1 s between first sentence appearing in the marmalade
client and TTS speech starting, even with "warm start" on. Traced both
repos; shipped 2 commits here + 1 in marmalade-client-android
(`fbfc718`, setupVoice() no longer re-runs per utterance). All pushed
to Forgejo.

- **`2bc9650` — keepalive now preloads models.** The keepalive service
  only held the *process*; the model still cold-loaded on first synth
  (~300–500 ms ORT session + espeak init). New
  `service/EngineWarmup.kt` singleton runs `ensureModelLoaded()` over
  installed engines; triggered from KeepaliveCoordinator (Smart/
  Persistent), MarmaladeTtsService.onLoadLanguage (was inline there),
  and a new `MarmaladeTtsApplication.onCreate` re-arm — the
  coordinator's promised app-startup trigger existed only in KDoc, so
  persistent mode never survived process death.
- **`c50ad71` — first chunk exempt from the 80-char minChars merge**
  (`TextChunker.minCharsExemptFirst`, on for Kitten + Kokoro streaming;
  Pocket deliberately untouched — chunk boundaries break its prosody
  seed). Closes the open TTFA item from AUDIT-2026-07-11. Tests added.
- **On-device verify (Max):** first utterance after fresh process with
  warm start on (expect `StreamPerf` `loadWait=0`); listen for
  "short-utterance" pacing on Kitten when a reply opens with a short
  sentence.
- **Deferred fix 4 (client repo):** the voice feeder blocks on the
  prompt.submit ACK (~1 RTT) before collecting speakable chunks —
  `MarmaladeVoiceSession.kt:977`. Assess fixes 1–3 on-device first.

## Previous session (2026-07-18)

Onboarding UX pass (commits `634f83a` + `eacabb8`, unpushed, on top of
the 2026-07-11 state below):

- **JarMascot port**: marmalade-android's live-drawn mascot animation
  (from `~/coding/marmalade/marmalade-android-native/.../ui/voice/JarMascot.kt`)
  now lives at `app/src/main/java/app/marmalade/tts/ui/components/JarMascot.kt`
  with a local `JarMascotState` enum. Install-progress onboarding step
  shows LISTENING (lid open, waves in) while downloads run, IDLE when done.
- **SystemDefault step self-updates**: new `isDefaultSystemTts()` in
  `ui/SystemSettings.kt` reads secure setting `tts_default_synth`;
  `SystemDefaultStep` re-checks it on every ON_RESUME. Once Marmalade is
  the system engine the screen shows "All done!" + a plain **Finish**
  button instead of "Finish — I'll do this later".
- Cleanup: 7 unreferenced static `mascot_*.xml` drawables deleted
  (happy + speaking remain in use).
- Verified: fdroid unit suite green, `assembleFdroidDebug` built and
  installed on the Pixel 8a debug app. NOT yet eyeballed on-device
  (onboarding only shows on fresh data — don't wipe the debug app's
  engines just to look; Max will see it on his next fresh-install test).
- Settings "ONNX threads" question answered: real, both flavors
  (direct-ORT Kokoro/Kitten set ONNX Runtime intra-op threads). Label
  left as-is.
- Still uncommitted in the tree (from a prior 2026-07-12 session, NOT
  mine): Momo Trust Display font + Type.kt/SpeakScreen.kt/LicenseCatalog/
  NOTICE/LICENSES edits. Left untouched; needs that session's owner to
  finish or commit.

## State (2026-07-11 baseline)

main is PUSHED to github (through the 2026-07-11 session). Recent
atoms: AA (`dc9392a`, CHECK_TTS_DATA install state — unblocked the
Settings Play button), AB (`cc11ee3`, quiet client-stop), AC
(`5529fe3`, abbreviation regex matched mid-word: "test." → "te saint"),
AD (`6f41cd3`, v22 engine bundles — no executable code at rest, espeak
data rebuilt from the 1.52.0 tag). Release runway (R8 smoke via
-PsmokeRelease, screenshots, engines re-spin + §6, README install
section) is DONE — see docs/release/DISTRIBUTION-GAMEPLAN.md for
what's left (Max: version decision + tag, keystore, Play Console;
plus the Pocket LISTEN test and Kokoro v22 update on the debug app).
All from `docs/AUDIT-2026-07-11.md` (the whole-app audit + fix-status —
**read that file first**, it is the master list with commit hashes).

- Waves 1–3 (atoms A–Z): 26 fix commits, unit suite green on BOTH flavors
  (`./gradlew :app:testFdroidDebugUnitTest :app:testPlayDebugUnitTest`).
- Debug APK at `app/build/outputs/apk/fdroid/debug/app-fdroid-debug.apk`,
  installed side-by-side on the Pixel 8a (`app.marmalade.tts.debug`) with
  Kitten Nano installed and a `default` alias (Bella). Max's daily release
  app (`app.marmalade.tts`, system TTS default) untouched.

## Device smoke results (Pixel 8a, wireless ADB — port rotates, ask Max)

Verified live:
- Atom C: onboarding Skip → CreateAlias, zero downloads (logcat clean).
- Atom K: ModelMissing banner cleared on Speak re-entry after install.
- In-app streaming: `kitten TTFA=1421ms`, rtf 0.16, no underrun.
- Atom N: share-sheet long-form streams — 4 chunks, TTFA 1650 ms, chunks
  1–3 synthesized behind playback. Pause/resume/stop via
  `adb shell cmd media_session dispatch pause|play|stop` all clean,
  service tore down properly.

RESOLVED 2026-07-11: **system TTS Play greyed out** was pre-existing on
release too (tapping release Play fired nothing) — NOT an audit
regression. Root cause: CheckVoiceDataActivity reported availability
from the vestigial `VoiceMeta.isInstalled` flag (never flipped in
production), so CHECK_TTS_DATA returned `available=[]` and Settings
disabled Play + Language. Fixed as atom AA (`dc9392a`, classify by
`TtsEngine.isInstalled()` disk state). Follow-up atom AB (`cc11ee3`):
client stop mid-stream no longer logs an `E Synthesis failed` stack.

Atoms F + G now device-verified through the unblocked Play button:
`speed=3.07` / `speed=0.5` at the rate-slider extremes, StreamPerf
per-chunk emits through the framework callback, onStop → clean
'Synthesis stopped by client'. Max's real speech rate is **307**, not
the 100 this file previously claimed — restore
`tts_default_rate` to 307 after tests (done).

## Next tasks (in order)

1. Pocket regression LISTEN (Max's ears): Pocket is installed on the
   debug app; StreamPerf on a 4-chunk share-sheet run (2026-07-11)
   shows only the known slower-than-realtime underrun gaps, no new
   seam signal. Listen for bitcrush-style seam artifacts to close it.
2. Device-gated perf work from the audit "Still open" list: Pocket
   voice-cond KV snapshot (top RTF item, machinery in PocketStateManager
   snapshot/restore, A/B via `adb logcat -s StreamPerf`), chunk-0 minChars
   TTFA exemption (needs listen test).
3. Remaining low items listed at the end of docs/AUDIT-2026-07-11.md
   (install mutex, AppMappings icon loading, REPO-MAP.md refresh — it still
   describes the sherpa-onnx architecture).
4. Pushed through 2026-07-11 (Max authorized). Future pushes: github
   only, never origin/Forgejo by hand.

## Guardrails

- Max's daily = the RELEASE app; never uninstall it, never
  `connectedAndroidTest` (wipes data). Debug app is disposable.
- Restore any `settings put secure tts_default_synth/tts_default_rate`
  changes after testing (original: `app.marmalade.tts` / 307).
- Lettered atoms, one commit each, compile + unit test before commit.
- Build: `./gradlew :app:compileFdroidDebugKotlin` (fast check),
  `:app:testFdroidDebugUnitTest` (suite), `:app:assembleFdroidDebug` (APK),
  `adb install -r` (upgrade in place).
