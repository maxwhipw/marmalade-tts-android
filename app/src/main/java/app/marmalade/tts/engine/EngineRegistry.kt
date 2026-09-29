package app.marmalade.tts.engine

import app.marmalade.tts.data.CloudApiVoiceCatalog
import app.marmalade.tts.data.KittenDirectVoiceCatalog
import app.marmalade.tts.data.KokoroDirectVoiceCatalog
import app.marmalade.tts.data.KokoroGermanVoiceCatalog
import app.marmalade.tts.data.PocketDevVoiceCatalog
import app.marmalade.tts.data.PocketVoiceCatalog
import app.marmalade.tts.data.VitsVoiceCatalog
import app.marmalade.tts.engine.api.CloudApiEngine
import app.marmalade.tts.engine.kitten.KittenDirectEngine
import app.marmalade.tts.engine.kokoro.KokoroDirectEngine
import app.marmalade.tts.engine.kokoro.KokoroGermanEngine
import app.marmalade.tts.engine.vits.VitsDirectEngine
import app.marmalade.tts.install.EngineCatalog
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The one `engineName → TtsEngine` table.
 *
 * Every place that turns an engine name into an engine goes through here:
 * the in-app playback service, system TTS, [app.marmalade.tts.audio.Synthesizer],
 * residency, warm-up and the installer's native-handle release. They each
 * kept their own `when` table before, and the copies drifted — the cloud and
 * dev-Pocket engines once went missing from one of them, so an alias pointing
 * at them was silently synthesized with Kokoro.
 *
 * Name rules are static ([Companion]) so pure code can narrow a name without
 * an instance; the instance maps names to the live engine singletons.
 */
@Singleton
class EngineRegistry internal constructor(engines: List<TtsEngine>) {

    @Inject
    constructor(
        kokoroDirect: KokoroDirectEngine,
        kokoroGerman: KokoroGermanEngine,
        kittenDirect: KittenDirectEngine,
        pocket: PocketEngine,
        pocketDev: PocketDevEngine,
        vits: VitsDirectEngine,
        cloudApi: CloudApiEngine,
    ) : this(listOf(kokoroDirect, kokoroGerman, kittenDirect, pocket, pocketDev, vits, cloudApi))

    private val table: Map<String, TtsEngine> = engines.associateByTo(LinkedHashMap()) { it.engineName }

    /**
     * Engines that hold a model in memory — every engine but the cloud one,
     * which has nothing to load or release. Residency evicts these, the
     * installer releases their native handles, and "release all" drops them.
     */
    val onDevice: List<TtsEngine> = table.values.filter { it.engineName != CloudApiVoiceCatalog.ENGINE }

    /**
     * What a warm-up loads: the on-device engines minus developer-only
     * diagnostic ones, which a regular user never routes to.
     */
    val warmable: List<TtsEngine> = onDevice.filter { it.engineName !in EngineCatalog.developerOnlyNames }

    /** The engine named [engineName], or null when this registry has none by that name. */
    fun find(engineName: String): TtsEngine? = table[engineName]

    /**
     * The engine named [engineName]; any name this registry doesn't hold
     * degrades to Kokoro Direct, the recommended default — callers narrow
     * names first, so this is the defensive last resort.
     */
    operator fun get(engineName: String): TtsEngine = table[engineName] ?: table.getValue(DEFAULT_ENGINE)

    companion object {
        /** Default engine: Kokoro Direct (matches `EngineCatalog.KOKORO_DIRECT.isRecommended`). */
        const val DEFAULT_ENGINE: String = KokoroDirectVoiceCatalog.ENGINE

        /** Every engine the app can dispatch to, in-app. */
        val ENGINE_NAMES: List<String> = listOf(
            KokoroDirectVoiceCatalog.ENGINE,
            KokoroGermanVoiceCatalog.ENGINE,
            KittenDirectVoiceCatalog.ENGINE,
            PocketVoiceCatalog.ENGINE,
            PocketDevVoiceCatalog.ENGINE,
            VitsVoiceCatalog.ENGINE,
            CloudApiVoiceCatalog.ENGINE,
        )

        /**
         * The engines system TTS dispatches to and advertises: every engine
         * except the developer-only diagnostic ones, which are never offered
         * to other apps.
         */
        val SYSTEM_TTS_ENGINE_NAMES: List<String> =
            ENGINE_NAMES.filter { it !in EngineCatalog.developerOnlyNames }

        /**
         * [name] if the app can dispatch to it, else [DEFAULT_ENGINE]. A name
         * missing from [ENGINE_NAMES] doesn't fail; it silently synthesizes
         * with Kokoro — which for a cloud alias would mean the wrong engine
         * and an unusable voice id with no error anywhere, hence one list.
         */
        fun knownEngineOrDefault(name: String): String =
            if (name in ENGINE_NAMES) name else DEFAULT_ENGINE

        /** System TTS's narrowing: [SYSTEM_TTS_ENGINE_NAMES] or [DEFAULT_ENGINE]. */
        fun systemTtsEngineOrDefault(name: String): String =
            if (name in SYSTEM_TTS_ENGINE_NAMES) name else DEFAULT_ENGINE

        /** True for an engine system TTS may route to. */
        fun isSystemTtsEngine(name: String): Boolean = name in SYSTEM_TTS_ENGINE_NAMES

        /**
         * The engine prefix of a voice id (`"<engine>:<voice>"`), unnarrowed;
         * null for a malformed id (no `:` or nothing before it).
         */
        fun enginePrefixOf(voiceId: String): String? {
            val sep = voiceId.indexOf(':')
            return if (sep <= 0) null else voiceId.substring(0, sep)
        }

        /** In-app engine for [voiceId]; malformed or unknown → [DEFAULT_ENGINE]. */
        fun engineNameFor(voiceId: String): String =
            enginePrefixOf(voiceId)?.let(::knownEngineOrDefault) ?: DEFAULT_ENGINE

        /** System-TTS engine for [voiceId]; malformed, unknown or developer-only → [DEFAULT_ENGINE]. */
        fun systemTtsEngineNameFor(voiceId: String): String =
            enginePrefixOf(voiceId)?.let(::systemTtsEngineOrDefault) ?: DEFAULT_ENGINE

        /** Human-friendly engine label for notification copy. Unknown → the raw name. */
        fun displayName(engineName: String): String = when (engineName) {
            KokoroDirectVoiceCatalog.ENGINE -> "Kokoro"
            KokoroGermanVoiceCatalog.ENGINE -> "Kokoro German"
            KittenDirectVoiceCatalog.ENGINE -> "Kitten Nano"
            PocketVoiceCatalog.ENGINE -> "Pocket TTS"
            PocketDevVoiceCatalog.ENGINE -> "Pocket TTS (clean)"
            VitsVoiceCatalog.ENGINE -> "VITS Marmalade"
            CloudApiVoiceCatalog.ENGINE -> "Cloud"
            else -> engineName
        }
    }
}
