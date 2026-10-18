package app.thermalcam.thermal

/**
 * 温度 → 色の対応表（256 段）。色の節目を並べて間を直線でつなぐ。
 * 画面のテーマの色（ui/theme）とは別物で、測定値を表すデータなのでここに置く。
 */
enum class Palette(private val stops: IntArray) {
    IRON(intArrayOf(0x000000, 0x1E0050, 0x6A0A8C, 0xB4235A, 0xE8551E, 0xFAA01E, 0xFFE664, 0xFFFFFF)),
    RAINBOW(intArrayOf(0x14005A, 0x0032DC, 0x00B4F0, 0x00D25A, 0xE6E600, 0xFF8C00, 0xE60000, 0xFFFFFF)),
    WHITE_HOT(intArrayOf(0x000000, 0xFFFFFF)),
    BLACK_HOT(intArrayOf(0xFFFFFF, 0x000000)),
    ;

    /** 0〜255 の段 → ARGB */
    val lut: IntArray by lazy {
        IntArray(256) { i ->
            val pos = i / 255f * (stops.size - 1)
            val k = pos.toInt().coerceAtMost(stops.size - 2)
            val f = pos - k
            val a = stops[k]; val b = stops[k + 1]
            fun ch(shift: Int) = (((a shr shift) and 0xFF) * (1 - f) + ((b shr shift) and 0xFF) * f).toInt()
            (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
        }
    }
}
