package app.marmalade.tts.phonemizer

import java.io.File
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Seeding contract for [SharedEspeakData]: seed once, skip when current,
 * re-seed on version change or damage, fail loudly on an incomplete copy.
 * Uses the internal constructor's seams — a temp target dir and a fake
 * asset copier — so no AssetManager is needed on the JVM.
 */
class SharedEspeakDataTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** Writes a complete-looking espeak tree, the way the real asset copy would. */
    private fun completeTreeWriter(content: String = "x"): (File) -> Unit = { dst ->
        for (
        f in listOf(
            "phondata", "phonindex", "phontab", "intonations",
            "en_dict", "es_dict", "fr_dict", "hi_dict", "it_dict", "pt_dict",
        )
        ) {
            File(dst, f).apply { parentFile?.mkdirs() }.writeText(content)
        }
        File(dst, "lang/en").apply { parentFile?.mkdirs() }.writeText(content)
        File(dst, "voices/!v/f1").apply { parentFile?.mkdirs() }.writeText(content)
    }

    private fun shared(
        target: File,
        version: String = "1",
        copy: (File) -> Unit = completeTreeWriter(),
    ) = SharedEspeakData(targetDir = target, dataVersion = version, copyAssets = copy)

    @Test
    fun seedsOnceAndSkipsWhenCurrent() {
        val target = File(tmp.root, "espeak-ng-data")
        var copies = 0
        val data = shared(target, copy = { copies++; completeTreeWriter()(it) })

        assertEquals(target, data.ensure())
        assertTrue(File(target, "es_dict").isFile)
        assertEquals(1, copies)

        data.ensure()
        assertEquals("Marker matches — no re-copy", 1, copies)
    }

    @Test
    fun reseedsOnVersionChange() {
        val target = File(tmp.root, "espeak-ng-data")
        shared(target, version = "1", copy = completeTreeWriter("old")).ensure()

        shared(target, version = "2", copy = completeTreeWriter("new")).ensure()
        assertEquals("new", File(target, "en_dict").readText())
    }

    @Test
    fun reseedsWhenTreeDamagedDespiteMatchingMarker() {
        val target = File(tmp.root, "espeak-ng-data")
        var copies = 0
        val data = shared(target, copy = { copies++; completeTreeWriter()(it) })
        data.ensure()

        // Partial deletion (user "clear storage" mishap, interrupted crash
        // cleanup) must not survive a matching marker.
        File(target, "phondata").delete()
        data.ensure()
        assertEquals(2, copies)
        assertTrue(File(target, "phondata").isFile)
    }

    @Test
    fun incompleteCopyThrowsAndLeavesNoTree() {
        val target = File(tmp.root, "espeak-ng-data")
        val data = shared(target, copy = { dst ->
            File(dst, "en_dict").apply { parentFile?.mkdirs() }.writeText("only this")
        })

        try {
            data.ensure()
            throw AssertionError("expected IOException for incomplete seed")
        } catch (expected: IOException) {
            // A half-seed must not be left behind masquerading as data —
            // the next ensure() should start clean.
            assertFalse(target.exists())
        }
    }
}
