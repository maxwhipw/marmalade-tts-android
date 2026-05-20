package app.marmalade.tts.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import androidx.room.Room
import app.marmalade.tts.data.db.MarmaladeDb
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
    fun provideDatabase(@ApplicationContext context: Context): MarmaladeDb {
        return Room.databaseBuilder(
            context,
            MarmaladeDb::class.java,
            "marmalade_db",
        ).build()
    }

    @Provides
    @Singleton
    fun provideDataStore(@ApplicationContext context: Context): DataStore<Preferences> {
        return context.dataStore
    }
}
