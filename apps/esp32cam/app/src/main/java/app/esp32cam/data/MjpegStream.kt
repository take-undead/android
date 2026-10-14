package app.esp32cam.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

/**
 * ESP32-CAM の MJPEG ストリーム（ポート 81）を裏のスレッドで受け続ける。元の Python の logic/stream.py と同じ切り出し方:
 * ファームウェアは各パートに Content-Length を付けないので、JPEG のマーカー（SOI FFD8 〜 EOI FFD9）で 1 枚ずつ切る。
 * 結果は呼び手のコールバックに渡す（UI スレッドではない。呼び手がメインに戻す）。
 */
class MjpegStream(
    private val url: String,
    private val onConnected: () -> Unit,
    private val onFrame: (Bitmap) -> Unit,
    private val onError: (message: String, beforeConnect: Boolean) -> Unit,
) {
    private val stopped = AtomicBoolean(false)
    private var thread: Thread? = null
    @Volatile private var conn: HttpURLConnection? = null

    companion object {
        private const val CHUNK = 8192
        private const val MAX_BUFFER = 1024 * 1024 // マーカーが見つからないまま溜まったデータを捨てる閾値
        private const val TIMEOUT_MS = 10_000
    }

    fun start() {
        if (thread != null) return
        stopped.set(false)
        thread = Thread({ loop() }, "esp32cam-stream").apply { isDaemon = true; start() }
    }

    /** 受信を止めて接続を閉じる（read() で待っているスレッドを解放するため先に閉じる） */
    fun stop() {
        stopped.set(true)
        try { conn?.disconnect() } catch (e: Exception) { /* 既に閉じている */ }
        thread?.join(2000)
        thread = null
    }

    private fun loop() {
        val c: HttpURLConnection
        val ins: InputStream
        try {
            c = (URL(url).openConnection(Proxy.NO_PROXY) as HttpURLConnection).apply {
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                useCaches = false
            }
            conn = c
            if (c.responseCode != 200) throw IllegalStateException("HTTP ${c.responseCode}")
            ins = c.inputStream
        } catch (e: Exception) {
            if (!stopped.get()) onError("映像に接続できませんでした: $url\nIP アドレスと、カメラの電源・WiFi 接続を確認してください。", true)
            conn = null
            return
        }
        onConnected()
        var buf = ByteArray(64 * 1024)
        var len = 0
        val chunk = ByteArray(CHUNK)
        try {
            while (!stopped.get()) {
                val n = ins.read(chunk)
                if (n < 0) {
                    if (!stopped.get()) onError("カメラとの接続が切断されました。", false)
                    break
                }
                if (len + n > buf.size) buf = buf.copyOf(maxOf(buf.size * 2, len + n))
                System.arraycopy(chunk, 0, buf, len, n)
                len += n
                // 完成した JPEG を取り出す
                while (true) {
                    val start = indexOf(buf, len, 0, 0xFF.toByte(), 0xD8.toByte())
                    if (start < 0) {
                        if (len > MAX_BUFFER) len = 0
                        break
                    }
                    val end = indexOf(buf, len, start + 2, 0xFF.toByte(), 0xD9.toByte())
                    if (end < 0) {
                        if (start > 0) { System.arraycopy(buf, start, buf, 0, len - start); len -= start }
                        if (len > MAX_BUFFER) len = 0
                        break
                    }
                    val jpegLen = end + 2 - start
                    val bmp = BitmapFactory.decodeByteArray(buf, start, jpegLen)
                    val rest = len - (end + 2)
                    System.arraycopy(buf, end + 2, buf, 0, rest)
                    len = rest
                    if (bmp != null) onFrame(bmp) // 壊れたフレームは捨てて次を待つ
                }
            }
        } catch (e: Exception) {
            if (!stopped.get()) onError("映像の受信が途切れました: ${e.message ?: e.javaClass.simpleName}", false)
        } finally {
            try { c.disconnect() } catch (e: Exception) { /* 無視 */ }
            conn = null
        }
    }

    private fun indexOf(buf: ByteArray, len: Int, from: Int, a: Byte, b: Byte): Int {
        var i = maxOf(from, 0)
        while (i + 1 < len) {
            if (buf[i] == a && buf[i + 1] == b) return i
            i++
        }
        return -1
    }
}
