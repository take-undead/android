package app.thermalcam.thermal

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * 自動位置合わせ。熱画像の「温度が急に変わる所」と可視画像の「明るさが急に変わる所」（輪郭の強さ）が
 * 一番よく重なる 大きさ・左右・上下 を探す。重なり具合は相関係数（−1〜1）で測る。
 * 粗く探してから、一番良かった所の周りを細かく探す。
 *
 * 座標はすべて縦持ちの枠（Renderer の Alignment と同じ式）:
 *   熱画像の画面座標 (dx, dy) → 可視画像の画面座標 (0.5 + (dx − 0.5) × scale + offsetX, 0.5 + (dy − 0.5) × scale + offsetY)
 *
 * 大きさは 2 つのカメラの画角の比で決まり距離では変わらないので、今の値のまま動かさない。左右・上下（視差）だけを探す。
 *
 * 限界（2026-10-18、実機のデータを PC で調べた事実）: 温度が変わる所と明るさが変わる所はずれていることが多く、
 * 結果は近くの強い明暗の境目に引っ張られる（モニターの例で 上下 が 0.08 ずれた）。大きさも探すと実行のたびに値が漂った。
 * そのため結果は「提案」として見せ、使う人が輪郭を見て採用するかを決める。
 */
object AutoAlign {
    data class Result(val align: Alignment, val score: Float)

    /** 結果。失敗の理由と測った値を画面に出す（しきい値を実機の事実で詰めるため） */
    sealed interface Outcome {
        data class Ok(val align: Alignment, val score: Float) : Outcome
        data class FlatScene(val edge: Float) : Outcome      // 熱画像に輪郭が少ない
        data class WeakMatch(val score: Float) : Outcome     // 一番良い所でも重なりが弱い
    }

    /** 可視画像の輪郭を作る格子（縦持ち）。熱画像 60×80 の 4 倍 */
    private const val VW = 240
    private const val VH = 320

    /** これより重なりが弱ければ「一致する位置が無い」とする。実機の例では一番良い所が 0.16、全体の中央値が −0.02 だった */
    const val MIN_SCORE = 0.05f

    /** 熱画像の輪郭がこれより弱い（ほぼ一様な温度）ときは探さない。単位は ℃/画素 */
    private const val MIN_THERMAL_EDGE = 0.15f

