// AGP 9 以降は Kotlin が組み込みなので org.jetbrains.kotlin.android は不要
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "com.example.helloworld"
    compileSdk = 37 // Compose BOM 2026.09.00 のライブラリが 37 以上を要求（AAR metadata。2026-10-14 ビルドで確認）

    defaultConfig {
        applicationId = "com.example.helloworld"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
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

    // 【警告】スマホ・タブレット両対応を壊す設定をエラーにする(CLAUDE.md「事前の注意事項」参照)
    lint {
        error += listOf(
            "LockedOrientationActivity",              // #1  向きの固定
            "HardcodedText",                          // C1  XML レイアウトへの文字直書き
            "HardcodedDebugMode",                     // B3  android:debuggable の直書き
            "TrustAllX509TrustManager",               // B2  証明書検証の無効化
            "UnusedMaterial3ScaffoldPaddingParameter", // D1  Scaffold の innerPadding 未使用
            "MutableCollectionMutableState",          // D2  mutableStateOf に可変コレクション
        )
        abortOnError = true
        warningsAsErrors = false
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
