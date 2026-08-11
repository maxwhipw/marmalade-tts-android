# Google Play release plan — marmalade-tts-android

Step-by-step path from the released tree (v1.0.0 = `0673905`,
versionCode 10000000) to a published Play listing. Steps marked
**[Max]** need a human; everything else Claude can do or has done.

> **State as of 2026-08-11:** v1.0.0 is tagged and released on GitHub
> with the signed Play AAB attached
> (`marmalade-tts-1.0.0-play.aab` on the v1.0.0 release) — Phases 0–1
> are DONE. What remains is the Play Console itself (Phase 2, all
> [Max]) plus listing upload and the closed test (Phases 3–4).
> Console answers are pre-written in
> [PLAY-CONSOLE-RESPONSES.md](PLAY-CONSOLE-RESPONSES.md); the live
> tracker is `launch-lab.html`.

> **There is no paywall.** The app ships free with every feature
> unlocked in both flavors — the Pro plan was withdrawn on 2026-07-26
> and the implementation removed; see
> [PAYWALL-PLAN.md](PAYWALL-PLAN.md) for the reasoning. No
> `marmalade_pro` IAP is created, and no in-app purchase or billing
> surface is declared to the Console. The `play`/`fdroid` flavor split
> survives only so the Play build can omit the GitHub Sponsors link.

## Phase 0 — code prerequisites

1. **DONE (2026-06-11): espeak-ng compiled from source into the APK.**
   Play's Device & Network Abuse policy forbids downloading executable
   code ("dex, JAR, .so files") from outside Play. espeak-ng is now a
   pinned submodule (`third_party/espeak-ng`, tag 1.52.0) built per-ABI
   into `libespeak-ng.so` by `app/src/main/cpp/espeak-ng/CMakeLists.txt`
   with 16 KB page alignment; `EspeakPhonemizer.apkLibFile()` loads the
   APK copy. The engines no longer require or load the bundles'
   `libttsespeak.so`. Note: the APK is now distributed under
   GPL-3.0-or-later (espeak is compiled in); source files remain MIT.
   Follow-up (non-blocking): re-spin the Kitten/Kokoro bundles without
   the leftover `.so` files so the downloads also contain no executable
   code at rest — see the session handoff.
2. **Commit everything + device-verify.** The sherpa removal, license
   screen, and licensing-posture fixes are uncommitted as of
   2026-06-10. Split into logical commits, install on the Pixel 8a,
   and smoke-test all four engines (Kokoro, Kitten ×2, Pocket) —
   including a **minified release build** — DONE 2026-07-11: R8 build
   smoke-tested on the Pixel 8a via `-PsmokeRelease` (side-by-side
   install mechanism in app/build.gradle.kts): onboarding, engine
   download/extract, synthesis + playback, system-TTS negotiation all
   pass minified.
3. Done already: R8/proguard fixed (ORT keep + commons-compress
   dontwarn), 16 KB alignment for in-APK libs, Room v7→v8 migration,
   licensing docs/in-app screen tell the MIT-APK story.

## Phase 1 — signing — DONE

4. **DONE (2026-07-26):** upload keystore `~/secure/marmalade-upload.jks`
   (RSA 4096, alias `marmalade-upload`), backed up in `~/secure` +
   `~/keys` (sha256-identical). Signing decision (revised 2026-08-09):
   this one keystore is the permanent F-Droid/GitHub identity too —
   both CI secret quartets point at it; a fresh Play key only if ever
   needed. See [SIGNING.md](SIGNING.md) / [CI-SIGNING.md](CI-SIGNING.md).
5. **DONE:** CI signs on tag — all 8 signing secrets live on the
   GitHub repo; no local `keystore.properties` needed for releases.
6. **DONE:** the signed AAB is attached to the v1.0.0 GitHub release
   as `marmalade-tts-1.0.0-play.aab` (with SHA256SUMS.txt). Download
   that for the Console upload — don't rebuild locally. (Enroll in
   Play App Signing at upload — Google holds the signing key, the
   keystore above is your upload key.)

## Phase 2 — Play Console setup **[Max]**

7. Play Console developer account ($25 one-time). **Note:** new
   personal accounts must run a closed test with 12+ testers for 14
   days before production access — check whether this applies and plan
   timeline accordingly.
8. Create app → "Marmalade TTS", Free, App (not game).
9. **Privacy policy URL** (required even with zero data collection):
   host PRIVACY.md publicly — GitHub blob URL works; GitHub Pages is
   nicer. *In-app half DONE 2026-08-08 (`e31b34d`):* the policy now
   renders in-app at Settings → About → "Privacy policy"
   (`PrivacyPolicyScreen.kt`, `Routes.Privacy`). The repo is public
   since the v1.0.0 push, so the URL is live:
   `https://github.com/maxwhipw/marmalade-tts-android/blob/main/PRIVACY.md`
   — before pasting it into the Console, check the in-app copy still
   matches the hosted text.
