package app.marmalade.tts.ui.screen

import app.marmalade.tts.audio.EffectBlock
import app.marmalade.tts.audio.EffectChain
import app.marmalade.tts.audio.EffectResolver
import app.marmalade.tts.audio.SpeechPlayer
import app.marmalade.tts.data.BuiltinEffects
import app.marmalade.tts.data.SettingsRepository
import app.marmalade.tts.data.db.AppAliasMapping
import app.marmalade.tts.data.db.AppAliasMappingDao
import app.marmalade.tts.data.db.Effect
import app.marmalade.tts.data.db.EffectDao
import app.marmalade.tts.data.db.VoiceAlias
import app.marmalade.tts.data.db.VoiceAliasDao
import app.marmalade.tts.data.db.VoiceMeta
import app.marmalade.tts.data.db.VoiceMetaDao
import app.marmalade.tts.preprocessing.EngineProfiles
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

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
 * effect blocks as well as text / voiceId. Destructuring `val (text, voiceId)
 * = …` still works via the generated component1 / component2.
 */
internal data class Call(
    val text: String,
    val voiceId: String,
    val speed: Float,
    val effectBlocks: List<EffectBlock>,
    val phonemizationLanguage: String? = null,
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
        effectBlocks: List<EffectBlock>,
        phonemizationLanguage: String?,
    ): Result<Unit> {
        calls += Call(text, voiceId, speed, effectBlocks, phonemizationLanguage)
        return behaviour()
    }

    override fun cancel() {
        cancelCount += 1
    }

    /** Records preload calls so tests can assert on Speak-screen pre-load behaviour. */
    val preloadCalls = mutableListOf<String>()

    /** What [preload] should report — flip to false to simulate a missing model. */
    var preloadResult: Boolean = true
    override suspend fun preload(voiceId: String): Boolean {
        preloadCalls += voiceId
        return preloadResult
    }

    var releaseAllCount = 0
    override suspend fun releaseAll() {
        releaseAllCount += 1
    }
}

/**
 * [EffectResolver] that maps the built-in effect ids to their canonical
 * [EffectChain] block lists directly — no Room, no `org.json`. Lets the
 * plain-JVM ViewModel tests verify that an alias's effect reaches the player
 * without standing up Robolectric for the JSON round-trip (decode is a stub
 * on the JVM). Unknown / null ids resolve to the dry chain, same as prod.
 */
