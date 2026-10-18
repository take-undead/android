package app.thermalcam.night

import android.graphics.Bitmap
import app.thermalcam.thermal.Orientation
import kotlin.math.pow
import kotlin.math.roundToInt

/** 表示の色: カラー / 白黒 / 緑（暗視装置風） */
enum class NightView { COLOR, MONO, GREEN }

/**
 * 写真の絵を作る（画面の映像は TextureView に直接流し、色の変換だけ描画の段階でかける。NightScreen）。
 *  - ノイズ除去: 連写した枚を足して割る
 *  - 暗部を明るく: 明るさ（Y）に 1 より小さいべき乗をかける（暗い所ほど持ち上がる）
 *  - 向き: センサーの並び → 画面の向きに回して並べる
 */
object NightProcessor {
        /** 撮影用: 連写した枚の平均で絵を作る */
        fun average(frames: List<YuvFrame>, orient: Orientation, lift: Float, view: NightView): Bitmap {
            val f0 = frames.first()
            val sy = IntArray(f0.y.size); val su = IntArray(f0.u.size); val sv = IntArray(f0.v.size)
            for (f in frames) {
                for (i in sy.indices) sy[i] += f.y[i].toInt() and 0xFF
                for (i in su.indices) { su[i] += f.u[i].toInt() and 0xFF; sv[i] += f.v[i].toInt() and 0xFF }
            }
            val n = frames.size.toFloat()
            val y = FloatArray(sy.size) { sy[it].toFloat() }
            val u = FloatArray(su.size) { su[it].toFloat() }
            val v = FloatArray(sv.size) { sv[it].toFloat() }
            return render(f0.w, f0.h, y, u, v, 1f / n, orient, lift, view)
        }

        /**
         * 暗部を明るくする表（0〜255 → 0〜255）。lift 0 = そのまま、1 = べき乗 0.6。
         * 0.4 まで下げると、真っ黒（0）の所と少しだけ明るい所の差が開き、暗い所に黒いまだらが出た（2026-10-18 実機の暗い部屋で確認）
         */
        private fun liftLut(lift: Float): FloatArray {
            val g = 1f - 0.4f * lift.coerceIn(0f, 1f)
            return FloatArray(256) { (it / 255f).pow(g) * 255f }
        }

        private fun render(
            w: Int, h: Int, y: FloatArray, u: FloatArray, v: FloatArray, scale: Float,
            orient: Orientation, lift: Float, view: NightView,
        ): Bitmap {
            val lut = liftLut(lift)
            val ow = if (orient.swapsAxes) h else w
            val oh = if (orient.swapsAxes) w else h
            val px = IntArray(ow * oh)
            val cw = w / 2
            val p = FloatArray(2)
            for (oy in 0 until oh) {
                for (ox in 0 until ow) {
                    orient.toNative((ox + 0.5f) / ow, (oy + 0.5f) / oh, p)
                    val nx = (p[0] * w).toInt().coerceIn(0, w - 1)
                    val ny = (p[1] * h).toInt().coerceIn(0, h - 1)
                    val yy0 = (y[ny * w + nx] * scale).coerceIn(0f, 255f)
                    val yy = lut[yy0.toInt()]
                    val gain = if (yy0 > 1f) yy / yy0 else 1f // 明るさを持ち上げた分だけ色の差も持ち上げる
                    val ci = (ny / 2) * cw + nx / 2
                    val c = when (view) {
                        NightView.MONO -> gray(yy)
                        NightView.GREEN -> green(yy)
                        NightView.COLOR -> {
                            val uu = (u[ci] * scale - 128f) * gain
                            val vv = (v[ci] * scale - 128f) * gain
                            rgb(yy + 1.402f * vv, yy - 0.344f * uu - 0.714f * vv, yy + 1.772f * uu)
                        }
                    }
                    px[oy * ow + ox] = c
                }
            }
            return Bitmap.createBitmap(px, ow, oh, Bitmap.Config.ARGB_8888)
        }

        private fun ch(x: Float) = x.roundToInt().coerceIn(0, 255)
        private fun rgb(r: Float, g: Float, b: Float) = (0xFF shl 24) or (ch(r) shl 16) or (ch(g) shl 8) or ch(b)
        private fun gray(l: Float) = rgb(l, l, l)
        private fun green(l: Float) = rgb(l * 0.15f, l, l * 0.3f)
}
