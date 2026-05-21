# Audio pipeline review

**Verdict:** PASS with notes

## Strengths

- **Clean DSP code.** `EffectChain.kt` and `ProsodyApplier.kt` are well-organized,
  comment-dense in the right places (every algorithm choice is justified at the
  block where it's made), and the file-level data-flow ASCII headers match the
  project convention seen in `KittenEngine.kt` and elsewhere.
- **Effect/prosody algorithms are correct.** CAVE's 3-tap comb correctly extends
  the output by `maxTap` samples and the iterative-feedback loop is the right way
  to get inter-tap density. ROBOT's bit-crush + LPF + fractional-index vibrato is
  textbook. TELEPHONE's cascaded one-pole HPF/LPF with `x / sqrt(1 + x²)` soft-
  clip is a nice choice (no `exp()`, smooth and monotonic).
- **Honest handling of the pitch/rate coupling problem.** `ProsodyApplier`'s
  KDoc and inline comments make no attempt to hide that v0.1 couples pitch and
  rate through linear resampling, names the right v0.2 fix (WSOLA), and points
  at the CLI's sox chain as the reference. That's good engineering hygiene.
- **`SpeechPlayer` interface widening is clean.** Defaults on `speed` and
  `effect` keep existing two-arg call sites working (verified — `VoicePickerVM`
  still calls `synthesizer.speak(phrase, voice.id)`). The interface is the
  right seam for unit tests of the ViewModels.
- **System-TTS chunking is correct.** `MarmaladeTtsService.streamPcm` respects
  `callback.maxBufferSize`, guards against the zero-size case with
  `coerceAtLeast(2)`, throws on `audioAvailable` failure (so the outer try/catch
  converts it to `callback.error()`), and the Robolectric test pins the
  invariants that matter (≥1 chunk, every chunk ≤ maxBuf, bytes sum to PCM size).
- **EmojiProsody.detect tie-breaking is solid.** Count wins; latest-occurrence
  is the tie-breaker; tests cover all three cases (no tie, two-way tie, three-
  way tie, and "higher count beats latest"). Intensity scaling and the
  unrecognized-emoji passthrough are also covered.
- **`Synthesizer.playPcm` releases the AudioTrack in `finally`** — the failure
  paths from `track.write`, `IllegalStateException`, etc. all converge on
  `track.release()` and the `currentTrack` clear-out.

## Issues

- **Severity:** major
  **File:line:** `app/src/main/java/app/marmalade/tts/service/MarmaladeSynthService.kt:265-298`
  **Issue:** Audio-focus leak on every early-return path in `runOne`. The
  function calls `requestFocus()`, then can `return` without `releaseFocus()` if
  (a) `cleaned.isBlank()` after emoji stripping, (b) the engine throws
  `EngineNotInstalledException`, or (c) synthesis fails with any other
  throwable. After return, `startNextLocked()` either calls `runOne` again
  (which calls `requestFocus()` again and overwrites the stored
  `focusRequest` — the old request becomes unrecoverable because
  `abandonAudioFocusRequest` on API 26+ needs the exact original object) or
  reaches `stopIfIdle()` which abandons only the *latest* focus request. Net
  effect: every failed/skipped queue item leaks an audio-focus grant against
  the system until app process death.
  **Suggested fix:** Wrap the body of `runOne` in `try { … } finally {
  releaseFocus() }`, OR guard `requestFocus()` with a "do I already hold
  focus?" check at the top so repeated calls are idempotent and only the
  outer `doStop()` / `stopIfIdle()` abandons it.

- **Severity:** minor
  **File:line:** `app/src/main/java/app/marmalade/tts/service/MarmaladeSynthService.kt:39`
  **Issue:** `import kotlinx.coroutines.runBlocking` is imported but never
  used in this file. Dead import — `runBlocking` only appears in
  `MarmaladeTtsService.kt`.
  **Suggested fix:** Remove the import.

- **Severity:** minor
  **File:line:** `app/src/main/java/app/marmalade/tts/service/MarmaladeSynthService.kt:619-623`
  **Issue:** The trailing `_mediaButtonReceiverRef` top-level private val that
  exists "to keep the import alive" is a code smell — it adds a real reflective
  reference to `MediaButtonReceiver.class` at file-load time just to suppress
  an unused-import warning, and the comment admits it's not wired up.
  **Suggested fix:** Delete both the val and the `MediaButtonReceiver` import.
  When BT-button passthrough is actually implemented the import comes back
  with a real call site.

- **Severity:** minor
  **File:line:** `app/src/main/java/app/marmalade/tts/audio/Synthesizer.kt:192`
  and `app/src/main/java/app/marmalade/tts/service/MarmaladeSynthService.kt:412`
  **Issue:** `minBuf.coerceAtLeast(pcm.size * 2)` sizes the AudioTrack
  internal buffer to the entire PCM payload in bytes. For long-form synthesis
  (say, 30 s of 24 kHz mono = ~1.44 MB) this allocates a multi-MB native
  buffer per playback, when a fraction of that would do — `MODE_STREAM` is
  designed to refill as the head drains. Not a correctness bug; works fine
  for short utterances.
  **Suggested fix:** Pick something like `maxOf(minBuf, sampleRate * 2 /* 1 s
  of headroom */)` and let the streaming write loop do its job.

- **Severity:** minor
  **File:line:** `app/src/main/java/app/marmalade/tts/audio/Synthesizer.kt:232-241`
  and `app/src/main/java/app/marmalade/tts/service/MarmaladeSynthService.kt:453-459`
  **Issue:** Drain loops `while (!cancelled) { … Thread.sleep(10L) }` have no
  watchdog — if `playbackHeadPosition` stalls (driver glitch, weird device,
  HDMI sink dropping out), the IO coroutine pins until `cancel()` is called.
  In practice AudioTrack always advances, but a 5–10 s absolute timeout would
  be a cheap insurance policy.
  **Suggested fix:** Track a `lastPosition` + `lastChange = SystemClock
  .elapsedRealtime()` and break out if the head hasn't moved in N seconds.

## Test coverage notes

- **EffectChain tests are property-based and meaningful.** The relaxations are
  honest and don't hide bugs:
    - ROBOT was changed from "distinct-value count drops" to "attenuates 6 kHz
      vs NONE by ≥6 dB." The original assertion is genuinely broken at the
      output (the post-crush LPF + vibrato resampler reintroduce intermediate
      values via interpolation, so distinct count climbs back into the
      thousands). The replacement assertion locks in the LPF, which is the
      perceptually-dominant stage. Sound trade.
    - TELEPHONE's ±6 dB tolerance around the 1 kHz passband is generous but
      defensible — cascaded one-pole filters do bleed at the edges.
    - The CAVE length test explicitly asserts `n + maxTapSamples` (10 800
      samples at 24 kHz for a 450 ms tap), which matches the implementation
      contract documented in `EffectChain.kt`. Pinning this catches future
      regressions if someone "fixes" CAVE to in-place length.
- **ProsodyApplier tests cover the right invariants** (Neutral identity,
  Happy pitch up via zero-crossing density per sample, Sad/Angry RMS deltas,
  Nervous windowed-RMS std dev, ±20 % length envelope, empty input). The
  per-window RMS std dev for Nervous detection is a nice property that
  doesn't pin coefficients. The Happy zero-crossing-density check correctly
  divides by output size (raw ZC count is invariant under pure resampling).
- **EmojiProsody tests cover all the interesting cases** and lock in the
  upstream 11-emoji set, which the comments correctly identify as the
  contract with the EmojiVoice "paige" checkpoint.
- **MarmaladeTtsServiceTest exists and exercises** the start→audioAvailable*
  →done call ordering, the error-on-throw path, the maxBufferSize chunking
  guarantee, and language negotiation. Reflection-based field injection is
  ugly but appropriate when Hilt isn't running in the JVM test (the
  alternative — making fields var-with-default-null — would be worse). The
  assertions are sensible.
- **Under-covered (not blockers, but worth noting):**
    - No test for the `MarmaladeSynthService` queue / pause / resume / stop /
      audio-focus state machine. The focus-leak issue above would have been
      caught by a focused unit test on `runOne`'s error paths. A Robolectric
      test like the one for `MarmaladeTtsService` is feasible.
    - No test for the `Synthesizer` happy-path / error-path / cancel
      sequencing. The AudioTrack interaction would have to be mocked, but
      the state machine around `cancelled` / `currentTrack` is non-trivial
      enough to deserve coverage.
    - These gaps are fine for v0.1 (the affected code is the kind of
      lifecycle wiring where integration testing on a device tells you more
      than mocks would), but they should land in STUBS.md if they aren't
      already.

## Recommendation

Ship this with the focus-leak fix in `MarmaladeSynthService.runOne` — that's a
real, observable bug that will accumulate against the AudioManager's focus
stack across an app session and only show up under specific failure
conditions, which is exactly the kind of issue users will report as "speaker
got muted after a while." The dead-import and dummy reference cleanups are
purely cosmetic and can ride along in the same commit. The DSP code, the
prosody pipeline, the system-TTS chunking, and the test suite are all in
good shape and don't need rework. Address the focus issue and this is a
clean PASS.
