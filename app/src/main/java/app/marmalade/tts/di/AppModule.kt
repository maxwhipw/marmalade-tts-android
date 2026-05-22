package app.marmalade.tts.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import androidx.room.Room
import app.marmalade.tts.audio.SpeechPlayer
import app.marmalade.tts.audio.Synthesizer
import app.marmalade.tts.data.db.MIGRATION_2_3
import app.marmalade.tts.data.db.MarmaladeDb
import app.marmalade.tts.data.db.VoiceAliasDao
import app.marmalade.tts.data.db.VoiceMetaDao
import app.marmalade.tts.engine.KittenEngine
import app.marmalade.tts.engine.KokoroEngine
import app.marmalade.tts.install.EngineFilesDir
import app.marmalade.tts.install.HttpFetcher
import app.marmalade.tts.install.NativeEngineHandle
import app.marmalade.tts.install.UrlHttpFetcher
import app.marmalade.tts.preprocessing.Preprocessor
import app.marmalade.tts.preprocessing.PreprocessingRules
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

// DataStore singleton — one instance per process via extension property
private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(
    name = "marmalade_settings"
)

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideDatabase(
        @ApplicationContext context: Context,
    ): MarmaladeDb {
        // Seeding has moved out of the Room callback and into
        // `MarmaladeTtsApplication.onCreate` so the seed coroutine is
        // attached to an application-scoped CoroutineScope rather than a
        // process-lived one captured by an anonymous Room callback. See
        // Major #4 in the v0.1 whole-project review.
        return Room.databaseBuilder(
            context,
            MarmaladeDb::class.java,
            "marmalade_db",
        )
            // v1→v2 is destructive (v1 was a placeholder with no user data).
            // v2→v3 prefers MIGRATION_2_3 so user-toggled isInstalled flags
            // on voice_meta survive the alias-table add; fallback stays as
            // a belt-and-braces option for any future hash drift.
            .addMigrations(MIGRATION_2_3)
            .fallbackToDestructiveMigration()
            .build()
    }

    @Provides
    @Singleton
    fun provideVoiceMetaDao(db: MarmaladeDb): VoiceMetaDao = db.voiceMetaDao()

    @Provides
    @Singleton
    fun provideVoiceAliasDao(db: MarmaladeDb): VoiceAliasDao = db.voiceAliasDao()

    @Provides
    @Singleton
    fun provideDataStore(@ApplicationContext context: Context): DataStore<Preferences> {
        return context.dataStore
    }

    // -- v0.1 UI plumbing -----------------------------------------------------
    //
    // SettingsRepository is `@Singleton` + `@Inject constructor` so Hilt finds
    // it without an explicit @Provides. The SpeechPlayer binding below is the
    // only thing we *have* to declare — Kotlin's `: SpeechPlayer` interface
    // implementation on Synthesizer isn't enough for Hilt to auto-route
    // SpeechPlayer requests to the concrete class.

    @Provides
    @Singleton
    fun provideSpeechPlayer(impl: Synthesizer): SpeechPlayer = impl

    /**
     * Engine install root — wraps the app's private `filesDir` so the
     * installer doesn't pull in a full Context dependency (lets unit
     * tests stand the installer up against a TemporaryFolder).
     */
    @Provides
    @Singleton
    fun provideEngineFilesDir(@ApplicationContext ctx: Context): EngineFilesDir =
        EngineFilesDir { ctx.filesDir }

    /**
     * Routes the installer's `NativeEngineHandle` to the live engine
     * singletons so uninstalls can release JNI handles before deleting
     * the model files. We release both — the installer doesn't tell us
     * which engine is being uninstalled, and `release()` is idempotent
     * on an unloaded engine, so releasing the wrong one is a harmless
     * no-op. Unit tests substitute a no-op handle.
     */
    @Provides
    @Singleton
    fun provideNativeEngineHandle(
        kitten: KittenEngine,
        kokoro: KokoroEngine,
    ): NativeEngineHandle = NativeEngineHandle {
        kitten.release()
        kokoro.release()
    }

    /**
     * HTTP fetcher used by [EngineInstaller]. Production uses
     * `java.net.HttpURLConnection`; tests inject a fake fetcher that
     * serves bytes from a synchronous in-memory map.
     */
    @Provides
    @Singleton
    fun provideHttpFetcher(): HttpFetcher = UrlHttpFetcher

    /**
     * Single shared [Preprocessor], initialised from the static
     * [PreprocessingRules.ALL] catalog. Stateless; the rules-by-name
     * map is constructed once at injection time.
     */
    @Provides
    @Singleton
    fun providePreprocessor(): Preprocessor = Preprocessor(
        rulesByName = PreprocessingRules.ALL.associateBy { it.name },
    )
}
