# HANDOFF — audit fix session, 2026-07-11

## State

30 local commits on `main` (NOT pushed), ending `d6c59f3` + smoke session.
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

NOT yet verified (blocked): **system TTS service path (atoms F/G)** — in
Android Settings → TTS output with the debug engine selected, the Play
example button is GREYED OUT (Language row also disabled), so no synth
fires. Release-app engine works there, so suspicion: something the debug
engine reports during voice/language negotiation (onIsLanguageAvailable /
CHECK_TTS_DATA / onGetVoices?) differs — CheckVoiceDataActivity DID launch.
Debug this next: compare `dumpsys texttospeech`, add logging to the
negotiation callbacks, or drive a real client (ebook reader / TalkBack).
Note: it may also be pre-existing behavior for a freshly-selected engine —
compare against the release app before assuming the audit fixes broke it.

## Next tasks (in order)

1. **System-TTS Play greyed out** — diagnose + verify atoms F (streaming,
   onStop) and G (speech-rate multiplier; look for `speed=` in
   `onSynthesizeText` logcat) on device.
2. Pocket regression listen: install Pocket on the debug app, share-sheet a
   paragraph, listen for chunk-seam artifacts (atom P removed dead code
   only, but confirm) + check `StreamPerf` gaps.
3. Device-gated perf work from the audit "Still open" list: Pocket
   voice-cond KV snapshot (top RTF item, machinery in PocketStateManager
   snapshot/restore, A/B via `adb logcat -s StreamPerf`), chunk-0 minChars
   TTFA exemption (needs listen test).
4. Remaining low items listed at the end of docs/AUDIT-2026-07-11.md
   (install mutex, AppMappings icon loading, REPO-MAP.md refresh — it still
   describes the sherpa-onnx architecture).
5. Push: `git push github main` when Max says so (github is authoritative;
   never push origin/Forgejo by hand).

## Guardrails

- Max's daily = the RELEASE app; never uninstall it, never
  `connectedAndroidTest` (wipes data). Debug app is disposable.
- Restore any `settings put secure tts_default_synth/tts_default_rate`
  changes after testing (original: `app.marmalade.tts` / 100).
- Lettered atoms, one commit each, compile + unit test before commit.
- Build: `./gradlew :app:compileFdroidDebugKotlin` (fast check),
  `:app:testFdroidDebugUnitTest` (suite), `:app:assembleFdroidDebug` (APK),
  `adb install -r` (upgrade in place).
