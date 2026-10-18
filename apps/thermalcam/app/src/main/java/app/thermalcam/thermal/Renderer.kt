package app.thermalcam.thermal

import android.graphics.Bitmap
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 可視カメラ 1 枚。data = 明るさ（Y、width × height、行ごと）。
 * u / v = 色差（横・縦とも半分の大きさ、行ごと）。「カラー土台」で色を出すのに使う。無ければ白黒
 */
class Luma(
    val data: ByteArray, val width: Int, val height: Int, val orientation: Orientation,
    val u: ByteArray? = null, val v: ByteArray? = null,
)

/**
 * 重ね方。名前は設定と記憶にそのまま保存するので変えない（CLAUDE.md G3）。
 * EDGES = 細部の明暗を足す（MSX 風）、LINES = 輪郭を白い線で描く、BLEND = 白黒の映像を半透明で重ねる、
 * COLOR = カラー映像を土台にして、熱画像を半透明で重ねる（熱画像は画角が狭いので中央だけに乗る）
 */
enum class OverlayMode { OFF, EDGES, LINES, BLEND, COLOR }

/**
 * 熱画像と可視画像の位置合わせ。熱画像の画面座標 (dx, dy) は可視画像の画面座標
 * (0.5 + (dx − 0.5) × scale + offsetX, 0.5 + (dy − 0.5) × scale + offsetY) に当たる。
 * 初期値の scale は画角の比: Lepton 2.5 の水平 50°（データシート）と、可視カメラ ID 2 の
 * 焦点距離 2.0mm・センサー幅 2.90mm から求めた水平 71.9°（dumpsys media.camera）→ tan25° / tan35.9° ≈ 0.644。縦も同じ比。
 */
data class Alignment(val scale: Float = 0.644f, val offsetX: Float = 0f, val offsetY: Float = 0f)

/** 1 枚分の統計（座標はセンサー上の画素） */
data class Stats(
    val min: Float, val minX: Int, val minY: Int,
    val max: Float, val maxX: Int, val maxY: Int,
    val mean: Float,
)

object Renderer {
    /**
     * 画面に出す大きさはセンサーの何倍か（80×60 → 640×480）。間は温度を補間する。
     * 4 倍（320×240）では画面で 4.5 倍に引き伸ばされ、重ねた輪郭や白線が太くギザギザになった（利用者の指摘）ので 8 倍にした
     */
    const val SCALE = 8

    /**
     * 輪郭の強さ（0〜1、「調整」のつまみ）。既定 0.37 は、つまみを付ける前の見え方（輪郭 3.2 倍・白線は上位 15%）と同じ
     * 輪郭: 明暗を足す倍率 1〜7 倍。白線: 輪郭の強さで上位 4〜34% の所を線にする（右ほど弱い輪郭まで線になる）
     */
    const val DEFAULT_EDGE_LEVEL = 0.37f
    fun edgeStrength(level: Float) = 1f + level.coerceIn(0f, 1f) * 6f
    fun lineFraction(level: Float) = 0.04f + level.coerceIn(0f, 1f) * 0.30f

    fun stats(f: ThermalFrame): Stats {
        val t = f.celsius
        var mi = 0; var ma = 0; var sum = 0.0
        for (i in t.indices) {
            if (t[i] < t[mi]) mi = i
            if (t[i] > t[ma]) ma = i
            sum += t[i]
        }
        val w = ThermalFrame.WIDTH
        return Stats(t[mi], mi % w, mi / w, t[ma], ma % w, ma / w, (sum / t.size).toFloat())
    }

    /** 点の温度: 指定した画素を中心に 3×3 画素の平均（1 画素だけより揺れが小さい） */
    fun spot(f: ThermalFrame, x: Int, y: Int): Float {
        var s = 0f; var n = 0
        for (yy in y - 1..y + 1) for (xx in x - 1..x + 1) {
            if (xx in 0 until ThermalFrame.WIDTH && yy in 0 until ThermalFrame.HEIGHT) { s += f[xx, yy]; n++ }
        }
        return s / n
    }

