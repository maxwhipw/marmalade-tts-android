package app.marmalade.tts.phonemizer

import android.util.Log
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

// -----------------------------------------------------------------------------
// OpenJtalkPhonemizer — Japanese text-analysis frontend.
//
// Wraps Open JTalk's NJD frontend via a JNI shim (app/src/main/cpp/
// openjtalk_jni.c). Open JTalk is BSD-3 and statically linked into
// libopenjtalk-jni.so (shipped in the APK); only the mecab dictionary
// (open_jtalk_dic_utf_8-1.11) ships in the engine bundle.
//
// This produces the NJD features — kanji→kana reading, mora segmentation,
// pitch accent — that [app.marmalade.tts.phonemizer.CutletJaG2P] turns into
// Kokoro's IPA + pitch-marker token string. It's the exact frontend
// pyopenjtalk wraps, which is what hexgrad trained Kokoro's Japanese voices on.
//
// One global Open JTalk instance lives on the native side (dictionary load is
// expensive). Calls serialise through [lock]; the native side also guards with
// a pthread mutex.
// -----------------------------------------------------------------------------

private const val TAG = "OpenJtalkPhonemizer"

/**
 * One analysed Japanese morpheme from Open JTalk's NJD frontend.
 *
 * @property string  surface form as it appeared in the input (kanji/kana/mixed)
 * @property read    yomi — the full katakana reading (kanji resolved)
 * @property pron    hatsuon — pronunciation katakana with diacritics: `ー`
 *                   (long vowel), `’` (devoiced), etc. This is what CutletJaG2P
 *                   maps to IPA.
 * @property acc     accent nucleus — the mora index where pitch falls. 0 means
 *                   heiban (no fall / flat-high after the first mora).
 * @property moraSize number of moras in this node
 * @property chainFlag phrase-chaining hint from Open JTalk (-1 = phrase start,
 *                   1 = chains onto previous, 0 = standalone). Used to group
 *                   nodes into accent phrases.
 * @property pos     part of speech (名詞 / 助詞 / 記号 …). Punctuation is 記号.
 */
data class NjdNode(
    val string: String,
    val read: String,
    val pron: String,
    val acc: Int,
    val moraSize: Int,
    val chainFlag: Int,
    val pos: String,
)

class OpenJtalkPhonemizer(private val dictDir: String) {

    private val opened = AtomicBoolean(false)

    init {
        if (!jniLoaded) {
            try {
                System.loadLibrary("openjtalk-jni")
                jniLoaded = true
            } catch (t: Throwable) {
                Log.e(TAG, "failed to load libopenjtalk-jni.so", t)
                throw IllegalStateException("Open JTalk JNI shim missing from APK", t)
            }
        }
        require(File(dictDir).isDirectory) { "Open JTalk dict dir not found: $dictDir" }
        require(File(dictDir, "sys.dic").isFile) { "Open JTalk dict missing sys.dic: $dictDir" }
    }

    /** Load the mecab dictionary. Returns true on success. Safe to call twice. */
    fun open(): Boolean {
        val status = nativeOpen(dictDir)
        val ok = status == 1
        opened.set(ok)
        if (ok) Log.i(TAG, "Open JTalk opened (dict=$dictDir)")
        else Log.e(TAG, "Open JTalk open failed (status=$status, dict=$dictDir)")
        return ok
    }

    /**
     * Analyse [text] into NJD morphemes. Returns an empty list on failure or
     * empty input. The frontend handles full Japanese — kanji, kana, mixed,
     * and Latin runs (which it reads character-by-character).
     */
    fun analyze(text: String): List<NjdNode> {
        if (!opened.get()) {
            Log.w(TAG, "analyze called on a closed phonemizer")
            return emptyList()
        }
        val serialized = nativeRunFrontend(text) ?: return emptyList()
        if (serialized.isEmpty()) return emptyList()
        return serialized.lineSequence()
            .filter { it.isNotEmpty() }
            .mapNotNull { line ->
                val f = line.split('\t')
                if (f.size < 7) {
                    Log.w(TAG, "malformed NJD line (${f.size} fields): '$line'")
                    return@mapNotNull null
                }
                NjdNode(
                    string = f[0],
                    read = f[1],
                    pron = f[2],
                    acc = f[3].toIntOrNull() ?: 0,
                    moraSize = f[4].toIntOrNull() ?: 0,
                    chainFlag = f[5].toIntOrNull() ?: 0,
                    pos = f[6],
                )
            }
            .toList()
    }

    fun close() {
        if (opened.compareAndSet(true, false)) {
            nativeClose()
        }
    }

    private external fun nativeOpen(dictDir: String): Int
    private external fun nativeRunFrontend(text: String): String?
    private external fun nativeClose()

    companion object {
        @Volatile private var jniLoaded: Boolean = false
    }
}
