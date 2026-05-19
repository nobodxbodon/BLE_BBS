package com.wuxuan.blemvp.storage

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "posts")
data class PostEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "text") val text: String,
    @ColumnInfo(name = "sender") val sender: String,
    @ColumnInfo(name = "timestamp_iso8601") val timestampIso8601: String
)
