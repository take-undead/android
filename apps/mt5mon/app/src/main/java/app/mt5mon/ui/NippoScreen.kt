package app.mt5mon.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.mt5mon.R
import app.mt5mon.data.Sup
import app.mt5mon.ui.theme.Mt5Colors
import app.mt5mon.vm.NippoViewModel
import java.util.Locale

/** 1 行（文と色）。見出しは区切り線付き */
private data class Line(val text: String, val color: Color, val section: Boolean = false)

/** 予測の矢印（↑≧65% ↗≧55% →45〜55% ↘≦45% ↓≦35%。上 = 赤・下 = 水色） */
private fun arrowOf(p: Double): Pair<String, Color> = when {
    p < 0 -> "－" to Mt5Colors.dim
    p >= 0.65 -> "↑" to Mt5Colors.up
    p >= 0.55 -> "↗" to Mt5Colors.up
    p > 0.45 -> "→" to Mt5Colors.dim
    p > 0.35 -> "↘" to Mt5Colors.down
    else -> "↓" to Mt5Colors.down
}

/** 要約の行（PicoCalc の nippo.c の build_summary と同じ内容） */
private fun summaryLines(s: Sup): List<Line> {
    val out = ArrayList<Line>()
    fun add(t: String, c: Color = Mt5Colors.text) = out.add(Line(t, c))
    fun section(t: String) = out.add(Line(t, Mt5Colors.head, true))
    val (ar, ac) = arrowOf(s.pUp)
    val conf = if (s.bias >= 2 || s.bias <= -2) "強い" else if (s.bias != 0) "弱い" else ""
    val dir = if (s.bias > 0) "買い" else if (s.bias < 0) "売り" else "様子見"
    if (s.pUp >= 0) add(String.format(Locale.US, "%s 上 %.0f%% / 下 %.0f%%   %s%s（4h）", ar, s.pUp * 100, (1 - s.pUp) * 100, conf, dir), ac)
    else add("$ar 確率なし   $conf$dir", ac)
    if (s.outlook.isNotEmpty()) add(s.outlook, if (s.bias > 0) Mt5Colors.up else if (s.bias < 0) Mt5Colors.down else Mt5Colors.text)
    if (s.regime.isNotEmpty()) add("局面: ${s.regime}")
    add(String.format(Locale.US, "判断 #%d %s  risk%d ×%.2f", s.seq, shortTime(s.cmdT), s.risk, s.lotFactor), if (s.risk >= 2) Mt5Colors.err else if (s.risk == 1) Mt5Colors.warn else Mt5Colors.dim)

    section("ライン")
    if (s.lineBuy > 0 || s.lineSell > 0) add(String.format(Locale.US, "買いライン %.3f   売りライン %.3f", s.lineBuy, s.lineSell))
    if (s.buyStop > 0 || s.sellStop > 0) add(String.format(Locale.US, "買い停止   %.3f   売り停止   %.3f", s.buyStop, s.sellStop), Mt5Colors.warn)
    add(String.format(Locale.US, "現在値    %.3f   前週 %.3f/%.3f", s.bid, s.weekHigh, s.weekLow), Mt5Colors.sym)

    section("EA_One")
    if (s.pos == "buy" || s.pos == "sell") {
        val buy = s.pos == "buy"
        add(String.format(Locale.US, "建玉 %s %.2fL %+.1fpips %d分", if (buy) "買い" else "売り", s.posLots, s.posPips, s.posMin), if (buy) Mt5Colors.up else Mt5Colors.down)
    } else add("建玉 なし", Mt5Colors.dim)
    val gui = when (s.gui) { "stop" -> "停止（人）"; "sell_first" -> "売り優先"; "buy_first" -> "買い優先"; else -> "おまかせ" }
    add("指示 $gui  適用 #${s.eaSeq}  今日の判断 ${if (s.todayDecision) "あり" else "なし"}", if (s.todayDecision) Mt5Colors.text else Mt5Colors.warn)
    add(String.format(Locale.US, "残高 %.0f  有効 %.0f  spr %.1f  %s", s.balance, s.equity, s.spread, s.eaVer), Mt5Colors.dim)
    if (s.lastClose.isNotEmpty()) add(String.format(Locale.US, "直近決済 %s %+.1fpips", shortTime(s.lastClose), s.lastPips), if (s.lastPips >= 0) Mt5Colors.ok else Mt5Colors.err)
    add(String.format(Locale.US, "24h USDJPY%+.0f EURUSD%+.0f EURJPY%+.0f 油%+.0f", s.chgUsdJpy, s.chgEurUsd, s.chgEurJpy, s.chgUsOil), Mt5Colors.dim)
    if (s.err.isNotEmpty() || s.offsetH != 3) add("⚠ err=${s.err} offset_h=${s.offsetH}", Mt5Colors.err)

    section("根拠")
    add(s.reason.ifEmpty { "（なし）" })

    section("判断の記録")
    for (d in s.decisions) {
        val t = shortTime(d.t)
        val res = d.res.ifEmpty { "—" }
        if (d.pUp >= 0) add(String.format(Locale.US, "%s #%d b%+d r%d p%.0f%% → %s", t, d.seq, d.bias, d.risk, d.pUp * 100, res), if (d.bias > 0) Mt5Colors.up else if (d.bias < 0) Mt5Colors.down else Mt5Colors.text)
        else add(String.format(Locale.US, "%s #%d b%+d r%d → %s", t, d.seq, d.bias, d.risk, res), if (d.bias > 0) Mt5Colors.up else if (d.bias < 0) Mt5Colors.down else Mt5Colors.text)
        if (d.why.isNotEmpty()) add("  ${d.why}", Mt5Colors.dim)
    }
    if (s.decisions.isEmpty()) add("（なし）", Mt5Colors.dim)

    section("申し送り")
    s.latest.forEachIndexed { i, t -> add(t, if (i == 0) Mt5Colors.head else Mt5Colors.text) }
    return out
}

