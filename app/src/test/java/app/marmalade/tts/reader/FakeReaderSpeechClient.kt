package app.marmalade.tts.reader

/**
 * Records what the reader asked [MarmaladeSynthService] to do, so the
 * controller's sequencing can be asserted without a Context or a service.
 */
class FakeReaderSpeechClient : ReaderSpeechClient {

    data class Spoken(
        val requestId: Long,
        val text: String,
        /** The session speed the controller asked for — 1.0 unless it was set. */
        val speedMultiplier: Float = 1.0f,
    )

    val spoken = mutableListOf<Spoken>()
    val stopped = mutableListOf<Long>()
    var pauses = 0
        private set
    var resumes = 0
        private set

    /** Set false to simulate the service refusing a background start. */
    var startAllowed = true

    override fun speak(requestId: Long, text: String, speedMultiplier: Float): Boolean {
        if (!startAllowed) return false
        spoken += Spoken(requestId, text, speedMultiplier)
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
