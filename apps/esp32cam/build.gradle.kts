// プロジェクト全体の設定。プラグインは宣言だけして、適用は app モジュール側で行う
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.compose.compiler) apply false
}
