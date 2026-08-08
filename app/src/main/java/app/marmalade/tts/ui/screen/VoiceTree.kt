package app.marmalade.tts.ui.screen

import app.marmalade.tts.data.VoicePathResolver
import app.marmalade.tts.data.db.VoiceMeta

// -----------------------------------------------------------------------------
// The voice drill-down: shared model
// -----------------------------------------------------------------------------
// Every voice-browsing surface in the app renders the same source › model ›
// voice tree over the same Room rows — the alias editor's bottom sheet and the
// full-screen picker reached from Speak and from an engine's detail page.
//
// The tree and the navigation rules live here rather than in either ViewModel
// so the two surfaces can't drift. The level-skipping rule in particular is
// easy to get half-right: a source with one model must skip the model level in
// BOTH directions, or Back appears to do nothing.
// -----------------------------------------------------------------------------

/**
 * One level-1 node of the voice picker: everything a single provider or
 * on-device engine can speak with.
 */
data class VoiceSource(
    val name: String,
    val isCloud: Boolean,
    val models: List<VoiceModel>,
) {
    /** Total voices under this source — shown as the row's subtitle. */
    val voiceCount: Int get() = models.sumOf { it.voices.size }
}

/** One level-2 node: a model and the voices it serves. */
data class VoiceModel(val name: String, val voices: List<VoiceMeta>)

/**
 * Where the drill-down currently is.
 *
 * Null [source] = the source list. Non-null [source] with null [model] =
 * the model list. Both non-null = the voice list. A non-blank [query]
 * overrides all of it with a flat cross-level search, because when you
 * know the voice's name you shouldn't have to remember which model it
 * belongs to.
 *
 * [isOpen] only means anything to the sheet; the full-screen picker is
 * always "open" and leaves it at its default.
 */
data class VoicePickerState(
    val isOpen: Boolean = false,
    val source: String? = null,
    val model: String? = null,
    val query: String = "",
) {
    val searching: Boolean get() = query.isNotBlank()
}

/**
 * Group [voices] into the drill-down tree.
 *
 * Built from Room rather than by asking the engines and the provider
 * descriptor separately: cloud voices are already rows keyed
 * `cloud-api-v1:<provider>:<model>:<voice>`, so resolving every row through
 * [VoicePathResolver] and grouping by (source, model) yields the tree for both
 * kinds with one code path.
 *
 * On-device engines produce exactly one model (themselves), so their middle
 * level is degenerate — [selectSourceIn] collapses it rather than rendering a
 * one-item list.
 */
fun buildVoiceTree(voices: List<VoiceMeta>, paths: VoicePathResolver): List<VoiceSource> =
    voices
        .groupBy { paths.resolve(it.id, it.engine).let { p -> p.source to p.isCloud } }
        .map { (key, rows) ->
            val (source, isCloud) = key
            VoiceSource(
                name = source,
                isCloud = isCloud,
                models = rows
                    .groupBy { paths.resolve(it.id, it.engine).model }
                    .map { (model, modelRows) ->
                        VoiceModel(
                            name = model,
                            // Curated order first (Kitten best-first, Kokoro
                            // US › GB › other languages), then name. Cloud rows
                            // keep the sortOrder default so they stay purely
                            // alphabetical.
                            voices = modelRows.sortedWith(
                                compareBy({ it.sortOrder }, { it.displayName.lowercase() }),
                            ),
                        )
                    }
                    .sortedBy { it.name.lowercase() },
            )
        }
        // On-device sources first: they're the ones that always work, and the
        // ones a user reaches for most.
        .sortedWith(compareBy({ it.isCloud }, { it.name.lowercase() }))

/**
 * Enter [source], skipping the degenerate middle level for single-model
 * sources so the user lands straight on the voices.
 */
fun VoicePickerState.selectSourceIn(tree: List<VoiceSource>, source: String): VoicePickerState =
    copy(
        source = source,
        model = tree.firstOrNull { it.name == source }?.models?.singleOrNull()?.name,
    )

/**
 * Step back one level. Clears an active search first, then unwinds the
 * hierarchy. A level that [selectSourceIn] skipped on the way in is skipped on
 * the way out too. At the top level this returns a closed/empty state — the
 * sheet dismisses on that, the full screen pops its own back stack instead and
 * never gets here.
 */
fun VoicePickerState.back(tree: List<VoiceSource>): VoicePickerState {
    val singleModel = tree.firstOrNull { it.name == source }?.models?.size == 1
    return when {
        query.isNotBlank() -> copy(query = "")
        model != null && !singleModel -> copy(model = null)
        source != null -> copy(source = null, model = null)
        else -> VoicePickerState()
    }
}

/** True when [back] would leave the picker rather than move up a level. */
fun VoicePickerState.atTopLevel(): Boolean = source == null && !searching

/** One flat search hit: the voice and the `source › model` path that owns it. */
data class VoiceSearchHit(val voice: VoiceMeta, val path: String)

/**
 * Flat cross-level search over the whole tree. Matches a voice's own name, its
 * model, or its source, so "venice" finds every Venice voice and "kokoro"
 * finds the model's voices even though none of them are called that. Each hit
 * carries its full path, which is what keeps the two different "Alice"
 * (ElevenLabs and Gradium) distinguishable.
 */
fun searchVoiceTree(tree: List<VoiceSource>, query: String): List<VoiceSearchHit> {
    val q = query.trim().lowercase()
    if (q.isEmpty()) return emptyList()
    return tree.flatMap { source ->
        source.models.flatMap { model ->
            model.voices
                .filter {
                    it.displayName.lowercase().contains(q) ||
                        model.name.lowercase().contains(q) ||
                        source.name.lowercase().contains(q)
                }
                .map { VoiceSearchHit(it, "${source.name} › ${model.name}") }
        }
    }
}
