package com.beeregg2001.komorebi.di

import com.beeregg2001.komorebi.data.repository.EpgProvider
import com.beeregg2001.komorebi.data.repository.KonomiRepository
import com.beeregg2001.komorebi.data.repository.LiveProvider
import com.beeregg2001.komorebi.data.repository.RecordProvider
import com.beeregg2001.komorebi.data.repository.ReserveProvider
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class DtvProviderModule {

    // 「LiveProvider」として要求されたら「KonomiRepository」の実体を渡す
    @Binds
    abstract fun bindLiveProvider(
        impl: KonomiRepository
    ): LiveProvider

    // 「RecordProvider」として要求されたら「KonomiRepository」の実体を渡す
    @Binds
    abstract fun bindRecordProvider(
        impl: KonomiRepository
    ): RecordProvider

    // 「ReserveProvider」として要求されたら「KonomiRepository」の実体を渡す
    @Binds
    abstract fun bindReserveProvider(
        impl: KonomiRepository
    ): ReserveProvider

    // 「EpgProvider」として要求されたら「KonomiRepository」の実体を渡す
    @Binds
    abstract fun bindEpgProvider(
        impl: KonomiRepository
    ): EpgProvider
}