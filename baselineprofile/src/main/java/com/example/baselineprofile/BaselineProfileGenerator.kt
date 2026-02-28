package com.beeregg2001.komorebi.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {

    @get:Rule
    val baselineProfileRule = BaselineProfileRule()

    @Test
    fun generate() {
        // ★修正: 引数から取得。取得できない場合はデフォルト値を試行
        val targetAppId = InstrumentationRegistry.getArguments().getString("targetAppId")
            ?: "com.beeregg2001.komorebi"

        baselineProfileRule.collect(
            packageName = targetAppId
        ) {
            pressHome()
            startActivityAndWait()

            // TV操作のシミュレーション
            device.waitForIdle()
            repeat(3) {
                device.pressDPadDown()
                Thread.sleep(500)
            }
        }
    }
}