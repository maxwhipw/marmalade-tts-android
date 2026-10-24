package app.marmalade.tts.service

import app.marmalade.tts.playback.PcmTrack
import java.io.File

/** Release twin of the debug source set's reader capture (#96): never captures. */
object NarratorCapture {

    fun wrap(track: PcmTrack, filesDir: File): PcmTrack = track
}
