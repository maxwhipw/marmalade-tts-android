# HANDOFF — brand + effects + voice picker, 2026-07-26 (branch verify/routing-on-api-engine)

## Branch state

Working branch **`verify/routing-on-api-engine`**, head **`e8272e5`**, 6 commits
past `21aebf3`. **367 unit tests green** (`:app:testFdroidDebugUnitTest`);
fdroid debug APK builds. **Nothing pushed** — github is the authoritative
public remote and needs Max's explicit all-clear each time.

Wireless ADB rotates its port every session; ask Max for the current one and
`adb connect 100.114.195.29:<port>`.

## What shipped today

- `0f3e9fa` **Wordmark = Momo Trust Display.** The font was sitting unused in
  `stash@{0}` behind a comment claiming it "isn't freely distributable" — it is
  (OFL-1.1, Google Fonts, © 2024 The Momo Trust Project Authors, read verbatim
  from the TTF name table). Momo ships a *single Regular master* (no `fvar`,
  `usWeightClass 400`), so the 600 at the call site is synthesized — the
  marmalade-design skill has been corrected to say so. `LICENSES/fonts.md`,
  `NOTICE.md` and the in-app `LicenseCatalog` all updated.
- `cadb464` **8-bit preset retuned** — Bitcrush 6/6 → 8/8, LP 4000 → 3450.
  `CATALOG_VERSION` 25 → 26 so existing installs re-seed the built-in row.
- `23cb703` **Alias cards ordered** primary → routed → unrouted (stable sort,
  creation order preserved inside each group).
