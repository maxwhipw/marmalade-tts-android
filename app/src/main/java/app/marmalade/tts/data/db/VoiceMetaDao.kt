package app.marmalade.tts.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Room DAO for [VoiceMeta] rows.
 *
 * Query methods return `Flow` so the UI can observe install-state changes
 * (e.g. a voice flipping `isInstalled = true` after a model download).
 * Single-row lookups used by the system-TTS callback path are suspend
 * functions so they don't block the service thread.
 *
 * Upserts use `OnConflictStrategy.REPLACE` so re-running the first-launch
 * seed is idempotent and a future "voice catalog refresh" can update
 * existing rows in place.
 */
@Dao
interface VoiceMetaDao {

    @Query("SELECT * FROM voice_meta ORDER BY engine, displayName")
    fun getAll(): Flow<List<VoiceMeta>>

    @Query("SELECT * FROM voice_meta WHERE engine = :engine ORDER BY displayName")
    fun getByEngine(engine: String): Flow<List<VoiceMeta>>

    @Query("SELECT * FROM voice_meta WHERE id = :id LIMIT 1")
    suspend fun findById(id: String): VoiceMeta?

    @Query("SELECT COUNT(*) FROM voice_meta")
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(voice: VoiceMeta)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(voices: List<VoiceMeta>)
}
