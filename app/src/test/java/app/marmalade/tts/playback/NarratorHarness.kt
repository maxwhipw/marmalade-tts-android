package app.marmalade.tts.playback

import app.marmalade.tts.audio.EffectBlock
import app.marmalade.tts.lang.VoiceChoice
import app.marmalade.tts.preprocessing.EmojiProsody
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail

/**
 * Narrator + fake engine + virtual output + fake ports, all on one test
 * scheduler (§5.1). The loop, render jobs and the virtual writer interleave
 * deterministically on virtual time.
 */
class NarratorHarness(private val test: TestScope, config: NarratorConfig = NarratorConfig()) {

    val now: () -> Long = { test.testScheduler.currentTime }
    val dispatcher = StandardTestDispatcher(test.testScheduler)

    val kokoro = FakeEngine(ENGINE, now)
    val cloudEngine = FakeEngine(CLOUD_ENGINE, now)
    val vits = FakeEngine(VITS_ENGINE, now, sampleRate = 22_050)
    private val engines = listOf(kokoro, cloudEngine, vits).associateBy { it.engineName }

    val output = VirtualAudioOutput(test.backgroundScope, now)
    val focus = FakeFocus()
    var foreground = true
    val residency = FakeResidency()
    var keepaliveStarts = 0
    val latencies = ArrayList<Pair<String, Long>>()
    val rtfs = ArrayList<Pair<String, Double>>()
    val logs = ArrayList<String>()

    /** Voice each resolution returns, by target; [resolveDelayMs] models Room/DataStore I/O. */
    var articleVoice = voice()
    var oneShotVoices: (OneShotRequest) -> ResolvedVoice = { articleVoice }
    var resolveDelayMs = 0L
    var resolutions = 0
        private set

    val narrator = Narrator(
        engines = { name -> engines.getValue(name) },
        resolver = { target ->
            resolutions++
            if (resolveDelayMs > 0) kotlinx.coroutines.delay(resolveDelayMs)
            when (target) {
                is ResolveTarget.Article -> articleVoice
                is ResolveTarget.OneShot -> oneShotVoices(target.request)
            }
        },
        preparer = { text, v ->
            PreparedSegment(
                text = EmojiProsody.stripEmojis(text),
                phonemizationLanguage = v.phonemizationLanguage,
                emotion = EmojiProsody.detect(text).emotion,
            )
        },
        output = output,
        focus = focus.port,
        host = { foreground },
        residency = residency,
        keepalive = { keepaliveStarts++ },
        stats = object : SynthStatsPort {
            override fun recordLatency(voiceId: String, engine: String, millis: Long, chars: Int) {
                latencies += engine to millis
            }
            override fun recordRtf(engine: String, rtf: Double) {
                rtfs += engine to rtf
            }
        },
        clock = now,
        loopDispatcher = dispatcher,
        workDispatcher = dispatcher,
        config = config,
        log = { logs += "${now()} $it" },
    )

    val state: NarratorState get() = narrator.state.value
    val article: ArticleView get() = state.article ?: error("no article")

    // -- driving -------------------------------------------------------------------

    fun run(ms: Long) {
        test.advanceTimeBy(ms)
        test.runCurrent()
    }

    /** Settle queued commands without moving the clock. */
    fun settle() = test.runCurrent()

    /** Advance in slices until [cond] holds; fails after [maxMs]. Returns the time it took. */
    fun runUntil(maxMs: Long = 120_000, what: String = "condition", cond: () -> Boolean): Long {
        val start = now()
        test.runCurrent()
        while (!cond()) {
            if (now() - start > maxMs) fail("timed out after ${maxMs}ms waiting for $what\n${logs.takeLast(30).joinToString("\n")}")
            run(10)
        }
        return now() - start
    }

    fun open(segments: List<String>, speed: Float = 1f, autoplay: Boolean = true, key: String = "k") {
        narrator.openArticle(key, segments, VoiceChoice.Primary, speed, autoplay)
        settle()
    }

    // -- observations ------------------------------------------------------------------

    /** Times each (session, segment, chunk) was first heard, in order. */
    fun heardStarts(): List<Pair<Long, ChunkTag>> =
        output.events.mapNotNull { (t, e) -> (e as? OutputEvent.ChunkStarted)?.let { t to it.tag } }

