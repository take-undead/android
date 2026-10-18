package app.thermalcam.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** 色は 1 か所に置く（CLAUDE.md G7）。熱画像を見やすくするため常に暗い配色にする */
object ThermalColors {
    val viewBackground = Color(0xFF000000)
    val maxMark = Color(0xFFE61E1E)
    val minMark = Color(0xFF1E6EF0)
    val spotMark = Color(0xFFFFFFFF)
    val markOutline = Color(0xCC000000)
    val warn = Color(0xFFFFB300)
    val err = Color(0xFFEF5350)
    val overlayBackground = Color(0x8C000000) // 画像や色の帯の上に重ねる文字の下地
    val screen = Color(0xFF0B0B0D)            // 画面の地（熱画像が映えるようにほぼ黒）
    val panel = Color(0xFF1C1C21)             // 調整の板・写真のボタンの地
    val accent = Color(0xFFFF8A3D)            // 有効なもの・シャッターの進み（熱画像のアイアンに合う橙）
    val subtle = Color(0xFFA6A6AE)            // 補足の文字
}

private val dark = darkColorScheme(
    primary = ThermalColors.accent, onPrimary = Color(0xFF1A0E05), secondary = Color(0xFF64B5F6),
    background = ThermalColors.screen, surface = Color(0xFF16161A), surfaceContainer = ThermalColors.panel,
    secondaryContainer = Color(0xFF2A2A31), onSecondaryContainer = Color.White,
)

@Composable
fun ThermalTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = dark, content = content)
}
