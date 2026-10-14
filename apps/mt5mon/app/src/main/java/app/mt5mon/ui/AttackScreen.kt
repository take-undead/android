package app.mt5mon.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.mt5mon.R
import app.mt5mon.ui.theme.Mt5Colors
import app.mt5mon.vm.AttackUi
import app.mt5mon.vm.AttackViewModel
import java.util.Locale

private fun pct(part: Long, whole: Long) = if (whole > 0) part * 100.0 / whole else 0.0

@Composable
fun AttackScreen(vm: AttackViewModel = viewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.attack_title), color = Mt5Colors.sym, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(8.dp))
            ui.attack?.let { Text(stringResource(R.string.attack_updated, timePart(it.updatedJst), it.since), color = Mt5Colors.dim, style = MaterialTheme.typography.bodySmall) }
            Spacer(Modifier.weight(1f))
            if (ui.err.isNotEmpty()) Text(ui.err, color = Mt5Colors.err, style = MaterialTheme.typography.bodySmall)
            IconButton(onClick = vm::refresh) { Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.refresh)) }
        }
        // 見張りごとの数
        ui.attack?.let { a ->
            Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                for (j in a.jails) {
                    Text(stringResource(R.string.attack_jail, j.label, j.ports, j.curBanned, j.totalBanned, j.totalFailed), color = Mt5Colors.text, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                }
                Text(stringResource(R.string.attack_perm_count, a.nperm), color = Mt5Colors.err, style = MaterialTheme.typography.bodySmall)
            }
        }
        TabRow(selectedTabIndex = ui.tab, containerColor = Mt5Colors.bg) {
            listOf(R.string.attack_tab_top, R.string.attack_tab_perm, R.string.attack_tab_recent, R.string.attack_tab_stats).forEachIndexed { i, r ->
                Tab(selected = ui.tab == i, onClick = { vm.setTab(i) }, text = { Text(stringResource(r)) })
            }
        }
        Column(Modifier.weight(1f)) { AttackList(ui, vm) }
        HorizontalDivider(color = Mt5Colors.frame)
        Detail(ui)
        // 操作
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (ui.tab == 3) {
                OutlinedButton(onClick = vm::toggleSort) { Text(if (ui.sortByFails) stringResource(R.string.attack_sort_fails) else stringResource(R.string.attack_sort_ips)) }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = vm::statsRefresh, enabled = !ui.sending) { Text(stringResource(R.string.attack_stats_refresh)) }
            } else {
                val enabled = ui.selectedIp.isNotEmpty() && !ui.sending
                Button(onClick = { vm.askBan(true) }, enabled = enabled, colors = ButtonDefaults.buttonColors(containerColor = Mt5Colors.err)) { Text(stringResource(R.string.attack_ban)) }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = { vm.askBan(false) }, enabled = enabled) { Text(stringResource(R.string.attack_unban)) }
            }
        }
        if (ui.msg.isNotEmpty()) Text(ui.msg, color = when (ui.msgOk) { true -> Mt5Colors.ok; false -> Mt5Colors.err; null -> Mt5Colors.head }, modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp), style = MaterialTheme.typography.bodySmall)
    }
    ui.confirm?.let { (ban, ip) ->
        AlertDialog(
            onDismissRequest = vm::cancelConfirm,
            title = { Text(if (ban) stringResource(R.string.attack_confirm_ban_title) else stringResource(R.string.attack_confirm_unban_title)) },
            text = { Text(stringResource(if (ban) R.string.attack_confirm_ban else R.string.attack_confirm_unban, ip)) },
            confirmButton = { TextButton(onClick = vm::confirmSend) { Text(stringResource(R.string.send), color = if (ban) Mt5Colors.err else Mt5Colors.ok) } },
            dismissButton = { TextButton(onClick = vm::cancelConfirm) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun RowItem(selected: Boolean, onClick: () -> Unit, content: @Composable RowScope.() -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(if (selected) Mt5Colors.sel else Color.Transparent).clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) { content() }
}

@Composable
private fun AttackList(ui: AttackUi, vm: AttackViewModel) {
    val a = ui.attack
    val mono = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
    when (ui.tab) {
        0 -> LazyColumn {
            items(a?.top ?: emptyList(), key = { it.ip }) { x ->
                val (stText, c) = when {
                    x.state == 'P' -> stringResource(R.string.attack_state_perm) to Mt5Colors.err
                    x.state == 'B' -> "~${x.until.ifEmpty { stringResource(R.string.attack_state_noend) }}" to Mt5Colors.warn
                    x.fails >= 50 -> "-" to Mt5Colors.orange // 遮断されずに失敗を重ねている（遅い総当たり）
                    else -> "-" to Mt5Colors.text
                }
                RowItem(ui.selectedIp == x.ip, { vm.select(x.ip) }) {
                    Text(x.ip, color = c, style = mono, modifier = Modifier.weight(1.5f))
                    Text(x.cc.ifEmpty { "--" }, color = c, style = mono, modifier = Modifier.weight(0.4f))
                    Text(x.ports, color = c, style = mono, modifier = Modifier.weight(1f), maxLines = 1)
                    Text("${x.fails}", color = c, style = mono, modifier = Modifier.weight(0.6f))
                    Text(stText, color = c, style = mono, modifier = Modifier.weight(0.8f))
                }
            }
        }
        1 -> LazyColumn {
            items(a?.perm ?: emptyList(), key = { it.ip }) { x ->
                RowItem(ui.selectedIp == x.ip, { vm.select(x.ip) }) {
                    Text(x.ip, color = Mt5Colors.err, style = mono, modifier = Modifier.weight(1.5f))
                    Text(x.cc.ifEmpty { "--" }, color = Mt5Colors.err, style = mono, modifier = Modifier.weight(0.4f))
                    Text(x.t, color = Mt5Colors.err, style = mono, modifier = Modifier.weight(1.2f))
                    Text(x.ports, color = Mt5Colors.err, style = mono, modifier = Modifier.weight(1f), maxLines = 1)
                }
            }
        }
        2 -> LazyColumn {
            items(a?.recent ?: emptyList(), key = { "${it.t}${it.ip}${it.act}" }) { x ->
                val (an, c) = when (x.act) {
                    "ban" -> stringResource(R.string.attack_act_ban) to Mt5Colors.orange
                    "unban" -> stringResource(R.string.attack_act_unban) to Mt5Colors.dim
                    "inc" -> stringResource(R.string.attack_act_inc) to Mt5Colors.err
                    "restore" -> stringResource(R.string.attack_act_restore) to Mt5Colors.warn
                    else -> x.act to Mt5Colors.text
                }
                RowItem(ui.selectedIp == x.ip, { vm.select(x.ip) }) {
                    Text(x.t, color = c, style = mono, modifier = Modifier.weight(1.2f))
                    Text(x.jail, color = c, style = mono, modifier = Modifier.weight(0.5f))
                    Text(an, color = c, style = mono, modifier = Modifier.weight(0.5f))
                    Text(x.ip, color = c, style = mono, modifier = Modifier.weight(1.5f))
                    Text(x.cc.ifEmpty { "--" }, color = c, style = mono, modifier = Modifier.weight(0.4f))
                }
            }
        }
        else -> {
            val s = ui.stats
            if (s == null) {
                Text(if (ui.err.isNotEmpty()) ui.err else stringResource(R.string.attack_stats_loading), color = Mt5Colors.warn, modifier = Modifier.padding(8.dp))
                return
            }
            val countries = if (ui.sortByFails) s.countries.sortedByDescending { it.fails } else s.countries.sortedByDescending { it.ips }
            Text(stringResource(R.string.attack_stats_head, s.from, s.to, s.ips, s.fails, s.ncountries, timePart(s.made)), color = Mt5Colors.dim, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 8.dp))
            LazyColumn {
                items(countries, key = { it.cc + it.name }) { c ->
                    val pi = pct(c.ips.toLong(), s.ips.toLong())
                    val pf = pct(c.fails, s.fails)
                    val col = if ((if (ui.sortByFails) pf else pi) >= 10) Mt5Colors.orange else Mt5Colors.text
                    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 3.dp)) {
                        Text(c.name, color = col, style = mono, modifier = Modifier.weight(1.6f), maxLines = 1)
                        Text(String.format(Locale.US, "%d %.1f%%", c.ips, pi), color = col, style = mono, modifier = Modifier.weight(1f))
                        Text(String.format(Locale.US, "%d %.1f%%", c.fails, pf), color = col, style = mono, modifier = Modifier.weight(1.2f))
                    }
                }
                if (s.nets.isNotEmpty()) {
                    item { Text(stringResource(R.string.attack_nets_head), color = Mt5Colors.frame, modifier = Modifier.padding(8.dp), style = MaterialTheme.typography.bodySmall) }
                    items(s.nets, key = { it.net }) { n ->
                        val col = if (pct(n.fails, s.fails) >= 10) Mt5Colors.orange else Mt5Colors.text
                        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 3.dp)) {
                            Text(n.net, color = col, style = mono, modifier = Modifier.weight(1.6f))
                            Text(n.cc, color = col, style = mono, modifier = Modifier.weight(0.4f))
                            Text(stringResource(R.string.attack_net_row, n.ips, n.fails), color = col, style = mono, modifier = Modifier.weight(1.8f))
                        }
                    }
                }
            }
        }
    }
}

/** 選んだ IP の詳しい 1 行 */
@Composable
private fun Detail(ui: AttackUi) {
    val a = ui.attack ?: return
    val ip = ui.selectedIp
    if (ip.isEmpty()) return
    val text = when (ui.tab) {
        0 -> a.top.firstOrNull { it.ip == ip }?.let { stringResource(R.string.attack_detail_top, it.country.ifEmpty { stringResource(R.string.attack_unknown_country) }, it.bans, it.last) }
        1 -> a.perm.firstOrNull { it.ip == ip }?.let { stringResource(R.string.attack_detail_perm, it.country.ifEmpty { stringResource(R.string.attack_unknown_country) }, it.ports, it.pkts) }
        else -> a.top.firstOrNull { it.ip == ip }?.let { stringResource(R.string.attack_detail_recent, it.country.ifEmpty { stringResource(R.string.attack_unknown_country) }, it.fails, it.ports) }
    } ?: return
    Text(text, color = Mt5Colors.dim, modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp), style = MaterialTheme.typography.bodySmall)
}
