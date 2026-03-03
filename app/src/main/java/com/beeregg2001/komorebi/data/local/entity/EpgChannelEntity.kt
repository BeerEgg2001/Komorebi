package com.beeregg2001.komorebi.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "epg_channel")
data class EpgChannelEntity(
    @PrimaryKey val id: String,
    val displayChannelId: String,
    val networkId: Int,
    val serviceId: Int,
    val transportStreamId: Int,
    val remoconId: Int,
    val channelNumber: String,
    val type: String,
    val name: String,
    val jikkyoForce: Int?,
    val isSubchannel: Boolean,
    val isRadiochannel: Boolean,
    val isWatchable: Boolean
)