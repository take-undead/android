package app.mt5mon.ui

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.mt5mon.data.AlertHit
import app.mt5mon.data.Bar
import app.mt5mon.data.Hist
import app.mt5mon.data.Status
import app.mt5mon.ui.theme.Mt5Colors
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.floor

/**
 * ローソク足（PicoCalc の ui.c の draw_chart と同じ描き方）。
 * 上げ = 赤、下げ = 水色。黄の点線が今の bid。赤・水色の点線が建値。紫がキリ値（1 円は実線、50 銭は点線）。
 */
object ChartData {
    /** M1（確定足＋形成中の足）を tf 分足にまとめる。最初の足が時間足の頭から始まっていなければ捨てる */
    fun buildFromM1(st: Status, tf: Int): List<Bar> {
        val out = ArrayList<Bar>()
        var firstPartial = false
        val all = if (st.cur != null) st.m1 + st.cur else st.m1
        for (b in all) {
            val bucket = b.min - b.min % tf
            val last = out.lastOrNull()
            if (last != null && last.min == bucket) {
                out[out.size - 1] = last.copy(h = maxOf(last.h, b.h), l = minOf(last.l, b.l), c = b.c)
            } else {
                if (out.isEmpty()) firstPartial = b.min != bucket
                out.add(b.copy(min = bucket))
            }
        }
        if (firstPartial && out.size > 1) out.removeAt(0)
        return out
    }

    /** 表示する足: VPS が貯めた古い足（hist）のうち M1 から作った足より前のもの + M1 から作った足 */
    fun buildBars(st: Status, tf: Int, hist: Hist?): List<Bar> {
        val local = buildFromM1(st, tf)
        val out = ArrayList<Bar>()
        if (tf > 1 && hist != null && hist.tf == tf) {
            val limit = local.firstOrNull()?.min ?: Long.MAX_VALUE
            for (b in hist.bars) {
                if (b.min >= limit) break
                out.add(b)
            }
        }
        out.addAll(local)
        return out
    }

    /** 目盛りの間隔: 線が 6 本以下になる、きりのよい値 */
    fun gridStep(range: Double): Double {
        val steps = doubleArrayOf(0.01, 0.02, 0.05, 0.1, 0.2, 0.5, 1.0, 2.0, 5.0, 10.0, 20.0, 50.0, 100.0, 200.0, 500.0, 1000.0)
        for (s in steps) if (range / s <= 6) return s
        return 1000.0
    }
}

