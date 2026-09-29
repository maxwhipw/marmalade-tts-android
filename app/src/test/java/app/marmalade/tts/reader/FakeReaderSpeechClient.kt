package app.marmalade.tts.reader

/**
 * Records what the reader asked [MarmaladeSynthService] to do, so the
 * controller's sequencing can be asserted without a Context or a service.
 */
class FakeReaderSpeechClient : ReaderSpeechClient {

    data class Spoken(
        val requestId: Long,
        val text: String,
        /** The session speed the controller asked for — the article's starting speed unless it was changed. */
        val speed: Float = 1.0f,
        /** False only for the request that starts a play — see [ReaderSpeechClient.speak]. */
        val continuation: Boolean = false,
        /** The voice the article is read in — see [ReaderVoice]. */
        val voice: ReaderVoice = ReaderVoice.Primary,
    )

    val spoken = mutableListOf<Spoken>()
    val stopped = mutableListOf<Long>()
    var pauses = 0
        private set
    var resumes = 0
        private set

    /** Set false to simulate the service refusing a background start. */
    var startAllowed = true

    override fun speak(
        requestId: Long,
        text: String,
        speed: Float,
        continuation: Boolean,
        voice: ReaderVoice,
    ): Boolean {
        if (!startAllowed) return false
        spoken += Spoken(requestId, text, speed, continuation, voice)
        return true
    }

    /** Every [changeSpeed] call: the request ids and the speed. */
    val speedChanges = mutableListOf<Pair<List<Long>, Float>>()

    /**
     * What [changeSpeed] answers: true for an engine that time-stretches (every
     * on-device one), false to simulate a cloud voice with the speed baked in.
     */
    var liveSpeed = true

    override fun changeSpeed(requestIds: List<Long>, speed: Float): Boolean {
        speedChanges += requestIds to speed
        return liveSpeed
    }

    override fun stopRequest(requestId: Long) {
        stopped += requestId
    }

    override fun pause() {
        pauses++
    }

    override fun resume() {
        resumes++
    }

    /** Texts handed over, in order — the readable form of [spoken]. */
    val spokenTexts: List<String> get() = spoken.map { it.text }
}
