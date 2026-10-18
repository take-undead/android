// サーマルカメラ（Blackview BL8800Pro 内蔵の FLIR Lepton）のビューア
// AGP 9 以降は Kotlin が組み込みなので org.jetbrains.kotlin.android は不要
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "app.thermalcam"
    compileSdk = 37 // Compose BOM 2026.09.00 のライブラリが 37 以上を要求（AAR metadata。2026-10-14 ビルドで確認）

    defaultConfig {
        applicationId = "app.thermalcam"
        minSdk = 26 // 対象の端末は Android 11。雛形（hello-world）と同じ 26
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
    }

    buildFeatures {
        compose = true
    }

    // 【警告】CLAUDE.md「事前の注意事項」「基本作法の注意事項」に反する設定をエラーにする
    lint {
        error += listOf(
            "LockedOrientationActivity",               // #1  向きの固定
            "HardcodedText",                           // C1  XML レイアウトへの文字直書き
            "HardcodedDebugMode",                      // B3  android:debuggable の直書き
            "TrustAllX509TrustManager",                // B2  証明書検証の無効化
            "UnusedMaterial3ScaffoldPaddingParameter", // D1  Scaffold の innerPadding 未使用
            "MutableCollectionMutableState",           // D2  mutableStateOf に可変コレクション
        )
        abortOnError = true
        warningsAsErrors = false
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.datastore.preferences) // 設定（色・範囲・位置合わせ）の保存
    implementation(libs.androidx.navigation.compose)    // 画面の切り替え（カメラ / 写真一覧 / 写真の表示。CLAUDE.md G4）
    implementation(libs.kotlinx.coroutines.android)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
