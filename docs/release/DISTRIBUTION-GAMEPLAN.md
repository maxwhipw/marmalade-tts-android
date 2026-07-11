# Distribution gameplan — Play, F-Droid, Izzy, and getting it to users

Sequencing layer on top of [PLAY-RELEASE-PLAN.md](PLAY-RELEASE-PLAN.md),
[FDROID-RELEASE-PLAN.md](FDROID-RELEASE-PLAN.md) and
[PAYWALL-PLAN.md](PAYWALL-PLAN.md) — those stay the step-by-step
checklists; this file decides order, records the Izzy verdict, and
holds the outreach plan. Authored 2026-07-11.

## Channel verdicts

| Channel | Verdict |
|---|---|
| Google Play | Yes — `play` flavor with `marmalade_pro` IAP |
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

1. Finish the device-gated audit tail: Pocket regression listen +
   remaining HANDOFF.md items that touch shipped behavior.
2. Runtime smoke of the **minified release build** (R8) on the Pixel —
   assembleRelease passes but has never been run on device.
3. Engines repo: re-spin bundles without the leftover `libttsespeak.so`
   (Play optics + F-Droid reviewer bait) and add GPL §6 provenance to
   its README (exact espeak-ng tag, build provenance).
4. Refresh screenshots (current ones are 2026-06-08, pre-audit UI) —
   reuse for Play, F-Droid fastlane, and the README.
5. Decide version: promote to `1.0.0` (or `-rc.1`), bump versionCode,
   update changelog, tag annotated `v1.0.0`, push tag. CI builds Play
   AAB + both APKs.
6. targetSdk: Google requires 36 for new apps by ~Aug 31 2026. We ship
   35 — fine if the listing goes live before then, but schedule the
   36 bump regardless.

### Phase B — Play Console (Max-heavy, needs GMS device for testing)

7. [Max] Keystore (SIGNING.md), Play Console account ($25), create app.
8. [Max] Console forms — privacy policy URL, data safety ("no data
   collected"), FGS declarations, IARC. Prepared answers in
   PLAY-CONSOLE-RESPONSES.md.
9. [Max] Create `marmalade_pro` one-time IAP (USD 3.99 per
   PAYWALL-PLAN.md).
10. Upload AAB to closed testing; recruit 12+ testers if the 14-day
    rule applies (church/friends/family + r/androidapps beta-tester
    threads are the usual well). **Max's phone has GMS disabled — all
    Play-flavor testing (install, purchase, refund-flips-isPro) runs
    on Tahlia's phone** or another GMS device.
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
   AudioGames.net forum, AppleVis's Android sibling communities.
   Angle: works as the system engine, accessibility never paywalled
   (that promise is already in PAYWALL-PLAN.md — quote it).
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