    fun firstHeard(sessionId: Long, segment: Int, after: Long = 0): Long? =
        heardStarts().firstOrNull { it.first >= after && it.second.sessionId == sessionId && it.second.segment == segment }?.first

    /**
     * Silence on [track] between its first and last audible slice in
     * [from, to) — the last one excluded: the audio's end rarely fills it.
     */
    fun gapsMs(track: Track = Track.Main, from: Long = 0, to: Long = Long.MAX_VALUE): Double {
        val slices = output.timeline.filter { it.track == track && it.atMs in from until to }
        val first = slices.indexOfFirst { it.audibleMs > 0 }
        val last = slices.indexOfLast { it.audibleMs > 0 }
        if (first < 0) return 0.0
        return slices.subList(first, last).sumOf { it.silentMs }
    }

    fun audibleMs(track: Track, from: Long = 0, to: Long = Long.MAX_VALUE): Double =
        output.timeline.filter { it.track == track && it.atMs in from until to }.sumOf { it.audibleMs }

    /** §5.1 invariants that hold after every scenario. */
    fun checkInvariants() {
        for (e in listOf(kokoro, cloudEngine, vits)) {
            assertTrue("≤ 1 render in flight per engine lock (${e.engineName})", e.maxConcurrent <= 1)
        }
        assertFalse("stale-epoch audio was heard", output.timeline.any { it.stale })
        assertEquals("focusHeld ⇔ granted and not abandoned", focus.held, state.focusHeld)
        focus.assertOneRequestPerHold()
        residency.assertBalancedFor(activeSessions())
        val idle = state.article?.status.let { it == null || it == Status.Idle || it == Status.Finished || it is Status.Failed } &&
            state.oneShot == null
        if (idle) {
            test.runCurrent()
            assertEquals("no PCM retained when idle", 0L, narrator.bufferedBytes())
        }
    }

    private fun activeSessions(): Boolean {
        val a = state.article?.status
        val articleActive = a is Status.Buffering || a is Status.Playing || a is Status.Paused
        return articleActive || state.oneShot != null
    }

    companion object {
        const val ENGINE = "kokoro-direct-v1_0"
        const val CLOUD_ENGINE = "cloud-api-v1"
        const val VITS_ENGINE = "vits-marmalade-v1"

        fun voice(
            engine: String = ENGINE,
            voiceId: String = "$engine:af_bella",
            effects: List<EffectBlock> = emptyList(),
            language: String? = null,
            aliasSpeed: Float = 1f,
            cloud: Boolean = false,
        ) = ResolvedVoice(engine, voiceId, effects, language, aliasSpeed, cloud, rules = emptySet())

        /** A sentence of [chars] characters ending in a period. */
        fun sentence(chars: Int, fill: Char = 'a'): String = fill.toString().repeat(chars - 1) + "."

        /** A paragraph of [n] sentences of [chars] characters each. */
        fun paragraph(n: Int, chars: Int = 60, fill: Char = 'a'): String =
            List(n) { sentence(chars, fill) }.joinToString(" ")
    }
}

class FakeFocus {
    var grant = true
    var requests = 0
        private set
    var abandons = 0
        private set
    var held = false
        private set
    private var violations = 0

    val port = object : FocusPort {
        override fun request(): Boolean {
            if (held) violations++
            requests++
            held = grant
            return grant
        }

        override fun abandon() {
            if (!held) violations++
            abandons++
            held = false
        }
    }

    fun assertOneRequestPerHold() = assertEquals("focus requested while held / abandoned while not held", 0, violations)
}

class FakeResidency : ResidencyPort {
    val held = HashMap<String, Int>()
    val begins = ArrayList<String>()

    override fun beginSynth(engine: String) {
        begins += engine
        held[engine] = (held[engine] ?: 0) + 1
    }

    override fun endSynth(engine: String) {
        val n = (held[engine] ?: 0) - 1
        check(n >= 0) { "endSynth without beginSynth for $engine" }
        if (n == 0) held.remove(engine) else held[engine] = n
    }

    /** Residency held iff a session is non-idle (a session that has not rendered yet may hold none). */
    fun assertBalancedFor(anyActive: Boolean) {
        if (!anyActive) assertTrue("residency still held with nothing active: $held", held.isEmpty())
    }
}
