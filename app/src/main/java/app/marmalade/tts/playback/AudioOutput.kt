package app.marmalade.tts.playback

import app.marmalade.tts.audio.EffectBlock

/**
 * The Narrator's audio sink. The implementation's writer thread is the ONLY
 * caller of its AudioTracks (C2: `AudioTrackOutput`; tests: the virtual
 * output). [submit] never blocks: the writer applies commands between short
 * slices, in submission order.
 *
 * Two kinds of command:
 *  - **stream items** ([OutputCommand.Begin], [OutputCommand.Write],
 *    [OutputCommand.End]) queue behind the audio already on the track and
 *    take effect at their exact frame;
 *  - **controls** ([OutputCommand.Play], [OutputCommand.Pause],
 *    [OutputCommand.Flush], [OutputCommand.SetTempo],
 *    [OutputCommand.Release]) apply at the next slice.
 *
 * At most two tracks: [Track.Main] (the article, or one-shots when no article
 * is on air) and [Track.Interrupt] (a one-shot reading over a paused article).
 */
interface AudioOutput {
    /** Where the writer reports [OutputEvent]s. Set once, by the Narrator. */
    fun attach(sink: (OutputEvent) -> Unit)

    fun submit(command: OutputCommand)
}

enum class Track { Main, Interrupt }

/** Identity of one rendered chunk. Everything the writer reports carries it. */
data class ChunkTag(val sessionId: Long, val epoch: Int, val segment: Int, val chunk: Int)

sealed interface OutputCommand {
    val track: Track

    /**
     * A session starts at this frame: its own sample rate (the track is
     * rebuilt only if the rate changes), effect chain and live tempo.
     */
    data class Begin(
        override val track: Track,
        val sessionId: Long,
        val epoch: Int,
        val sampleRate: Int,
        val effectBlocks: List<EffectBlock>,
        val tempo: Float,
    ) : OutputCommand

    /** Raw engine PCM; the writer runs the session's chain on it just before the track. */
    data class Write(override val track: Track, val tag: ChunkTag, val pcm: ShortArray) : OutputCommand {
        override fun equals(other: Any?): Boolean = other is Write && other.track == track && other.tag == tag
        override fun hashCode(): Int = 31 * track.hashCode() + tag.hashCode()
    }

    /** The session's audio ends here: the writer flushes its chain tail, then reports [OutputEvent.SessionEnded]. */
    data class End(override val track: Track, val sessionId: Long, val epoch: Int) : OutputCommand

    data class Play(override val track: Track) : OutputCommand
    data class Pause(override val track: Track) : OutputCommand

    /**
     * Drop everything queued on the track (and the chain state), and from now
     * on any item of [sessionId] tagged with an epoch below [minEpoch].
     */
    data class Flush(override val track: Track, val sessionId: Long, val minEpoch: Int) : OutputCommand

    /** Live speed for [sessionId]'s chain, from the next Tempo frame on. */
    data class SetTempo(override val track: Track, val sessionId: Long, val tempo: Float) : OutputCommand

    data class Release(override val track: Track) : OutputCommand
}

sealed interface OutputEvent {
    val track: Track

    /** The chunk's first frame was heard (measured after the effect chain). */
    data class ChunkStarted(override val track: Track, val tag: ChunkTag) : OutputEvent

    /** The chunk's last frame was heard. */
    data class ChunkFinished(override val track: Track, val tag: ChunkTag) : OutputEvent

    /** The session's last frame, chain tail included, was heard. */
    data class SessionEnded(override val track: Track, val sessionId: Long, val epoch: Int) : OutputEvent
}
