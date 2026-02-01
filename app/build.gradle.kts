plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    alias(libs.plugins.kotlin.compose) // KSPを適用
    id("com.google.dagger.hilt.android") // 追加
    id("kotlin-kapt")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.example.komorebi"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.Komorebi"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
    }
    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
            // もし追加の引数がある場合はここにも書けます
            freeCompilerArgs.add("-Xjvm-default=all")
        }
    }
}
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

kapt {
    correctErrorTypes = true
}

// app/build.gradle.kts
configurations.all {
    resolutionStrategy {
        // Kotlin 2.x のメタデータを正しく読み取れるバージョンに強制
        force("org.jetbrains.kotlinx:kotlinx-metadata-jvm:0.9.0")
        force("org.jetbrains.kotlin:kotlin-stdlib:2.1.0")
        force("org.jetbrains.kotlin:kotlin-reflect:2.1.0")
    }
}

dependencies {
    // Compose で Hilt を使うためのライブラリ
    implementation("androidx.hilt:hilt-navigation-compose:1.1.0")
    // 依存関係を直接指定（バージョンカタログを使わず、確実に同期させる）
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation(platform("androidx.compose:compose-bom:2023.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation(libs.androidx.tv.material)

    // --- 不足分：Retrofit (API通信用) ---
    implementation("com.squareup.retrofit2:retrofit:2.9.0")
    implementation("com.squareup.retrofit2:converter-gson:2.9.0")
    implementation(libs.androidx.compose.ui.test)
    implementation(libs.androidx.compose.ui.text)
    implementation(libs.androidx.compose.foundation) // JSON変換用

    // --- 不足分：Paging 3 (HomeViewModelで使用) ---
    val paging_version = "3.3.0"
    implementation("androidx.paging:paging-runtime:$paging_version")
    implementation("androidx.paging:paging-compose:$paging_version")

    // --- 不足分：Coil (画像表示用) ---
    implementation("io.coil-kt:coil-compose:2.5.0")

    // --- 不足分：Media3 (ExoPlayer - ライブ視聴用) ---
    val media3_version = "1.4.1"
    implementation("androidx.media3:media3-exoplayer:$media3_version")
    implementation("androidx.media3:media3-ui:$media3_version")
    implementation("androidx.media3:media3-common:$media3_version")
    implementation("org.jellyfin.media3:media3-ffmpeg-decoder:1.5.0+1")

    // --- 不足分：TV用 Material3 (ChannelListなどで使用) ---
    implementation("androidx.tv:tv-material:1.0.0-rc02")
    implementation("androidx.tv:tv-foundation:1.0.0-alpha10")

    // Room (ここがクラッシュの原因箇所)
    val room_version = "2.7.0"
    implementation("androidx.room:room-runtime:$room_version")
    implementation("androidx.room:room-ktx:$room_version")
    kapt("androidx.room:room-compiler:$room_version") // kaptではなくksp
    implementation("androidx.room:room-paging:${room_version}")

    // この行を追加（AAPTエラーを解消するために必要）
    implementation("com.google.android.material:material:1.11.0")

    implementation("androidx.compose.ui:ui-text-google-fonts:1.5.4")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.6.2")

    implementation("androidx.media3:media3-exoplayer-hls:1.2.0")
    // Hilt
    implementation("com.google.dagger:hilt-android:2.54")
    // ★ ここで unresolved reference だった 'kapt' が使えるようになります
    kapt("com.google.dagger:hilt-compiler:2.54")

    implementation("androidx.hilt:hilt-navigation-compose:1.2.0")
    // VLC for Android の公式ライブラリ
    implementation("org.videolan.android:libvlc-all:3.6.5")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")
}