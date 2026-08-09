# Distribution gameplan — Play, F-Droid, Izzy, and getting it to users

Sequencing layer on top of [PLAY-RELEASE-PLAN.md](PLAY-RELEASE-PLAN.md),
[FDROID-RELEASE-PLAN.md](FDROID-RELEASE-PLAN.md) and
[PAYWALL-PLAN.md](PAYWALL-PLAN.md) — those stay the step-by-step
checklists; this file decides order, records the Izzy verdict, and
holds the outreach plan. Authored 2026-07-11.

## Channel verdicts

| Channel | Verdict |
|---|---|
| Google Play | Yes — `play` flavor, free, no IAP (paywall withdrawn 2026-07-26) |
| F-Droid | Yes — `fdroid` flavor, everything free, expect `NonFreeNet` |
| IzzyOnDroid | **No — do not submit.** Policy states "We are strongly opposed to apps which are fully or in part created by generative AI tools" (izzyondroid.org/docs/general/AppInclusionPolicy, checked 2026-07-11). This project is openly AI-assisted (Co-Authored-By trailers throughout the history); submitting means either concealment or near-certain rejection. Also 84 MB debug APK vs their ~30 MB rule-of-thumb. |
| GitHub Releases + Obtainium | Yes — free replacement for the Izzy niche. CI already attaches APKs on tag; sideloaders add the repo in Obtainium and get auto-updates. Document it in the README install section. |

F-Droid's inclusion policy has no AI-authorship clause (checked
2026-07-11), but individual reviewers may ask; answer honestly if they
do — MIT source, human-reviewed, audited (docs/AUDIT-2026-07-11.md),
unit-tested, device-verified.

## Order of operations

Rationale: Play's closed-testing clock (new personal accounts need a
12+ tester / 14-day closed test before production) is the long pole —
start it first and run F-Droid's review (typically weeks in the MR
queue) in parallel. Don't announce anywhere until at least one store
listing is live, so there's a one-tap install link.

### Phase A — cut the release candidate (repo work, mostly Claude)

1. ~~Pocket regression data~~ DONE 2026-07-11 (StreamPerf on a 4-chunk
   share-sheet run shows the KNOWN slower-than-realtime underrun gaps,
   no new seam regression signal). **[Max] listen test still open** —
   share a paragraph with a Pocket alias and listen for chunk-seam
   artifacts.
2. ~~R8 runtime smoke~~ DONE 2026-07-11 via `-PsmokeRelease`
   (side-by-side `.rc` install): onboarding, engine download/extract,
   synthesis + playback, system-TTS negotiation all pass minified.
3. ~~Engines re-spin + GPL §6~~ DONE 2026-07-11: engines repo release
   v22 (kitten/kitten-mini/kokoro without libttsespeak.so, espeak data
   rebuilt from the 1.52.0 tag; pocket clean on v21), catalog pinned
   (atom AD), NOTICE.md version-split note resolved, engines README
   rewritten with §6 provenance. v16→v22 update flow device-verified.
4. ~~Screenshots~~ DONE 2026-07-11: fresh 5-screen set in fastlane +
   the README's docs/screenshots/ (which had been EMPTY — broken
   embeds since the README was written). **Superseded 2026-08-08:**
   the fastlane set is now stale against the shipped UI; a 10-shot
   styled replacement waits in `screenshots-inbox/styled/` for Max's
   keeper picks (F-Droid gate G1).
5. **[Max] Decide version**: promote to `1.0.0` (or `-rc.1`), bump
   versionCode, update changelog, tag annotated `v1.0.0`, push tag.
   CI builds Play AAB + both APKs.
6. ~~targetSdk~~ DONE: Google requires 36 for new apps by ~Aug 31 2026
   and the tree now ships `compileSdk`/`targetSdk` 36 (minSdk 28), so
   the deadline no longer constrains the listing date.

### Tag-readiness snapshot — 2026-08-08

