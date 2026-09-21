# UX Notes

Running log of UX decisions and direction from Max. Newest first.

## 2026-09-20 — Speed-up warning: RTF-aware, not a static threshold

**The tension (lose-lose):** if we allow speeding up voices, users on
slower hardware and larger models run into problems (synthesis can't keep
up with the requested rate). If we don't allow it, speed control is
probably the most-requested missing feature.

**Decision: we keep the feature.** The goal shifts to minimizing how many
users hit bad UX, with two priorities in order:

1. **Never let someone turn up the speed and hit problems without knowing
   why.** The warning must appear wherever the effective speed becomes a
   problem (alias editor AND reader sheet — chips multiply the alias
   speed).
2. **Don't warn when we don't need to.** A fast phone running a small
   model at 1.5× is fine; a static "above 1.3×" warning there is noise
   that trains users to ignore warnings.

**Direction:** gate the warning on how the engine actually runs on this
device — the same way engine recommendations work. `EngineRecommender` /
`DeviceCapability.probe()` already turn a measured Kitten RTF into a
predicted per-engine RTF. An engine keeps up at speed S roughly while
`RTF × S < 1`, so the warning threshold should be derived per
engine-on-this-device (with some safety margin), not hardcoded.

**Current state:** the shipped warning is a static threshold
(`VoiceAlias.SPEED_PERF_WARNING_THRESHOLD = 1.35f`), shown in the alias
editor and, since `cc624f3`, the reader sheet (on chip × alias speed).
That constant is the thing the RTF-derived threshold should replace when
this lands.

Open questions for the design pass: margin size, whether to use the
onboarding probe's prediction or a live measured RTF for the installed
engine, and what the warning says when it does fire (name the cause:
"this voice can't render this fast on this phone").

### Addendum (same day) — hard cap at 1.5×?

Max floated a global speed cap at 1.5×: would get pushback, but avoids
some hard failures. Context that shapes the call: the actual failure is
between-sentence stalling (non-native-speed engines render at 1.0× and
time-stretch, so playback outruns synthesis when RTF × speed ≥ ~1), not a
crash; and speed-hungry users skew accessibility (screen-reader users run
2×+), the group a flat cap hurts most. A flat cap also doesn't save weak
phones on big models, which can stall below 1.5×.

Variant on the table: a per-device+engine cap derived from the same RTF
headroom — the slider max simply stops where this phone can't keep up,
warning zone just beneath it. Fast phones keep 2.0×, weak ones get an
honest ceiling instead of a stall. NOT DECIDED — options are: flat 1.5×
cap, RTF-derived cap, or warning-only (no cap).

### Addendum 2 (same day) — converged design: flat 1.5× cap + dynamic RTF warning

Max's converged position (cap still tentative, warning firm):

- **Cap: flat 1.5×** rather than an RTF-derived per-device cap — a
  dynamic slider max is opaque ("why does my friend's phone go higher?"),
  while a flat cap is explainable in one sentence. Add an **info icon**
  next to the speed control so users understand why the cap exists.
- **Warning: whenever speed-adjusted RTF > ~0.8** (measured RTF ×
  effective speed), i.e. dynamic per device+engine. This is the firm
  part ("we have to") — it also catches weak phones that struggle below
  1.5×. The ~0.2 headroom absorbs thermal throttling / background load.
- **Why TTFA is the frame:** with speed-aware preroll, exceeding
  realtime doesn't stall mid-audio — it converts into waiting (longer
  time-to-first-audio per chunk). On short texts a user may accept that
  tradeoff; on audiobooks / large bodies of text TTFA becomes
  astronomical. The warning exists so nobody discovers that the hard way.

**RTF source:** cold start from the onboarding probe's per-engine
prediction (EngineRecommender ratios); then a rolling measured warm RTF
per engine (rendered-audio seconds ÷ wall render time, which the synth
service can observe on every utterance) stored in the datastore — after a
few utterances the warning runs on measurement, not prediction.

Post-v1.1 work; v1.1 ships the static 1.35 threshold as-is.
