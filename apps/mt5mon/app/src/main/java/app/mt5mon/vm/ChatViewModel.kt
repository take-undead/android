package app.mt5mon.vm

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.mt5mon.Mt5App
import app.mt5mon.data.ChatMsg
import app.mt5mon.data.ParseError
import app.mt5mon.data.Parsers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** VPS の Claude と話す（PicoCalc の vpschat） */
data class ChatUi(
    val msgs: List<ChatMsg> = emptyList(),
    val busy: Boolean = false,       // VPS が返事を作っている
    val busySec: Int = 0,
    val sending: Boolean = false,
    val note: String = "",           // 送った・送れなかった の知らせ
    val noteOk: Boolean? = null,
    val fetchErr: String = "",
    val restoreInput: String = "",   // 送れなかった文章（入力欄に戻す）
)

class ChatViewModel(app: Application) : AndroidViewModel(app) {
    private val mt5 = app as Mt5App
    private val api = mt5.api
    private val _ui = MutableStateFlow(ChatUi())
    val ui: StateFlow<ChatUi> = _ui
    private val now = Channel<Unit>(Channel.CONFLATED)
    private var lastN = 0

    companion object {
        const val POLL_BUSY_MS = 3000L
        const val POLL_IDLE_MS = 15000L
        const val INPUT_MAX_BYTES = 2000 // VPS の CHAT_MAX_IN
    }

    init {
        viewModelScope.launch {
            while (true) {
                if (mt5.config.value != null && mt5.tunnel.info.value.up) poll()
                withTimeoutOrNull(if (_ui.value.busy || _ui.value.sending) POLL_BUSY_MS else POLL_IDLE_MS) { now.receive() }
            }
        }
    }

    private suspend fun poll() {
        val r = api.get("chat.json?since=$lastN")
        val body = r.okBody
        if (body == null) {
            _ui.update { it.copy(fetchErr = MainViewModel.fetchErrText(r)) }
            return
        }
        val page = try {
            Parsers.chat(body)
        } catch (e: ParseError) {
            _ui.update { it.copy(fetchErr = e.message ?: "") }
            return
        }
        var msgs = _ui.value.msgs
        if (page.last < lastN) { // VPS の記録が消えた（作り直し）: 手元も消して取り直す
            msgs = emptyList()
            lastN = 0
        }
        var gotReply = false
        val add = page.msgs.filter { it.num > lastN }
        if (add.isNotEmpty()) {
            msgs = msgs + add
            lastN = add.last().num
            gotReply = add.any { it.who != "me" }
        }
        _ui.update {
            it.copy(
                msgs = msgs, busy = page.busy, busySec = page.busySec, fetchErr = "",
                note = if (gotReply) "" else it.note,
            )
        }
    }

    fun refresh() = now.trySend(Unit)

    fun send(text: String) {
        val t = text.trim()
        if (t.isEmpty() || _ui.value.sending) return
        val bytes = t.toByteArray(Charsets.UTF_8)
        if (bytes.size > INPUT_MAX_BYTES) {
            _ui.update { it.copy(note = "長すぎる（$INPUT_MAX_BYTES バイトまで）", noteOk = false) }
            return
        }
        _ui.update { it.copy(sending = true, note = "", restoreInput = "") }
        viewModelScope.launch {
            val r = api.post("chat", bytes)
            val body = r.okBody
            val res = body?.let { b -> try { Parsers.postResult(b) } catch (e: ParseError) { null } }
            if (res != null && res.ok) {
                _ui.update { it.copy(sending = false, busy = true, note = "送った。返事を待っている…", noteOk = true) }
            } else {
                val why = res?.msg ?: MainViewModel.fetchErrText(r)
                // 打った文章は入力欄に戻す（消えないように）
                _ui.update { it.copy(sending = false, note = "送れなかった: $why", noteOk = false, restoreInput = t) }
            }
            now.trySend(Unit)
        }
    }

    fun consumeRestore() = _ui.update { it.copy(restoreInput = "") }

    fun newSession() {
        if (_ui.value.busy) {
            _ui.update { it.copy(note = "返事を待っている間は新しくできない", noteOk = false) }
            return
        }
        viewModelScope.launch {
            val r = api.post("chat_new")
            val res = r.okBody?.let { b -> try { Parsers.postResult(b) } catch (e: ParseError) { null } }
            _ui.update { it.copy(note = res?.msg ?: "送れなかった: ${MainViewModel.fetchErrText(r)}", noteOk = res?.ok ?: false) }
            now.trySend(Unit)
        }
    }
}
