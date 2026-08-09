# F-Droid release plan — marmalade-tts-android

Corrected plan as of 2026-08-08. Live tracker with checkboxes + copy
deck: [fdroid-lab.html](fdroid-lab.html). Durable app-agnostic
knowledge: agent-wiki `tech/coding/fdroid-publishing.md` + the
`fdroid-publishing` skill.

**Current state: MR !44636 is OPEN and ON HOLD.** Opened 2026-08-02
(fork `maxwhipw/fdroiddata`, branch `marmalade-tts`), CI fully green
including `fdroid build`. **That green run is stale evidence** (Opus
review 2026-08-07): main is ~96 commits past the built tag and now
contains the build-time espeak-data generator
(`tools/espeak-hostgen/`, `generateEspeakData` in
`app/build.gradle.kts`), which the buildserver has never exercised.
It used to shell out to a bare host `cmake` from `$PATH`, which the
F-Droid buildserver may not provide — **RESOLVED 2026-08-08 in
`a0239ed`**: the task now prefers the SDK's own pinned
`cmake/3.22.1/bin/cmake` and only falls back to `$PATH`, so no
`sudo: apt-get install` line is needed in the recipe. (The fix sat
uncommitted in the working tree for a day; it is now on `main`.)
Still required before pinging the reviewer: re-run `fdroid build`
(their CI or a local buildserver) against the new tag — the generator
has still never run on their infrastructure. It builds the `v1.0.0-beta.1` tag
(versionCode 33), which is premature: F-Droid reads icon, screenshots
and descriptions from the fastlane folder **in the tag it builds**, and
that tag's screenshots show a visually older app. Reviewer `linsui`
(2026-08-03, label `waiting-on-response`) asked for two things, both
deliberately unanswered until the re-cut:

1. Use the **App Inclusion** MR template and tick its boxes.
2. `commit:` must be the **full 40-char commit hash**, not a tag.

The fork/branch/MR stay open; when the finalized tag exists, the recipe
is updated **on the same branch** and the same MR proceeds. Nothing is
pushed to the MR without Max's sign-off (public surface). No interim
holding note to the reviewer (Max, 2026-08-07) — the next MR activity
is the complete corrected re-cut.

> **F-Droid builds the `fdroid` product flavor** (`gradle: [fdroid]`).
> Both flavors are fully free (paywall withdrawn 2026-07-26, billing
> dependency removed); the fdroid flavor only adds the GitHub Sponsors
> link in About.

## Blocking gates before cutting the 1.0.0 tag

Everything below must be in the commit we submit — graphics and
metadata ship from the tag.

- **G1 Screenshots** — SHOT 2026-08-08 (Max, 11 candidates) and styled
  in the Play treatment (status bar cropped, black 1200×2400 frame):
  `screenshots-inbox/styled/` (10 styled: speak light/dark, aliases,
  Kokoro voices ×2, effects, effect editor, engines, engines-cloud,
  voice picker). **9 of the 10 survive the 2026-08-08 UI landings**
  (re-checked against the device that evening): the styled shots were
  taken from a build that already had the engines-tab redesign now
  committed in `e31b34d`, and the set contains no onboarding shot, so
  the same-day onboarding rework (built-in Kitten card, device-probe
  labels) doesn't invalidate any of them. **`07-engines.png` needs a
  re-shoot:** `3e4b075` (later the same night) rewrote Kitten's tagline
  from "The quickest to start speaking, and the smallest download." to
  "The fastest, most responsive engine, and it runs on any device.",
  and that shot shows the old line. It's the only styled shot that
  renders an engine tagline. **Re-shot 2026-08-09** (Max) and styled in
  `dadb247` — but the reshoot arrived via chat at 900×2000 and was
  upscaled; before the set ships, restyle from the original 1080×2400
  (drop it in `screenshots-inbox/` or pull via ADB). Remaining: Max
  picks keepers + order in the **F-Droid listing lab**
  (http://marmalade:8095/marmalade-tts-release/fdroid-listing-lab.html
  — mock listing, picker persists in-browser; 4–8 is typical), then
  copy the picks into
  `fastlane/metadata/android/en-US/images/phoneScreenshots/`.
  *Standing rule: engine copy changes stale the engines screenshots —
  check this gate whenever a tagline or spec string moves.*
