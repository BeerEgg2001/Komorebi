package com.beeregg2001.komorebi.ui.components

import com.beeregg2001.komorebi.common.UrlBuilder
import com.beeregg2001.komorebi.viewmodel.Channel

/**
 * KonomiTVモード（Mirakurun設定なし）かどうかを判定するヘルパー
 */
fun isKonomiTvMode(mirakurunIp: String): Boolean {
    return mirakurunIp.isEmpty() || mirakurunIp == "localhost" || mirakurunIp == "127.0.0.1"
}

/**
 * Channelオブジェクトから適切なロゴURLを生成する拡張関数
 */
fun Channel.getLogoUrl(
    mirakurunIp: String,
    mirakurunPort: String,
    konomiIp: String,
    konomiPort: String
): String {
    return if (isKonomiTvMode(mirakurunIp)) {
        UrlBuilder.getKonomiTvLogoUrl(konomiIp, konomiPort, this.displayChannelId)
    } else {
        UrlBuilder.getMirakurunLogoUrl(mirakurunIp, mirakurunPort, this.networkId, this.serviceId)
    }
}