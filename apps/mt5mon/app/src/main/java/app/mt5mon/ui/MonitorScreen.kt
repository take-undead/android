package app.mt5mon.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.mt5mon.R
import app.mt5mon.data.Fetch
import app.mt5mon.ui.theme.Mt5Colors
import app.mt5mon.vm.MainUi
import app.mt5mon.vm.MainViewModel
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** 右上の状態の札 */
private data class Badge(val text: String, val color: Color)

/** 今の問題（無ければ空）と状態の札（PicoCalc の ui.c の problem() と同じ判定） */
@Composable
private fun problem(ui: MainUi): Pair<String, Badge> {
    val t = ui.tunnel
    var text = ""
    var fail = true
    if (ui.config == null) {
        text = if (ui.configError == "none") stringResource(R.string.problem_no_config) else stringResource(R.string.problem_config, ui.configError)
    } else if (!t.started) {
        text = if (t.error.isNotEmpty()) stringResource(R.string.problem_vpn_error, t.error) else stringResource(R.string.problem_vpn_off)
    } else if (!t.up) {
        text = if (t.handshakeAgeSec < 0) stringResource(R.string.problem_vpn_handshake) else stringResource(R.string.problem_vpn_stale, t.handshakeAgeSec)
    } else {
        fail = false
        when (val r = ui.lastFetch) {
            null, is Fetch.Ok -> {}
            is Fetch.Connect -> { text = stringResource(R.string.problem_http_connect, ui.config.urlHost, ui.config.urlPort, r.why); fail = true }
            Fetch.Timeout -> { text = stringResource(R.string.problem_http_timeout); fail = true }
            is Fetch.Http -> { text = stringResource(R.string.problem_http_code, r.code) + if (r.code == 503) stringResource(R.string.problem_http_503) else ""; fail = true }
            Fetch.TooBig -> { text = stringResource(R.string.problem_http_big); fail = true }
        }
        if (!fail && ui.parseErr.isNotEmpty()) { text = ui.parseErr; fail = true }
    }
    if (fail) return text to Badge(stringResource(R.string.badge_fail), Mt5Colors.err)
    val st = ui.status ?: return text to Badge(stringResource(R.string.badge_loading), Mt5Colors.warn)
    return when {
        ui.frozen -> stringResource(R.string.problem_frozen, MainUi.FROZEN_MS / 1000) to Badge(stringResource(R.string.badge_frozen), Mt5Colors.err)
        st.stale -> stringResource(R.string.problem_stale) to Badge(stringResource(R.string.badge_stale), Mt5Colors.warn)
        !st.connected -> stringResource(R.string.problem_disconnected) to Badge(stringResource(R.string.badge_disconnected), Mt5Colors.warn)
        else -> "" to Badge(stringResource(R.string.badge_live), Mt5Colors.ok)
    }
}