- **G2 Icon** — RESOLVED 2026-08-08 (Max): icon + mascot are good
  enough to launch as-is, with one change — flat background (amber ramp
  at depth 0.34, `#f97a20`), shipped in `ef748f2` incl. regenerated
  fastlane icon. No further icon work blocks the tag.
- **G3 Engines** — Kitten nano + Pocket finalization; the tag must
  contain the final engine builds.
- **G5 Fastlane translations** — the app UI ships 8 locales but
  fastlane has only `en-US`, and F-Droid's Latest-tab visibility gate
  requires **at least one translation** of the listing texts. Because
  fastlane ships from the built tag, adding a locale later costs a
  whole release. Sequencing (Max, 2026-08-07): **first Max signs off
  the final English listing text** (title, short + full description —
  public copy), **then** translate it into all supported in-app
  languages as `fastlane/metadata/android/<locale>/` folders. Both
  steps land before the tag. The sign-off surface is the F-Droid
  listing lab (URL under G1): current fastlane copy rendered in a mock
  listing, plus a highlighted proposed multilingual/auto-detect section
  awaiting Max's verdict.
- **G4 Versioning** — DECIDED 2026-08-03: ship **1.0.0**,
  `versionCode = MAJOR*10_000_000 + MINOR*10_000 + PATCH*10 + ABI`
  → 1.0.0 universal = **10000000**. ABI digit reserved from the start
  (0=universal, 1=armv7, 2=arm64, 3=x86, 4=x86_64). Encode as a Gradle
  helper + comment at the 1.0.0 cut. Drop the `-beta` suffix for the
  public release.

## R — Reproducible builds (decided: do it)

Near one-way door: F-Droid can't switch an app to developer-signed
after first publishing F-Droid-signed. Payoff: F-Droid / GitHub /
Obtainium APKs share one signature and cross-update.

- **R1 Fix release CI** — release.yml design is right (signed fdroid
  APK attached to the GitHub Release at a stable URL + SHA256SUMS) but
  had never produced a signed APK: workflow-file bug fixed 2026-08-03
  (`1169c0f`, needs GitHub push) and the four signing secrets are not
  yet configured (Max, per [CI-SIGNING.md](CI-SIGNING.md)). Key
  decision: reuse `marmalade-upload.jks` (strong PKCS12, backed up) as
  the permanent distribution key. **Signing verdict (2026-08-07,
  3-reviewer panel, empirically round-trip-tested on a real APK): keep
  Gradle `signingConfig` exactly as-is.** The apksigner-≥35 problem
  (fdroiddata#3299 — `0xd935` alignment fields apksigcopier can't
  reproduce; apksigcopier is archived/wontfix) applies only to the
  standalone apksigner binary. AGP signs in-process via the apksig
  library with zero padding, and an AGP-8.9.3-signed APK verifies with
  apksigcopier 1.1.1 (proven both by two independent reviewers). The
  earlier "build unsigned + sign with build-tools 34" idea is
  withdrawn — its zipalign step would have silently broken 16 KB page
  alignment. Instead add cheap CI guards after `assembleFdroidRelease`:
  `apksigcopier extract` must succeed, `zipalign -c -P 16 -v 4` must
  pass, fail on any `0xd935` local-header field. Pre-tag, decide two
  signing-config details **before** the cert is pinned forever in
  `AllowedAPKSigningKeys`: whether to set `enableV3Signing = true`
  (rotation lineage; current release APK signs v2-only) and
  `dependenciesInfo { includeInApk = false }` (F-Droid scanners dislike
  the encrypted Google dependency block).
