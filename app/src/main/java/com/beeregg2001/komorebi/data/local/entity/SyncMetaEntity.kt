package com.beeregg2001.komorebi.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 同期エンジンの進捗・中断再開を管理するメタデータ
 */
@Entity(tableName = "sync_meta")
data class SyncMetaEntity(
    @PrimaryKey
    val id: Int = 1, // アプリ全体で1レコードのみ保持するためIDを固定

    @ColumnInfo(name = "last_synced_page")
    val lastSyncedPage: Int,

    @ColumnInfo(name = "last_synced_at")
    val lastSyncedAt: Long,

    @ColumnInfo(name = "is_initial_build_completed")
    val isInitialBuildCompleted: Boolean
)