Where the tree stands against Phase A, and the raw material for
`changelogs/10000000.txt` (which still has to be *written*, and is
public copy, so it needs Max's sign-off). ~96 commits sit between
`v1.0.0-beta.1` and `main`; the user-visible ones since 2026-08-07:

- **Automatic language detection** — own char-trigram + script
  detector sharing an 81 KB table with the CLI; per-utterance, replaces
  request-language voice switching. Auto-detect is the Kokoro default,
  Kitten/Pocket pin English, and Kitten renders a detected language
  when no Kokoro voice can. Device-verified for Spanish on a Kokoro
  voice; ja/zh verified by unit test only.
- **Onboarding rework** — a device-capability probe (CPU topology score
  + a one-shot benchmark on the baked-in Kitten engine) labels each
  engine card as recommended / fine / may-be-slow for *this* phone, and
  Kitten now reads as "Built in — ready to use" instead of faking a
  download. Card order never changes.
- **Engines tab redesign** — On-device / Cloud segmented control, spec
  columns with a traffic-light speed meter, "See all 9" languages
  affordance.
- **In-app privacy policy** at Settings → About (Play Console item 9).
- **Launcher icon** — flat background (`ef748f2`); system-bar icon
  contrast now follows the in-app theme.
- **F-Droid buildability** — `generateEspeakData` resolves the SDK's
  pinned cmake rather than a bare `$PATH` one (`a0239ed`), removing the
  buildserver's missing-cmake risk.

Not yet done for the tag: G1 keeper picks, G5 fastlane translations,
the versionCode formula switch (G4 → 10000000, applied *at* the cut),
the engines bundle re-spin, and the RB decision (F-Droid R3).

### Phase B — Play Console (Max-heavy, needs GMS device for testing)

7. [Max] Keystore (SIGNING.md), Play Console account ($25), create app.
8. [Max] Console forms — privacy policy URL, data safety ("no data
   collected"), FGS declarations, IARC. Prepared answers in
   PLAY-CONSOLE-RESPONSES.md.
10. Upload AAB to closed testing; recruit 12+ testers if the 14-day
    rule applies (church/friends/family + r/androidapps beta-tester
    threads are the usual well). **Max's phone has GMS disabled — any
    Play-flavor install testing runs on Tahlia's phone** or another
    GMS device. There is no purchase flow left to test.
11. Pre-launch report → fix → production.

### Phase C — F-Droid (parallel with B)

12. Follow FDROID-RELEASE-PLAN.md phase 2: fork fdroiddata, add
    `metadata/app.marmalade.tts.yml` against the v1.0.0 tag
    (`gradle: [fdroid]`, `submodules: yes`), open the MR with the
    licensing story stated up front (MIT source / GPL-3.0-or-later APK,
    opt-in model downloads, NonFreeNet expected).
13. Respond to review; AutoUpdateMode handles future tags.

### Phase D — sideload channel

14. README "Install" section: GitHub Releases APK + Obtainium
    instructions + which flavor APK to pick (fdroid flavor for
    sideloaders — no billing).

## Outreach — who actually needs a private on-device TTS engine

Lead with what it does for the reader, not the tech: natural neural
voices, fully offline, free, no account, open source. One channel at a
time, each with the store link + a 30–60 s screen recording with audio
(the voice IS the pitch — every post should let people hear it).

**Prep once, reuse everywhere:**
- Voice-samples page (GitHub Pages on the repo): one clip per engine ×
  a few voices, the effects presets, and a speech-rate demo.
- Short demo video: share-sheet a news article → instant speech;
  switching voices; per-app routing.
- The mobile-TTS-techniques blog post already planned in the wiki
  (open-source-publishing note) doubles as the HN/Lobsters artifact.

**Audiences, in order of fit:**
1. **Screen-reader / low-vision users** — TalkBack users live in TTS
   all day and hate that quality voices need Google/network. r/Blind,
   AudioGames.net forum, the Blind Android Users community/podcast.
   Angle: works as the system engine, every feature free — nothing an
   assistive-tech user needs sits behind a purchase — and the app's own
   UI is fully TalkBack-accessible (audited + fixed 2026-08-01/02, see
   `docs/ACCESSIBILITY-AUDIT-2026-08.md` + `docs/WCAG-2.1-AA-SWEEP-2026-08.md`;
   designed to WCAG 2.1 AA, dark theme carries AA contrast).
   **Gate before posting here:** a real on-device TalkBack pass (still
   pending), and ideally one actual screen-reader user's session —
   these communities are the harshest reviewers of accessibility
   claims, and rightly so. Wording rule: "designed to meet WCAG 2.1 AA"
   until the device pass is done; never bare "WCAG compliant".
2. **Read-aloud / ebook people** — MobileRead forums, @Voice Aloud +
   Moon+ Reader + Librera + KOReader user communities. Angle: point
   your reader's TTS at Marmalade, get Kokoro-quality narration
   offline.
3. **FOSS Android crowd** — r/fossdroid, r/FDroid (once listed),
   r/androidapps ("I made a…" flair), XDA thread, Mastodon
   (#FOSS #Android #TTS — the F-Droid account and FOSS bots boost new
   listings organically).
4. **Local-AI crowd** — r/LocalLLaMA and friends; angle: modern neural
   TTS running fully on-device (ONNX Runtime, no cloud), pairs with
   local-LLM voice pipelines.
5. **Show HN / Lobsters** — repo + blog post + samples page. Expect
   AI-authorship questions in every FOSS venue; the stance is simple
   and honest: yes, built with Claude, openly credited in the history,
   human-directed, audited and device-tested, MIT.
6. **Listings that compound quietly** — AlternativeTo (alternative to
   Google Speech Services / SherpaTTS / RHVoice), the F-Droid forum
   new-apps thread.

**Cadence:** stagger posts over weeks, not one blast day — each
community's feedback improves the next post, and store review cycles
will interleave anyway.
