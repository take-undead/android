package app.thermalcam.thermal

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import androidx.core.graphics.createBitmap
import java.util.Locale

/** 保存する写真を作る: 熱画像（拡大）+ 点・最高・最低の印 + 右に色の帯と温度 + 下に日時とゲイン */
object PhotoComposer {
    private const val IMAGE_LONG = 960
    private const val BAR = 140
    private const val FOOTER = 56

    fun fmt(c: Float) = String.format(Locale.US, "%.1f°C", c)

    fun compose(
        image: Bitmap, lo: Float, hi: Float, palette: Palette,
        spot: Marker?, max: Marker?, min: Marker?, footer: String,
    ): Bitmap {
        val k = IMAGE_LONG.toFloat() / maxOf(image.width, image.height)
        val iw = (image.width * k).toInt(); val ih = (image.height * k).toInt()
        val out = createBitmap(iw + BAR, ih + FOOTER)
        val c = Canvas(out)
        c.drawColor(Color.BLACK)
        c.drawBitmap(image, Rect(0, 0, image.width, image.height), Rect(0, 0, iw, ih), Paint(Paint.FILTER_BITMAP_FLAG))

        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 34f; setShadowLayer(4f, 0f, 0f, Color.BLACK) }
        val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 4f; style = Paint.Style.STROKE; setShadowLayer(3f, 0f, 0f, Color.BLACK) }

        spot?.let {
            val x = it.x * iw; val y = it.y * ih
            line.color = Color.WHITE
            c.drawLine(x - 30, y, x - 8, y, line); c.drawLine(x + 8, y, x + 30, y, line)
            c.drawLine(x, y - 30, x, y - 8, line); c.drawLine(x, y + 8, x, y + 30, line)
            c.drawText(fmt(it.celsius), x + 14, y - 14, text)
        }
        fun tri(m: Marker, color: Int, up: Boolean) {
            val x = m.x * iw; val y = m.y * ih
            val p = Path().apply {
                if (up) { moveTo(x, y - 14); lineTo(x - 13, y + 10); lineTo(x + 13, y + 10) }
                else { moveTo(x, y + 14); lineTo(x - 13, y - 10); lineTo(x + 13, y - 10) }
                close()
            }
            c.drawPath(p, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color })
            c.drawPath(p, line.apply { this.color = Color.WHITE; strokeWidth = 2f })
            val tx = if (x > iw - 160) x - 160 else x + 16
            c.drawText(fmt(m.celsius), tx, y + 12, text)
        }
        max?.let { tri(it, Color.rgb(230, 30, 30), true) }
        min?.let { tri(it, Color.rgb(30, 110, 240), false) }

        // 色の帯（上が高温）
        val bx = iw + 16f; val bw = 36f; val top = 50f; val bottom = ih - 50f
        val lut = palette.lut
        val seg = Paint()
        val n = (bottom - top).toInt()
        for (i in 0 until n) {
            seg.color = lut[(255 - i * 255 / maxOf(1, n - 1)).coerceIn(0, 255)]
            c.drawRect(RectF(bx, top + i, bx + bw, top + i + 1), seg)
        }
        val small = Paint(text).apply { textSize = 28f }
        c.drawText(fmt(hi), iw + 8f, top - 14, small)
        c.drawText(fmt(lo), iw + 8f, bottom + 36, small)
        c.drawText(fmt((lo + hi) / 2), bx + bw + 6, (top + bottom) / 2 + 10, Paint(small).apply { textSize = 22f })

        c.drawText(footer, 16f, ih + FOOTER - 16f, Paint(text).apply { textSize = 28f })
        return out
    }
}
