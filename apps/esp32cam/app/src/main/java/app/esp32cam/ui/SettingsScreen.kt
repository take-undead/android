package app.esp32cam.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.esp32cam.R
import app.esp32cam.data.CameraApi
import app.esp32cam.data.HostStore
import app.esp32cam.vm.CamerasViewModel

/** 6 台分の IP アドレス。保存ボタンで一度に保存する（次回の起動時にも残る） */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class) // TopAppBar は material3 1.4 でも experimental
@Composable
fun SettingsScreen(vm: CamerasViewModel, onBack: () -> Unit) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    var hosts by rememberSaveable { mutableStateOf(ui.tiles.map { it.host }) }
    var base by rememberSaveable { mutableStateOf(ui.tiles.firstOrNull()?.host ?: HostStore.DEFAULT_BASE_HOST) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back)) } },
            )
        },
    ) { inner ->
        Column(Modifier.fillMaxSize().padding(inner).verticalScroll(rememberScrollState()).padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Column(Modifier.widthIn(max = 600.dp).fillMaxWidth()) {
                Text(stringResource(R.string.set_hosts_title), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.set_hosts_hint), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 8.dp))
                hosts.forEachIndexed { i, h ->
                    OutlinedTextField(
                        value = h,
                        onValueChange = { v -> hosts = hosts.mapIndexed { j, x -> if (j == i) v else x } },
                        label = { Text(stringResource(R.string.camera_n, i + 1)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                    OutlinedTextField(value = base, onValueChange = { base = it }, label = { Text(stringResource(R.string.set_base)) }, singleLine = true, modifier = Modifier.weight(1f))
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(onClick = { hosts = HostStore.defaultHosts(CameraApi.toHalfWidth(base).replace(" ", "").trim()) }) { Text(stringResource(R.string.set_fill)) }
                }
                Button(onClick = { vm.setHosts(hosts); onBack() }, modifier = Modifier.padding(top = 16.dp)) { Text(stringResource(R.string.save)) }
            }
        }
    }
}