@Composable
fun MonitorScreen(vm: MainViewModel, onOpenSettings: () -> Unit) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val (prob, badge) = problem(ui)
    val st = ui.status

    Column(Modifier.fillMaxSize().background(Mt5Colors.bg)) {
        Header(ui, badge, onRefresh = vm::refresh)
        HorizontalDivider(color = Mt5Colors.frame)
        if (st == null) {
            Waiting(ui, prob, onOpenSettings)
            return@Column
        }
        if (prob.isNotEmpty()) {
            Text(prob, color = badge.color, modifier = Modifier.fillMaxWidth().background(Color(0xFF280000)).padding(horizontal = 8.dp, vertical = 2.dp), style = MaterialTheme.typography.bodySmall)
        }
        // チャートを過去へ送った本数（右端から隠す本数。0 = 最新）。時間足を変えたら最新に戻す（PicoCalc の T と同じ）
        var scroll by rememberSaveable { mutableIntStateOf(0) }
        LaunchedEffect(ui.prefs.tf) { scroll = 0 }
        // 時間足と足の幅（横に収まらない幅の端末では横に送れる）
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            MainViewModel.TF_NAMES.forEachIndexed { i, label ->
                FilterChip(selected = ui.prefs.tf == i, onClick = { vm.setTf(i) }, label = { Text(label) }, modifier = Modifier.padding(end = 6.dp))
            }
            // 3 → 4 → 2 → 1 → 3（PicoCalc の Z と同じ。1 は一番多くの足を一度に見る）
            val next = when (ui.prefs.barWidth) { 3 -> 4; 4 -> 2; 2 -> 1; else -> 3 }
            FilterChip(selected = false, onClick = { vm.setBarWidth(next) }, label = { Text(stringResource(R.string.bar_width, ui.prefs.barWidth)) }, modifier = Modifier.padding(end = 6.dp))
            if (scroll > 0) {
                FilterChip(selected = true, onClick = { scroll = 0 }, label = { Text(stringResource(R.string.chart_to_now)) })
            }
        }
        val alertLabel = if (ui.prefs.alertOn) stringResource(R.string.chart_alert_on, ui.prefs.alertPips, ui.prefs.alertMinutes) else stringResource(R.string.chart_alert_off)
        CandleChart(
            st = st, tf = MainViewModel.tfMinutes(ui.prefs.tf), hist = ui.hist, barWidthPx = ui.prefs.barWidth,
            scroll = scroll, onScroll = { scroll = it },
            alert = ui.alert, nowMillis = ui.nowMillis, histErr = ui.histErr, histLoading = ui.hist == null, alertLabel = alertLabel,
            modifier = Modifier.fillMaxWidth().height(260.dp),
        )
        HorizontalDivider(color = Mt5Colors.frame)
        Account(ui)
        HorizontalDivider(color = Mt5Colors.frame)
        Lists(ui)
    }
}

