package app.thermalcam.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.thermalcam.thermal.Marker
import app.thermalcam.thermal.PhotoComposer
import app.thermalcam.ui.theme.ThermalColors

/**
 * 熱画像と印（点 = 十字、最高 = 赤▲、最低 = 青▼）。タップで点を動かす。
 * 位置合わせは指では動かさない（不意に触れてずれるため。「調整」のつまみか自動位置合わせで行う）。
 */
@Composable
fun ThermalView(
    image: Bitmap?,
    spot: Marker?, max: Marker?, min: Marker?,
    onTap: (Float, Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val textPx = with(density) { 15.sp.toPx() }
    val gestures = Modifier.pointerInput(Unit) {
        detectTapGestures { o -> onTap(o.x / size.width, o.y / size.height) }
    }
    Box(modifier.background(ThermalColors.viewBackground).then(gestures)) {
        if (image != null) {
            Image(
                bitmap = image.asImageBitmap(),
                contentDescription = null, // 温度の値は上の文字で読み上げる（C5: 装飾扱い）
                contentScale = ContentScale.FillBounds,
                filterQuality = FilterQuality.Medium,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Canvas(Modifier.fillMaxSize()) {
            val r = 9.dp.toPx()
            max?.let { triangle(it, ThermalColors.maxMark, up = true, r = r, textPx = textPx) }
            min?.let { triangle(it, ThermalColors.minMark, up = false, r = r, textPx = textPx) }
            spot?.let { cross(it, 16.dp.toPx(), textPx) }
        }
    }
}

private fun DrawScope.label(text: String, x: Float, y: Float, textPx: Float) {
    drawIntoCanvas { c ->
        val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.White.toArgb(); textSize = textPx
            setShadowLayer(4f, 0f, 0f, android.graphics.Color.BLACK)
        }
        val w = p.measureText(text)
        val tx = if (x + w + 8 > size.width) x - w - 8 else x + 8
        c.nativeCanvas.drawText(text, tx, y.coerceIn(textPx, size.height - 4), p)
    }
}

private fun DrawScope.triangle(m: Marker, color: Color, up: Boolean, r: Float, textPx: Float) {
    val x = m.x * size.width; val y = m.y * size.height
    val path = Path().apply {
        if (up) { moveTo(x, y - r); lineTo(x - r, y + r * 0.7f); lineTo(x + r, y + r * 0.7f) }
        else { moveTo(x, y + r); lineTo(x - r, y - r * 0.7f); lineTo(x + r, y - r * 0.7f) }
        close()
    }
    drawPath(path, color)
    drawPath(path, Color.White, style = Stroke(1.5f))
    label(PhotoComposer.fmt(m.celsius), x + r * 0.5f, y + textPx * 0.35f, textPx)
}

private fun DrawScope.cross(m: Marker, r: Float, textPx: Float) {
    val c = Offset(m.x * size.width, m.y * size.height)
    val gap = r * 0.3f
    for (w in listOf(5f to ThermalColors.markOutline, 2.5f to ThermalColors.spotMark)) {
        drawLine(w.second, c + Offset(-r, 0f), c + Offset(-gap, 0f), w.first)
        drawLine(w.second, c + Offset(gap, 0f), c + Offset(r, 0f), w.first)
        drawLine(w.second, c + Offset(0f, -r), c + Offset(0f, -gap), w.first)
        drawLine(w.second, c + Offset(0f, gap), c + Offset(0f, r), w.first)
    }
    label(PhotoComposer.fmt(m.celsius), c.x + r * 0.4f, c.y - r * 0.5f, textPx)
}
