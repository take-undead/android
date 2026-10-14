package app.mt5mon.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.SocketTimeoutException
import java.net.URL

/** HTTP の結果（PicoCalc の fetch_result_t と同じ区分） */
sealed class Fetch {
    data class Ok(val body: String, val ms: Long) : Fetch()
    data class Http(val code: Int, val ms: Long) : Fetch()      // 200 以外
    data class Connect(val why: String) : Fetch()               // つながらない・切られた
    data object Timeout : Fetch()
    data object TooBig : Fetch()

    val okBody: String? get() = (this as? Ok)?.body
}

/**
 * VPS の status_server.py と話す（WireGuard の内側の http://10.8.0.1:8080）。
 * サーバーは HTTP/1.0 で答えて切るので、素の HttpURLConnection で足りる。
 * メインスレッドでは呼ばない（CLAUDE.md E1）。必ず Dispatchers.IO で動かす。
 */
class VpsApi(private val configProvider: () -> AppConfig?) {
    companion object {
        const val CONNECT_TIMEOUT_MS = 5000
        const val READ_TIMEOUT_MS = 8000
        const val MAX_BODY = 256 * 1024
    }

    /** config の url と同じフォルダの name（"status.json"・"bars.json?tf=5&n=140"・"ban?ip=…"）に対して */
    suspend fun get(name: String): Fetch = request("GET", name, null)

    suspend fun post(name: String, body: ByteArray? = null): Fetch = request("POST", name, body ?: ByteArray(0))

    private suspend fun request(method: String, name: String, body: ByteArray?): Fetch = withContext(Dispatchers.IO) {
        val cfg = configProvider() ?: return@withContext Fetch.Connect("設定が無い")
        val t0 = System.currentTimeMillis()
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(cfg.urlOf(name)).openConnection(Proxy.NO_PROXY) as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                useCaches = false
                setRequestProperty("Connection", "close")
                if (body != null) {
                    doOutput = true
                    setRequestProperty("Content-Type", "text/plain; charset=utf-8")
                    setFixedLengthStreamingMode(body.size)
                }
            }
            if (body != null) conn.outputStream.use { it.write(body) }
            val code = conn.responseCode
            val ms = System.currentTimeMillis() - t0
            if (code != 200) return@withContext Fetch.Http(code, ms)
            val out = ByteArrayOutputStream()
            conn.inputStream.use { ins ->
                val buf = ByteArray(8192)
                while (true) {
                    val n = ins.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    if (out.size() > MAX_BODY) return@withContext Fetch.TooBig
                }
            }
            Fetch.Ok(out.toString("UTF-8"), System.currentTimeMillis() - t0)
        } catch (e: SocketTimeoutException) {
            Fetch.Timeout
        } catch (e: Exception) {
            Fetch.Connect(e.javaClass.simpleName + (e.message?.let { ": $it" } ?: ""))
        } finally {
            conn?.disconnect()
        }
    }
}
