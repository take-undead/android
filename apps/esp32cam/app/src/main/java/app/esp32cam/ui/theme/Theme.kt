package app.esp32cam.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** 色は 1 か所に置く（CLAUDE.md G7）。端末の設定に合わせて明暗を切り替える */
object CamColors {
    val selected = Color(0xFF2196F3)   // 対象のタイルの枠（元の Python と同じ青）
    val video = Color(0xFF202020)      // 映像の背景
    val ok = Color(0xFF2E7D32)
    val warn = Color(0xFFF9A825)
    val err = Color(0xFFC62828)
}

private val light = lightColorScheme(primary = Color(0xFF1B5E20), secondary = Color(0xFF2196F3))
private val dark = darkColorScheme(primary = Color(0xFF81C784), secondary = Color(0xFF64B5F6))

@Composable
fun CamTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) dark else light, content = content)
}
