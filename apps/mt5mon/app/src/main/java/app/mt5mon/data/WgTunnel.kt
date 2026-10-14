package app.mt5mon.data

import android.content.Context
import android.content.Intent
import android.net.VpnService
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config
import com.wireguard.config.Interface
import com.wireguard.config.Peer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

/** トンネルの今の様子（PicoCalc の g_net の WireGuard の部分に相当） */
data class TunnelInfo(
    val started: Boolean = false,      // UP を頼んで通った
    val up: Boolean = false,           // 最近ハンドシェイクが成っている（3 分以内）
    val handshakeAgeSec: Long = -1,    // 最後のハンドシェイクからの秒（-1 = まだ）
    val rxBytes: Long = 0,
    val txBytes: Long = 0,
    val error: String = "",            // 上げられなかった理由
)

/**
 * WireGuard の公式ライブラリ（com.wireguard.android:tunnel）の GoBackend を使い、
 * PicoCalc と同じ鍵・同じアドレス（10.8.0.3）で VPS につなぐ。
 * AllowedIPs は 10.8.0.0/24 だけなので、他の通信は VPN を通らない（スプリットトンネル）。
 * 【注意】PicoCalc と同時にはつなげない（同じ鍵のピアは 1 つ。後からハンドシェイクした方が勝つ）。
 */
class WgTunnel(context: Context) {
    private val appContext = context.applicationContext
    private val backend by lazy { GoBackend(appContext) }
    private val tunnel = object : Tunnel {
        override fun getName() = "mt5mon"
        override fun onStateChange(newState: Tunnel.State) {
            _info.value = _info.value.copy(started = newState == Tunnel.State.UP)
        }
    }
    private val _info = MutableStateFlow(TunnelInfo())
    val info: StateFlow<TunnelInfo> = _info
    private var peerKey: com.wireguard.crypto.Key? = null

    /** VPN の許可がまだなら、ユーザーに見せる Intent を返す（null なら許可済み） */
    fun prepareIntent(): Intent? = VpnService.prepare(appContext)

    suspend fun up(cfg: AppConfig): Boolean = withContext(Dispatchers.IO) {
        try {
            val iface = Interface.Builder()
                .parsePrivateKey(cfg.wgPrivateKey)
                .parseAddresses(cfg.wgAddress)
                .build()
            val peer = Peer.Builder()
                .parsePublicKey(cfg.wgPeerPublicKey)
                .parseEndpoint(cfg.wgEndpoint)
                .parseAllowedIPs(cfg.wgAllowed)
                .setPersistentKeepalive(cfg.wgKeepalive)
                .build()
            val config = Config.Builder().setInterface(iface).addPeer(peer).build()
            peerKey = peer.publicKey
            val st = backend.setState(tunnel, Tunnel.State.UP, config)
            _info.value = _info.value.copy(started = st == Tunnel.State.UP, error = "")
            st == Tunnel.State.UP
        } catch (e: Exception) {
            _info.value = _info.value.copy(started = false, up = false, error = e.message ?: e.javaClass.simpleName)
            false
        }
    }

    suspend fun down() = withContext(Dispatchers.IO) {
        try {
            backend.setState(tunnel, Tunnel.State.DOWN, null)
        } catch (e: Exception) {
            // 既に落ちている
        }
        _info.value = TunnelInfo()
    }

    /** 統計を読んで info を更新する（2 秒ごとに呼ぶ） */
    suspend fun refresh() = withContext(Dispatchers.IO) {
        if (!_info.value.started) return@withContext
        try {
            val stats = backend.getStatistics(tunnel)
            val key = peerKey
            // PeerStats は Java の record（rxBytes / txBytes / latestHandshakeEpochMillis）。アクセサはメソッドとして呼ぶ
            val p = if (key != null) stats.peer(key) else null
            val hs = p?.latestHandshakeEpochMillis() ?: 0L
            val age = if (hs > 0) (System.currentTimeMillis() - hs) / 1000 else -1
            _info.value = _info.value.copy(
                up = age in 0..180,
                handshakeAgeSec = age,
                rxBytes = p?.rxBytes() ?: stats.totalRx(),
                txBytes = p?.txBytes() ?: stats.totalTx(),
            )
        } catch (e: Exception) {
            _info.value = _info.value.copy(error = e.message ?: e.javaClass.simpleName)
        }
    }
}
