package com.beeregg2001.komorebi.ui.video.smb.player

import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import jcifs.CIFSContext

@UnstableApi
class SmbDataSourceFactory(
    private val cifsContext: CIFSContext
) : DataSource.Factory {
    override fun createDataSource(): DataSource {
        return SmbDataSource(cifsContext)
    }
}