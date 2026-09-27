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
    ): Boolean {
        if (!startAllowed) return false
        spoken += Spoken(requestId, text, speed, continuation)
        return true
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
