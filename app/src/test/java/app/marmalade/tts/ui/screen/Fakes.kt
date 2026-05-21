package app.marmalade.tts.ui.screen

import app.marmalade.tts.audio.EffectPreset
import app.marmalade.tts.audio.SpeechPlayer
import app.marmalade.tts.data.SettingsRepository
import app.marmalade.tts.data.db.VoiceAlias
import app.marmalade.tts.data.db.VoiceAliasDao
import app.marmalade.tts.data.db.VoiceMeta
import app.marmalade.tts.data.db.VoiceMetaDao
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf

/**
 * Shared hand-rolled fakes for the ViewModel tests in this package.
 *
 * No mockito / mockk in this project — the trade-off is a few more lines
 * of plumbing here in exchange for keeping the test dependency footprint
 * to junit + kotlinx-coroutines-test only.
 */

/**
 * SpeechPlayer that records every call and lets each test inject the
 * Result it wants back. `cancel()` increments a counter instead of doing
 * any real work — both ViewModels use cancel() defensively before /
 * after speak() and we want to observe that.
 *
 * `Call` is a data class (not a Pair) so tests can assert against speed /
 * effect as well as text / voiceId. Destructuring `val (text, voiceId) = …`
 * still works via the generated component1 / component2.
 */
internal data class Call(
    val text: String,
    val voiceId: String,
    val speed: Float,
    val effect: EffectPreset,
)

internal class RecordingPlayer(
    private val behaviour: () -> Result<Unit> = { Result.success(Unit) },
) : SpeechPlayer {
    val calls = mutableListOf<Call>()
    var cancelCount = 0
        private set

    override suspend fun speak(
        text: String,
        voiceId: String,
        speed: Float,
        effect: EffectPreset,
    ): Result<Unit> {
        calls += Call(text, voiceId, speed, effect)
        return behaviour()
    }

    override fun cancel() {
        cancelCount += 1
    }
}

/**
 * In-memory [SettingsRepository] for tests.
 *
 * The real class is `open` so we can override its two members directly
 * and avoid the DataStore dependency. The base constructor still requires
 * a `DataStore<Preferences>` instance — we satisfy it with a no-op
 * implementation whose data Flow is never collected.
 */
internal class FakeSettings(
    initialId: String,
    initialOnboarded: Boolean = true,
) : SettingsRepository(
    dataStore = NoOpPreferencesDataStore,
) {
    private val state = MutableStateFlow(initialId)
    private val onboardedState = MutableStateFlow(initialOnboarded)
    override val defaultVoiceId: Flow<String> = state
    override suspend fun setDefaultVoiceId(id: String) {
        state.value = id
    }

    override val onboarded: Flow<Boolean> = onboardedState
    override suspend fun setOnboarded(value: Boolean) {
        onboardedState.value = value
    }
}

/**
 * Minimal in-memory [VoiceMetaDao].
 *
 * Read-only — both ViewModels only consume the Flow / suspend lookup
 * surfaces. Write methods throw so a test that touches them fails loudly
 * instead of silently no-op'ing.
 */
internal class FakeDao(private val voices: List<VoiceMeta>) : VoiceMetaDao {
    override fun getAll() = flowOf(voices)
    override fun getByEngine(engine: String) =
        flowOf(voices.filter { it.engine == engine })
    override suspend fun findById(id: String): VoiceMeta? =
        voices.firstOrNull { it.id == id }
    override suspend fun count(): Int = voices.size
    override suspend fun upsert(voice: VoiceMeta) {
        throw UnsupportedOperationException("read-only fake")
    }
    override suspend fun upsertAll(voices: List<VoiceMeta>) {
        throw UnsupportedOperationException("read-only fake")
    }
}

/**
 * In-memory [VoiceAliasDao] used by both [SpeakViewModel] and
 * [AliasViewModel] tests.
 *
 * Originally read-only (SpeakViewModel only consumes `getAll()` /
 * `findByName`). Extended for [AliasViewModelTest] to support writes —
 * the alias editor exercises upsert + delete, and tests assert on the
 * recorded calls. The state Flow also reflects writes so subsequent
 * `aliases.value` reads inside the ViewModel see the updated list.
 *
 * The recorded-call lists ([upsertedAliases], [deletedNames]) are
 * append-only; tests inspect them after the ViewModel coroutine has
 * settled (UnconfinedTestDispatcher + viewModelScope.launch resolves
 * synchronously under `runTest`).
 */
internal class FakeAliasDao(
    private val initial: List<VoiceAlias> = emptyList(),
) : VoiceAliasDao {
    private val state = MutableStateFlow(initial)
    val upsertedAliases = mutableListOf<VoiceAlias>()
    val deletedNames = mutableListOf<String>()

    override fun getAll() = state
    override suspend fun findByName(name: String): VoiceAlias? =
        state.value.firstOrNull { it.name == name }

    override suspend fun upsert(alias: VoiceAlias) {
        upsertedAliases += alias
        // REPLACE semantics: drop any row with the same PK before adding.
        state.value = state.value.filterNot { it.name == alias.name } + alias
    }

    override suspend fun delete(name: String) {
        deletedNames += name
        state.value = state.value.filterNot { it.name == name }
    }
}

/**
 * A `DataStore<Preferences>` that does nothing. [FakeSettings] passes
 * this to its parent constructor purely to satisfy nullability — the
 * parent's `defaultVoiceId` field is shadowed by the override so the
 * upstream `data.map { … }` flow is never collected.
 */
internal val NoOpPreferencesDataStore =
    object : androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences> {
        override val data: Flow<androidx.datastore.preferences.core.Preferences> = emptyFlow()

        override suspend fun updateData(
            transform: suspend (
                androidx.datastore.preferences.core.Preferences,
            ) -> androidx.datastore.preferences.core.Preferences,
        ): androidx.datastore.preferences.core.Preferences =
            throw UnsupportedOperationException("test stub")
    }
