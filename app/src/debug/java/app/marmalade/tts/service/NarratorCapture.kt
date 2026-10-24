package app.marmalade.tts.service

import android.os.SystemClock
import android.util.Log
import app.marmalade.tts.playback.ChunkTag
import app.marmalade.tts.playback.PcmTrack
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Copies what the Narrator writes to an output track into a raw PCM file,
 * DEBUG BUILDS ONLY (issue #96): the in-app reader's audio, after speed and
 * effects, for measuring its gaps on a phone without playing it aloud. Armed
 * by `${filesDir}/dev/narrator-capture` existing (pushed with run-as); each
 * track appends mono PCM16 to `dev/narrator-capture-<rate>.pcm`.
 *
 * By default only the Narrator's own chunks are kept, not the silence the
 * writer pads with while waiting. When the arming file says `timeline`, the
 * file is what was heard instead: the padding too, and zeros for the time the
 * track sat empty (a stall, its length estimated from the head), so a gap
 * between sentences includes any wait for the next one.
 *
 * The release source set has a twin that returns the track unchanged.
 */
object NarratorCapture {

    fun wrap(track: PcmTrack, filesDir: File): PcmTrack {
        val arm = File(filesDir, "dev/narrator-capture")
        if (!arm.isFile) return track
        val timeline = arm.readText().trim() == "timeline"
        val out = File(filesDir, "dev/narrator-capture-${track.sampleRate}.pcm")
        Log.i("NarratorCapture", "capturing to $out timeline=$timeline")
        val stream = FileOutputStream(out, true)
        return object : PcmTrack by track {
            /** Frames written to the track since it was built or flushed, padding included. */
            private var total = 0L
            private var lastWriteMs = 0L
            private var lastHead = 0L

            private fun append(pcm: ShortArray, offset: Int, n: Int) {
                val bytes = ByteBuffer.allocate(n * 2).order(ByteOrder.LITTLE_ENDIAN)
                bytes.asShortBuffer().put(pcm, offset, n)
                stream.write(bytes.array())
            }

            override fun write(pcm: ShortArray, offset: Int, size: Int, tag: ChunkTag?): Int {
                val n = track.write(pcm, offset, size, tag)
                if (n <= 0) return n
                if (!timeline) {
                    if (tag != null) append(pcm, offset, n)
                    return n
                }
                val now = SystemClock.elapsedRealtime()
                val head = track.head()
                if (total > 0 && head >= total) {
                    // Everything written was heard: the track ran dry at about
                    // the last write plus what was still queued then.
                    val dryAt = lastWriteMs + (total - lastHead) * 1000 / sampleRate
                    val emptyMs = now - dryAt
                    if (emptyMs > 0) {
                        val zeros = (emptyMs * sampleRate / 1000).toInt()
                        stream.write(ByteArray(zeros * 2))
                    }
                }
                append(pcm, offset, n)
                total += n
                lastWriteMs = now
                lastHead = head
                return n
            }

            override fun flush() {
                track.flush()
                total = 0
                lastHead = 0
            }

            override fun release() {
                stream.close()
                track.release()
            }
        }
    }
}