- **R0 Reproducibility risk register** (what breaks first, in order):
  (a) host-compiled espeak dictionaries — produced by a host gcc/glibc
  binary, the least-controlled input in the build; (b)
  `generateEspeakData` runs `cmake --build -j <availableProcessors>`
  with all dicts sharing one `dictsource` working dir —
  core-count-dependent ordering; (c) no Gradle dependency locking — a
  transitive published between our CI run and F-Droid's rebuild
  changes bytes; (d) NDK object determinism across ABIs — no
  `--build-id=none` / `-ffile-prefix-map` flags are set today, and
  embedded build paths differ between the GitHub runner and F-Droid's
  buildserver (F-Droid's most-cited native RB failure); (e) baseline
  profiles — `androidx.profileinstaller` is in the graph, so release
  APKs likely embed `assets/dexopt/baseline.prof(m)`, a documented
  nondeterminism source (workaround: disable `*ArtProfile*` tasks);
  (f) unpinned `ubuntu-latest` runner drift; (g) R8 (least risky —
  pinned by AGP). Fixing (b) and adding dependency locking are worth
  doing regardless of RB. (b) is FIXED and now fully committed:
  `generateEspeakData` serialises with `-j 1` (2026-08-07) and resolves
  the SDK's pinned cmake instead of `$PATH` (`a0239ed`, 2026-08-08 —
  the change existed only in the working tree when this register was
  first written). Watch item: fdroidserver#1354
  (open) — signature copying broken on newer Fedora/Debian, could cause
  spurious verification failures on their side.
- **R2 Recipe fields** —
  `Binaries: https://github.com/maxwhipw/marmalade-tts-android/releases/download/v%v/marmalade-tts-%v-fdroid.apk`
  + `AllowedAPKSigningKeys: <sha256>` (from `apksigner verify
  --print-certs` on the first CI-signed APK, or `keytool -list -v`).
