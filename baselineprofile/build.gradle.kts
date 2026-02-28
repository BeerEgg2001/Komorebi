plugins {
    alias(libs.plugins.android.test)
    alias(libs.plugins.baselineprofile)
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.beeregg2001.komorebi.baselineprofile"

    // ★修正: ライブラリの要求に合わせて 36 に引き上げ
    compileSdk = 36

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    defaultConfig {
        minSdk = 28
        // ★修正: コンパイルSDKに合わせて引き上げ
        targetSdk = 36

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    targetProjectPath = ":app"

    kotlinOptions {
        jvmTarget = "11"
    }
}

baselineProfile {
    // 実機で収集するため true
    useConnectedDevices = true
}

dependencies {
    implementation(libs.androidx.junit)
    implementation(libs.androidx.espresso.core)
    implementation(libs.androidx.uiautomator)
    implementation(libs.androidx.benchmark.macro.junit4)
    implementation(libs.androidx.core.ktx)
}

androidComponents {
    onVariants { v ->
        val artifactsLoader = v.artifacts.getBuiltArtifactsLoader()
        v.instrumentationRunnerArguments.put(
            "targetAppId",
            v.testedApks.map { artifactsLoader.load(it)?.applicationId }
        )
    }
}