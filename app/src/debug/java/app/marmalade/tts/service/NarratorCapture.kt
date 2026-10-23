package app.marmalade.tts.service

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
 * track appends mono PCM16 to `dev/narrator-capture-<rate>.pcm`. Only the
 * Narrator's own chunks are kept, not the silence the writer pads with
 * while waiting.
 *
 * The release source set has a twin that returns the track unchanged.
 */
object NarratorCapture {

    fun wrap(track: PcmTrack, filesDir: File): PcmTrack {
        if (!File(filesDir, "dev/narrator-capture").isFile) return track
        val out = File(filesDir, "dev/narrator-capture-${track.sampleRate}.pcm")
        Log.i("NarratorCapture", "capturing to $out")
        val stream = FileOutputStream(out, true)
        return object : PcmTrack by track {
            override fun write(pcm: ShortArray, offset: Int, size: Int, tag: ChunkTag?): Int {
                val n = track.write(pcm, offset, size, tag)
                if (n > 0 && tag != null) {
                    val bytes = ByteBuffer.allocate(n * 2).order(ByteOrder.LITTLE_ENDIAN)
                    bytes.asShortBuffer().put(pcm, offset, n)
                    stream.write(bytes.array())
                }
                return n
            }

            override fun release() {
                stream.close()
                track.release()
            }
        }
    }
}