    fun run(f: ThermalFrame, thermalOrient: Orientation, l: Luma, current: Alignment): Outcome {
        val p = FloatArray(2)

        // 1) 熱画像を縦持ちの格子に並べ、輪郭の強さを出す
        val tw = if (thermalOrient.swapsAxes) ThermalFrame.HEIGHT else ThermalFrame.WIDTH
        val th = if (thermalOrient.swapsAxes) ThermalFrame.WIDTH else ThermalFrame.HEIGHT
        val t = FloatArray(tw * th)
        for (y in 0 until th) for (x in 0 until tw) {
            thermalOrient.toNative((x + 0.5f) / tw, (y + 0.5f) / th, p)
            val nx = (p[0] * ThermalFrame.WIDTH).toInt().coerceIn(0, ThermalFrame.WIDTH - 1)
            val ny = (p[1] * ThermalFrame.HEIGHT).toInt().coerceIn(0, ThermalFrame.HEIGHT - 1)
            t[y * tw + x] = f[nx, ny]
        }
        val tg = gradient(t, tw, th)
        val edge = tg.average().toFloat()
        if (edge < MIN_THERMAL_EDGE) return Outcome.FlatScene(edge)

        // 2) 可視画像を縦持ちの格子に並べ、少しぼかしてから輪郭の強さを出す（熱画像は粗いので細かい模様を消す）
        val v = FloatArray(VW * VH)
        for (y in 0 until VH) for (x in 0 until VW) {
            l.orientation.toNative((x + 0.5f) / VW, (y + 0.5f) / VH, p)
            val sx = (p[0] * l.width).toInt().coerceIn(0, l.width - 1)
            val sy = (p[1] * l.height).toInt().coerceIn(0, l.height - 1)
            v[y * VW + x] = (l.data[sy * l.width + sx].toInt() and 0xFF).toFloat()
        }
        // ぼかしは 6 回（2・6・12 回を比べ、6 回で一番良い所が他と最もはっきり分かれた）
        var vb = v
        repeat(6) { vb = blur(vb, VW, VH) }
        val vg = gradient(vb, VW, VH)

        // 熱画像の輪郭は平均を引いて一度だけ用意する（端の 1 画素は輪郭を出せないので除く）
        val idx = ArrayList<Int>(tw * th)
        for (y in 1 until th - 1) for (x in 1 until tw - 1) idx.add(y * tw + x)
        val tMean = idx.sumOf { tg[it].toDouble() }.toFloat() / idx.size
        val tc = FloatArray(idx.size) { tg[idx[it]] - tMean }
        val tx = FloatArray(idx.size) { (idx[it] % tw + 0.5f) / tw - 0.5f }
        val ty = FloatArray(idx.size) { (idx[it] / tw + 0.5f) / th - 0.5f }

        fun score(s: Float, ox: Float, oy: Float): Float {
            // 相関係数。可視画像の外に出た点は数えない。重なりが半分未満なら採らない
            var n = 0; var sv = 0.0; var svv = 0.0; var stv = 0.0; var stt = 0.0; var st = 0.0
            for (k in tc.indices) {
                val vx = 0.5f + tx[k] * s + ox
                val vy = 0.5f + ty[k] * s + oy
                if (vx < 0f || vx >= 1f || vy < 0f || vy >= 1f) continue
                val g = vg[(vy * VH).toInt() * VW + (vx * VW).toInt()].toDouble()
                val a = tc[k].toDouble()
                n++; sv += g; svv += g * g; stv += a * g; stt += a * a; st += a
            }
            if (n < tc.size / 2) return -1f
            val cov = stv - st * sv / n
            val varV = svv - sv * sv / n
            val varT = stt - st * st / n
            if (varV <= 0 || varT <= 0) return -1f
            return (cov / sqrt(varV * varT)).toFloat()
        }

        // 3) 左右・上下を粗く探す → 一番良かった所の周りを細かく探す（大きさは今の値のまま）
        val sc = current.scale
        var best = Result(current, score(sc, current.offsetX, current.offsetY))
        fun search(cx: Float, cy: Float, span: Float, step: Float) {
            var ox = cx - span
            while (ox <= cx + span + 1e-4f) {
                var oy = cy - span
                while (oy <= cy + span + 1e-4f) {
                    val c = score(sc, ox, oy)
                    if (c > best.score) best = Result(Alignment(sc, ox, oy), c)
                    oy += step
                }
                ox += step
            }
        }
        search(0f, 0f, 0.2f, 0.01f)
        search(best.align.offsetX, best.align.offsetY, 0.01f, 0.0025f)

        return if (best.score >= MIN_SCORE) Outcome.Ok(best.align, best.score) else Outcome.WeakMatch(best.score)
    }

    /** 輪郭の強さ（Sobel の大きさ）。端の 1 画素は 0 */
    private fun gradient(a: FloatArray, w: Int, h: Int): FloatArray {
        val out = FloatArray(w * h)
        for (y in 1 until h - 1) for (x in 1 until w - 1) {
            val i = y * w + x
            val gx = (a[i - w + 1] + 2 * a[i + 1] + a[i + w + 1]) - (a[i - w - 1] + 2 * a[i - 1] + a[i + w - 1])
            val gy = (a[i + w - 1] + 2 * a[i + w] + a[i + w + 1]) - (a[i - w - 1] + 2 * a[i - w] + a[i - w + 1])
            out[i] = (abs(gx) + abs(gy)) / 8f
        }
        return out
    }

    /** 3×3 の平均でぼかす（端はそのまま） */
    private fun blur(a: FloatArray, w: Int, h: Int): FloatArray {
        val out = a.copyOf()
        for (y in 1 until h - 1) for (x in 1 until w - 1) {
            val i = y * w + x
            out[i] = (a[i - w - 1] + a[i - w] + a[i - w + 1] + a[i - 1] + a[i] + a[i + 1] + a[i + w - 1] + a[i + w] + a[i + w + 1]) / 9f
        }
        return out
    }
}
