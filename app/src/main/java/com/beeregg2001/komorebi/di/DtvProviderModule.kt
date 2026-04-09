package com.beeregg2001.komorebi.di

import com.beeregg2001.komorebi.data.repository.DtvProviderProxy
import com.beeregg2001.komorebi.data.repository.EpgProvider
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

    // ★ 修正: 全ての実体を Proxy (代理人) クラスに差し替えます

    @Binds
    abstract fun bindLiveProvider(impl: DtvProviderProxy): LiveProvider

    @Binds
    abstract fun bindRecordProvider(impl: DtvProviderProxy): RecordProvider

    @Binds
    abstract fun bindReserveProvider(impl: DtvProviderProxy): ReserveProvider

    @Binds
    abstract fun bindEpgProvider(impl: DtvProviderProxy): EpgProvider
}