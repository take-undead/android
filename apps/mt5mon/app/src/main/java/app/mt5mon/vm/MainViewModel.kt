package app.mt5mon.vm

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.mt5mon.Mt5App
import app.mt5mon.data.AlertDetector
import app.mt5mon.data.AlertHit
import app.mt5mon.data.AppConfig
import app.mt5mon.data.Chime
import app.mt5mon.data.ConfigError
import app.mt5mon.data.ConfigParser
import app.mt5mon.data.Fetch
import app.mt5mon.data.Hist
import app.mt5mon.data.Notice
import app.mt5mon.data.ParseError
import app.mt5mon.data.Parsers
import app.mt5mon.data.Prefs
import app.mt5mon.data.Status
import app.mt5mon.data.TunnelInfo
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** モニター画面と全画面共通の状態（PicoCalc の g_app・g_net・g_alert・notice に相当） */
data class MainUi(
    val prefs: Prefs = Prefs(),
    val config: AppConfig? = null,
    val configError: String = "",      // config.txt の不備（空なら無し）。何も読み込んでいなければ "none"
    val tunnel: TunnelInfo = TunnelInfo(),
    val vpnBusy: Boolean = false,      // 上げ下げの最中
    val status: Status? = null,
    val lastOkAt: Long = 0,            // 最後に取得・読み込みに成功した時刻（ミリ秒）
    val genChangedAt: Long = 0,        // gen_jst が最後に変わった時刻
    val parseErr: String = "",
    val lastFetch: Fetch? = null,      // null = まだ一度も試していない
    val fetching: Boolean = false,
    val okCount: Int = 0,
    val failCount: Int = 0,
    val hist: Hist? = null,            // 古い足（VPS の /bars.json。直近 2 時間は status の M1 からまとめた足で上書き）
    val histErr: String = "",
    val alert: AlertHit? = null,       // 最後に知らせた急な値動き
    val notice: Notice? = null,
    val noticeOpen: Boolean = false,
    val noticeOpenedAt: Long = 0,
    val nowMillis: Long = System.currentTimeMillis(), // 毎秒更新（経過秒の表示用）
) {
    val have: Boolean get() = status != null
    val frozen: Boolean get() = status != null && nowMillis - genChangedAt >= FROZEN_MS
    val wifiOk: Boolean get() = true // Android では OS が面倒を見る（表示の都合で常に true）
    val vpnOk: Boolean get() = tunnel.up
    val httpOk: Boolean get() = tunnel.up && lastFetch is Fetch.Ok && parseErr.isEmpty()

    companion object {
        const val FROZEN_MS = 20_000L // gen_jst がこの間変わらなければ「停止」
    }
}

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val mt5 = app as Mt5App
    private val store = mt5.store
    private val api = mt5.api
    private val tunnel = mt5.tunnel

    private val _ui = MutableStateFlow(MainUi(configError = "none"))
    val ui: StateFlow<MainUi> = _ui

    /** VPN の許可画面を出す必要があるとき、Activity に渡す Intent */
    val vpnPermission = Channel<Intent>(Channel.CONFLATED)

    // init より前に置く（viewModelScope.launch は Main.immediate なので、最初の suspend まで即座に走る）
    private val refreshNow = Channel<Unit>(Channel.CONFLATED)
    private val histNow = Channel<Unit>(Channel.CONFLATED)
    private var lastNoticeAt = 0L
    private val detector = AlertDetector()
    private var lastGen = ""
    private var noticeFirst = true
    private var noticeSeen = ""
    private var autoVpnTried = false

    init {
        // 設定の変化を追う（config.txt の中身・チャイムの設定）
        viewModelScope.launch {
            store.prefs.collect { p ->
                val (cfg, err) = if (p.externalVpn) AppConfig.EXTERNAL to "" else parseConfig(p.configText)
                mt5.config.value = cfg
                _ui.update { it.copy(prefs = p, config = cfg, configError = err) }
                // 端末の VPN を使う・使わないの切り替え(自分のトンネルは落とす)
                if (p.externalVpn != tunnel.info.value.external) {
                    if (p.externalVpn) tunnel.useExternal() else tunnel.down()
                    autoVpnTried = false
                }
                if (cfg != null && !cfg.external && p.autoVpn && !autoVpnTried) {
                    autoVpnTried = true
                    connectVpn()
                }
            }
        }
        // トンネルの様子
        viewModelScope.launch {
            tunnel.info.collect { t ->
                val wasUp = _ui.value.tunnel.up
                _ui.update { it.copy(tunnel = t) }
                if (t.up && !wasUp) { // つながった瞬間に取りにいく（30 秒待たない。実機で「過去の足: 取得中」が残った）
                    refreshNow.trySend(Unit)
                    histNow.trySend(Unit)
                }
            }
        }
        viewModelScope.launch {
            while (true) {
                tunnel.refresh()
                _ui.update { it.copy(nowMillis = System.currentTimeMillis()) }
                delay(1000)
            }
        }
        viewModelScope.launch { statusLoop() }
        viewModelScope.launch { histLoop() }
        viewModelScope.launch { noticeLoop() }
    }

    private fun parseConfig(text: String): Pair<AppConfig?, String> {
        if (text.isBlank()) return null to "none"
        return try {
            ConfigParser.parse(text) to ""
        } catch (e: ConfigError) {
            null to (e.message ?: "")
        }
    }

    // ---- VPN ------------------------------------------------------------------

    fun connectVpn() {
        val cfg = _ui.value.config ?: return
        if (cfg.external) return
        val intent = tunnel.prepareIntent()
        if (intent != null) {
            vpnPermission.trySend(intent)
            return
        }
        viewModelScope.launch {
            _ui.update { it.copy(vpnBusy = true) }
            tunnel.up(cfg)
            _ui.update { it.copy(vpnBusy = false) }
            refreshNow.trySend(Unit)
        }
    }

    /** 許可画面から戻ってきたら呼ぶ */
    fun onVpnPermissionResult(granted: Boolean) {
        if (granted) connectVpn()
    }

    /** 許可画面を開けなかった（端末に VPN の許可画面が無い等）。落とさずに理由を出す */
    fun onVpnPermissionUnavailable(msg: String) = tunnel.setError(msg)

    fun disconnectVpn() {
        viewModelScope.launch {
            _ui.update { it.copy(vpnBusy = true) }
            tunnel.down()
            _ui.update { it.copy(vpnBusy = false) }
        }
    }

    // ---- 設定 ------------------------------------------------------------------

    fun saveConfigText(text: String) = viewModelScope.launch {
        store.setConfigText(text)
        autoVpnTried = false
    }

    fun setAlert(on: Boolean, pips: Int, minutes: Int, volume: Int) = viewModelScope.launch {
        val before = _ui.value.prefs
        store.setAlert(on, pips, minutes, volume)
        if (before.alertMinutes != minutes) detector.reset() // 窓の長さが変わったので覚えた値を捨てる
    }

    fun setNoticeSeconds(sec: Int) = viewModelScope.launch { store.setNoticeSeconds(sec) }
    fun setAutoVpn(on: Boolean) = viewModelScope.launch { store.setAutoVpn(on) }
    fun setExternalVpn(on: Boolean) = viewModelScope.launch { store.setExternalVpn(on) }
    fun setTf(tf: Int) = viewModelScope.launch {
        store.setTf(tf)
        histNow.trySend(Unit)
    }

    fun setBarWidth(w: Int) = viewModelScope.launch { store.setBarWidth(w) }

    /** 音量を確かめる（設定画面） */
    fun testChime(volume: Int) = viewModelScope.launch { Chime.play(1, 1, volume) }

    fun refresh() {
        refreshNow.trySend(Unit)
        histNow.trySend(Unit)
    }

    // ---- 状況 JSON（/status.json） -----------------------------------------------

    private suspend fun statusLoop() {
        while (true) {
            val s = _ui.value
            val interval = (s.config?.intervalSec ?: 5) * 1000L
            if (s.config != null && s.tunnel.up) {
                _ui.update { it.copy(fetching = true) }
                val r = api.get("status.json")
                handleStatus(r)
            }
            withTimeoutOrNull(interval) { refreshNow.receive() }
        }
    }

    private suspend fun handleStatus(r: Fetch) {
        val body = r.okBody
        if (body == null) {
            _ui.update { it.copy(fetching = false, lastFetch = r, failCount = it.failCount + 1) }
            return
        }
        val st = try {
            Parsers.status(body)
        } catch (e: ParseError) {
            _ui.update { it.copy(fetching = false, lastFetch = r, parseErr = e.message ?: "", failCount = it.failCount + 1) }
            return
        }
        val now = System.currentTimeMillis()
        val genChanged = st.genJst != lastGen
        if (genChanged) lastGen = st.genJst
        val prev = _ui.value
        var hit: AlertHit? = prev.alert
        if (genChanged && !st.stale) {
            val p = prev.prefs
            detector.onPrice(st.bid, st.digits, p.alertPips, p.alertMinutes, now)?.let { h ->
                hit = h
                if (p.alertOn) viewModelScope.launch { Chime.play(h.dir, h.reps, p.alertVolume) }
            }
        }
        if (prev.status != null && prev.status.symbol != st.symbol) detector.reset()
        _ui.update {
            it.copy(
                fetching = false, lastFetch = r, status = st, parseErr = "", lastOkAt = now,
                genChangedAt = if (genChanged || it.genChangedAt == 0L) now else it.genChangedAt,
                okCount = it.okCount + 1, alert = hit,
            )
        }
    }

    // ---- 古い足（/bars.json）。どの時間足でも、時間足を変えたときと 30 秒ごとに取る ------------------------

    private suspend fun histLoop() {
        while (true) {
            val s = _ui.value
            val tfMin = tfMinutes(s.prefs.tf)
            if (s.config != null && s.tunnel.up) {
                val r = api.get("bars.json?tf=$tfMin&n=${Parsers.HIST_BARS}")
                val body = r.okBody
                if (body == null) {
                    _ui.update { it.copy(histErr = fetchErrText(r)) }
                } else {
                    try {
                        _ui.update { it.copy(hist = Parsers.hist(body, tfMin), histErr = "") }
                    } catch (e: ParseError) {
                        _ui.update { it.copy(histErr = e.message ?: "") }
                    }
                }
            }
            withTimeoutOrNull(30_000) { histNow.receive() }
        }
    }

    // ---- VPS からの知らせ（/notice.json） -----------------------------------------------

    private suspend fun noticeLoop() {
        while (true) {
            val s = _ui.value
            // 自動で閉じる
            if (s.noticeOpen && s.prefs.noticeSeconds > 0 && System.currentTimeMillis() - s.noticeOpenedAt >= s.prefs.noticeSeconds * 1000L) {
                _ui.update { it.copy(noticeOpen = false) }
            }
            if (s.have && s.config != null && s.tunnel.up && System.currentTimeMillis() - lastNoticeAt >= 30_000) {
                lastNoticeAt = System.currentTimeMillis()
                api.get("notice.json").okBody?.let { body ->
                    try {
                        handleNotice(Parsers.notice(body))
                    } catch (e: ParseError) {
                        // 無ければ何もしない
                    }
                }
            }
            delay(1000)
        }
    }

    private fun handleNotice(n: Notice) {
        if (n.doneJst.isEmpty()) return
        var isNew = n.doneJst != noticeSeen
        if (noticeFirst) {
            noticeFirst = false
            // 起動直後: 30 分より古い知らせは見たことにする（開くたびに鳴らさない）
            val t = parseJst(n.doneJst)
            val now = jstNowEpochSec()
            if (t == 0L || now < t || now - t > 30 * 60) isNew = false
        }
        noticeSeen = n.doneJst
        _ui.update { it.copy(notice = n, noticeOpen = if (isNew) true else it.noticeOpen, noticeOpenedAt = if (isNew) System.currentTimeMillis() else it.noticeOpenedAt) }
        if (isNew) viewModelScope.launch { Chime.play(1, 3, _ui.value.prefs.alertVolume) }
    }

    fun closeNotice() = _ui.update { it.copy(noticeOpen = false) }
    fun openNotice() = _ui.update { it.copy(noticeOpen = it.notice != null, noticeOpenedAt = System.currentTimeMillis()) }

    companion object {
        private val fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        private val fmtDot = DateTimeFormatter.ofPattern("yyyy.MM.dd HH:mm:ss")
        val JST: ZoneId = ZoneId.of("Asia/Tokyo")

        /** 時間足の番号 → 分（0 = M1、1 = M5、2 = M15、3 = M30、4 = H1） */
        val TF_MINUTES = intArrayOf(1, 5, 15, 30, 60)
        val TF_NAMES = arrayOf("M1", "M5", "M15", "M30", "H1")
        fun tfMinutes(tf: Int) = TF_MINUTES.getOrElse(tf) { 1 }

        /** "YYYY-MM-DD HH:MM:SS"（JST）→ 1970 からの秒（JST のまま）。読めなければ 0 */
        fun parseJst(s: String): Long = try {
            LocalDateTime.parse(s.take(19), fmt).toEpochSecond(java.time.ZoneOffset.UTC)
        } catch (e: Exception) {
            try {
                LocalDateTime.parse(s.take(19), fmtDot).toEpochSecond(java.time.ZoneOffset.UTC)
            } catch (e2: Exception) {
                0L
            }
        }

        fun jstNowEpochSec(): Long = LocalDateTime.now(JST).toEpochSecond(java.time.ZoneOffset.UTC)

        /** 取得失敗の短い理由（画面の補助表示用。VM が作る文のため文字列資源にしていない） */
        fun fetchErrText(r: Fetch): String = when (r) {
            is Fetch.Ok -> ""
            is Fetch.Http -> "HTTP ${r.code}"
            is Fetch.Connect -> "接続できない"
            Fetch.Timeout -> "時間切れ"
            Fetch.TooBig -> "応答が大きすぎる"
        }
    }
}
