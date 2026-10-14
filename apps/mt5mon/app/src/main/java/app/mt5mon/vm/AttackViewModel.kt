package app.mt5mon.vm

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.mt5mon.Mt5App
import app.mt5mon.data.Attack
import app.mt5mon.data.Fetch
import app.mt5mon.data.ParseError
import app.mt5mon.data.Parsers
import app.mt5mon.data.Stats
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** 攻撃の記録（/f2b.json・/stats.json と POST /ban・/unban・/stats_refresh） */
data class AttackUi(
    val tab: Int = 0,               // 0 上位、1 永久、2 記録、3 国別
    val attack: Attack? = null,
    val stats: Stats? = null,
    val err: String = "",           // 取得失敗の理由
    val msg: String = "",           // 操作の知らせ（VPS の文面）
    val msgOk: Boolean? = null,     // null = 情報、true = 成功（緑）、false = 失敗（赤）
    val sortByFails: Boolean = false,
    val selectedIp: String = "",
    val sending: Boolean = false,
    val confirm: Pair<Boolean, String>? = null, // (永久ブロックか, IP) の確かめ中
) {
    val permIps: Set<String> get() = attack?.perm?.map { it.ip }?.toSet() ?: emptySet()
}

class AttackViewModel(app: Application) : AndroidViewModel(app) {
    private val mt5 = app as Mt5App
    private val api = mt5.api
    private val _ui = MutableStateFlow(AttackUi())
    val ui: StateFlow<AttackUi> = _ui
    private val now = Channel<Unit>(Channel.CONFLATED)

    init {
        viewModelScope.launch {
            while (true) {
                if (mt5.config.value != null && mt5.tunnel.info.value.up) fetch()
                withTimeoutOrNull(30_000) { now.receive() }
            }
        }
    }

    private suspend fun fetch() {
        val stats = _ui.value.tab == 3
        val r = api.get(if (stats) "stats.json" else "f2b.json")
        val body = r.okBody
        if (body == null) {
            val e = if (stats && r is Fetch.Http && r.code == 503) "集計が無い（作り直しを頼む）" else MainViewModel.fetchErrText(r)
            _ui.update { it.copy(err = e) }
            return
        }
        try {
            if (stats) _ui.update { it.copy(stats = Parsers.stats(body), err = "") }
            else _ui.update { it.copy(attack = Parsers.attack(body), err = "") }
        } catch (e: ParseError) {
            _ui.update { it.copy(err = e.message ?: "") }
        }
    }

    fun refresh() = now.trySend(Unit)

    fun setTab(t: Int) {
        _ui.update { it.copy(tab = t, selectedIp = "") }
        now.trySend(Unit)
    }

    fun select(ip: String) = _ui.update { it.copy(selectedIp = if (it.selectedIp == ip) "" else ip) }
    fun toggleSort() = _ui.update { it.copy(sortByFails = !it.sortByFails) }

    fun askBan(ban: Boolean) {
        val ip = _ui.value.selectedIp
        if (ip.isEmpty()) return
        _ui.update { it.copy(confirm = ban to ip) }
    }

    fun cancelConfirm() = _ui.update { it.copy(confirm = null, msg = "やめた", msgOk = null) }

    /** Y で確かめた後に送る（本当に VPS の nftables を操作する） */
    fun confirmSend() {
        val (ban, ip) = _ui.value.confirm ?: return
        _ui.update { it.copy(confirm = null, sending = true, msg = if (ban) "永久ブロックを送信中…" else "解除を送信中…", msgOk = null) }
        viewModelScope.launch {
            val r = api.post("${if (ban) "ban" else "unban"}?ip=$ip")
            done(r)
            now.trySend(Unit)
        }
    }

    fun statsRefresh() {
        if (_ui.value.sending) return
        _ui.update { it.copy(sending = true, msg = "集計の作り直しを頼んでいる…", msgOk = null) }
        viewModelScope.launch {
            done(api.post("stats_refresh"))
            // VPS は別プロセスで数秒かけて作る。少し待ってから取り直す
            kotlinx.coroutines.delay(8000)
            now.trySend(Unit)
        }
    }

    private fun done(r: Fetch) {
        val body = r.okBody
        if (body == null) {
            _ui.update { it.copy(sending = false, msg = "送れなかった: ${MainViewModel.fetchErrText(r)}", msgOk = false) }
            return
        }
        try {
            val p = Parsers.postResult(body)
            _ui.update { it.copy(sending = false, msg = p.msg, msgOk = p.ok) }
        } catch (e: ParseError) {
            _ui.update { it.copy(sending = false, msg = e.message ?: "", msgOk = false) }
        }
    }
}
