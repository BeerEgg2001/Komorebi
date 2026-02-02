package com.example.komorebi.util

import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import com.example.komorebi.NativeLib

@UnstableApi
class TsReadExDataSourceFactory(
    private val nativeLib: NativeLib,
    initialArgs: Array<String> // 名前を変更
) : androidx.media3.datasource.DataSource.Factory {

    // 外部から書き換え可能なように var にする
    var tsArgs: Array<String> = initialArgs

    override fun createDataSource(): androidx.media3.datasource.DataSource {
        return TsReadExDataSource(nativeLib, tsArgs)
    }
}