- `ab677ae` Desktop-CLI reference dropped from the effect editor's chain blurb.
- `f1fbebb` **Effects: per-card play/stop**, user effects sorted above built-ins
  (in `EffectDao.getAll`, so the alias editor's effect picker agrees), and the
  FAB takes the same explicit primary/onPrimary colors the Aliases FAB had.
- `e8272e5` **Full-screen voice picker now drills down** like the alias sheet.
  It was one flat list grouped by engine — with Venice configured, ~186 voices
  under a single header. Shared logic extracted to `ui/screen/VoiceTree.kt`
  (tree build, level-skipping nav, cross-level search) and
  `ui/screen/VoiceDrillDown.kt` (source/model lists, latency chip).

## Verified on device (Pixel 8a)

Via `uiautomator dump` + pulling the Room DB with `run-as … cat databases/…`:

- `builtin:eight_bit` re-seeded to `bitcrush 8/8` + `lowpass 3450`.
- Alias order: Maximilian (PRIMARY) → default (2 apps) → Venice (unrouted).
- Effects screen: user effects (`8-bit-v2`, `test`) above the built-ins;
  `Preview <name>` is the leftmost action on every card.

## NOT verified on device

Wireless ADB dropped before the last install. Unseen:

- The Momo wordmark actually rendering (only the resource is confirmed present).
- Effects FAB colour; the editor's reworded chain line.
- **The whole voice-picker drill-down rewrite** — biggest untested surface.
  Worth walking: Speak → voice name → drill Venice → a model → a voice;
  the search box; system Back unwinding one level at a time; and the
  engine-scoped entry (`voices?engine=…`) from an engine's detail page.

## Traps worth knowing

- `FakeSettings` extends the real `SettingsRepository` over a
  `NoOpPreferencesDataStore` that **never emits**. Any member it doesn't
  override never emits, and a `combine()` including one never emits at all —
  the test HANGS for 60s and reports `UncompletedCoroutinesError` pointing at
  the test body, not the missing override. It now overrides
  `showDeveloperEngines` + `anyCloudApiKeySet` for exactly this reason.
- `stash@{0}` still holds ~71 lines of unrelated `KittenDirectEngine.kt` WIP
  plus a stale copy of the font work. Only the `.ttf` was taken from it.
- `fallbackToDestructiveMigration()` is still armed at `AppModule.kt:80` —
  any schema hash drift wipes aliases + routes. Write real migrations.

## Still open (carried forward)

- `MarmaladeSynthService` **cannot play cloud voices at all** (`:308-319`,
  `:389-398`, `:536`, `:552`, `:561`) — they fall through to `DEFAULT_ENGINE`
  and speak Kokoro. Breaks share-to-speak, the QS tile, `SpeakDispatcher`.
  Pre-existing, untouched.
- Do the MP3 models honour `speed`? They ignore `response_format`, so they
  may no-op the speed slider.
- `CloudApiVoiceCatalog.kt:72-73` stamps every discovered voice `en-US`,
  including multilingual MiniMax/Gemini sets.

---

# HANDOFF — voice hierarchy + alias screen, 2026-07-25 (branch verify/routing-on-api-engine)

## Branch state

Working branch **`verify/routing-on-api-engine`**, head **`21aebf3`**.
351 unit tests green on both flavors; fdroid debug APK **built but NOT
installed** — wireless ADB dropped mid-session and the port rotates, so
re-pair and `./gradlew :app:installFdroidDebug` before eyeballing anything. **Nothing pushed** — github is the authoritative public
remote and needs Max's explicit all-clear each time.

Room schema is now **v9**. The migration was verified on Max's real device:
`user_version 9`, both aliases survived, 232 voice rows (151 cloud), zero
app routes lost. `fallbackToDestructiveMigration()` is still armed at
`AppModule.kt:80`, so any future hash drift wipes aliases + routes — write
real migrations.

## What shipped today

**Cloud engine** (`33f960f`, `f09bd6c`)
- Model **allowlist** replaces the substring blocklist; discovery joins onto
  it by id instead of replacing it wholesale. Fails closed.
- Descriptor is schema **v2** and the version is now read — a cached remote
  copy only wins if `>=` the bundled one. Previously the cache won forever.
- Per-voice **sample rate**; `CloudApiEngine` checks the response against
  the model's declared rate.
- **MP3 decode** via `MediaCodecAudioDecoder` behind the
  `CompressedAudioDecoder` seam. 7 of Venice's 11 models now usable.

**Design lab "tts-voice-hierarchy"** — signed off by Max 2026-07-25, decision
text in `~/coding/marmalade/design-lab/labs.json`. Shipped over `58376bd`,
`307491f`, `a5b8f6d`, `d37ded1`:
- `VoicePath` / `VoicePathResolver` — one source › model › voice hierarchy,
  middle level collapsed when it carries nothing.
- **Drill-down voice picker** (`VoicePickerSheet`) replacing a 156-entry
  dropdown; search cuts across levels; model step skipped for on-device.
- Alias editor has **one Voice row**, no engine dropdown, no branching on
  cloud vs on-device.
- **Offline fallback** — `VoiceAlias.fallbackAliasName`, auto-armed with the
  primary on-device alias when a cloud voice is chosen.
- `surfaceContainer*` roles defined (the lavender was Material's baseline
  palette leaking through `Card`/`NavigationBar`).
- Card polish: PRIMARY pill top-right in accent orange, compact MetaChips,
  no star (promote lives in the editor as "Make primary"), rounded-square
  app icons, synthetic "all apps" tile for the primary, terser copy.

**Alias screen polish from device review** (`aff7f41`, `502db25`)
- App icons: `AdaptiveIconDrawable.draw()` applies the *device* mask (a
  circle), so the tile held a floating disc. We now paint background +
  foreground ourselves and let the tile's own corners do the shaping —
  every icon is full-bleed and centred. `AppIconTile` is the single
  implementation; `AppRoutingRow` no longer duplicates it.
- Alias editor: back to the original text-button row (Max reverted the
  pills), Cancel stays removed. Alias names are no longer quoted in the
  editor title or the delete confirmation. Effect is a `PickerField` —
  the whole field taps, matching the Voice row; a read-only
  `OutlinedTextField` only answered taps on its trailing icon.
- The PRIMARY pill sits hard right. The name had `weight(1f, fill =
  false)` followed by a weighted Spacer, and two weighted siblings split
  the leftover evenly — landing the pill mid-card.
- Settings: "More from Marmalade" → "More Marmalade".

**Voice-picker speed badge** (`51dd005`)
- "Instant" / "Quick" / "Slow" on the picker's source and model rows, and
  on search hits. Tracked per MODEL (`latencyKeyFor`), never per voice.
- Seeded by a `latency` field in `cloud-providers.json` — remotely
  fetchable and version-gated, so correcting a provider that got faster
  is a JSON publish, not a release. Seeds from Max's Pixel 8a ranking:
  Venice Kokoro instant; ElevenLabs / MiniMax / xAI quick; Inworld and
  Gemini 3.1 Flash slow; Gradium instant. **Both OpenAI models unseeded**
  — nobody has timed them.
- Overridden per-device by the median of the last 10 measured
  time-to-first-audio samples once a model has 3. Timed in
  `Synthesizer.streamForEngine`; samples only taken for 10–120-char
  utterances. Cut points live in `LatencyBucket` (600 ms / 1800 ms) and
  are calibrated against Max's ranking, not measured — worth revisiting
  once real numbers land.
- `MarmaladeTtsService` is instrumented too, but **metered**: three
  samples per model per week (`recordMetered` → `claimLatencyQuota`).
  Passive only — it times synthesis another app already asked for and
  never issues a request of its own, so no provider is billed for a
  measurement. In-app Speak and the picker preview stay unmetered.
- **`MarmaladeSynthService` is deliberately NOT instrumented.** It can't
  route cloud voices yet and falls through to `DEFAULT_ENGINE`, so a
  sample there would file Kokoro's speed against whichever cloud model
  the user picked. Instrument it when that bug is fixed, not before.
- `VoicePickerScreen` (the standalone browse screen off Speak) has no
  badge — only `VoicePickerSheet` does.
- Removed `Synthesizer.synthesizeForEngine`, dead since the streaming
  path landed. It rode along in `51dd005` rather than getting its own
  commit.

## Known limits, deliberate

- **Fallback can't rescue a rate mismatch.** The retry only fires when the
  fallback's sample rate matches what `callback.start()` already committed;
  the framework can't revise it mid-request, so a 48 kHz cloud voice
  (Gradium, Inworld) logs and reports the original error instead of playing
  at the wrong pitch. Lifting this needs deferred `start()`.
- **Re-routing an app now takes two steps.** A row owned by another alias is
  disabled rather than steal-able (Max's call, 2026-07-25). Un-route it
  where it lives first.

## Not verified on device

Everything below builds, installs, migrates and runs, but has **not been
eyeballed**:
- the drill-down picker, the one-row editor, the fallback field
- MP3 decode actually producing audio (MediaCodec is the one thing unit
  tests can't cover — Robolectric's shadow doesn't decode)
- **the 48 kHz path — and a wrong rate is INAUDIBLE on the Speak screen**,
  which rebuilds its AudioTrack from the chunk rate. Verify a Gradium voice
  through Settings → Text-to-speech → Play example, or a third-party app.

## Still open

- `MarmaladeSynthService` **cannot play cloud voices at all** (`:308-319`,
  `:389-398`, `:536`, `:552`, `:561`) — they fall through to `DEFAULT_ENGINE`
  and speak Kokoro. Breaks share-to-speak, the QS tile, `SpeakDispatcher`.
  Pre-existing, untouched.
- Wordmark font: pop `stash@{0}`, bundle Momo Trust Display **400** (OFL-1.1,
  © 2026 The MoMo Trust Display Project Authors), add `LICENSES/fonts.md`,
  and fix `Type.kt:14`'s false claim that it isn't freely distributable. The
  design doc's "600" is wrong — that weight does not exist.
- Do the MP3 models honour `speed`? They ignore `response_format`, so they
  may no-op the speed slider.
- `CloudApiVoiceCatalog.kt:72-73` stamps every discovered voice `en-US`,
  including multilingual MiniMax/Gemini sets.

# HANDOFF — Venice capability audit, 2026-07-24 (branch verify/routing-on-api-engine)

## Branch state

Working branch: **`verify/routing-on-api-engine`**, merge commit **`5674aa4`**
(merges `main`'s alias-routing redesign into the `api-engine` work so one
build carries both). `api-engine` and `main` are untouched; `stash@{0}` is
still parked. 328 unit tests green on both flavors; fdroid debug APK built
and installed on the Pixel 8a.

**Not pushed.** github is the authoritative public remote and needs Max's
explicit all-clear each time.

## Reported bug: "Cloud API response is not a WAV stream"

Max picked voice `InspirationalGirl` on the Speak screen and got that error.
Root cause is NOT a bad request — Venice returned **HTTP 200 with a valid
MP3**. `WavStreamHeader.parse` sniffed for RIFF, didn't find it, threw.

`cloud-providers.json` carries `modelExclude: ["tts-qwen3"]` and
`discoverVoices: true`. Venice now serves **11 TTS models / 186 voices**;
`CloudProviders.kt:108` blocklists by substring, so every model Venice adds
appears in the picker and is *presumed* WAV/24 kHz/streaming until it
fails. Discovery fails open — that is the structural bug.

## Measured capability matrix (live against api.venice.ai, 2026-07-24)

Request was always `response_format:"wav", streaming:true`.

| Model | Body | Rate | Warm TTFB | Streams | Works today |
|---|---|---|---|---|---|
| `tts-kokoro` | WAV | 24k | 0.9–1.5s | yes | **yes** |
| `tts-xai-v1` | WAV | 24k | 2.9s | no | **yes** |
| `tts-gradium-v1` | WAV | 48k | 0.4–1.4s | yes | no — rate |
| `tts-inworld-1-5-max` | WAV | 48k | 4.5s | no | no — rate |
| `tts-gemini-3-1-flash` | MP3 | 24k | ~7s | no | no — codec |
| `tts-minimax-speech-02-hd` | MP3 | 32k | 3.4–4.5s | no | no — codec+rate |
| `tts-elevenlabs-turbo-v2-5` | MP3 | 44.1k | 1.8s | no | no — codec+rate |
| `tts-orpheus` | WAV | — | 7.2s | no | no — 60s timeout when cold |
| `tts-chatterbox-hd` | WAV | — | 15.1s | no | no — 60s timeout when cold |
| `tts-qwen3-0-6b` / `-1-7b` | MP3 | — | — | no | no — already excluded |

Only **Kokoro and xAI** work end-to-end. Hard facts behind the table:

- **`response_format` is ignored** by the MP3 models. Asking for `pcm`,
  `flac` or `wav` returns byte-identical ID3v2.4 MP3. There is no server-side
  flag that fixes this; client-side decode is the only route.
- **`content-type` is unreliable in both directions** — three models send
  `audio/mpeg` with a RIFF body. Only magic-byte sniffing works, which the
  engine already does (`CloudApiEngine.kt:207`).
- **Sample rate is the real blocker**, not MP3. `CloudApiEngine.kt:116`
  hard-fails anything that isn't 24 kHz mono, because
  `MarmaladeTtsService` commits the rate via `callback.start()` *before*
  synthesis. Gradium and Inworld are fast, streaming, native-WAV — and
  broken purely on rate.
- **Cold start dominates the outliers.** Orpheus measured 214s → 110s →
  7.2s over three runs; Chatterbox 80s → 91s → 15.1s; Inworld 24.9s → 4.4s.
  Never rank these models on a single cold sample.
- Only **Kokoro and Gradium genuinely stream** (TTFB ≪ total). Everything
  else buffers server-side, so TTFB ≈ total.

## Plan (post-Fable review, in this order)

1. **Data-only, ships immediately:** narrow `modelExclude` to leave only
   `tts-kokoro` + `tts-xai-v1`. Fixes the reported bug with a JSON edit.
   The descriptor is already remotely updatable via
   `CloudProviderStore.refreshProviders()`.
2. **Structural:** per-model capability metadata (format, sampleRate,
   streams, latency class) in the descriptor; discovery *intersects*
   known-good models instead of substring-blocklisting bad ones.
3. **Resampling** — unlocks Gradium + Inworld. Higher value than MP3
   decode: those two are the fast streaming models.
4. **MP3 decode** via MediaCodec behind an **injectable seam** — MediaCodec
   doesn't exist on plain JVM and would break the unit tests the same way
   `org.json` did. Buffer-and-emit-once is the right shape (these models
   don't stream anyway); no streaming decoder needed. A pure-Java decoder
   (JLayer etc.) is LGPL — stop and ask Max, don't default to it.
5. **Classify the sniff failure** ("this model returned MP3") rather than
   surfacing an error body. Non-2xx JSON errors are *already* surfaced
   verbatim at `CloudApiEngine.kt:296-300`.
6. **Voice picker grouped by model** — currently 186 voices flattened
   alphabetically, so there are two "Alice" (ElevenLabs + Gradium) and a
   stray "Alex" (Kokoro + Inworld), and no way to tell `InspirationalGirl`
   is MiniMax. Show the latency class here too.

### Open, untested
- Do the MP3 models honour `speed`? They ignore `response_format`, so they
  may no-op the speed slider — which would violate the project's
  speed-handling convention. One curl each settles it.
- `CloudApiVoiceCatalog.kt:72-73` stamps every discovered voice
  `languageCode = "en-US"`, including multilingual MiniMax/Gemini sets.
  Grouping the picker by model is cosmetic while this metadata is wrong.
- Pre-existing fragilities Fable flagged in `WavStreamHeader.parse`:
  rejects WAVE_FORMAT_EXTENSIBLE wrapping PCM (line 225); no RIFF odd-size
  pad handling; a 0xFFFFFFFF chunk size goes negative through `leInt` and
  desyncs the parser; `ByteArray(size)` trusts an unvalidated 32-bit size.
- `isInstalled()` (line 69) is true if *any* provider has a key, so a user
  with only an OpenAI key tapping a Venice voice gets "engine not
  installed" while the engine visibly works elsewhere.
- **Stale comment** `CloudApiEngine.kt:38-41` claims MP3 models "are
  excluded via the provider descriptor's modelExclude list". Five are live.
  Update it with whatever fix lands.

## Wordmark font — resolved, not yet implemented

The app bundles **Fredoka** only. `Type.kt:14` justifies this with a claim
that is **factually wrong**: it says Momo Trust Display "isn't freely
distributable". It is a Google Font under **OFL-1.1**, `Copyright 2024 The
MoMo Trust Display Project Authors`, by Type Associates.

The authoritative reference is `marmalade-agent/build.sh:284`, which bakes
`fontDisplay: '"Momo Trust Display", "Fredoka", sans-serif'` and requests
`family=Momo+Trust+Display` **with no `:wght@` suffix** — because the family
ships **Regular 400 only**. `wght@600` returns HTTP 400 from Google Fonts.

So: the marmalade-design SKILL.md's "Momo Trust Display | 600" is wrong and
should read 400. `stash@{0}` already holds WIP Momo bundling — pop that
rather than redo it, and add the OFL entry to `LICENSES/fonts.md` plus the
in-app licenses screen.

## Still unverified on device

The alias-routing redesign has **never been eyeballed running**. The APK is
installed; the five checks are listed in the section below. Max was on a
call when the walk was attempted, so it was abandoned rather than fight him
for the screen.

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
