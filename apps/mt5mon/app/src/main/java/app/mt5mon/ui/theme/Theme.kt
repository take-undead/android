package app.mt5mon.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * 色は 1 か所に置く（CLAUDE.md G7）。PicoCalc の ui.c の色をそのまま使う（上げ・買い = 赤、下げ・売り = 水色。利用者の指定）。
 * チャート向けに黒地のダークテーマで固定する。
 */
object Mt5Colors {
    val bg = Color(0xFF000000)
    val surface = Color(0xFF14161C)
    val text = Color(0xFFE6E6E6)
    val dim = Color(0xFF828282)
    val grid = Color(0xFF2D2D2D)
    val sym = Color(0xFFFFDC3C)
    val up = Color(0xFFFF4646)
    val down = Color(0xFF50C8FF)
    val ok = Color(0xFF3CDC5A)
    val warn = Color(0xFFF0C828)
    val err = Color(0xFFFF3C3C)
    val plus = Color(0xFF50DC78)
    val minus = Color(0xFFFF6E3C)
    val bid = Color(0xFFFFDC3C)
    val clock = Color(0xFF6EDCFF)
    val kiri = Color(0xFFBE6EFF)
    val frame = Color(0xFF505F78)
    val head = Color(0xFF6EDCFF)
    val orange = Color(0xFFFF9632)
    val me = Color(0xFF6EDCFF)
    val sel = Color(0xFF283C64)
    val noticeBg = Color(0xFF181028)
}

private val scheme = darkColorScheme(
    primary = Mt5Colors.head,
    onPrimary = Mt5Colors.bg,
    secondary = Mt5Colors.sym,
    background = Mt5Colors.bg,
    onBackground = Mt5Colors.text,
    surface = Mt5Colors.surface,
    onSurface = Mt5Colors.text,
    surfaceVariant = Color(0xFF1E2230),
    onSurfaceVariant = Mt5Colors.dim,
    error = Mt5Colors.err,
    outline = Mt5Colors.frame,
)

@Composable
fun Mt5Theme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, content = content)
}
