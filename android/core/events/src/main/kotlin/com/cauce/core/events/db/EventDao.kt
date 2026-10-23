package com.cauce.core.events.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface EventDao {
    @Query("SELECT * FROM events ORDER BY ts ASC, id ASC")
    fun observeAll(): Flow<List<EventEntity>>

    @Query("SELECT * FROM events WHERE type IN (:types) ORDER BY ts ASC, id ASC")
    fun observeByTypes(types: List<String>): Flow<List<EventEntity>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(rows: List<EventEntity>): List<Long>

    @Query("SELECT * FROM events WHERE synced = 0 ORDER BY ts ASC, id ASC")
    suspend fun pending(): List<EventEntity>

    @Query("UPDATE events SET synced = 1 WHERE id IN (:ids)")
    suspend fun markSynced(ids: List<String>)
}
