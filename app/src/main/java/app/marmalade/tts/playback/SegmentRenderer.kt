package app.marmalade.tts.playback

import app.marmalade.tts.engine.EngineNotInstalledException
import app.marmalade.tts.engine.SynthAudio
import app.marmalade.tts.engine.TtsEngine
import app.marmalade.tts.preprocessing.Emotion
import app.marmalade.tts.preprocessing.ProsodyApplier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first

/** What one render job reports back to the Narrator's loop. */
internal sealed interface RenderEvent {
    /**
     * One engine chunk, raw (no speed, no effects — those run in the writer).
     * [renderMs] excludes any wait for a budget permit. [skewed] marks every
     * chunk collected after the job's first such wait: the engines render
     * ahead into their own buffer (a 64-chunk channelFlow) while the collector
     * waits, so those chunks arrive with near-zero gaps and would drag the
     * RTF far below the truth — they are neither sampled nor recorded.
     */
    class Chunk(val index: Int, val audio: SynthAudio, val renderMs: Long, val skewed: Boolean) : RenderEvent

    data object Done : RenderEvent

    data class Failed(val kind: FailureKind, val message: String?) : RenderEvent
}

/**
 * Renders one segment: prepare the text (rules, emoji strip, per-segment
 * phonemizer language), stream the engine at 1.0 (the session speed is a
 * time-stretch in the writer), and hand each chunk to [emit] as it arrives.
 * Between chunks it waits for [permit] — the Narrator's ahead budget — so the
 * engine is at most one chunk ahead of what the Narrator allowed.
 *
 * A non-neutral emotion renders the whole segment first and runs
 * [ProsodyApplier] on it (its resample is the emotion's own), then emits it
 * as a single chunk; the session chain still applies at playback, so a live
 * speed change reaches it too.
 *
 * Bookkeeping it owns (§2.9): one time-to-first-audio sample per job, and one
 * warm, unskewed RTF sample per job.
 */
internal class SegmentRenderer(
    private val preparer: SegmentPreparer,
    private val stats: SynthStatsPort,
    private val clock: () -> Long,
) {
    suspend fun render(
        engine: TtsEngine,
        voice: ResolvedVoice,
        text: String,
        playbackRate: Float,
        permit: StateFlow<Boolean>,
        emit: (RenderEvent) -> Unit,
    ) {
        try {
            val prepared = preparer.prepare(text, voice)
            if (prepared.text.isBlank()) {
                emit(RenderEvent.Done)
                return
            }
            // Model load is not render speed: load first, then time chunks.
            val warm = engine.isLoaded()
            engine.ensureModelLoaded()
            val stream = engine.synthesizeStream(
                prepared.text, voice.voiceId, 1f, prepared.phonemizationLanguage, playbackRate,
            )
            val startedAt = clock()
            var lastResume = startedAt
            var first = true
            var waited = false
            var index = 0
            var renderSum = 0L
            var audioSum = 0.0
            if (prepared.emotion != Emotion.Neutral) {
                val parts = ArrayList<SynthAudio>()
                stream.collect { parts.add(it) }
                if (parts.isEmpty()) {
                    emit(RenderEvent.Done)
                    return
                }
                val rate = parts[0].sampleRate
                val whole = ShortArray(parts.sumOf { it.pcm.size })
                var at = 0
                for (p in parts) {
                    p.pcm.copyInto(whole, at)
                    at += p.pcm.size
                }
                val shaped = ProsodyApplier.apply(whole, rate, prepared.emotion)
                val renderMs = clock() - startedAt
                stats.recordLatency(voice.voiceId, voice.engine, renderMs, text.length)
                emit(RenderEvent.Chunk(0, SynthAudio(shaped, rate), renderMs, skewed = false))
                emit(RenderEvent.Done)
                return
            }
            stream.collect { audio ->
                val now = clock()
                val renderMs = now - lastResume
                if (first) {
                    first = false
                    stats.recordLatency(voice.voiceId, voice.engine, now - startedAt, text.length)
                }
                if (!waited) {
                    renderSum += renderMs
                    audioSum += audio.pcm.size * 1000.0 / audio.sampleRate
                }
                emit(RenderEvent.Chunk(index++, audio, renderMs, skewed = waited))
                if (!permit.value) {
                    permit.first { it }
                    waited = true
                }
                lastResume = clock()
            }
            if (warm && audioSum >= MIN_RTF_AUDIO_MS && renderSum > 0) {
                stats.recordRtf(voice.engine, renderSum / audioSum)
            }
            emit(RenderEvent.Done)
        } catch (e: CancellationException) {
            throw e
        } catch (e: EngineNotInstalledException) {
            emit(RenderEvent.Failed(FailureKind.MODEL_MISSING, e.message))
        } catch (e: UnsupportedOperationException) {
            // The direct engines' "model files absent" signal.
            emit(RenderEvent.Failed(FailureKind.MODEL_MISSING, e.message))
        } catch (t: Throwable) {
            emit(RenderEvent.Failed(FailureKind.FAILED, t.message))
        }
    }

    companion object {
        /** Same floor as the service's RTF sampling: shorter audio is too noisy. */
        const val MIN_RTF_AUDIO_MS = 200.0
    }
}