    /**
     * 熱画像を描く。lo〜hi の温度を色の 0〜255 段に割り当てる（範囲の外は端の色）。
     * luma があり mode が OFF でなければ、可視画像の輪郭（EDGES）か白黒の映像（BLEND）を重ねる。
     */
    fun render(
        f: ThermalFrame, lo: Float, hi: Float, palette: Palette, orient: Orientation,
        luma: Luma?, mode: OverlayMode, align: Alignment, edgeLevel: Float = DEFAULT_EDGE_LEVEL,
    ): Bitmap {
        val edgeStrength = edgeStrength(edgeLevel)
        val nw = ThermalFrame.WIDTH; val nh = ThermalFrame.HEIGHT
        val w = (if (orient.swapsAxes) nh else nw) * SCALE
        val h = (if (orient.swapsAxes) nw else nh) * SCALE
        if (mode == OverlayMode.COLOR && luma != null) return renderOnColor(f, lo, hi, palette, orient, luma, align, w, h)
        val px = IntArray(w * h)
        val lut = palette.lut
        val span = (hi - lo).coerceAtLeast(0.1f)
        val t = f.celsius
        val p = FloatArray(2)

        // 可視画像を熱画像の画面の格子に並べ直す（重ねないときは作らない。COLOR で可視が無いときは熱画像だけ）
        val vis: FloatArray? = if (luma != null && mode != OverlayMode.OFF && mode != OverlayMode.COLOR) sampleLuma(luma, w, h, align) else null
        val detail: FloatArray? = if (vis != null && mode == OverlayMode.EDGES) highPass(vis, w, h) else null
        val lines: FloatArray? = if (vis != null && mode == OverlayMode.LINES) lines(vis, w, h, lineFraction(edgeLevel)) else null

        for (oy in 0 until h) for (ox in 0 until w) {
            orient.toNative((ox + 0.5f) / w, (oy + 0.5f) / h, p)
            // 双一次補間（画素の中心が整数座標）
            val fx = (p[0] * nw - 0.5f).coerceIn(0f, nw - 1f)
            val fy = (p[1] * nh - 0.5f).coerceIn(0f, nh - 1f)
            val x0 = fx.toInt().coerceAtMost(nw - 2); val y0 = fy.toInt().coerceAtMost(nh - 2)
            val ax = fx - x0; val ay = fy - y0
            val i0 = y0 * nw + x0
            val v = (t[i0] * (1 - ax) + t[i0 + 1] * ax) * (1 - ay) + (t[i0 + nw] * (1 - ax) + t[i0 + nw + 1] * ax) * ay
            val idx = (((v - lo) / span) * 255f).roundToInt().coerceIn(0, 255)
            var c = lut[idx]
            val o = oy * w + ox
            if (detail != null) {
                c = addLight(c, detail[o] * edgeStrength)
            } else if (lines != null) {
                if (lines[o] > 0f) c = mix(c, 255f, lines[o])
            } else if (vis != null) {
                c = mix(c, vis[o], 0.45f)
            }
            px[o] = c
        }
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
    }

    /** カラー土台で、可視の色に重ねる熱画像の濃さ（0 = 色だけ、1 = 熱画像だけ） */
    const val COLOR_THERMAL_ALPHA = 0.6f

