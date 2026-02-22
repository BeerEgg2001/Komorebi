plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    alias(libs.plugins.kotlin.compose)
    id("com.google.dagger.hilt.android")
    id("kotlin-kapt")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.beeregg2001.komorebi"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.beeregg2001.Komorebi"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // --- C++ ビルド設定 (1/2): ABIの設定 ---
        externalNativeBuild {
            cmake {
                // 必要に応じて C++ コンパイラ引数を追加
                cppFlags("-std=c++11")
            }
        }
        ndk {
            // 低スペック端末(Android TV等)で一般的なアーキテクチャに限定してビルド時間を短縮
            // 実機が 64bit なら arm64-v8a、32bit なら armeabi-v7a です
            abiFilters.addAll(listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64"))
        }
    }

    // --- C++ ビルド設定 (2/2): CMakeLists.txt のパス指定 ---
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "4.2.3" // インストールされているCMakeのバージョンに合わせて変更可能
        }
    }

    // NDKのバージョンを明示的に指定（Android Studio の SDK Manager でインストール済みのもの）
    // 指定しない場合は最新が使われますが、固定したほうがビルドが安定します
    // ndkVersion = "25.1.8937393"

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
            freeCompilerArgs.addAll(
                "-Xjvm-default=all",
                // ↓ この行を追加：TV Material3 の実験的API警告を無視（許可）します
                "-opt-in=androidx.tv.material3.ExperimentalTvMaterial3Api"
            )
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

// dependencies ブロックを以下のように書き換えてください
dependencies {
    // 1. Compose BOM を最新に近いバージョンに更新 (ここが最重要)
    // 2023.10.01 だと Tv-Foundation 1.0.0-alpha11 と互換性がありません
    implementation(platform("androidx.compose:compose-bom:2024.10.01"))

    // 2. 各ライブラリの指定 (バージョンは BOM が管理するので書かない)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.foundation:foundation") // 追加
    implementation("androidx.compose.ui:ui-graphics")       // 追加

    // 【追加】拡張アイコンセット (CastConnected, Dns, Tv 等を使用するため)
    implementation("androidx.compose.material:material-icons-extended")

    // --- TV用ライブラリ ---
    // これらは BOM に含まれないため、バージョンを固定します
    implementation("androidx.tv:tv-material:1.0.0")
    implementation("androidx.tv:tv-foundation:1.0.0-alpha11")

    // --- Hilt ---
    implementation("androidx.hilt:hilt-navigation-compose:1.2.0")
    implementation("com.google.dagger:hilt-android:2.54")
    kapt("com.google.dagger:hilt-compiler:2.54")

    // --- Retrofit ---
    implementation("com.squareup.retrofit2:retrofit:2.9.0")
    implementation("com.squareup.retrofit2:converter-gson:2.9.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")

    // --- OkHttp ---
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:okhttp-sse:4.12.0")

    // --- Room (KSPへの移行を推奨) ---
    val room_version = "2.7.0-alpha11" // 2.7.0より安定している2.6.1を一旦推奨
    implementation("androidx.room:room-runtime:$room_version")
    implementation("androidx.room:room-ktx:$room_version")
    implementation("androidx.room:room-paging:$room_version")
    ksp("androidx.room:room-compiler:$room_version") // kaptからkspへ

    // --- Media3 ---
    val media3_version = "1.4.1"
    implementation("androidx.media3:media3-exoplayer:$media3_version")
    implementation("androidx.media3:media3-ui:$media3_version")
    implementation("androidx.media3:media3-common:$media3_version")
    implementation("androidx.media3:media3-exoplayer-hls:$media3_version")

    // --- その他 ---
    implementation("io.coil-kt:coil-compose:2.5.0")
    implementation("androidx.paging:paging-runtime:3.3.0")
    implementation("androidx.paging:paging-compose:3.3.0")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("com.google.android.material:material:1.12.0")

    // Maven Central版の正しいID (DanmakuFlameMaster -> dfm)
    implementation("com.github.ctiao:dfm:0.9.25")

    // NDK Bitmap (バージョンは 0.9.21 を指定する必要があります)
    implementation("com.github.ctiao:ndkbitmap-armv7a:0.9.21")
    implementation("com.github.ctiao:ndkbitmap-armv5:0.9.21")
    implementation("com.github.ctiao:ndkbitmap-x86:0.9.21")

}