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
