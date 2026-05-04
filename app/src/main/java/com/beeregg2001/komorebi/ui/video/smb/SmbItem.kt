package com.beeregg2001.komorebi.ui.video.smb

data class SmbItem(
    val name: String,
    val path: String,       // "smb://192.168.x.x/share/video.mp4"
    val isDirectory: Boolean,
    val size: Long,         // ファイルサイズ
    val lastModified: Long, // 更新日時
    val thumbnailUrl: String? = null // 同一階層の .jpg などのパスを後で入れる
)