package app.mt5mon.data

import com.wireguard.crypto.Key
import com.wireguard.crypto.KeyPair

/**
 * PicoCalc の /mt5mon/config.txt と同じ形の設定（「キー = 値」の行。# は注釈）。
 * PC の tools\mt5mon_config.py が作ったファイルをそのまま読み込む（鍵を共用するため）。
 * 読めるキー: wg_private_key, wg_address, wg_peer_public_key, wg_endpoint, wg_allowed, wg_keepalive, url, interval
 * （ntp・alert_* は Android では使わない。alert_* はアプリ側の設定で持つ）
 */
data class AppConfig(
    val wgPrivateKey: String,
    val wgPublicKey: String,         // 秘密鍵から導いた公開鍵（VPS の [Peer] と照合するため）
    val wgAddress: String,           // "10.8.0.3/32" の形
    val wgPeerPublicKey: String,
    val wgEndpoint: String,          // "host:port"
    val wgAllowed: String,           // "10.8.0.0/24"
    val wgKeepalive: Int,
    val urlHost: String,
    val urlPort: Int,
    val urlPath: String,             // "/status.json"
    val intervalSec: Int,
    val external: Boolean = false,   // 端末の VPN(WireGuard アプリなど)を使う。鍵は持たない
) {
    companion object {
        /** 「今ある VPN を使う」ときの設定。url と取得間隔は tools\mt5mon_config.py の既定と同じ */
        val EXTERNAL = AppConfig(
            wgPrivateKey = "", wgPublicKey = "", wgAddress = "", wgPeerPublicKey = "", wgEndpoint = "",
            wgAllowed = "", wgKeepalive = 0,
            urlHost = "10.8.0.1", urlPort = 8080, urlPath = "/status.json", intervalSec = 5, external = true,
        )
    }

    /** 同じ所（…/status.json のフォルダ）の別のファイルの URL */
    fun urlOf(name: String): String {
        val dir = urlPath.substringBeforeLast('/', "")
        return "http://$urlHost:$urlPort$dir/$name"
    }
}

class ConfigError(message: String) : Exception(message)

object ConfigParser {
    /** 行を読んで AppConfig にする。不備があれば ConfigError */
    fun parse(text: String): AppConfig {
        val kv = HashMap<String, String>()
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val eq = line.indexOf('=')
            if (eq <= 0) continue
            kv[line.substring(0, eq).trim()] = line.substring(eq + 1).trim()
        }
        val priv = kv["wg_private_key"] ?: throw ConfigError("wg_private_key が無い（base64 44 字）")
        if (priv.length != 44) throw ConfigError("wg_private_key が無い（base64 44 字）")
        val peer = kv["wg_peer_public_key"] ?: throw ConfigError("wg_peer_public_key が無い（base64 44 字）")
        if (peer.length != 44) throw ConfigError("wg_peer_public_key が無い（base64 44 字）")
        val pub = try {
            KeyPair(Key.fromBase64(priv)).publicKey.toBase64()
        } catch (e: Exception) {
            throw ConfigError("wg_private_key が読めない: ${e.message}")
        }
        val addrRaw = kv["wg_address"] ?: throw ConfigError("wg_address が無い")
        val addr = if (addrRaw.contains('/')) addrRaw else "$addrRaw/32"
        val endpoint = kv["wg_endpoint"] ?: throw ConfigError("wg_endpoint が無い")
        if (!endpoint.contains(':')) throw ConfigError("wg_endpoint が読めない: $endpoint")
        val url = kv["url"] ?: throw ConfigError("url が無い")
        if (!url.startsWith("http://")) throw ConfigError("url が読めない: $url")
        val rest = url.removePrefix("http://")
        val slash = rest.indexOf('/')
        val hostPort = if (slash >= 0) rest.substring(0, slash) else rest
        val path = if (slash >= 0) rest.substring(slash) else "/"
        val colon = hostPort.indexOf(':')
        val host = if (colon >= 0) hostPort.substring(0, colon) else hostPort
        val port = if (colon >= 0) hostPort.substring(colon + 1).toIntOrNull() ?: throw ConfigError("url のポートが読めない") else 80
        if (host.isEmpty()) throw ConfigError("url が読めない: $url")
        return AppConfig(
            wgPrivateKey = priv,
            wgPublicKey = pub,
            wgAddress = addr,
            wgPeerPublicKey = peer,
            wgEndpoint = endpoint,
            wgAllowed = kv["wg_allowed"] ?: "10.8.0.0/24",
            wgKeepalive = kv["wg_keepalive"]?.toIntOrNull() ?: 25,
            urlHost = host,
            urlPort = port,
            urlPath = path,
            intervalSec = (kv["interval"]?.toIntOrNull() ?: 5).coerceAtLeast(2),
        )
    }
}
