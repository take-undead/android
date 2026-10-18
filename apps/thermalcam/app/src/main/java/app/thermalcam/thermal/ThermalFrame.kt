package app.thermalcam.thermal

/**
 * 1 枚の熱画像（Lepton 2.5、80×60 画素）。温度は ℃。
 *
 * 形の根拠（2026-10-18、実機の readFrame と「Lepton with Radiometry Datasheet」500-0763-01-09 Rev 110）:
 * - readFrame は 164 バイトのパケット × 63 = 10332 バイト。パケット = ID 2 バイト + CRC 2 バイト + 80 画素 × 2 バイト（上位バイトが先）。
 * - パケット 0〜59 が画像の行、60〜62 がテレメトリー A / B / C（末尾に置く設定にしている）。
 * - ID の下位 12 bit が行番号。上位 4 bit は Lepton 2 では使わない（実機で 0x3000 などが混じることがあった）ので見ない。
 * - TLinear が有効なので各画素は絶対温度（分解能 0.01 なら K×100、0.1 なら K×10）。
 * - テレメトリー A: 3〜4 = 状態ビット、20〜21 = フレームカウンター（新しいフレームのときだけ増える）、
 *   24 = FPA 温度 K×100、26 = 筐体温度 K×100。
 */
class ThermalFrame(
    val celsius: FloatArray,   // WIDTH × HEIGHT、行ごと
    val frameCounter: Long,
    val fpaC: Float,
    val housingC: Float,
    val statusBits: Int,
) {
    /** 00 = 未実行, 01 = まもなく, 10 = 実行中, 11 = 完了（データシート Table 6、状態ビットの bit 4〜5） */
    val ffcState: Int get() = (statusBits shr 4) and 3
    val ffcDesired: Boolean get() = (statusBits shr 3) and 1 == 1
    val ffcRunning: Boolean get() = ffcState == 1 || ffcState == 2

    operator fun get(x: Int, y: Int): Float = celsius[y * WIDTH + x]

    companion object {
        const val WIDTH = 80
        const val HEIGHT = 60
        private const val PACKET = 164
        private const val PACKETS = 63
        const val FRAME_BYTES = PACKET * PACKETS

        private fun word(b: ByteArray, packet: Int, index: Int): Int {
            val o = packet * PACKET + 4 + index * 2
            return ((b[o].toInt() and 0xFF) shl 8) or (b[o + 1].toInt() and 0xFF)
        }

        /** 形が合わない（パケットの並びが崩れている）ときは null */
        fun parse(b: ByteArray, kelvinPerCount: Float): ThermalFrame? {
            if (b.size < FRAME_BYTES) return null
            for (p in 0 until PACKETS) {
                val id = ((b[p * PACKET].toInt() and 0x0F) shl 8) or (b[p * PACKET + 1].toInt() and 0xFF)
                if (id != p) return null
            }
            val t = FloatArray(WIDTH * HEIGHT)
            for (y in 0 until HEIGHT) for (x in 0 until WIDTH) {
                t[y * WIDTH + x] = word(b, y, x) * kelvinPerCount - 273.15f
            }
            // 32 bit の値は下位ワードが先（実機の CSV でカウンターが 0x070B0000 と出たことで確認。状態ビットも同じ並び）
            val counter = word(b, 60, 20).toLong() or (word(b, 60, 21).toLong() shl 16)
            val status = word(b, 60, 3) or (word(b, 60, 4) shl 16)
            return ThermalFrame(
                celsius = t,
                frameCounter = counter,
                fpaC = word(b, 60, 24) / 100f - 273.15f,
                housingC = word(b, 60, 26) / 100f - 273.15f,
                statusBits = status,
            )
        }
    }
}
