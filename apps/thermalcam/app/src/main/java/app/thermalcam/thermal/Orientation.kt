package app.thermalcam.thermal

/**
 * センサーの並び（native）と画面に出す向き（display）の対応。座標はどちらも 0〜1 に正規化したもの。
 * rotation は「native を時計回りに何度回すと画面で正立するか」。mirror は回したあとに左右を反転するか。
 */
data class Orientation(val rotation: Int, val mirror: Boolean = false) {
    val swapsAxes: Boolean get() = rotation == 90 || rotation == 270

    /** 画面上の (dx, dy) → センサー上の (nx, ny)。結果は out[0], out[1] に入れる（毎画素で呼ぶので配列を使い回す） */
    fun toNative(dx0: Float, dy: Float, out: FloatArray) {
        val dx = if (mirror) 1f - dx0 else dx0
        when (rotation) {
            90 -> { out[0] = dy; out[1] = 1f - dx }
            180 -> { out[0] = 1f - dx; out[1] = 1f - dy }
            270 -> { out[0] = 1f - dy; out[1] = dx }
            else -> { out[0] = dx; out[1] = dy }
        }
    }

    /** センサー上の (nx, ny) → 画面上の (dx, dy) */
    fun toDisplay(nx: Float, ny: Float, out: FloatArray) {
        when (rotation) {
            90 -> { out[0] = 1f - ny; out[1] = nx }
            180 -> { out[0] = 1f - nx; out[1] = 1f - ny }
            270 -> { out[0] = ny; out[1] = 1f - nx }
            else -> { out[0] = nx; out[1] = ny }
        }
        if (mirror) out[0] = 1f - out[0]
    }
}
