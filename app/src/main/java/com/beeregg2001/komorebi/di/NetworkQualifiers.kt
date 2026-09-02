package com.beeregg2001.komorebi.di

import javax.inject.Qualifier

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class EpgStationClient

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class EpgStationRetrofit
