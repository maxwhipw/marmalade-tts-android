package app.marmalade.tts.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Room DAO for [Effect] rows. The user's own effects sort first (isBuiltin ASC)
 * — a handful of them shouldn't sit below ~20 seeded presets — then by creation
 * time. Both the Effects catalog and the alias editor's effect picker read this
 * one query, so they agree on order. [deleteCustom] refuses to delete built-ins
 * (the WHERE guard), so a stray call can't wipe a seeded preset.
 */
@Dao
interface EffectDao {

    @Query("SELECT * FROM effect ORDER BY isBuiltin ASC, createdAt ASC, name ASC")
    fun getAll(): Flow<List<Effect>>

    @Query("SELECT * FROM effect WHERE id = :id LIMIT 1")
    suspend fun findById(id: String): Effect?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(effect: Effect)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(effects: List<Effect>)

    @Query("DELETE FROM effect WHERE id = :id AND isBuiltin = 0")
    suspend fun deleteCustom(id: String)

    /**
     * Drop seeded built-ins that are no longer in the shipped catalog (e.g. a
     * preset removed in a later version). Only touches built-ins — custom
     * effects are never pruned. An alias still pointing at a pruned id falls
     * back to the dry chain via the resolver.
     */
    @Query("DELETE FROM effect WHERE isBuiltin = 1 AND id NOT IN (:keepIds)")
    suspend fun pruneBuiltinsNotIn(keepIds: Collection<String>)
}
