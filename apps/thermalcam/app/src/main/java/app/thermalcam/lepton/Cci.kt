package app.thermalcam.lepton

import android.os.SystemClock

/**
 * Lepton の CCI（I2C のコマンド口）。FLIR が公開している Lepton SDK（LEPTON_I2C_Protocol.c / LEPTON_SDK.c）の手順を写した。
 * 出典: github.com/groupgets/LeptonModule（leptonSDKEmb32PUB）と groupgets/purethermal1-firmware（LEPTON_RAD.h ほか）。2026-10-18 確認。
 *
 * - レジスタ: STATUS 0x0002（bit0 = 忙しい、上位 8 bit = 結果コード）、COMMAND 0x0004、DATA_LENGTH 0x0006、DATA_0 0x0008〜（16 ワード）、
 *   17 ワード以上は DATA_BUFFER 0xF800。
 * - コマンド ID = モジュールの基底 + 番号 + 種類（GET 0 / SET 1 / RUN 2）。OEM と RAD は 0x4000 のビットを含む。
 * - 32 bit の値（列挙など）は 2 ワードで、下位ワードが先。
 * - このサービスの i2cRead / i2cWrite のワードは「下位バイトが先」（起動直後の STATUS が 06 00 = 0x0006 と読めたことで確認）。
 */
class Cci(private val svc: LeptonService) {

    class CciException(message: String) : Exception(message)

    private fun toBytes(words: IntArray) = ByteArray(words.size * 2).also { b ->
        words.forEachIndexed { i, w -> b[i * 2] = (w and 0xFF).toByte(); b[i * 2 + 1] = ((w shr 8) and 0xFF).toByte() }
    }

    private fun toWords(b: ByteArray, n: Int) = IntArray(n) { i -> (b[i * 2].toInt() and 0xFF) or ((b[i * 2 + 1].toInt() and 0xFF) shl 8) }

    private fun readReg(addr: Int, n: Int = 1): IntArray {
        val b = svc.i2cRead(addr, n) ?: throw CciException("I2C 読み取り失敗 0x%04x".format(addr))
        if (b.size < n * 2) throw CciException("I2C 読み取りが短い 0x%04x".format(addr))
        return toWords(b, n)
    }

    private fun writeReg(addr: Int, words: IntArray) {
        val r = svc.i2cWrite(addr, toBytes(words))
        if (r < 0) throw CciException("I2C 書き込み失敗 0x%04x (%d)".format(addr, r))
    }

    /** 忙しいビットが落ちるまで待ち、結果コード（0 = 成功、負 = LEP_RESULT）を返す */
    private fun waitIdle(timeoutMs: Long = 5000): Int {
        val t0 = SystemClock.elapsedRealtime()
        while (true) {
            val st = readReg(REG_STATUS)[0]
            if (st and 1 == 0) return (st shr 8).toByte().toInt()
            if (SystemClock.elapsedRealtime() - t0 > timeoutMs) throw CciException("CCI が忙しいまま")
            SystemClock.sleep(1)
        }
    }

    private fun check(cmd: Int, code: Int) {
        if (code != 0) throw CciException("CCI 0x%04x の結果 %d".format(cmd, code))
    }

    @Synchronized
    fun get(cmd: Int, words: Int): IntArray {
        waitIdle()
        writeReg(REG_DATA_LENGTH, intArrayOf(words))
        writeReg(REG_COMMAND, intArrayOf(cmd or TYPE_GET))
        check(cmd, waitIdle())
        return readReg(if (words <= 16) REG_DATA_0 else REG_BUFFER_0, words)
    }

    @Synchronized
    fun set(cmd: Int, data: IntArray) {
        waitIdle()
        writeReg(if (data.size <= 16) REG_DATA_0 else REG_BUFFER_0, data)
        writeReg(REG_DATA_LENGTH, intArrayOf(data.size))
        writeReg(REG_COMMAND, intArrayOf(cmd or TYPE_SET))
        check(cmd, waitIdle())
    }

    @Synchronized
    fun run(cmd: Int, timeoutMs: Long = 5000) {
        waitIdle()
        writeReg(REG_DATA_LENGTH, intArrayOf(0))
        writeReg(REG_COMMAND, intArrayOf(cmd or TYPE_RUN))
        check(cmd, waitIdle(timeoutMs))
    }

    /** 32 bit の値（列挙・真偽）を読む / 書く */
    fun getInt(cmd: Int): Int = get(cmd, 2).let { it[0] or (it[1] shl 16) }
    fun setInt(cmd: Int, v: Int) = set(cmd, intArrayOf(v and 0xFFFF, (v ushr 16) and 0xFFFF))

    companion object {
        private const val REG_STATUS = 0x0002
        private const val REG_COMMAND = 0x0004
        private const val REG_DATA_LENGTH = 0x0006
        private const val REG_DATA_0 = 0x0008
        private const val REG_BUFFER_0 = 0xF800
        private const val TYPE_GET = 0
        private const val TYPE_SET = 1
        private const val TYPE_RUN = 2

        // SYS（基底 0x0200）
        const val SYS_FLIR_SERIAL = 0x0208          // 4 ワード
        const val SYS_UPTIME = 0x020C               // 2 ワード（ms）
        const val SYS_AUX_TEMP_K = 0x0210           // 1 ワード（K×100）
        const val SYS_FPA_TEMP_K = 0x0214           // 1 ワード（K×100）
        const val SYS_TELEMETRY_ENABLE = 0x0218
        const val SYS_TELEMETRY_LOCATION = 0x021C   // 0 = 先頭, 1 = 末尾
        const val SYS_FFC_SHUTTER_MODE = 0x023C
        const val SYS_RUN_FFC = 0x0240              // RUN で 0x0242
        const val SYS_FFC_STATUS = 0x0244
        const val SYS_GAIN_MODE = 0x0248            // 0 = 高, 1 = 低, 2 = 自動

        // OEM（基底 0x0800 + 0x4000）
        const val OEM_PART_NUMBER = 0x481C          // 16 ワード（文字列）
        const val OEM_SOFTWARE_VERSION = 0x4820     // 4 ワード
        const val OEM_GPIO_MODE = 0x4854            // 5 = VSYNC
        const val OEM_VSYNC_DELAY = 0x4858          // -3〜3
        const val OEM_TEMPORAL_FILTER = 0x4870
        const val OEM_COLUMN_NOISE = 0x4874

        // RAD（基底 0x0E00 + 0x4000）
        const val RAD_ENABLE = 0x4E10
        const val RAD_FLUX_LINEAR_PARAMS = 0x4EBC   // 8 ワード（放射率・背景温度など）
        const val RAD_TLINEAR_ENABLE = 0x4EC0
        const val RAD_TLINEAR_RESOLUTION = 0x4EC4   // 0 = 0.1K, 1 = 0.01K
        const val RAD_TLINEAR_AUTO_RES = 0x4EC8
        const val RAD_SPOTMETER_ROI = 0x4ECC        // 4 ワード（開始行, 開始列, 終了行, 終了列）
        const val RAD_SPOTMETER_KELVIN = 0x4ED0     // 4 ワード（値, 最大, 最小, 画素数）
    }
}
