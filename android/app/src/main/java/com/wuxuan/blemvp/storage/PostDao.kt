package com.wuxuan.blemvp.storage

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface PostDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(post: PostEntity)

    @Query("SELECT * FROM posts ORDER BY timestamp_iso8601 DESC")
    suspend fun getAllLatestFirst(): List<PostEntity>

    @Query("SELECT * FROM posts ORDER BY timestamp_iso8601 DESC")
    fun getAllLatestFirstFlow(): Flow<List<PostEntity>>
}