    /**
     * カラー土台: 画面の格子は可視画像の全体（縦持ちで 3:4。熱画像の 480×640 と同じ比）。
     * 各点の可視の色を出し、位置合わせの逆変換で当たる熱画像の点があればその色を半透明で乗せる。
     * 熱画像の画角（50°）は可視（72°）より狭いので、scale 0.644 なら中央の約 64% × 64% にだけ乗る。
     */
    private fun renderOnColor(
        f: ThermalFrame, lo: Float, hi: Float, palette: Palette, orient: Orientation,
        l: Luma, a: Alignment, w: Int, h: Int,
    ): Bitmap {
        val nw = ThermalFrame.WIDTH; val nh = ThermalFrame.HEIGHT
        val px = IntArray(w * h)
        val lut = palette.lut
        val span = (hi - lo).coerceAtLeast(0.1f)
        val t = f.celsius
        val p = FloatArray(2)
        val cw = l.width / 2
        val k = COLOR_THERMAL_ALPHA
        for (oy in 0 until h) for (ox in 0 until w) {
            val vx = (ox + 0.5f) / w; val vy = (oy + 0.5f) / h
            // 可視の色（BT.601。NightProcessor と同じ式）
            l.orientation.toNative(vx, vy, p)
            val sx = (p[0] * l.width).toInt().coerceIn(0, l.width - 1)
            val sy = (p[1] * l.height).toInt().coerceIn(0, l.height - 1)
            val yy = (l.data[sy * l.width + sx].toInt() and 0xFF).toFloat()
            var r = yy; var g = yy; var b = yy
            if (l.u != null && l.v != null) {
                val ci = (sy / 2) * cw + sx / 2
                val uu = (l.u[ci].toInt() and 0xFF) - 128f
                val vv = (l.v[ci].toInt() and 0xFF) - 128f
                r = yy + 1.402f * vv; g = yy - 0.344f * uu - 0.714f * vv; b = yy + 1.772f * uu
            }
            // この点に当たる熱画像の点（位置合わせの逆変換）
            val dx = 0.5f + ((vx - 0.5f) - a.offsetX) / a.scale
            val dy = 0.5f + ((vy - 0.5f) - a.offsetY) / a.scale
            if (dx >= 0f && dx < 1f && dy >= 0f && dy < 1f) {
                orient.toNative(dx, dy, p)
                val fx = (p[0] * nw - 0.5f).coerceIn(0f, nw - 1f)
                val fy = (p[1] * nh - 0.5f).coerceIn(0f, nh - 1f)
                val x0 = fx.toInt().coerceAtMost(nw - 2); val y0 = fy.toInt().coerceAtMost(nh - 2)
                val ax = fx - x0; val ay = fy - y0
                val i0 = y0 * nw + x0
                val v = (t[i0] * (1 - ax) + t[i0 + 1] * ax) * (1 - ay) + (t[i0 + nw] * (1 - ax) + t[i0 + nw + 1] * ax) * ay
                val c = lut[(((v - lo) / span) * 255f).roundToInt().coerceIn(0, 255)]
                r = r * (1 - k) + ((c shr 16) and 0xFF) * k
                g = g * (1 - k) + ((c shr 8) and 0xFF) * k
                b = b * (1 - k) + (c and 0xFF) * k
            }
            px[oy * w + ox] = (0xFF shl 24) or (r.roundToInt().coerceIn(0, 255) shl 16) or
                (g.roundToInt().coerceIn(0, 255) shl 8) or b.roundToInt().coerceIn(0, 255)
        }
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
    }

    /** 熱画像の画面の各点に当たる可視画像の明るさ（0〜255）。可視画像の外は −1 */
    private fun sampleLuma(l: Luma, w: Int, h: Int, a: Alignment): FloatArray {
        val out = FloatArray(w * h)
        val p = FloatArray(2)
        for (oy in 0 until h) for (ox in 0 until w) {
            val vx = 0.5f + ((ox + 0.5f) / w - 0.5f) * a.scale + a.offsetX
            val vy = 0.5f + ((oy + 0.5f) / h - 0.5f) * a.scale + a.offsetY
            if (vx < 0f || vx >= 1f || vy < 0f || vy >= 1f) { out[oy * w + ox] = -1f; continue }
            l.orientation.toNative(vx, vy, p)
            val sx = (p[0] * l.width).toInt().coerceIn(0, l.width - 1)
            val sy = (p[1] * l.height).toInt().coerceIn(0, l.height - 1)
            out[oy * w + ox] = (l.data[sy * l.width + sx].toInt() and 0xFF).toFloat()
        }
        return out
    }

