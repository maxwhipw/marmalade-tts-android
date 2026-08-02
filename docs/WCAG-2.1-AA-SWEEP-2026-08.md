# WCAG 2.1 A/AA delta sweep — marmalade-tts-android (2026-08-02)

Companion to `ACCESSIBILITY-AUDIT-2026-08.md`, which covered screen-reader
semantics, live regions, contrast, text scaling, and touch targets. This
sweep checks the *remaining* WCAG 2.1 Level A + AA criteria (per WCAG2ICT
mobile interpretation) so the public claim can be grounded. Code-level,
read-only; paths relative to `app/src/main/java/app/marmalade/tts/`.

## Verdict

**No Level A or AA hard failures found.** Most criteria pass structurally,
not by luck: no custom gestures anywhere (zero `pointerInput` /
`detectTapGestures` / drag / swipe / long-press in `ui/`), reordering uses
Move up/down buttons rather than drag, stock Material 3 everywhere, no
focus-indication or traversal overrides, no sensors, no timers or
auto-playing audio.

Four small items surfaced (all fixed in the same-day follow-up commit —
see the Status section of the main audit doc):

1. **SpeakScreen landscape** — the main screen's Column had no
   `verticalScroll`; in landscape the Speak button and status line fell
   off-screen (`SpeakScreen.kt:193-197`). The only item that would have
   blocked a public claim (a claim covers both orientations).
2. **Heading semantics (1.3.1)** — no `semantics { heading() }` anywhere;
   section headers were plain styled Text.
3. **PickerField accessible name (4.1.2)** — the label Text sat outside the
   clickable Surface, so TalkBack announced only the value, never "Voice"
   (`AliasScreen.kt:811-879`).
4. **Search fields placeholder-only (3.3.2)** — `VoicePickerScreen.kt:225`,
   `VoicePickerSheet.kt:122`, `AppRoutingSheet.kt:136`.

## Per-criterion results (as audited, pre-fix)

| Criterion | Result | Evidence |
|---|---|---|
| 1.3.4 Orientation | PASS* | No `screenOrientation` locks in the manifest; *SpeakScreen landscape overflow noted above (fixed). |
| 2.1.1 Keyboard / 2.1.2 No trap | PASS | All actions via `clickable`/`Surface(onClick)`/M3 components; effect reorder is Move up/down IconButtons (`EffectEditorScreen.kt:243-253`); sheets/dialogs all dismissible via back/Esc/scrim. |
| 2.4.3 Focus order | PASS | Linear Column/Row/LazyColumn layouts; zero `traversalIndex`/`FocusRequester`. |
| 2.4.7 Focus visible | PASS | Zero `indication =` overrides; default ripple/focus intact. |
| 2.5.1 Pointer gestures | PASS | No path-based or multipoint gestures exist. |
| 2.5.2 Pointer cancellation | PASS | No `onPress`-triggered actions; everything fires on up-event click. |
| 2.5.3 Label in name | PASS | All 26 `contentDescription` sites are icon-only controls; no conflicts with visible text. |
| 2.5.4 Motion actuation | N/A | No sensor usage. |
| 1.4.2 Audio control | PASS | Speech only on user action; Speak button becomes Stop during playback (`SpeakScreen.kt:243-254`); previews toggle. |
| 2.2.1 / 2.2.2 Timing, pause | PASS | No timeouts or auto-advance. See JarMascot note below. |
| 2.3.1 Flashes | PASS | Mascot blink is 130ms per 3.4s cycle — nowhere near 3/s. |
| 3.2.1 / 3.2.2 On focus/input | PASS | Zero `onFocusChanged`; no context change except on activation. |
| 3.3.1 / 3.3.2 / 3.3.3 Errors, labels | PASS* | Real `label =` on primary fields; `isError` + `supportingText` with distinct messages (`AliasScreen.kt:669-681`); *search fields noted above (fixed). |
| 1.3.1 Info & relationships | PASS* | *Heading semantics noted above (fixed). |
| 1.4.13 Hover/focus content | N/A | No tooltips or hover popups. |
| 4.1.2 Name/Role/Value (custom) | PASS* | Chips are stock M3 FilterChip; routing rows toggleable+Role since the main-audit fixes; *PickerField noted above (fixed). `PhonemizationLanguageDropdown` operable via its labeled IconButton — cosmetic role gap, accepted. |

**JarMascot note (2.2.2):** the onboarding mascot idles on an indefinite
decorative animation with no in-app pause. Compose's `InfiniteTransition`
honors the system animator duration scale, so Android's "Remove animations"
accessibility setting freezes it — a platform-level mechanism, which
WCAG2ICT accepts. Keep this sentence in any formal conformance statement;
it is the softest pass in the sweep. (The 1.5s-polling BenchmarkScreen is
debug-only — `AppRoot.kt:335` — and out of scope for a release claim.)

## The claim

Combined with the main audit and Max's theme ruling (2026-08-01):

- **Claimable now:** "Designed to meet WCAG 2.1 Level AA (per WCAG2ICT).
  Full TalkBack support; an AA-contrast dark theme is available in
  Settings (and follows the system dark mode by default)."
- **Not claimable:** "WCAG 2.1 AA conformant by default in light mode" —
  the brand orange fails 1.4.3/1.4.11 there; conformance for contrast is
  via the dark theme as a conforming alternate version (Conformance
  Requirement 1), which is legitimate but should be stated, not hidden.
- **Upgrade path to a flat "conforms" statement:** a real TalkBack pass on
  device (still pending) — code-level checks are necessary, not
  sufficient. WCAG 2.2 AA additionally needs the deferred "Show more"
  target-size fix (2.5.8).
