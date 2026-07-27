# HANDOFF — Play Console compliance pass, 2026-07-27 (branch `main`)

## State

Branch **`main`**, head **`087dbb2`**, **pushed to `github/main`**.
**372 unit tests green**; both flavors assemble; release APK verified.

Everything from the previous sessions (paywall removal, targetSdk 36,
Monotone male/female, feature graphic, release lab) is now public on
`main`.

Build: `./gradlew :app:compileFdroidDebugKotlin` (fast check),
`:app:testFdroidDebugUnitTest` (suite), `:app:assembleFdroidDebug` (APK),
`adb install -r` (upgrade in place).

## What this session did

### Cloud consent gate — `8c3b06b`

Engines → Cloud voices now renders **only** a disclaimer until the user
explicitly accepts. One nav route to `CloudApiScreen`, one call site that
writes a key, both behind the gate — verified, not assumed.

- `SettingsRepository.cloudDisclaimerAccepted` / `acceptCloudDisclaimer()`
- `CloudApiViewModel.disclaimerAccepted` is `StateFlow<Boolean?>` — the
  null state matters; a `false` initial would flash the gate for one
  frame on every later visit while DataStore reads.
- Never re-armed when keys are removed: it records "was told", not
  "is using".
- `FakeSettings` overrides it — an unoverridden flow never emits and the
  gate would hang on null.

### Security audit — `a23cac7`

Two real findings, both fixed:

1. **Provider `baseUrl` was unvalidated.** `cloud-providers.json` is
   fetched from the network, so it is remote input — and it is where the
   user's text and API key get POSTed. Now rejected at parse time unless
   `https://`, and rejection fails the **whole document** so the store
   falls back to the bundled asset rather than half-applying a tampered
   list. Manifest states `usesCleartextTraffic="false"` explicitly so a
   dependency merging in `"true"` fails the merge.
2. **Provider HTTP error bodies were reaching logcat.** They were folded
   into the `IOException` message, and synthesis failures log the
   throwable — so a provider echoing the request back in a validation
   error would have put user text in logcat and any bug report. The body
   is now discarded unread; `explainStatus(code)` supplies the message.

Verified against the **built release APK**, not the config: `"input='"`,
`"ipa='"`, and the ja/zh encode lines all strip to **0** occurrences
under R8; the shipped manifest carries `usesCleartextTraffic=false` at
targetSdk 36.

### Docs corrected — several were factually wrong

- **`PRIVACY.md`** — the opening paragraph now carries both halves in the
  first sentence (speaks on-device offline by default, optional cloud
  voices), so a reader who leaves after one line is not misled. Download
  description fixed: `libespeak-ng.so` is compiled into the APK and never
  downloaded, but bundles **do** still carry `espeak-ng-data` — it now
  says voice models + pronunciation dictionaries and states plainly that
  no executable code is ever downloaded. Added the third network path
  nobody had written down: the provider-list fetch, which happens whether
  or not cloud voices are used.
- **`SECURITY.md`** — predated the cloud engine and claimed engine
  downloads were the only network use. Rewritten around the three real
  outbound paths plus the hardening that now exists.
- **`docs/release/PLAY-CONSOLE-RESPONSES.md`** — see below.

## Play Console — the answers, and how they were reached

**Data safety: Yes**, it collects. Two earlier answers were wrong and are
recorded in the doc so nobody re-derives them:

- "Text never leaves the device" — false since the cloud engine (07-24).
- "The prominent-disclosure exemption covers it" — that is a **sharing**
  exemption; the question asks collect **or** share. Collection's only
  exemptions are on-device-only, end-to-end encryption, and ephemeral.

| | Text | API key |
|---|---|---|
| Type | App activity → Other user-generated content | Personal info → User IDs |
| Collected | Yes | Yes |
| Shared | **No** — exemption relied on | **No** — exemption relied on |
| Ephemeral | No | No |
| Purpose | App functionality | App functionality |
| Optional | Yes | Yes |

Encrypted in transit **Yes**. No account creation, no external login.