    /** 細部（明るさ − 周り 3×3 の平均）。FLIR の MSX のように輪郭の明暗を足すのに使う */
    private fun highPass(v: FloatArray, w: Int, h: Int): FloatArray {
        val out = FloatArray(w * h)
        for (y in 1 until h - 1) for (x in 1 until w - 1) {
            val i = y * w + x
            if (v[i] < 0f) continue
            var s = 0f; var bad = false
            for (dy in -1..1) for (dx in -1..1) { val q = v[i + dy * w + dx]; if (q < 0f) bad = true; s += q }
            if (!bad) out[i] = v[i] - s / 9f
        }
        return out
    }

    /**
     * 白い線の濃さ（0〜1）。可視画像を少しぼかしてから Sobel で輪郭の強さと向きを出し、
     * 輪郭を横切る向きで一番強い所だけを残して 1 画素の線にする（細い線にするため）。
     * 線にする強さの下限はその画面の上位 fraction（既定 15%）の値（明るい場面・暗い場面のどちらでも線の量が大きく変わらないように）
     */
    private fun lines(v0: FloatArray, w: Int, h: Int, fraction: Float): FloatArray {
        val v = FloatArray(w * h)
        for (y in 1 until h - 1) for (x in 1 until w - 1) {
            val i = y * w + x
            var s = 0f; var bad = false
            for (dy in -1..1) for (dx in -1..1) { val q = v0[i + dy * w + dx]; if (q < 0f) bad = true; s += q }
            v[i] = if (bad) -1f else s / 9f
        }
        val mag = FloatArray(w * h)
        val dir = ByteArray(w * h) // 0 = 横, 1 = 縦, 2 = 右下がり, 3 = 右上がり（輪郭を横切る向き）
        val hist = IntArray(256)
        for (y in 2 until h - 2) for (x in 2 until w - 2) {
            val i = y * w + x
            if (v[i - w - 1] < 0f || v[i - w + 1] < 0f || v[i + w - 1] < 0f || v[i + w + 1] < 0f) continue
            val gx = (v[i - w + 1] + 2 * v[i + 1] + v[i + w + 1]) - (v[i - w - 1] + 2 * v[i - 1] + v[i + w - 1])
            val gy = (v[i + w - 1] + 2 * v[i + w] + v[i + w + 1]) - (v[i - w - 1] + 2 * v[i - w] + v[i - w + 1])
            val m = (abs(gx) + abs(gy)) / 8f
            mag[i] = m
            hist[m.toInt().coerceIn(0, 255)]++
            val ax = abs(gx); val ay = abs(gy)
            dir[i] = when {
                ax > 2.4f * ay -> 0
                ay > 2.4f * ax -> 1
                (gx > 0) == (gy > 0) -> 2
                else -> 3
            }
        }
        var total = 0; for (c in hist) total += c
        var acc = 0; var thr = 255
        for (b in 255 downTo 0) { acc += hist[b]; if (acc >= total * fraction) { thr = b; break } }
        val t = maxOf(thr.toFloat(), 6f) // ほぼ一様な場面で雑音を線にしない
        val out = FloatArray(w * h)
        for (y in 2 until h - 2) for (x in 2 until w - 2) {
            val i = y * w + x
            val m = mag[i]
            if (m < t) continue
            val (a, b) = when (dir[i].toInt()) {
                0 -> mag[i - 1] to mag[i + 1]
                1 -> mag[i - w] to mag[i + w]
                2 -> mag[i - w - 1] to mag[i + w + 1]
                else -> mag[i - w + 1] to mag[i + w - 1]
            }
            if (m >= a && m >= b) out[i] = ((m - t) / t + 0.55f).coerceAtMost(0.95f)
        }
        return out
    }

    private fun addLight(c: Int, d: Float): Int {
        fun ch(s: Int) = (((c shr s) and 0xFF) + d).roundToInt().coerceIn(0, 255)
        return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    private fun mix(c: Int, g: Float, k: Float): Int {
        if (g < 0f) return c
        fun ch(s: Int) = (((c shr s) and 0xFF) * (1 - k) + g * k).roundToInt().coerceIn(0, 255)
        return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }
}

