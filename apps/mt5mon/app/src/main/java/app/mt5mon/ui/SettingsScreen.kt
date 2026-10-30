package app.mt5mon.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.mt5mon.R
import app.mt5mon.ui.theme.Mt5Colors
import app.mt5mon.vm.MainViewModel

private val PIPS_CHOICES = listOf(5, 8, 10, 12, 15, 20, 25, 30, 40, 50, 70, 100)
private val MIN_CHOICES = listOf(1, 2, 3, 5, 10, 15, 20)
private val NOTICE_CHOICES = listOf(0, 5, 10, 20, 30, 60, 120, 300)

@Composable
fun SettingsScreen(vm: MainViewModel) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var pasteOpen by rememberSaveable { mutableStateOf(false) }
    var pasteText by rememberSaveable { mutableStateOf("") }
    var importErr by rememberSaveable { mutableStateOf("") }

    // config.txt をファイルから読む（PC の build\mt5mon\config.txt を端末に写したもの）
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        try {
            val text = context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
            if (text.length > 64 * 1024) throw IllegalArgumentException("大きすぎる")
            vm.saveConfigText(text)
            importErr = ""
        } catch (e: Exception) {
            importErr = e.message ?: e.javaClass.simpleName
        }
    }

    ContentColumn(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
        // ---- VPN ----
        Card(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
            Column(Modifier.padding(12.dp)) {
                Text(stringResource(R.string.set_vpn_title), style = MaterialTheme.typography.titleMedium, color = Mt5Colors.sym)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.set_vpn_external), color = Mt5Colors.text, modifier = Modifier.weight(1f))
                    Switch(checked = ui.prefs.externalVpn, onCheckedChange = vm::setExternalVpn, enabled = !ui.vpnBusy)
                }
                val t = ui.tunnel
                if (t.external) {
                    Text(stringResource(R.string.set_vpn_external_note), color = Mt5Colors.dim, style = MaterialTheme.typography.bodySmall)
                    return@Column
                }
                val stText = when {
                    t.error.isNotEmpty() -> stringResource(R.string.set_vpn_error, t.error)
                    !t.started -> stringResource(R.string.set_vpn_down)
                    t.up -> stringResource(R.string.set_vpn_up, t.handshakeAgeSec)
                    t.handshakeAgeSec < 0 -> stringResource(R.string.set_vpn_handshaking)
                    else -> stringResource(R.string.set_vpn_stale, t.handshakeAgeSec)
                }
                Text(stText, color = if (t.up) Mt5Colors.ok else if (t.started) Mt5Colors.warn else Mt5Colors.dim)
                if (t.started) Text(stringResource(R.string.set_vpn_bytes, t.rxBytes / 1024, t.txBytes / 1024), color = Mt5Colors.dim, style = MaterialTheme.typography.bodySmall)
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                    if (t.started) OutlinedButton(onClick = vm::disconnectVpn, enabled = !ui.vpnBusy) { Text(stringResource(R.string.set_vpn_disconnect)) }
                    else Button(onClick = vm::connectVpn, enabled = ui.config != null && !ui.vpnBusy) { Text(stringResource(R.string.set_vpn_connect)) }
                    Spacer(Modifier.width(16.dp))
                    Text(stringResource(R.string.set_vpn_auto), color = Mt5Colors.text, style = MaterialTheme.typography.bodySmall)
                    Switch(checked = ui.prefs.autoVpn, onCheckedChange = vm::setAutoVpn, modifier = Modifier.padding(start = 4.dp))
                }
                Text(stringResource(R.string.set_vpn_note), color = Mt5Colors.dim, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
            }
        }
        // ---- 設定ファイル ----
        Card(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
            Column(Modifier.padding(12.dp)) {
                Text(stringResource(R.string.set_config_title), style = MaterialTheme.typography.titleMedium, color = Mt5Colors.sym)
                val cfg = ui.config
                if (cfg != null && cfg.external) {
                    Text(stringResource(R.string.set_config_external), color = Mt5Colors.dim)
                } else if (cfg != null) {
                    Text(stringResource(R.string.set_config_pubkey), color = Mt5Colors.dim, style = MaterialTheme.typography.bodySmall)
                    Text(cfg.wgPublicKey, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, color = Mt5Colors.text)
                    Text(stringResource(R.string.set_config_lines, cfg.wgAddress, cfg.wgEndpoint, cfg.wgAllowed, cfg.wgKeepalive, "http://${cfg.urlHost}:${cfg.urlPort}${cfg.urlPath}", cfg.intervalSec), color = Mt5Colors.text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
                } else if (ui.configError == "none") {
                    Text(stringResource(R.string.set_config_none), color = Mt5Colors.warn)
                } else {
                    Text(stringResource(R.string.set_config_bad, ui.configError), color = Mt5Colors.err)
                }
                if (importErr.isNotEmpty()) Text(stringResource(R.string.set_config_import_err, importErr), color = Mt5Colors.err, style = MaterialTheme.typography.bodySmall)
                Row(Modifier.padding(top = 8.dp)) {
                    Button(onClick = { picker.launch(arrayOf("*/*")) }) { Text(stringResource(R.string.set_config_import)) }
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(onClick = { pasteText = ui.prefs.configText; pasteOpen = true }) { Text(stringResource(R.string.set_config_paste)) }
                }
                Text(stringResource(R.string.set_config_hint), color = Mt5Colors.dim, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
            }
        }
        // ---- チャイム ----
        Card(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
            Column(Modifier.padding(12.dp)) {
                val p = ui.prefs
                Text(stringResource(R.string.set_alert_title), style = MaterialTheme.typography.titleMedium, color = Mt5Colors.sym)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.set_alert_on), color = Mt5Colors.text, modifier = Modifier.weight(1f))
                    Switch(checked = p.alertOn, onCheckedChange = { vm.setAlert(it, p.alertPips, p.alertMinutes, p.alertVolume) })
                }
                Text(stringResource(R.string.set_alert_pips), color = Mt5Colors.dim, style = MaterialTheme.typography.bodySmall)
                ChipRow(PIPS_CHOICES, p.alertPips) { vm.setAlert(p.alertOn, it, p.alertMinutes, p.alertVolume) }
                Text(stringResource(R.string.set_alert_minutes), color = Mt5Colors.dim, style = MaterialTheme.typography.bodySmall)
                ChipRow(MIN_CHOICES, p.alertMinutes) { vm.setAlert(p.alertOn, p.alertPips, it, p.alertVolume) }
                Text(stringResource(R.string.set_alert_volume, p.alertVolume), color = Mt5Colors.dim, style = MaterialTheme.typography.bodySmall)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    var vol by rememberSaveable(p.alertVolume) { mutableFloatStateOf(p.alertVolume.toFloat()) }
                    Slider(
                        value = vol, onValueChange = { vol = it }, valueRange = 1f..10f, steps = 8,
                        onValueChangeFinished = { vm.setAlert(p.alertOn, p.alertPips, p.alertMinutes, vol.toInt()); vm.testChime(vol.toInt()) },
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { vm.testChime(p.alertVolume) }) { Text(stringResource(R.string.set_alert_test)) }
                }
                Text(stringResource(R.string.set_alert_note), color = Mt5Colors.dim, style = MaterialTheme.typography.bodySmall)
            }
        }
        // ---- 知らせ ----
        Card(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
            Column(Modifier.padding(12.dp)) {
                Text(stringResource(R.string.set_notice_title), style = MaterialTheme.typography.titleMedium, color = Mt5Colors.sym)
                Text(stringResource(R.string.set_notice_seconds), color = Mt5Colors.dim, style = MaterialTheme.typography.bodySmall)
                ChipRow(NOTICE_CHOICES, ui.prefs.noticeSeconds, labelOf = { if (it == 0) stringResource(R.string.set_notice_manual) else "$it" }) { vm.setNoticeSeconds(it) }
                if (ui.notice != null) TextButton(onClick = vm::openNotice) { Text(stringResource(R.string.set_notice_show_last)) }
            }
        }
        Spacer(Modifier.height(24.dp))
    }

    if (pasteOpen) AlertDialog(
        onDismissRequest = { pasteOpen = false },
        title = { Text(stringResource(R.string.set_config_paste)) },
        text = {
            OutlinedTextField(value = pasteText, onValueChange = { pasteText = it }, modifier = Modifier.fillMaxWidth().height(260.dp), textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace))
        },
        confirmButton = { TextButton(onClick = { vm.saveConfigText(pasteText); pasteOpen = false }) { Text(stringResource(R.string.save)) } },
        dismissButton = { TextButton(onClick = { pasteOpen = false }) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun ChipRow(choices: List<Int>, current: Int, labelOf: @Composable (Int) -> String = { "$it" }, onPick: (Int) -> Unit) {
    androidx.compose.foundation.layout.FlowRow(Modifier.fillMaxWidth()) {
        for (c in choices) {
            FilterChip(selected = c == current, onClick = { onPick(c) }, label = { Text(labelOf(c)) }, modifier = Modifier.padding(end = 6.dp))
        }
    }
}
