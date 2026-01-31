package com.example.komorebi.di

import com.example.komorebi.data.SettingsRepository
import com.example.komorebi.data.repository.KonomiRepository
import com.example.komorebi.data.repository.EpgRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object RepositoryModule {

    // SettingsRepository は @Inject constructor があるので
    // 本来は定義しなくても Hilt が解決できますが、明示的に管理する場合の記述です
    @Provides
    @Singleton
    fun provideSettingsRepository(
        @dagger.hilt.android.qualifiers.ApplicationContext context: android.content.Context
    ): SettingsRepository {
        return SettingsRepository(context)
    }

    // 他の Repository もここに追加していくと見通しが良くなります
    // 例: KonomiRepository や EpgRepository がインターフェースでない場合
    /*
    @Provides
    @Singleton
    fun provideKonomiRepository(...): KonomiRepository {
        return KonomiRepository(...)
    }
    */
}