@Composable
private fun Header(ui: MainUi, badge: Badge, onRefresh: () -> Unit) {
    val st = ui.status
    Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (st != null) {
                Text(st.symbol, color = Mt5Colors.sym, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(8.dp))
                Text("${fmtPrice(st.bid, st.digits)}/${fmtPrice(st.ask, st.digits)}", color = Mt5Colors.text, fontFamily = FontFamily.Monospace)
                Spacer(Modifier.width(8.dp))
                Text(String.format(Locale.US, "%.1f", st.spreadPips), color = Mt5Colors.dim)
            } else {
                Text(stringResource(R.string.app_name), color = Mt5Colors.sym, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.weight(1f))
            Text(badge.text, color = badge.color, fontWeight = FontWeight.Bold)
            IconButton(onClick = onRefresh) { Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.refresh)) }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            val now = LocalTime.now(MainViewModel.JST).format(DateTimeFormatter.ofPattern("HH:mm:ss"))
            Text(now, color = Mt5Colors.clock, fontFamily = FontFamily.Monospace)
            Spacer(Modifier.width(10.dp))
            Text("VPN", color = if (ui.vpnOk) Mt5Colors.ok else Mt5Colors.err, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.width(6.dp))
            Text("HTTP", color = if (ui.httpOk) Mt5Colors.ok else if (ui.vpnOk) Mt5Colors.err else Mt5Colors.dim, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.width(10.dp))
            if (st != null) {
                val age = (ui.nowMillis - ui.lastOkAt) / 1000
                val interval = ui.config?.intervalSec ?: 5
                Text(stringResource(R.string.gen_age, timePart(st.genJst), age), color = if (age > 2 * interval + 5) Mt5Colors.warn else Mt5Colors.dim, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/** データがまだ無いときの中央: 今どこで止まっているかと、VPS に登録する公開鍵 */
@Composable
private fun Waiting(ui: MainUi, prob: String, onOpenSettings: () -> Unit) {
    ContentColumn(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text(if (prob.isEmpty()) stringResource(R.string.waiting) else prob, color = if (ui.config != null) Mt5Colors.warn else Mt5Colors.err)
        Spacer(Modifier.height(16.dp))
        val cfg = ui.config
        if (cfg == null) {
            Text(stringResource(R.string.waiting_no_config_hint), color = Mt5Colors.dim)
            Spacer(Modifier.height(8.dp))
            Button(onClick = onOpenSettings) { Text(stringResource(R.string.open_settings)) }
        } else {
            Text(stringResource(R.string.waiting_pubkey), color = Mt5Colors.dim)
            Text(cfg.wgPublicKey, color = Mt5Colors.text, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            Text(stringResource(R.string.waiting_endpoint, cfg.wgEndpoint), color = Mt5Colors.dim)
            if (!ui.tunnel.started) {
                Spacer(Modifier.height(8.dp))
                Button(onClick = onOpenSettings) { Text(stringResource(R.string.open_settings)) }
            }
        }
    }
}

@Composable
private fun Account(ui: MainUi) {
    val st = ui.status ?: return
    Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
        Row {
            Text(stringResource(R.string.acc_balance, fmtMoney(st.balance), fmtMoney(st.equity)), color = Mt5Colors.text, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.acc_profit), color = Mt5Colors.dim, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.width(4.dp))
            Text(fmtMoney(st.profit, true), color = if (st.profit > 0) Mt5Colors.plus else if (st.profit < 0) Mt5Colors.minus else Mt5Colors.text, style = MaterialTheme.typography.bodySmall)
        }
        Row {
            val alive = st.eas.count { it.alive }
            val ml = if (st.marginLevel > 0) String.format(Locale.US, "%.0f%%", st.marginLevel) else "---"
            Text(stringResource(R.string.acc_margin, ml), color = Mt5Colors.dim, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.width(12.dp))
            Text(stringResource(R.string.acc_ea, alive, st.eas.size), color = if (alive == st.eas.size) Mt5Colors.dim else Mt5Colors.warn, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.width(12.dp))
            Text(stringResource(R.string.acc_pos, st.positions.size), color = Mt5Colors.dim, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun Lists(ui: MainUi) {
    val st = ui.status ?: return
    var mode by rememberSaveable { mutableIntStateOf(0) }
    TabRow(selectedTabIndex = mode, containerColor = Mt5Colors.bg) {
        Tab(selected = mode == 0, onClick = { mode = 0 }, text = { Text(stringResource(R.string.tab_positions)) })
        Tab(selected = mode == 1, onClick = { mode = 1 }, text = { Text(stringResource(R.string.tab_eas)) })
    }
    if (mode == 0) {
        if (st.positions.isEmpty()) Text(stringResource(R.string.no_positions), color = Mt5Colors.dim, modifier = Modifier.padding(8.dp))
        LazyColumn(Modifier.fillMaxWidth()) {
            items(st.positions, key = { it.ticket }) { p ->
                val other = p.symbol != st.symbol
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 3.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(if (other) p.symbol else p.ea, color = if (other) Mt5Colors.warn else Mt5Colors.text, modifier = Modifier.weight(1.4f), maxLines = 1)
                    Text(if (p.buy) stringResource(R.string.buy) else stringResource(R.string.sell), color = if (p.buy) Mt5Colors.up else Mt5Colors.down, modifier = Modifier.weight(0.5f))
                    Text(String.format(Locale.US, "%.2f", p.lots), color = Mt5Colors.text, modifier = Modifier.weight(0.6f), fontFamily = FontFamily.Monospace)
                    Text(fmtPrice(p.open, if (p.digits > 0) p.digits else st.digits), color = Mt5Colors.text, modifier = Modifier.weight(0.9f), fontFamily = FontFamily.Monospace)
                    Text(fmtMoney(p.profit, true), color = if (p.profit > 0) Mt5Colors.plus else if (p.profit < 0) Mt5Colors.minus else Mt5Colors.text, modifier = Modifier.weight(0.9f), fontFamily = FontFamily.Monospace)
                }
            }
        }
    } else {
        LazyColumn(Modifier.fillMaxWidth()) {
            items(st.eas, key = { it.name }) { e ->
                val np = st.positions.count { it.ea == e.name }
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 3.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(e.name, color = if (e.alive) Mt5Colors.text else Mt5Colors.err, modifier = Modifier.weight(2f), maxLines = 1)
                    Text(if (e.alive) stringResource(R.string.ea_alive) else stringResource(R.string.ea_dead), color = if (e.alive) Mt5Colors.ok else Mt5Colors.err, modifier = Modifier.weight(0.6f))
                    Text("$np", color = if (np > 0) Mt5Colors.text else Mt5Colors.dim, modifier = Modifier.weight(0.4f))
                }
            }
        }
    }
}