/** 全文（Markdown）の行: # は色を変え、## は区切り、|--- は捨て、表の | は空白に */
private fun fullLines(md: String): List<Line> {
    val out = ArrayList<Line>()
    for (raw in md.lines()) {
        val ln = raw.trimEnd()
        if (ln.startsWith("|---") || ln.startsWith("---")) continue
        if (ln.startsWith("#")) {
            var level = 0
            var t = ln
            while (t.startsWith("#")) { t = t.substring(1); level++ }
            out.add(Line(t.trim(), if (level <= 1) Mt5Colors.sym else Mt5Colors.head, section = level == 2))
            continue
        }
        if (ln.startsWith("|")) {
            out.add(Line(ln.split("|").joinToString(" ") { it.trim() }.trim(), Mt5Colors.text))
            continue
        }
        out.add(Line(ln.replace("**", ""), Mt5Colors.text))
    }
    return out
}

/** 監督の生存（見出しの右） */
private fun aliveLabel(s: Sup): Pair<String, Color> = when (s.aliveStatus) {
    "ok" -> "●生存 ${shortTime(s.aliveT)}" to Mt5Colors.ok
    "skip" -> "●休場" to Mt5Colors.dim
    "running" -> "●判断中" to Mt5Colors.head
    "error" -> "●失敗 ${s.aliveNote}" to Mt5Colors.err
    "" -> "●未受信" to Mt5Colors.err
    else -> "●${s.aliveStatus} ${s.aliveNote}" to Mt5Colors.orange
}

@Composable
fun NippoScreen(vm: NippoViewModel = viewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.nippo_title), color = Mt5Colors.sym, fontWeight = FontWeight.Bold)
            Spacer(Modifier.padding(6.dp))
            ui.sup?.let { s ->
                val (t, c) = aliveLabel(s)
                Text(t, color = c, style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.weight(1f))
            val err = if (ui.page == 0) ui.supErr else ui.nippoErr
            if (err.isNotEmpty()) Text(err, color = Mt5Colors.err, style = MaterialTheme.typography.bodySmall)
            IconButton(onClick = vm::refresh) { Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.refresh)) }
        }
        TabRow(selectedTabIndex = ui.page, containerColor = Mt5Colors.bg) {
            Tab(selected = ui.page == 0, onClick = { vm.setPage(0) }, text = { Text(stringResource(R.string.nippo_summary)) })
            Tab(selected = ui.page == 1, onClick = { vm.setPage(1) }, text = { Text(stringResource(R.string.nippo_full)) })
        }
        val lines = if (ui.page == 0) {
            ui.sup?.let { summaryLines(it) } ?: listOf(Line(stringResource(R.string.nippo_loading), Mt5Colors.warn))
        } else {
            if (ui.nippo.isEmpty()) listOf(Line(stringResource(R.string.nippo_loading_full), Mt5Colors.warn)) else fullLines(ui.nippo)
        }
        SelectionContainer {
            LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
                itemsIndexed(lines) { _, l ->
                    if (l.section) HorizontalDivider(color = Mt5Colors.frame, modifier = Modifier.padding(top = 8.dp, bottom = 2.dp))
                    Text(l.text, color = l.color, style = if (l.section) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}