internal class FakeEffectResolver(
    private val mapping: Map<String, List<EffectBlock>> = mapOf(
        BuiltinEffects.CAVE_ID to EffectChain.CAVE_BLOCKS,
        BuiltinEffects.TELEPHONE_ID to EffectChain.TELEPHONE_BLOCKS,
    ),
) : EffectResolver {
    override suspend fun blocksFor(effectId: String?): List<EffectBlock> =
        effectId?.let { mapping[it] } ?: emptyList()
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

    // Primary alias pointer (nullable). Mirrors the prod field — null
    // means "no primary set" and is the default for fresh test fixtures.
    private val primaryAlias = MutableStateFlow<String?>(null)
    override val primaryAliasName: Flow<String?> = primaryAlias
    override suspend fun setPrimaryAliasName(value: String?) {
        primaryAlias.value = value
    }

    // Backed by NoOpPreferencesDataStore, the real implementations of these
    // two never emit — and any flow that `combine`s one of them then never
    // emits either, which is a hang rather than a failure. Override with
    // real state so consumers like VoicePickerViewModel.voices are testable.
    private val developerEngines = MutableStateFlow(false)
    override val showDeveloperEngines: Flow<Boolean> = developerEngines

    private val cloudKeySet = MutableStateFlow(false)
    override val anyCloudApiKeySet: Flow<Boolean> = cloudKeySet

    // Same reason: CloudApiViewModel gates its whole screen on this, so a
    // never-emitting flow would leave the gate stuck on its null state.
    private val disclaimerAccepted = MutableStateFlow(false)
    override val cloudDisclaimerAccepted: Flow<Boolean> = disclaimerAccepted
    override suspend fun acceptCloudDisclaimer() {
        disclaimerAccepted.value = true
    }

    // Per-engine preprocessing-rule sets. Defaults to "nothing stored"
    // — `enabledRules(engine)` falls back to EngineProfiles.defaultsFor.
    // Tests that want to start from a stored set can call
    // setEnabledRules() in @Before.
    private val rulesByEngine =
        MutableStateFlow<Map<String, Set<String>>>(emptyMap())

    override fun enabledRules(engineName: String): Flow<Set<String>> =
        rulesByEngine.map { stored ->
            stored[engineName] ?: EngineProfiles.defaultsFor(engineName)
        }

    override suspend fun setEnabledRules(engineName: String, rules: Set<String>) {
        rulesByEngine.value = rulesByEngine.value + (engineName to rules)
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
    override suspend fun deleteByEngine(engine: String) {
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
 * In-memory [EffectDao]. Seeded with a couple of built-ins (dummy `blocksJson`
 * — the alias editor's picker only needs id + name, and a real chain would pull
 * in `org.json`, a JVM stub). `BuiltinEffects.*_ID` are `const` so referencing
 * them here doesn't trigger that object's lazy seed-row encode.
 */
internal class FakeEffectDao(
    initial: List<Effect> = listOf(
        Effect(BuiltinEffects.CAVE_ID, "Cave", isBuiltin = true, blocksJson = "[]", createdAt = 0L),
        Effect(BuiltinEffects.TELEPHONE_ID, "Telephone", isBuiltin = true, blocksJson = "[]", createdAt = 0L),
    ),
) : EffectDao {
    private val state = MutableStateFlow(initial)
    override fun getAll() = state
    override suspend fun findById(id: String): Effect? = state.value.firstOrNull { it.id == id }
    override suspend fun upsert(effect: Effect) {
        state.value = state.value.filterNot { it.id == effect.id } + effect
    }
    override suspend fun upsertAll(effects: List<Effect>) {
        val ids = effects.map { it.id }.toSet()
        state.value = state.value.filterNot { it.id in ids } + effects
    }
    override suspend fun deleteCustom(id: String) {
        state.value = state.value.filterNot { it.id == id && !it.isBuiltin }
    }
    override suspend fun pruneBuiltinsNotIn(keepIds: Collection<String>) {
        state.value = state.value.filterNot { it.isBuiltin && it.id !in keepIds }
    }
}

/**
 * In-memory [AppAliasMappingDao]. Same shape as [FakeAliasDao]: a
 * MutableStateFlow standing in for Room's Flow, plus append-only call
 * records so a test can assert what the ViewModel actually wrote.
 */
internal class FakeAppAliasMappingDao(
    initial: List<AppAliasMapping> = emptyList(),
) : AppAliasMappingDao {
    private val state = MutableStateFlow(initial)
    val upserted = mutableListOf<AppAliasMapping>()
    val deleted = mutableListOf<String>()

    override fun getAll() = state
    override suspend fun findByPackage(packageName: String): AppAliasMapping? =
        state.value.firstOrNull { it.packageName == packageName }

    override suspend fun upsert(mapping: AppAliasMapping) {
        upserted += mapping
        // REPLACE semantics: packageName is the PK, so an app that moves to
        // another alias replaces its old row rather than adding a second.
        state.value = state.value.filterNot { it.packageName == mapping.packageName } + mapping
    }

    override suspend fun delete(packageName: String) {
        deleted += packageName
        state.value = state.value.filterNot { it.packageName == packageName }
    }
}

/** Roster stub — returns whatever the test seeds, with no PackageManager. */
internal class FakeInstalledAppsProvider(
    private val apps: List<InstalledApp> = emptyList(),
) : InstalledAppsProvider {
    override suspend fun load(): List<InstalledApp> = apps
}

/** Pro entitlement pinned to a fixed answer (F-Droid behaves as `true`). */
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
