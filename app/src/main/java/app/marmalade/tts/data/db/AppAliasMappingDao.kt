package app.marmalade.tts.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Room DAO for [AppAliasMapping] rows.
 *
 * Mirrors [VoiceAliasDao]'s split between Flow-based observation and
 * suspend single-row lookup. The mapping list is observed by the
 * per-app voices screen; the suspend `findByPackage` is used on the
 * synthesis path (one-shot lookup in [app.marmalade.tts.service.TtsRouter]).
 *
 * Upserts use `OnConflictStrategy.REPLACE` so editing a mapping is just
 * an `upsert(mapping.copy(...))` with the same package name as PK.
 */
@Dao
interface AppAliasMappingDao {

    @Query("SELECT * FROM app_alias_mapping ORDER BY createdAt ASC")
    fun getAll(): Flow<List<AppAliasMapping>>

    @Query("SELECT * FROM app_alias_mapping WHERE packageName = :packageName LIMIT 1")
    suspend fun findByPackage(packageName: String): AppAliasMapping?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(mapping: AppAliasMapping)

    @Query("DELETE FROM app_alias_mapping WHERE packageName = :packageName")
    suspend fun delete(packageName: String)

    /**
     * Drop every mapping pointing at [aliasName].
     *
     * Used when an alias is promoted to primary. The primary already
     * catches every caller that has no rule of its own, so rows naming it
     * are redundant — and worse, the alias card deliberately makes the
     * primary's routing strip inert, so those rows become unreachable and
     * the apps are stuck on it with no way to move them.
     */
    @Query("DELETE FROM app_alias_mapping WHERE aliasName = :aliasName")
    suspend fun releaseAppsRoutedTo(aliasName: String)
}