**The API key is collected** — Max caught this; a 3-model panel confirmed
it. The clause: *"irrespective of whether data is transmitted to you or a
third-party server."* Destination is explicitly irrelevant, which kills
both "it only goes to its issuer" and "the developer has no server".

**Sharing left unticked** — Max weighed declaring anyway and chose to
rely on the user-initiated-action exemption. **If a reviewer challenges
it, tick Shared rather than argue**; the declaration is always available
and costs one listing line.

**Ephemeral No** — OpenAI documents abuse-monitoring retention "up to 30
days", and answering Yes removes the entry from the public listing.

**Target audience** — the IARC questionnaire produced ESRB **Teen or
higher**, locking out under-13. Worth finding which answer caused it, but
13+ is also the safer landing: under-13 pulls in Families policy and
COPPA, which sit badly with a third-party cloud path.

**IARC terms** read and recorded at
`~/.nexus/agent-wiki/coding/store-terms-iarc.md`. Verdict: sign it; the
uncapped indemnity attaches to questionnaire accuracy.

Release tracking lives at
`~/.nexus/agent-wiki/projects/marmalade-tts-play-release.md` and the
interactive checklist at `docs/release/play-release-lab.html`.

## Open — needs Max

- [ ] **The deletion question is still unanswered.** The panel split.
      "No" reads it as being about developer-held data, of which there is
      none; "Yes" is truthful if grounded in in-app key removal plus no
      developer-side retention. **Not** the 90-day option. Whichever is
      chosen, `PRIVACY.md` must match.
- [ ] **Keystore has no durable backup.** `~/secure/marmalade-upload.jks`
      (RSA 4096, alias `marmalade-upload`, mode 600) plus a transit copy
      at `/sdcard/Download/marmalade-upload.jks` on the Pixel that should
      be deleted. Wanted: KeePassXC attachment + a non-git path under
      `~/.nexus`. **Not agent-wiki** — it is a git repo pushed to Forgejo.
- [ ] **`keystore.properties`** is Max's to write (typing passwords into
      a session transcript is the thing to avoid). Four keys, repo root,
      already gitignored. After that the signed AAB is one command:
      `./gradlew :app:bundlePlayRelease`.
- [ ] **marmalade-tts-cli is still unpushed** (head `8ccfff7`) — the
      Megaphone 0.8 / Intercom 0.9 trims and the `next_room` removal that
      mirror what is now public on Android. Needs its own all-clear.

## Unverified on device

Nothing from the last two sessions has been seen on the Pixel: the cloud
consent gate, both Monotone presets (90 Hz male is a new target), the
Megaphone/Intercom trims, the voice picker opening on the engine list,
the inert primary routing strip, the Settings top bar, and edge-to-edge
at targetSdk 36. Store screenshots were shot at targetSdk 35 and may want
re-shooting.

## Older threads still open

- Pocket regression listen test (Max's ears) — StreamPerf on a 4-chunk
  share-sheet run shows only the known slower-than-realtime underrun
  gaps, no new seam signal.
- Device-gated perf work in `docs/AUDIT-2026-07-11.md` "Still open":
  Pocket voice-cond KV snapshot (top RTF item), chunk-0 minChars TTFA
  exemption.
- `REPO-MAP.md` still describes the sherpa-onnx architecture.
- Whether two product flavors still earn their keep now the paywall — the
  original reason they differed — is gone.

## Guardrails

- **Never retune Trailer** — Max signed it off 2026-07-26.
- Max's daily driver is the **release** app (`app.marmalade.tts`); never
  uninstall it and never run `connectedAndroidTest` (it wipes installed
  engines + config). The debug app is disposable.
- Restore any `settings put secure tts_default_synth/tts_default_rate`
  changes after testing (original: `app.marmalade.tts` / rate **307**).
- Any built-in effect chain change needs a `CATALOG_VERSION` bump.
- Effect presets are mirrored in the CLI and must change together.
- **github is public.** Max gives an explicit all-clear per push. Never
  push `origin`/Forgejo by hand.