10. **Data safety form:** collect = **Yes** (decided 2026-07-27 — the
    cloud API engine transmits the text to be spoken plus the user's
    API key when a provider is configured). Declare exactly two types:
    App activity → Other user-generated content, and Personal info →
    User IDs; Shared unticked (user-initiated-action exemption relied
    on). Full reasoning + every checkbox in
    [PLAY-CONSOLE-RESPONSES.md](PLAY-CONSOLE-RESPONSES.md) — transcribe
    from there, don't re-derive.
11. **Foreground service declarations** (App content page, each needs
    a use-case text + demo video):
    - `mediaPlayback` (MarmaladeSynthService) — straightforward.
    - `specialUse` (MarmaladeKeepaliveService) — weak fit, moderate
      rejection risk. Decide: keep (it's user-opt-in) or cut the
      keepalive service from the Play build if it bounces.
12. `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`: defensible (uninterrupted
    FGS audio is core function for a TTS engine); explain in review
    notes; the prompt is user-initiated.
13. Content rating questionnaire (IARC) → Everyone.

## Phase 3 — listing assets

14. **DONE:** the Max-approved 7-screenshot set (order: speak-light,
    speak-dark, aliases, kokoro-multilang, engines, effects,
    engines-cloud) ships in
    `fastlane/metadata/android/en-US/images/phoneScreenshots/` — the
    F-Droid set is the Play set. Max wants a stylized pass (device
    frames/captions) for Play *later*; sources in
    `screenshots-inbox/styled/`. Not a launch blocker.
15. **DONE:** `icon.png` (512×512) + `featureGraphic.png` (1024×500)
    are in `fastlane/metadata/android/en-US/images/`.
16. Listing text: **DECIDED 2026-08-09 (Max): the Play copy IS the
    approved F-Droid copy** in `fastlane/metadata/android/en-US/`
    (signed off in the F-Droid listing lab; translations ×7 locales
    alongside). One mechanical conversion at submission: the fastlane
    file is F-Droid HTML (`<p>/<ul>/<li>/<b>/<i>`) — Play's console
    renders only `<b>/<i>/<u>`, so flatten paragraphs/bullets to plain
    lines ("• ") and keep bold/italic. The BUILT FOR SCREEN READERS
    section says "designed to meet WCAG 2.1 AA" — keep that wording,
    not "compliant/conformant", until the on-device TalkBack pass is
    done; grounding in `docs/ACCESSIBILITY-AUDIT-2026-08.md` and
    `docs/WCAG-2.1-AA-SWEEP-2026-08.md`. Screenshots: the approved
    F-Droid set ships as-is; Max wants a stylized pass for Play later
    (device frames/captions) — the styled originals live in
    `screenshots-inbox/styled/`.
    - **[Max, 2026-08-04] Promotion note — call out that the app is
      MULTILINGUAL and name the languages.** The listing copy should say the
      app speaks multiple languages and specify which: via Kokoro it's **9
      locales — English (US), English (UK), Spanish, French, Italian, Hindi,
      Portuguese (Brazil), Japanese, Mandarin Chinese** (source of truth =
      distinct BCP-47 codes in `KokoroDirectVoiceCatalog`; it's "9" because
      American + British English count separately — 8 language names, 9
      locales). Kitten/Pocket are English-only. Also localize the store
      listing itself into the 7 shipped UI locales over time. Don't leave
      "English TTS app" implied. Final public copy needs Max's sign-off.
    - **New since that note (2026-08-08): the app auto-detects the
      language per utterance** — a Kokoro voice reads Spanish with
      Spanish pronunciation with no setting changed (device-verified;
      auto-detect is the Kokoro default, Kitten/Pocket pin English).
      Worth a listing line, and it's the feature that makes the
      multilingual claim land for someone who never opens settings.

## Phase 4 — submit

17. Upload the AAB to a **closed testing** track first; run the
    pre-launch report (catches 16 KB/page-size and crash issues on
    Google's device farm).
18. Fix anything it flags → promote to production (or open testing
    first if the 12-tester rule applies).
19. Post-launch calendar items: **targetSdk 36 required by
    ~Aug 31, 2026** for updates; keep engine-bundle URLs stable
    (catalog pins exact GitHub release assets + SHA-256).

## Reference: policy citations

- Runtime code download ban: https://support.google.com/googleplay/android-developer/answer/9888379
- Data safety: https://support.google.com/googleplay/android-developer/answer/10787469
- Target API: https://developer.android.com/google/play/requirements/target-sdk
- 16 KB pages: https://developer.android.com/guide/practices/page-sizes
- FGS declarations: https://support.google.com/googleplay/android-developer/answer/13392821
