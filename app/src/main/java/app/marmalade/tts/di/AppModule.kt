package app.marmalade.tts.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import androidx.room.Room
import app.marmalade.tts.audio.DefaultEffectResolver
import app.marmalade.tts.audio.EffectResolver
import app.marmalade.tts.audio.SpeechPlayer
import app.marmalade.tts.audio.Synthesizer
import app.marmalade.tts.data.db.AppAliasMappingDao
import app.marmalade.tts.data.db.EffectDao
import app.marmalade.tts.data.db.MIGRATION_2_3
import app.marmalade.tts.data.db.MIGRATION_3_4
import app.marmalade.tts.data.db.MIGRATION_4_5
import app.marmalade.tts.data.db.MIGRATION_5_6
import app.marmalade.tts.data.db.MIGRATION_6_7
import app.marmalade.tts.data.db.MIGRATION_7_8
import app.marmalade.tts.data.db.MIGRATION_8_9
import app.marmalade.tts.data.db.MIGRATION_10_11
import app.marmalade.tts.data.db.MarmaladeDb
import app.marmalade.tts.data.db.VoiceAliasDao
import app.marmalade.tts.data.db.VoiceMetaDao
import app.marmalade.tts.engine.PocketDevEngine
import app.marmalade.tts.engine.PocketEngine
import app.marmalade.tts.engine.kitten.KittenDirectEngine
import app.marmalade.tts.engine.kokoro.KokoroDirectEngine
import app.marmalade.tts.data.cloud.CloudJsonHttp
import app.marmalade.tts.data.VoiceLatencySource
import app.marmalade.tts.data.VoiceLatencyTracker
import app.marmalade.tts.data.cloud.CloudProviderDirectory
import app.marmalade.tts.data.cloud.CloudProviderStore
import app.marmalade.tts.data.cloud.UrlCloudJsonHttp
import app.marmalade.tts.engine.api.CloudSpeechHttp
import app.marmalade.tts.engine.api.CompressedAudioDecoder
import app.marmalade.tts.engine.api.MediaCodecAudioDecoder
import app.marmalade.tts.engine.api.UrlCloudSpeechHttp
import app.marmalade.tts.install.EngineFilesDir
import app.marmalade.tts.lang.LangDetector
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
            // on voice_meta survive the alias-table add; v3→v4 prefers
            // MIGRATION_3_4 (additive CREATE TABLE only — no other tables
            // touched). Fallback stays as a belt-and-braces option for any
            // future hash drift.
            .addMigrations(MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_10_11)
            // v10 re-keyed voice_alias from its display name to a UUID and
            // repointed every reference at it. A migration for that is four
            // statements of correlated-subquery SQL whose failure mode is
            // silently mis-linked routing — and Max, the only user pre-1.0,
            // would rather reinstall than carry that risk in the codebase.
            // So there is deliberately no 9→10 migration: any pre-10
            // database has no complete path and this fallback resets it.
            // (Room forbids pairing fallbackToDestructiveMigrationFrom(9)
            // with a migration that ENDS at 9 — crashed at first launch —
            // and the scoped variant bought nothing: without 9→10, older
            // versions have no complete path either way.)
            .fallbackToDestructiveMigration()
            .build()
    }

    @Provides
    @Singleton
    fun provideVoiceMetaDao(db: MarmaladeDb): VoiceMetaDao = db.voiceMetaDao()

    @Provides
    @Singleton
    fun provideEffectDao(db: MarmaladeDb): EffectDao = db.effectDao()

    @Provides
    @Singleton
    fun provideVoiceAliasDao(db: MarmaladeDb): VoiceAliasDao = db.voiceAliasDao()

    @Provides
    @Singleton
    fun provideAppAliasMappingDao(db: MarmaladeDb): AppAliasMappingDao =
        db.appAliasMappingDao()

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
     * The launchable-app roster behind the alias screen's routing sheet.
     * An interface so [app.marmalade.tts.ui.screen.AppRoutingViewModel] can be
     * unit-tested without a PackageManager.
     */
    @Provides
    @Singleton
    fun provideInstalledAppsProvider(
        impl: app.marmalade.tts.ui.screen.PackageManagerAppsProvider,
    ): app.marmalade.tts.ui.screen.InstalledAppsProvider = impl

    /**
     * Resolves an alias's `effectId` to the playable [EffectBlock] chain.
     * Wraps [EffectDao] + JSON decode behind the [EffectResolver] seam so the
     * synth-path callers don't depend on Room/org.json directly.
     */
    @Provides
    @Singleton
    fun provideEffectResolver(
        effectDao: EffectDao,
    ): EffectResolver = DefaultEffectResolver(effectDao)

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
     * the model files. We release all engines — the installer doesn't
     * tell us which engine is being uninstalled, and `release()` is
     * idempotent on an unloaded engine, so releasing the wrong one is
     * a harmless no-op. Unit tests substitute a no-op handle.
     */
    @Provides
    @Singleton
    fun provideNativeEngineHandle(
        kittenDirect: KittenDirectEngine,
        kokoroDirect: KokoroDirectEngine,
        pocket: PocketEngine,
        pocketDev: PocketDevEngine,
    ): NativeEngineHandle = NativeEngineHandle {
        kittenDirect.release()
        kokoroDirect.release()
        pocket.release()
        pocketDev.release()
    }

    /**
     * Synthesis POST seam for the Cloud API engine. Tests substitute a
     * fake that serves a canned WAV stream.
     */
    @Provides
    @Singleton
    fun provideCloudSpeechHttp(): CloudSpeechHttp = UrlCloudSpeechHttp()

    /**
     * MP3→PCM decode seam for the cloud engine. Real impl is MediaCodec;
     * unit tests inject a fake because MediaCodec has no plain-JVM
     * implementation (and Robolectric's shadow doesn't actually decode).
     */
    @Provides
    @Singleton
    fun provideCompressedAudioDecoder(
        @ApplicationContext context: Context,
    ): CompressedAudioDecoder = MediaCodecAudioDecoder(context.cacheDir)

    /**
     * GET seam for [app.marmalade.tts.data.cloud.CloudProviderStore]
     * (provider-list refresh + voice discovery). Tests serve canned JSON.
     */
    @Provides
    @Singleton
    fun provideCloudJsonHttp(): CloudJsonHttp = UrlCloudJsonHttp()

    /** The engine's narrow view of the provider store (base-URL lookup). */
    @Provides
    @Singleton
    fun provideCloudProviderDirectory(store: CloudProviderStore): CloudProviderDirectory = store

    /** The UI's read-only view of measured + seeded voice latency. */
    @Provides
    @Singleton
    fun provideVoiceLatencySource(tracker: VoiceLatencyTracker): VoiceLatencySource = tracker

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

    /**
     * Shared language detector, parsed once from `assets/langdetect.tab`.
     * [LangDetector.load] caches internally too, so the three synthesis
     * routes share one table however they get hold of it.
     */
    @Provides
    @Singleton
    fun provideLangDetector(@ApplicationContext context: Context): LangDetector =
        LangDetector.load(context)
}
