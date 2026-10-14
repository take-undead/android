package app.esp32cam.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** カメラとの通信、またはカメラ側の処理に失敗したとき（元の Python の ApiError と同じ） */
class ApiError(message: String) : Exception(message)

/** /status の応答 */
data class CameraStatus(val mounted: Boolean, val type: String, val totalMb: Long, val usedMb: Long, val wifi: String)

/**
 * ESP32-CAM（net_cam ファームウェア）の HTTP API（ポート 80）。元の Python の logic/api.py と同じ口を叩く。
 *   GET /capture?flash=on|off&t=YYYYMMDDHHmmss  撮影して SD カードに保存 → {"file": "YYYYMM/YYYYMMDD_HHmmss.jpg"}
 *   GET /photos                                 → {"files": [...]}（新しい順）
 *   GET /photo?file=…                           JPEG そのもの
 *   GET /status                                 → {"sd": {"mounted", "type", "total_mb", "used_mb"}, "wifi": "…"}
 * 応答が {"status":"error","message":"…"} ならカメラ側の失敗。
 * メインスレッドでは呼ばない（CLAUDE.md E1）。すべて Dispatchers.IO で動かす。
 */
object CameraApi {
    const val STREAM_PORT = 81
    private const val DEFAULT_TIMEOUT_MS = 15_000
    private const val MAX_BODY = 8 * 1024 * 1024

    /** 日本語 IME が入れる全角の数字・点・空白を半角にする（実機で Gboard が「１９２．１６８…」と入れた） */
    fun toHalfWidth(s: String): String = buildString(s.length) {
        for (ch in s) {
            when (ch) {
                in '０'..'９' -> append('0' + (ch - '０'))
                '．', '。', '、' -> append('.')
                '：' -> append(':')
                '／' -> append('/')
                '　' -> append(' ')
                else -> append(ch)
            }
        }
    }

    /** "http://192.168.1.141/" や "192.168.1.141:81" のような入力も受け付けて IP（ホスト）だけにする */
    fun normalizeHost(host: String): String {
        var text = toHalfWidth(host).replace(" ", "").trim()
        if (text.isEmpty()) throw ApiError("カメラの IP アドレスを入力してください。")
        if ("//" in text) text = text.substringAfter("//")
        text = text.substringBefore('/').substringBefore(':')
        if (text.isEmpty()) throw ApiError("カメラの IP アドレスが正しくありません。")
        return text
    }

    fun streamUrl(host: String): String = "http://${normalizeHost(host)}:$STREAM_PORT/stream"

    /** ファームウェアが要求する YYYYMMDDHHmmss（端末の時計）。java.time は API 26 からなので SimpleDateFormat を使う */
    fun formatTime(now: Date = Date()): String = SimpleDateFormat("yyyyMMddHHmmss", Locale.US).format(now)

    private suspend fun request(host: String, path: String, params: Map<String, String> = emptyMap(), timeoutMs: Int = DEFAULT_TIMEOUT_MS): ByteArray =
        withContext(Dispatchers.IO) {
            var url = "http://${normalizeHost(host)}$path"
            if (params.isNotEmpty()) url += "?" + params.entries.joinToString("&") { "${it.key}=${URLEncoder.encode(it.value, "UTF-8")}" }
            var conn: HttpURLConnection? = null
            try {
                conn = (URL(url).openConnection(Proxy.NO_PROXY) as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = timeoutMs
                    readTimeout = timeoutMs
                    useCaches = false
                }
                val code = conn.responseCode
                if (code != 200) throw ApiError("カメラがエラーを返しました（HTTP $code）: $url")
                val out = ByteArrayOutputStream()
                conn.inputStream.use { ins ->
                    val buf = ByteArray(16 * 1024)
                    while (true) {
                        val n = ins.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        if (out.size() > MAX_BODY) throw ApiError("カメラの応答が大きすぎます: $url")
                    }
                }
                out.toByteArray()
            } catch (e: ApiError) {
                throw e
            } catch (e: Exception) {
                throw ApiError("カメラに接続できませんでした: $url\nIP アドレスと、カメラの電源・WiFi 接続を確認してください。")
            } finally {
                conn?.disconnect()
            }
        }

    private suspend fun requestJson(host: String, path: String, params: Map<String, String> = emptyMap(), timeoutMs: Int = DEFAULT_TIMEOUT_MS): JSONObject {
        val body = request(host, path, params, timeoutMs)
        val json = try {
            JSONObject(String(body, Charsets.UTF_8))
        } catch (e: JSONException) {
            throw ApiError("カメラの応答を解釈できませんでした: $path")
        }
        if (json.optString("status") == "error") throw ApiError("カメラ側でエラーが発生しました: ${json.optString("message", "原因不明")}")
        return json
    }

    /** 撮影して SD カードに保存させ、保存先の相対パス（"YYYYMM/YYYYMMDD_HHmmss.jpg"）を返す */
    suspend fun capture(host: String, flash: Boolean): String {
        val json = requestJson(host, "/capture", mapOf("flash" to if (flash) "on" else "off", "t" to formatTime()))
        val name = json.optString("file", "")
        if (name.isEmpty()) throw ApiError("撮影はできましたが、保存先を取得できませんでした。")
        return name
    }

    /** SD カード内の写真一覧（新しい順） */
    suspend fun listPhotos(host: String): List<String> {
        val json = requestJson(host, "/photos")
        val files = json.optJSONArray("files") ?: throw ApiError("写真一覧を取得できませんでした。")
        val out = ArrayList<String>(files.length())
        for (i in 0 until files.length()) files.optString(i, null)?.let { if (it.isNotEmpty()) out.add(it) }
        return out
    }

    /** 写真 1 枚を JPEG のバイト列として取得 */
    suspend fun fetchPhoto(host: String, name: String): ByteArray = request(host, "/photo", mapOf("file" to name), timeoutMs = 30_000)

    suspend fun fetchStatus(host: String): CameraStatus {
        val json = requestJson(host, "/status", timeoutMs = 10_000)
        val sd = json.optJSONObject("sd")
        return CameraStatus(
            mounted = sd?.optBoolean("mounted", false) ?: false,
            type = sd?.optString("type", "不明") ?: "不明",
            totalMb = sd?.optLong("total_mb", 0) ?: 0,
            usedMb = sd?.optLong("used_mb", 0) ?: 0,
            wifi = json.optString("wifi", "不明"),
        )
    }
}
