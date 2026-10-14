package app.mt5mon.vm

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.mt5mon.Mt5App
import app.mt5mon.data.ParseError
import app.mt5mon.data.Parsers
import app.mt5mon.data.Sup
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** AI 日報（/sup.json の要約と /nippo.md の全文） */
data class NippoUi(
    val sup: Sup? = null,
    val supErr: String = "",
    val nippo: String = "",
    val nippoErr: String = "",
    val page: Int = 0,          // 0 = 要約、1 = 全文
    val loading: Boolean = false,
)

class NippoViewModel(app: Application) : AndroidViewModel(app) {
    private val mt5 = app as Mt5App
    private val api = mt5.api
    private val _ui = MutableStateFlow(NippoUi())
    val ui: StateFlow<NippoUi> = _ui
    private val now = Channel<Unit>(Channel.CONFLATED)
    private var lastNippoAt = 0L

    init {
        viewModelScope.launch {
            while (true) {
                if (mt5.config.value != null && mt5.tunnel.info.value.up) {
                    _ui.update { it.copy(loading = true) }
                    val r = api.get("sup.json")
                    val body = r.okBody
                    if (body == null) _ui.update { it.copy(supErr = MainViewModel.fetchErrText(r), loading = false) }
                    else try {
                        _ui.update { it.copy(sup = Parsers.sup(body), supErr = "", loading = false) }
                    } catch (e: ParseError) {
                        _ui.update { it.copy(supErr = e.message ?: "", loading = false) }
                    }
                    // 全文は開いたときと 5 分ごと
                    if (_ui.value.page == 1 && System.currentTimeMillis() - lastNippoAt >= 5 * 60_000) fetchNippo()
                }
                withTimeoutOrNull(60_000) { now.receive() }
            }
        }
    }

    private suspend fun fetchNippo() {
        lastNippoAt = System.currentTimeMillis()
        val r = api.get("nippo.md")
        val body = r.okBody
        if (body == null) _ui.update { it.copy(nippoErr = MainViewModel.fetchErrText(r)) }
        else _ui.update { it.copy(nippo = body, nippoErr = "") }
    }

    fun refresh() {
        lastNippoAt = 0
        now.trySend(Unit)
    }

    fun setPage(p: Int) {
        _ui.update { it.copy(page = p) }
        if (p == 1 && _ui.value.nippo.isEmpty()) now.trySend(Unit)
    }
}
