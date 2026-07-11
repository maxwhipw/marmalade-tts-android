# HANDOFF — audit fix session, 2026-07-11

## State

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
