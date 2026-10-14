package app.esp32cam.ui

import androidx.activity.compose.LocalActivity
import android.view.WindowManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale

import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.esp32cam.R
import app.esp32cam.ui.theme.CamColors
import app.esp32cam.vm.CamerasViewModel
import app.esp32cam.vm.Tile
import app.esp32cam.vm.TileState

@Composable
fun CamerasScreen(vm: CamerasViewModel, onOpenPhotos: () -> Unit, onOpenSettings: () -> Unit, onOpenFull: (Int) -> Unit) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val activity = LocalActivity.current

    // 映像を受けている間は画面を消さない
    DisposableEffect(ui.anyConnected) {
        val w = activity?.window
        if (ui.anyConnected) w?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else w?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { w?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }

    Scaffold { inner ->
        BoxWithConstraints(Modifier.fillMaxSize().padding(inner)) {
            val columns = if (maxWidth >= 600.dp) 3 else 2 // 縦持ちは 2 列 × 3 行、横長・タブレットは 3 列 × 2 行
            Column(Modifier.fillMaxSize()) {
                // 題名の行は置かず、小さなボタンを 1 段に並べて映像の場所を広く取る（利用者「ボタンが大きく表示スペースの邪魔」）
                Toolbar(ui.busy, ui.anyConnected, ui.flash, vm, onOpenPhotos)
                HorizontalDivider()
                // 行を画面の高さいっぱいに広げる（映像は 4:3 のまま中に収める）
                val rows = ui.tiles.chunked(columns)
                Column(Modifier.weight(1f).fillMaxWidth().padding(2.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    for (row in rows) {
                        Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                            for (t in row) Box(Modifier.weight(1f).fillMaxHeight()) { CameraTile(t, selected = t.index == ui.selected, vm, onOpenFull) }
                        }
                    }
                }
                HorizontalDivider()
                Row(Modifier.fillMaxWidth().padding(start = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.target, ui.selected + 1) + (if (ui.status.isNotEmpty()) "   " + ui.status else ""),
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.weight(1f).padding(vertical = 2.dp),
                        maxLines = 2,
                    )
                    IconButton(onClick = onOpenSettings, modifier = Modifier.size(32.dp)) { Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.settings)) }
                }
            }
        }
    }
}

/** 小さなボタン（高さ 30dp、余白を詰める） */
@Composable
private fun SmallButton(text: String, enabled: Boolean = true, filled: Boolean = false, onClick: () -> Unit) {
    val pad = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
    val m = Modifier.height(28.dp)
    if (filled) Button(onClick = onClick, enabled = enabled, contentPadding = pad, modifier = m) { Text(text, style = MaterialTheme.typography.labelMedium) }
    else OutlinedButton(onClick = onClick, enabled = enabled, contentPadding = pad, modifier = m) { Text(text, style = MaterialTheme.typography.labelMedium) }
}

@Composable
private fun Toolbar(busy: Boolean, anyConnected: Boolean, flash: Boolean, vm: CamerasViewModel, onOpenPhotos: () -> Unit) {
    FlowRow(
        Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        if (anyConnected) SmallButton(stringResource(R.string.disconnect_all_short), onClick = vm::disconnectAll)
        else SmallButton(stringResource(R.string.connect_all_short), filled = true, onClick = vm::connectAll)
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.height(30.dp).clickable { vm.setFlash(!flash) }) {
            Checkbox(checked = flash, onCheckedChange = { vm.setFlash(it) }, modifier = Modifier.size(28.dp))
            Text(stringResource(R.string.flash_short), style = MaterialTheme.typography.labelMedium)
        }
        SmallButton(stringResource(R.string.capture), enabled = !busy, filled = true, onClick = vm::capture)
        SmallButton(stringResource(R.string.capture_all_short), enabled = !busy, onClick = vm::captureAll)
        SmallButton(stringResource(R.string.status), enabled = !busy, onClick = vm::fetchStatus)
        SmallButton(stringResource(R.string.photos), enabled = !busy, onClick = onOpenPhotos)
    }
}

/** カメラ 1 台分のタイル。どこをタップしても対象になる。青い枠が対象 */
@Composable
private fun CameraTile(t: Tile, selected: Boolean, vm: CamerasViewModel, onOpenFull: (Int) -> Unit) {
    val frame by vm.frames[t.index].collectAsStateWithLifecycle()
    Column(
        Modifier
            .fillMaxSize()
            .border(if (selected) 3.dp else 1.dp, if (selected) CamColors.selected else MaterialTheme.colorScheme.outline)
            // 1 回タップで対象にする。ダブルタップでそのカメラを全画面に（利用者の要望）
            .pointerInput(t.index) { detectTapGestures(onTap = { vm.select(t.index) }, onDoubleTap = { vm.select(t.index); onOpenFull(t.index) }) }
            .padding(2.dp),
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.tile_head, t.index + 1, t.host), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelSmall, maxLines = 1, softWrap = false, modifier = Modifier.weight(1f))
            val pad = PaddingValues(horizontal = 6.dp, vertical = 0.dp)
            if (t.connected) androidx.compose.material3.TextButton(onClick = { vm.disconnect(t.index) }, contentPadding = pad, modifier = Modifier.height(26.dp)) { Text(stringResource(R.string.disconnect), style = MaterialTheme.typography.labelMedium) }
            else androidx.compose.material3.TextButton(onClick = { vm.connect(t.index) }, contentPadding = pad, modifier = Modifier.height(26.dp)) { Text(stringResource(R.string.connect), style = MaterialTheme.typography.labelMedium) }
        }
        Box(Modifier.fillMaxWidth().weight(1f).background(CamColors.video), contentAlignment = Alignment.Center) {
            val bmp = frame
            if (t.state == TileState.STREAMING && bmp != null) {
                Image(bmp.asImageBitmap(), contentDescription = stringResource(R.string.camera_n, t.index + 1), modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            } else {
                val (text, color) = when (t.state) {
                    TileState.IDLE -> stringResource(R.string.st_idle) to Color.LightGray
                    TileState.CONNECTING -> stringResource(R.string.st_connecting) to Color.LightGray
                    TileState.WAITING -> stringResource(R.string.st_waiting) to Color.LightGray
                    TileState.STREAMING -> "" to Color.LightGray
                    TileState.FAILED -> stringResource(R.string.st_failed) to CamColors.err
                    TileState.DISCONNECTED -> stringResource(R.string.st_disconnected) to CamColors.warn
                }
                Text(text, color = color, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(8.dp))
            }
        }
    }
}
