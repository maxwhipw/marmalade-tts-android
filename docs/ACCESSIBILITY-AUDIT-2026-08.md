# Accessibility Audit — marmalade-tts-android (2026-08-01)

Audience: blind and low-vision users (TalkBack, large font scale, high
contrast). Read-only code-level audit of all Compose UI under
`app/src/main/java/app/marmalade/tts/ui/` plus manifest and theme. Companion
CLI audit: `marmalade-tts-cli/docs/ACCESSIBILITY-AUDIT-2026-08.md`.

## Verdict

The app's **structure** is TalkBack-friendly by good design taste — almost
every row/card is one big `clickable` target (which merges descendants into
one announcement), icon buttons mostly carry name-bearing labels ("Preview
Trailer", "Delete Robot"), and Material3 components supply correct roles. But
there is **zero explicit accessibility work**: not a single
`Modifier.semantics`, `liveRegion`, `stateDescription`, `Role`, `toggleable`,
or announcement anywhere in the UI source (verified by grep).

**Raised stakes:** `AndroidManifest.xml:103-115` registers `MarmaladeTtsService`
as a system TTS engine (`android.intent.action.TTS_SERVICE` + voice-data /
sample-text activities). A blind user can select Marmalade as their TalkBack
voice — which makes blind users disproportionately likely users of this app,
and they must be able to drive its UI (pick voices, set speed, install
engines) with TalkBack.

## Findings

### 1. TalkBack semantics

| # | Severity | Location | Issue / fix |
|---|----------|----------|-------------|
| 1.1 | **Blocker** | `ui/screen/EffectEditorScreen.kt:493-517` (`LabeledSlider`, used ~30×) | Slider has no semantic link to label or value — TalkBack says bare "slider, 37%" (raw 0..1 fraction, not "620 Hz"); effects editing unusable blind. Fix: `semantics { contentDescription = label; stateDescription = valueText }` on the Slider. |
| 1.2 | Major | `ui/onboarding/OnboardingScreen.kt:652-658` + alias-editor speed slider in `AliasScreen.kt` | Same unnamed-slider problem; the "Speed: 1.25×" text is a separate node. Same fix. |
| 1.3 | Major | `AdvancedSettingsScreen.kt:182-194`, `EngineDetailScreen.kt:382-397`, `AppRoutingSheet.kt:198-228`, `OnboardingScreen.kt:339-393` | Row-plus-Switch/Checkbox pattern: row is `clickable` (no role/state) AND the inner control keeps its own `onCheckedChange` → two swipe stops per setting, and the row stop announces no checked state. Fix: `Modifier.toggleable(value, role = Role.Switch/Checkbox, onValueChange)` on the row, `onCheckedChange = null` on the control. |
| 1.4 | Major | `ui/screen/EnginesScreen.kt:209-226` | "Show more"/"Show less" is plain clickable Text — no `Role.Button`, no expanded-state semantics, and the action is duplicated on the description text (two stops, same action). |
| 1.5 | Minor | `ui/screen/SpeakScreen.kt:198-203` | Mascot Image has `contentDescription = "Mascot speaking"` — it's decorative; becomes the first swipe stop on the main screen. Fix: `null`. |
| 1.6 | Minor | `ui/screen/VoicePickerScreen.kt:353-359` | Gender via emoji Text "👩"/"👨" — reads "woman"/"man" (works, fragile; "N/A" case reads raw glyph). |
| 1.7 | Minor | `AliasScreen.kt:1053-1093` (`MetaChip`) + `CloudMark` (`SpeakScreen.kt:364-374`) | CloudMark's description creates a separate unmerged focus stop inside the card; the chip's "Cloud" text already says it. Fix: glyph description `null` inside merged cards. |

Model examples (already good): `EffectsScreen.kt:217-244` and
`EffectEditorScreen.kt:218-229` — every icon action includes the target's name.

### 2. Grouping / traversal

| # | Severity | Location | Issue / fix |
|---|----------|----------|-------------|
| 2.1 | Major | `ui/screen/EnginesScreen.kt` engine card | Card is not clickable → title, description, sizes, license, progress are each separate swipe stops (~6 swipes per engine before Install). Fix: `semantics(mergeDescendants = true)` on the info block. |
| 2.2 | Minor | `AliasScreen.kt:414-548` (`RoutingStrip`) | Inconsistent merge behavior between primary/non-primary strips; give the strip a summary `contentDescription` ("Routed apps: Signal, K-9, plus all unrouted"). |

### 3. Dynamic announcements — systematically absent

| # | Severity | Location | Issue / fix |
|---|----------|----------|-------------|
| 3.1 | **Blocker** (for errors) | `ui/screen/SpeakScreen.kt:247-275` status line | No `liveRegion` — playback start/stop and synthesis **errors are never announced**; blind user taps Speak, gets silence, no explanation. Fix: `semantics { liveRegion = LiveRegionMode.Polite }` on the status Text. |
| 3.2 | Major | `ui/screen/EnginesScreen.kt:250-293` | Download/extract progress and `InstallState.Failed` reason have no liveRegion — a 100+ MB install completes or fails silently under TalkBack. |
| 3.3 | Major | whole app | No `SnackbarHost` anywhere; errors are inline color-only text. (The two `Toast` uses — `SpeakClipboardTileService.kt:64`, `ShareIntentActivity.kt:57` — are fine; TalkBack reads Toasts.) |
| 3.4 | Minor | `EffectsScreen.kt` / `VoicePickerScreen.kt` preview | Playing state = icon swap + tint only; add stateDescription. |

### 4. Touch targets

| # | Severity | Location | Issue / fix |
|---|----------|----------|-------------|
| 4.1 | Major | `ui/screen/EnginesScreen.kt:219-226` | "Show more"/"Show less": labelMedium text with 2dp padding — ~16dp tall tap target. Fix: `defaultMinSize(minHeight = 48.dp)` or make the whole card the toggle. |

(IconButtons and list rows verified fine elsewhere.)

### 5. Text scaling

| # | Severity | Location | Issue / fix |
|---|----------|----------|-------------|
| 5.1 | Major | `ui/screen/SpeakScreen.kt:304-356` (`CurrentVoiceRow`) | Fixed `height(56.dp)` with two lines of sp text + `maxLines=1` — at 1.5-2× font scale the subtitle clips on the app's primary control. Fix: `heightIn(min = 56.dp)`. |
| 5.2 | Minor | `SpeakScreen.kt:211-215` | Text field fixed `height(160.dp)` with `minLines=5` — clips at 2×. Use `heightIn`. |
| 5.3 | Minor | `AliasScreen.kt:387`, `SpeakScreen.kt:330,339` | `maxLines=1`+ellipsis on user-chosen names — truncated at large font (tolerable; tapping opens the editor with the full name). |

Good: all text in sp via theme typography; no forced font sizes on body content.

### 6. Contrast (light theme, `ui/theme/Color.kt`)

| # | Severity | Location | Issue / fix |
|---|----------|----------|-------------|
| 6.1 | Major | `Color.kt:15-16` | White on `primary #F97316` ≈ **2.8:1** — fails WCAG AA on every filled button ("Speak", "Install") in light mode. Fix: darken toward Orange 700 `#C2410C` (≈4.9:1) or dark ink on orange. |
| 6.2 | Major | `SpeakScreen.kt:406-410`, `OnboardingScreen.kt:363-368`, `EnginesScreen.kt:222`, `AdvancedSettingsScreen.kt:234` | `#F97316` as small text on cream `#FFF7ED` ≈ 2.7:1 at labelSmall size. Fix: use `tertiary #C2410C` for small orange text in light mode. |
| 6.3 | Minor | `AppRoutingSheet.kt:203` | `.alpha(0.45f)` on disabled rows makes the *reason* text ("Routed to X — un-route it there first") nearly invisible; keep the reason line at full alpha. |

Dark scheme verified fine (≈7:1; onPrimary is dark ink — correct).

### 7. Non-visual affordances

| # | Severity | Location | Issue / fix |
|---|----------|----------|-------------|
| 7.1 | Minor | `ui/MarmaladeChips.kt:39-54` | FilterChip selection = fill color only in base overload (semantics OK; low-vision/color-blind cue missing). Prefer the leading-check-icon overload. |
| 7.2 | Major | `AliasScreen.kt:505-517` | *Which* apps are routed conveyed only by launcher-icon images (no descriptions); text says "3 apps" but never which. Fix via 2.2's merged description. |

## Suggested priority order

1. **liveRegion on SpeakScreen status + engine install progress** (3.1, 3.2) —
   silence on error is the worst blind-user experience in the app.
2. **LabeledSlider semantics** (1.1) — unblocks the entire effects editor.
3. **Row-toggle pattern → `toggleable` + role** (1.3) — four files, one
   mechanical pattern.
4. Light-mode orange contrast pair (6.1, 6.2) — a two-line theme change.
5. Engine-card merge + "Show more" target (2.1, 4.1), CurrentVoiceRow
   heightIn (5.1), routing-strip descriptions (7.2).

## Status (2026-08-01)

Applied (non-visual only — nothing changes at default font scale):

| Batch | Commit | Findings | What landed |
|-------|--------|----------|-------------|
| A | `73543a5` | 3.1, 3.2 | `liveRegion = Polite` on the Speak status line, the missing-engine CTA, and the engine card's download / install / failure text. |
| B | `a910563` | 1.1, 1.2 | `contentDescription` + `stateDescription` on `LabeledSlider` and both speed sliders — TalkBack reads "Mix, 40%" / "Speed, 1.25×". |
| C | `562280c` | 1.3 | `Modifier.toggleable` + `Role.Switch`/`Role.Checkbox` on the rows in AdvancedSettingsScreen, EngineDetailScreen, AppRoutingSheet and OnboardingScreen; inner controls take `onCheckedChange = null`. |
| D | `8e8f072` | 2.1, 2.2, 1.4, 1.5, 1.7, 7.2 | Engine card info block merged to one stop carrying the expand action; routing strip announces routed apps by name (`rememberAppLabels`); mascot and in-chip CloudMark descriptions nulled. |
| E | `acfb348` | 5.1, 5.2 | `heightIn(min = …)` on CurrentVoiceRow and the text field (the row's inner `fillMaxSize` became `fillMaxWidth` so a minimum height doesn't stretch it). |

Not applied:

- **3.4** — both preview buttons (EffectsScreen, VoicePickerScreen) already
  flip their `contentDescription` between "Preview X" and "Stop X" /
  "Previewing X", so a `stateDescription` would only duplicate it.
- **1.4's second half** — the description Text keeps its `clickable`. It is no
  longer a separate swipe stop (D merges it), and removing it would take a tap
  target away from sighted users.

Deferred by Max (visual): **6.1**, **6.2**, **6.3** (contrast), **7.1**
(FilterChip check icon), **4.1** sizing ("Show more" 48 dp target), **1.6**
(gender emoji), **3.3** (Snackbar adoption).

Visual changes deferred at Max's request 2026-08-01 — revisit deliberately,
don't fold into unrelated work.

**Max's ruling on the contrast items (2026-08-01): the light theme's
white-on-orange stays — it's the brand.** The accessibility answer is the
existing escape hatch, not a default change: Settings → Appearance already
offers mode (system/light/dark, default "system") and color presets, the
dark scheme passes AA (≈7:1), and the Settings path is TalkBack-accessible
as of batch C. Users on system dark mode get the conforming theme with zero
extra steps; light-mode users can switch in Settings. This is the WCAG
"conforming alternate version" pattern — defensible for calling the app
accessible, though not AA-conformant-by-default; don't claim the latter.
No theme step is to be added to onboarding (Max: don't add a step, and
encourage the orange default).

**2026-08-02 — WCAG 2.1 AA delta sweep done** (`WCAG-2.1-AA-SWEEP-2026-08.md`):
the remaining A/AA criteria (keyboard, focus, orientation, gestures, timing,
errors) show no hard failures; the four small items it surfaced (SpeakScreen
landscape scroll, heading semantics, PickerField name, search-field names)
are fixed in `a8543ab`. Public wording: "designed to meet WCAG 2.1 AA" until
the on-device TalkBack pass upgrades it — see the sweep doc's "The claim".

## What's already good

- Registered system TTS engine — genuinely valuable to blind users.
- Whole-row/whole-card tap targets → automatic descendant merging.
- Name-bearing icon-button labels (EffectsScreen/EffectEditorScreen are model
  examples); nav tabs labeled; search-field decorative icons correctly null.
- Material3 throughout (native roles for buttons, switches, nav).
- All text scalable (sp via theme); dark-theme contrast solid.
