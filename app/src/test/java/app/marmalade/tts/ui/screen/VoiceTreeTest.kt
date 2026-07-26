package app.marmalade.tts.ui.screen

import app.marmalade.tts.data.CloudApiVoiceCatalog
import app.marmalade.tts.data.VoicePathResolver
import app.marmalade.tts.data.cloud.CloudModel
import app.marmalade.tts.data.cloud.CloudProvider
import app.marmalade.tts.data.cloud.CloudProviderDirectory
import app.marmalade.tts.data.db.VoiceMeta
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Coverage for the drill-down model shared by the alias editor's sheet and
 * the full-screen picker.
 *
 * The rule worth pinning is the degenerate middle level: a source with one
 * model must be skipped in BOTH directions. Skipping it on the way in but not
 * on the way out makes Back look broken — you tap it and the same voice list
 * comes back, because you only moved from (source, model) to (source, null),
 * which renders a one-item model list nobody asked for.
 */
class VoiceTreeTest {

    private val venice = CloudProvider(
        id = "venice",
        displayName = "Venice",
        baseUrl = "https://api.venice.ai/api/v1",
        keyHint = "",
        discoverVoices = true,
        models = listOf(
            CloudModel("tts-kokoro", "Kokoro", listOf("af_heart", "am_adam"), 24_000),
            CloudModel("tts-elevenlabs-turbo-v2-5", "ElevenLabs", listOf("Alice"), 44_100),
        ),
    )

    private val cloudPaths = VoicePathResolver(
        CloudProviderDirectory { id -> venice.takeIf { id == "venice" } },
    )

    /** No cloud providers configured — on-device ids resolve from the catalog. */
    private val localPaths = VoicePathResolver { null }

    private fun localVoice(name: String) = VoiceMeta(
        id = "kitten-direct-v0_8:$name",
        engine = "kitten-direct-v0_8",
        displayName = name,
        languageCode = "en-US",
        sampleRate = 24_000,
        gender = null,
    )

    private fun cloudVoice(model: String, name: String) = VoiceMeta(
        id = CloudApiVoiceCatalog.voiceId("venice", model, name),
        engine = CloudApiVoiceCatalog.ENGINE,
        displayName = name,
        languageCode = "en-US",
        sampleRate = 24_000,
        gender = null,
    )

    @Test
    fun `on-device engine yields one source with one model`() {
        val tree = buildVoiceTree(listOf(localVoice("Bella"), localVoice("Kiki")), localPaths)

        assertEquals(1, tree.size)
        assertEquals(1, tree.single().models.size)
        assertEquals(2, tree.single().voiceCount)
        assertTrue(!tree.single().isCloud)
    }

    @Test
    fun `a cloud provider splits into one model per hosted model`() {
        val tree = buildVoiceTree(
            listOf(
                cloudVoice("tts-kokoro", "af_heart"),
                cloudVoice("tts-kokoro", "am_adam"),
                cloudVoice("tts-elevenlabs-turbo-v2-5", "Alice"),
            ),
            cloudPaths,
        )

        val source = tree.single()
        assertTrue(source.isCloud)
        assertEquals(2, source.models.size)
        assertEquals(3, source.voiceCount)
    }

    @Test
    fun `on-device sources sort above cloud ones`() {
        val tree = buildVoiceTree(
            listOf(cloudVoice("tts-kokoro", "af_heart"), localVoice("Bella")),
            cloudPaths,
        )

        assertEquals(listOf(false, true), tree.map { it.isCloud })
    }

    @Test
    fun `voices sort by display name within a model`() {
        val tree = buildVoiceTree(
            listOf(localVoice("Zoe"), localVoice("bella"), localVoice("Kiki")),
            localPaths,
        )

        assertEquals(
            listOf("bella", "Kiki", "Zoe"),
            tree.single().models.single().voices.map { it.displayName },
        )
    }

    @Test
    fun `entering a single-model source skips straight to its voices`() {
        val tree = buildVoiceTree(listOf(localVoice("Bella")), localPaths)
        val source = tree.single()

        val state = VoicePickerState().selectSourceIn(tree, source.name)

        assertEquals(source.name, state.source)
        assertEquals(source.models.single().name, state.model)
    }

    @Test
    fun `entering a multi-model source stops at the model list`() {
        val tree = buildVoiceTree(
            listOf(cloudVoice("tts-kokoro", "af_heart"), cloudVoice("tts-elevenlabs-turbo-v2-5", "Alice")),
            cloudPaths,
        )

        val state = VoicePickerState().selectSourceIn(tree, tree.single().name)

        assertEquals(tree.single().name, state.source)
        assertNull(state.model)
    }

    @Test
    fun `back out of a single-model source clears both levels at once`() {
        val tree = buildVoiceTree(listOf(localVoice("Bella")), localPaths)
        val entered = VoicePickerState().selectSourceIn(tree, tree.single().name)

        val state = entered.back(tree)

        assertNull(state.source)
        assertNull(state.model)
        assertTrue(state.atTopLevel())
    }

    @Test
    fun `back out of a multi-model source unwinds one level at a time`() {
        val tree = buildVoiceTree(
            listOf(cloudVoice("tts-kokoro", "af_heart"), cloudVoice("tts-elevenlabs-turbo-v2-5", "Alice")),
            cloudPaths,
        )
        val atVoices = VoicePickerState(source = tree.single().name, model = "Kokoro")

        val atModels = atVoices.back(tree)
        assertEquals(tree.single().name, atModels.source)
        assertNull(atModels.model)

        assertTrue(atModels.back(tree).atTopLevel())
    }

    @Test
    fun `back clears an active search before touching the hierarchy`() {
        val tree = buildVoiceTree(listOf(localVoice("Bella")), localPaths)
        val searching = VoicePickerState(source = tree.single().name, query = "bel")

        val state = searching.back(tree)

        assertEquals("", state.query)
        assertEquals(tree.single().name, state.source)
    }

    @Test
    fun `search matches voice, model and source names`() {
        val tree = buildVoiceTree(
            listOf(
                cloudVoice("tts-kokoro", "af_heart"),
                cloudVoice("tts-elevenlabs-turbo-v2-5", "Alice"),
                localVoice("Bella"),
            ),
            cloudPaths,
        )

        assertEquals(listOf("Alice"), searchVoiceTree(tree, "ali").map { it.voice.displayName })
        // Matching the model name returns every voice under it, even though
        // none of them is called "kokoro".
        assertEquals(listOf("af_heart"), searchVoiceTree(tree, "kokoro").map { it.voice.displayName })
        // Matching the source returns everything it hosts.
        assertEquals(2, searchVoiceTree(tree, "venice").size)
    }

    @Test
    fun `search hits carry the path that disambiguates duplicate names`() {
        val tree = buildVoiceTree(
            listOf(cloudVoice("tts-elevenlabs-turbo-v2-5", "Alice")),
            cloudPaths,
        )

        val hit = searchVoiceTree(tree, "alice").single()

        assertTrue("expected source and model in '${hit.path}'", hit.path.contains("›"))
    }

    @Test
    fun `blank search matches nothing rather than everything`() {
        val tree = buildVoiceTree(listOf(localVoice("Bella")), localPaths)

        assertTrue(searchVoiceTree(tree, "   ").isEmpty())
    }
}
