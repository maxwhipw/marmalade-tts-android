package app.marmalade.tts.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import app.marmalade.tts.audio.SpeechPlayer
import app.marmalade.tts.audio.Synthesizer
import app.marmalade.tts.data.KittenVoiceCatalog
import app.marmalade.tts.data.db.MarmaladeDb
import app.marmalade.tts.data.db.VoiceMetaDao
import app.marmalade.tts.engine.KittenEngine
import app.marmalade.tts.install.EngineFilesDir
import app.marmalade.tts.install.HttpFetcher
import app.marmalade.tts.install.NativeEngineHandle
import app.marmalade.tts.install.UrlHttpFetcher
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Provider
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

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
        // Provider<> breaks the cyclic dep: the DAO is fetched from the DB,
        // but seeding the DB needs a DAO. Provider defers resolution until
        // after Room finishes building.
        daoProvider: Provider<VoiceMetaDao>,
    ): MarmaladeDb {
        // Single-shot coroutine scope for the seed task — independent of any
        // Android lifecycle. SupervisorJob so a seed failure doesn't tear
        // down the whole process.
        val seedScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        return Room.databaseBuilder(
            context,
            MarmaladeDb::class.java,
            "marmalade_db",
        )
            // v1 was a placeholder with no user data — destructive migration
            // to v2 is safe and avoids hand-writing an empty migration.
            .fallbackToDestructiveMigration()
            .addCallback(object : RoomDatabase.Callback() {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    super.onCreate(db)
                    // Fires exactly once, the first time the DB is created.
                    // Idempotent because upsertAll() uses REPLACE — but the
                    // callback's once-per-lifetime contract means it usually
                    // runs exactly once anyway.
                    seedScope.launch {
                        daoProvider.get().upsertAll(KittenVoiceCatalog.voices)
                    }
                }
            })
            .build()
    }

    @Provides
    @Singleton
    fun provideVoiceMetaDao(db: MarmaladeDb): VoiceMetaDao = db.voiceMetaDao()

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
     * Routes the installer's `NativeEngineHandle` to the live
     * [KittenEngine] so uninstalls can release the JNI handle before
     * deleting the model files. Unit tests substitute a no-op handle.
     */
    @Provides
    @Singleton
    fun provideNativeEngineHandle(engine: KittenEngine): NativeEngineHandle =
        NativeEngineHandle { engine.release() }

    /**
     * HTTP fetcher used by [EngineInstaller]. Production uses
     * `java.net.HttpURLConnection`; tests inject a fake fetcher that
     * serves bytes from a synchronous in-memory map.
     */
    @Provides
    @Singleton
    fun provideHttpFetcher(): HttpFetcher = UrlHttpFetcher
}