- **R3 Prove reproducibility** — verify with `fdroid build` +
  `reproducible-apk-tools` against the R0 register before claiming it.
  If it fails, the RB fields (R2) are **omitted** from the recipe, not
  included.
  - **Local two-path experiment RUN 2026-08-09** (full evidence:
    `~/coding/scratch/rb-experiment/FINDINGS.md`): two clean worktrees
    at different absolute paths, sequential `assembleFdroidRelease`,
    per-entry sha256. **688/694 entries byte-identical** — espeak
    dictionaries (a)(b), classes.dex (g), baseline profiles (e), zip
    metadata all reproduced. The ONLY diffs are the six natively-built
    `.so` (register (d)): linker build-ids + absolute `__FILE__` paths
    in libopenjtalk-jni (path strings reorder LLD's `.rodata` string
    merge, cascading ~35 KB — must be fixed at compile time with
    `-ffile-prefix-map` + `-Wl,--build-id=none`, not post-processed).
    Still unproven locally: (a) across a different host gcc/glibc,
    (c) dependency drift, (f) runner drift — only a real `fdroid build`
    closes those.
  - **Round 2 (same day): both fixes VERIFIED and LANDED (`b7cfb9e`).**
    With the lint task-dependency fix + `-ffile-prefix-map` +
    `--build-id=none`, two clean builds from different paths produce
    **byte-identical APKs** (whole-file sha256 match, 694/694 entries,
    no lint exclusions, 16 KB alignment re-verified). The clean-tree
    `assembleFdroidRelease` failure (lint consumed generated
    espeak/privacy assets with no task dependency — would have broken
    CI release.yml AND F-Droid's buildserver) is fixed in the same
    commit. Remaining R3 work = the real `fdroid build` run for items
    (a)/(c)/(f).
- **OPEN DECISION (Max): does RB failure gate the listing?** The
  one-way door means falling back to F-Droid-signed forfeits RB for
  `app.marmalade.tts` permanently (no signing-key change without
  reinstall). Option A: hold the MR and iterate until the build
  reproduces (defensible given irreversibility; costs reviewer wait
  time). Option B: ship 1.0.0 F-Droid-signed and accept the split
  signature forever. The plan previously said both "we try first" and
  "fallback is normal" without choosing — this is the actual call.
  Context: RB is rare in this app class (only Muse among comparable
  recipes), so Option B is respectable; but note RB also carries a
  **permanent per-release cost** (see Maintenance).

## Submission re-cut (after gates + R)

1. Cut annotated `v1.0.0` tag (versionCode 10000000) with final UI,
   screenshots, icon, engines, `changelogs/10000000.txt`.
2. Update `metadata/app.marmalade.tts.yml` on the `marmalade-tts`
   branch: full 40-char commit hash (`git rev-parse v1.0.0^{commit}`),
   versionName/versionCode **and `CurrentVersion`/
   `CurrentVersionCode`**, RB fields (R2, only if R3 passed),
   `UpdateCheckMode: Tags ^v\d+\.\d+\.\d+$` (bare `Tags` would
   auto-publish the next `-beta` tag), keep `submodules: true`
   (literal boolean), `gradle: [fdroid]`,
   `scanignore: third_party/espeak-ng/phsource`, `ndk: r26d`, canonical
   field order via `fdroid rewritemeta`. Two upgrades from the
   comparable-app survey (2026-08-07): switch the anti-feature to the
   per-locale dict syntax so the reason renders on the listing —
   `AntiFeatures: {NonFreeNet: {en-US: "Downloads voice models from
   GitHub Releases on explicit opt-in."}}` (SherpaTTS/Supertonic
   convention) — and use the **Text to Speech** category (SherpaTTS
   precedent; better discoverability than Multimedia/Reading).
   eSpeak NG's own recipe (`com.reecedunn.espeak.yml`) is the closest
   native-build precedent — it compiles the same espeak-ng from source
   in the F-Droid build.
3. Rewrite the MR description with the **App Inclusion template**,
   boxes ticked honestly (author = submitter). Reply to linsui.
   **Max signs off on all public MR text first.**
4. Respond to review; listing appears automatically post-merge.

## Maintenance after listing

- Future releases: bump versionCode/versionName per the G4 formula,
  tag, push — `AutoUpdateMode: Version` + tag-regex UpdateCheckMode
  handle updates with no new MR.
- **If RB shipped, every future release carries it forever:** the
  GitHub Actions run must finish and upload the release asset before
  F-Droid's build cycle picks up the tag (else the `Binaries:` URL
  404s), and every release must reproduce byte-for-byte — an
  irreproducible release means F-Droid users get *nothing* for that
  version (no fallback to F-Droid signing; key changes are forbidden).
  Also the `Binaries:` template hard-couples tag name to
  `v<versionName>` and release.yml derives the asset filename from the
  tag — any tag not exactly `v<versionName>` silently breaks the URL.
- Engines-repo releases stay immutable (catalog pins SHA-256 per
  asset).
- Keep Play/F-Droid **base** versionCodes coordinated. (If F-Droid
  ever ships per-ABI APKs the last digit diverges by design — Play's
  AAB keeps one code for all ABIs. Only strict increase matters to
  Play.)
- Post-listing: install from F-Droid on-device, smoke-test engine
  install + TTS output; add the F-Droid badge to the README and fill
  the launch-lab copy placeholders.

## Reference

- MR: https://gitlab.com/fdroid/fdroiddata/-/merge_requests/44636
- Inclusion policy: https://f-droid.org/en/docs/Inclusion_Policy/
- Anti-features: https://f-droid.org/en/docs/Anti-Features/
- Build metadata reference: https://f-droid.org/en/docs/Build_Metadata_Reference/
- Reproducible builds: https://f-droid.org/en/docs/Reproducible_Builds/
- Precedent (SherpaTTS, NonFreeNet): https://gitlab.com/fdroid/fdroiddata/-/blob/master/metadata/org.woheller69.ttsengine.yml
