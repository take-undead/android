// MT5 Monitor（PicoCalc の mt5mon + vpschat を 1 本にした Android 版）
// AGP 9 以降は Kotlin が組み込みなので org.jetbrains.kotlin.android は不要
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "app.mt5mon"
    compileSdk = 37 // Compose BOM 2026.09.00 のライブラリが 37 以上を要求（AAR metadata。2026-10-14 ビルドで確認）

    defaultConfig {
        applicationId = "app.mt5mon"
        minSdk = 23 // Android 6.0 の MECHEN H1 で使うため 26 から下げた（2026-10-14。CLAUDE.md G8 の例外。アイコンは mipmap-anydpi に予備を置く）
        targetSdk = 36
        versionCode = 1
        versionName = "0.1"
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
        // WireGuard の tunnel ライブラリが Java 8 の API を使うため（ライブラリの README の指示）
        isCoreLibraryDesugaringEnabled = true
    }

    buildFeatures {
        compose = true
    }

    // 【警告】CLAUDE.md「事前の注意事項」「基本作法の注意事項」に反する設定をエラーにする
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
    coreLibraryDesugaring(libs.desugar.jdk.libs)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.wireguard.tunnel)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
