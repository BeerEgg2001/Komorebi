package com.beeregg2001.komorebi.ui.video.smb.player

import jcifs.CIFSContext
import jcifs.context.BaseContext
import jcifs.config.PropertyConfiguration
import jcifs.smb.NtlmPasswordAuthenticator
import java.util.Properties

object SmbContextBuilder {
    fun build(user: String, pass: String): CIFSContext {
        val props = Properties().apply {
            setProperty("jcifs.smb.client.enableSMB2", "true")
            setProperty("jcifs.smb.client.useSMB2Negotiation", "true")
            setProperty("jcifs.smb.client.minVersion", "SMB1")
            setProperty("jcifs.smb.client.maxVersion", "SMB311")
            setProperty("jcifs.smb.client.ipcSigningEnforced", "false")
            setProperty("jcifs.smb.client.disableSpnegoIntegrity", "true")
            setProperty("jcifs.smb.client.dfs.disabled", "true")

            // ★ 新規追加: 大容量の動画再生向けに通信バッファを最適化 (1MB)
            setProperty("jcifs.smb.client.rcv_buf_size", "1048576")
            setProperty("jcifs.smb.client.snd_buf_size", "1048576")
            setProperty("jcifs.smb.client.tcpNoDelay", "true")
        }
        val baseContext = BaseContext(PropertyConfiguration(props))

        return if (user.isBlank()) {
            try {
                baseContext.withGuestCrendentials()
            } catch (e: Exception) {
                baseContext.withAnonymousCredentials()
            }
        } else {
            val auth = NtlmPasswordAuthenticator(null, user, pass)
            baseContext.withCredentials(auth)
        }
    }
}