@Composable
fun CandleChart(
    st: Status,
    tf: Int,
    hist: Hist?,
    barWidthPx: Int,          // PicoCalc の px（2・3・4）。dp に 2 倍して使う
    alert: AlertHit?,
    nowMillis: Long,
    histErr: String,
    histLoading: Boolean,
    alertLabel: String,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val textPx = with(density) { 11.sp.toPx() }
    val smallPx = with(density) { 10.sp.toPx() }
    val bars = ChartData.buildBars(st, tf, hist)
    val labelFontColor = Mt5Colors.dim.toArgb()

    Canvas(modifier) {
        val scaleW = with(density) { 48.dp.toPx() }      // 右の価格目盛り
        val timeH = with(density) { 14.dp.toPx() }       // 下の時刻
        val chartW = size.width - scaleW
        val candleW = chartW - with(density) { 60.dp.toPx() } // 最新の足が現在値の札に隠れないよう手前で止める
        val chartH = size.height - timeH
        val paint = Paint().apply { isAntiAlias = true; textSize = textPx; typeface = Typeface.MONOSPACE }

        if (bars.isEmpty()) {
            paint.color = labelFontColor
            drawContext.canvas.nativeCanvas.drawText("—", chartW / 2, chartH / 2, paint)
            return@Canvas
        }
        // 足の幅
        var bw = with(density) { (barWidthPx * 2).dp.toPx() }
        if (tf > 1) {
            val w = candleW / bars.size
            val maxW = with(density) { 24.dp.toPx() }
            bw = maxOf(bw, minOf(w, maxW))
        }
        val vis = minOf(bars.size, (candleW / bw).toInt().coerceAtLeast(1))
        val first = bars.size - vis
        var hi = -1e30f
        var lo = 1e30f
        for (i in first until bars.size) {
            if (bars[i].h > hi) hi = bars[i].h
            if (bars[i].l < lo) lo = bars[i].l
        }
        if (st.bid > 0) {
            if (st.bid > hi) hi = st.bid.toFloat()
            if (st.bid < lo) lo = st.bid.toFloat()
        }
        var pad = (hi - lo) * 0.06f
        if (pad < 0.005f) pad = 0.005f
        hi += pad
        lo -= pad
        val y0 = 2f
        val h = chartH - 4f
        fun py(p: Float): Float = y0 + ((hi - p) / (hi - lo)) * (h - 1)
        val dash = PathEffect.dashPathEffect(floatArrayOf(4f, 6f))
        val dash2 = PathEffect.dashPathEffect(floatArrayOf(8f, 6f))

        // 横の目盛り
        val step = ChartData.gridStep((hi - lo).toDouble())
        paint.color = labelFontColor
        var v = ceil(lo / step) * step
        while (v <= hi) {
            val y = py(v.toFloat())
            drawLine(Mt5Colors.grid, Offset(0f, y), Offset(chartW, y), pathEffect = dash)
            drawContext.canvas.nativeCanvas.drawText(String.format(Locale.US, if (v >= 1000) "%.0f" else "%.2f", v), chartW + 4f, y + textPx / 3, paint)
            v += step
        }
        // 時刻の目盛り（M1・M5 は 30 分ごと、M15 は 1 時間ごと）
        val labelStep = if (tf >= 15) 60 else 30
        var lastLabelX = -1000f
        paint.textSize = smallPx
        for (i in first until bars.size) {
            val m = (bars[i].min % 1440).toInt()
            if (m % labelStep != 0) continue
            val x = (i - first) * bw + bw / 2
            drawLine(Mt5Colors.grid, Offset(x, 0f), Offset(x, chartH))
            if (x - lastLabelX >= smallPx * 5 && x + smallPx * 2 <= chartW) {
                val s = String.format(Locale.US, "%02d:%02d", m / 60, m % 60)
                paint.color = labelFontColor
                paint.textAlign = Paint.Align.CENTER
                drawContext.canvas.nativeCanvas.drawText(s, x, size.height - 2f, paint)
                paint.textAlign = Paint.Align.LEFT
                lastLabelX = x
            }
        }
        // キリ値: 1 円ごとに実線、50 銭に点線。左端に値
        run {
            val big = if (st.bid >= 1000) 10.0 else 1.0
            val half = big / 2
            var k = ceil(lo / half) * half
            paint.color = Mt5Colors.kiri.toArgb()
            while (k <= hi) {
                val y = py(k.toFloat())
                val whole = ((k + 1e-6) % big) < 1e-3
                if (whole) drawLine(Mt5Colors.kiri, Offset(0f, y), Offset(chartW, y))
                else drawLine(Mt5Colors.kiri, Offset(0f, y), Offset(chartW, y), pathEffect = dash2)
                // 左上の時間足の名前（2 行分）と重なる位置には値を書かない（実機で「M15」と重なった）
                if (y > textPx * 3.6f) drawContext.canvas.nativeCanvas.drawText(String.format(Locale.US, "%.2f", k), 2f, y - 3f, paint)
                k += half
            }
            // 画面の外にあるときは、上下の端に一番近いキリ値と今の値からの距離（pips）
            if (st.bid > 0) {
                val pip = if (st.digits >= 4) 0.0001 else 0.01
                val up = ceil(hi / half) * half
                val dn = floor(lo / half) * half
                paint.textAlign = Paint.Align.RIGHT
                if (up > hi) drawContext.canvas.nativeCanvas.drawText(String.format(Locale.US, "↑%.2f +%.1f", up, (up - st.bid) / pip), candleW, smallPx + 2f, paint)
                if (dn < lo) drawContext.canvas.nativeCanvas.drawText(String.format(Locale.US, "↓%.2f -%.1f", dn, (st.bid - dn) / pip), candleW, chartH - 4f, paint)
                paint.textAlign = Paint.Align.LEFT
            }
        }
        // 建玉の建値（買い = 赤、売り = 水色の点線）
        for (p in st.positions) {
            if (p.symbol != st.symbol || p.open < lo || p.open > hi) continue
            val y = py(p.open.toFloat())
            val c = if (p.buy) Mt5Colors.up else Mt5Colors.down
            drawLine(c, Offset(0f, y), Offset(chartW, y), pathEffect = dash2)
            paint.color = c.toArgb()
            drawContext.canvas.nativeCanvas.drawText(if (p.buy) "買" else "売", 2f, y - 2f, paint)
        }
        // ローソク（上げ = 赤、下げ = 水色）
        val gap = if (bw >= 12f) 4f else 2f
        val bodyW = if (bw > gap) bw - gap else 1f
        for (i in first until bars.size) {
            val b = bars[i]
            val col = if (b.c >= b.o) Mt5Colors.up else Mt5Colors.down
            val x = (i - first) * bw
            val wickX = x + bodyW / 2
            drawLine(col, Offset(wickX, py(b.h)), Offset(wickX, py(b.l)), strokeWidth = 1.5f)
            val yo = py(b.o)
            val yc = py(b.c)
            val top = minOf(yo, yc)
            val bot = maxOf(yo, yc)
            drawRect(col, Offset(x, top), Size(bodyW, maxOf(bot - top, 1.5f)))
        }
        // 時間足の名前とチャイムの設定（左上）
        paint.textSize = textPx
        paint.color = Mt5Colors.sym.toArgb()
        val tfLabel = "M$tf"
        drawContext.canvas.nativeCanvas.drawText(tfLabel, 2f, textPx * 2.4f, paint)
        paint.color = labelFontColor
        val tfW = paint.measureText(tfLabel)
        var extra = " $alertLabel"
        if (tf > 1 && (hist == null || hist.tf != tf)) extra += if (histErr.isNotEmpty()) "  過去の足: $histErr" else if (histLoading) "  過去の足: 取得中" else ""
        drawContext.canvas.nativeCanvas.drawText(extra, 2f + tfW, textPx * 2.4f, paint)
        // 急な値動きを知らせてから 30 秒は帯で出す
        if (alert != null && alert.dir != 0 && nowMillis - alert.atMillis < 30_000) {
            val s = String.format(Locale.US, " %s %+.1fpips ♪x%d ", if (alert.dir > 0) "急騰" else "急落", alert.pips, alert.reps)
            val c = if (alert.dir > 0) Mt5Colors.up else Mt5Colors.down
            val w = paint.measureText(s)
            drawRect(c, Offset(2f, textPx * 3f), Size(w, textPx * 1.4f))
            paint.color = Mt5Colors.bg.toArgb()
            drawContext.canvas.nativeCanvas.drawText(s, 2f, textPx * 4.1f, paint)
        }
        // チャートと価格の目盛りの区切り
        drawLine(Mt5Colors.frame, Offset(chartW, 0f), Offset(chartW, chartH))
        // 今の bid（黄の点線と、目盛りの所に値）
        if (st.bid > 0) {
            val y = py(st.bid.toFloat())
            drawLine(Mt5Colors.bid, Offset(0f, y), Offset(chartW, y), pathEffect = dash)
            val s = fmtPrice(st.bid, st.digits)
            val w = paint.measureText(s) + 6f
            drawRect(Mt5Colors.bid, Offset(size.width - w, y - textPx * 0.7f), Size(w, textPx * 1.3f))
            paint.color = Mt5Colors.bg.toArgb()
            drawContext.canvas.nativeCanvas.drawText(s, size.width - w + 3f, y + textPx * 0.35f, paint)
        }
    }
}

