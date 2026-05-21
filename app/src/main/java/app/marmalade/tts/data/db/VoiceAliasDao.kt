package app.marmalade.tts.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Room DAO for [VoiceAlias] rows.
 *
 * Mirrors [VoiceMetaDao]'s split between Flow-based observation and
 * suspend single-row lookup. The alias list is observed by the alias
 * screen and the speak-screen chip row; the suspend `findByName` is
 * used on the synthesis path (one-shot lookup before applying).
 *
 * Upserts use `OnConflictStrategy.REPLACE` so editing an alias is just
 * an `upsert(alias.copy(...))` with the same name as PK.
 */
@Dao
interface VoiceAliasDao {

    @Query("SELECT * FROM voice_alias ORDER BY createdAt ASC")
    fun getAll(): Flow<List<VoiceAlias>>

    @Query("SELECT * FROM voice_alias WHERE name = :name LIMIT 1")
    suspend fun findByName(name: String): VoiceAlias?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(alias: VoiceAlias)

    @Query("DELETE FROM voice_alias WHERE name = :name")
    suspend fun delete(name: String)
}
