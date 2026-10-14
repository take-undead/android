package app.esp32cam.ui

import android.view.WindowManager
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.esp32cam.R
import app.esp32cam.ui.theme.CamColors
import app.esp32cam.vm.CamerasViewModel
import app.esp32cam.vm.TileState

/**
 * カメラ 1 台の全画面表示（タイルのダブルタップで開く。利用者の要望 2026-10-14）。
 * 1 回タップか「戻る」で元の画面へ。未接続ならつないでから出す。
 */
@Composable
fun FullScreen(vm: CamerasViewModel, index: Int, onBack: () -> Unit) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val frame by vm.frames[index].collectAsStateWithLifecycle()
    val tile = ui.tiles.getOrNull(index) ?: run { LaunchedEffect(Unit) { onBack() }; return }
    val activity = LocalActivity.current

    LaunchedEffect(index) { if (!tile.connected) vm.connect(index) }
    // 全画面の間は画面を消さない
    DisposableEffect(Unit) {
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) { detectTapGestures(onTap = { onBack() }, onDoubleTap = { onBack() }) },
        contentAlignment = Alignment.Center,
    ) {
        val bmp = frame
        if (tile.state == TileState.STREAMING && bmp != null) {
            Image(bmp.asImageBitmap(), contentDescription = stringResource(R.string.camera_n, index + 1), modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        } else {
            val (text, color) = when (tile.state) {
                TileState.IDLE, TileState.CONNECTING -> stringResource(R.string.st_connecting) to Color.LightGray
                TileState.WAITING -> stringResource(R.string.st_waiting) to Color.LightGray
                TileState.STREAMING -> "" to Color.LightGray
                TileState.FAILED -> stringResource(R.string.st_failed) to CamColors.err
                TileState.DISCONNECTED -> stringResource(R.string.st_disconnected) to CamColors.warn
            }
            Text(text, color = color)
        }
        // 左上に小さく番号と IP（映像の上に重ねる）
        Text(
            stringResource(R.string.tile_head, index + 1, tile.host),
            color = Color.White,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.align(Alignment.TopStart).padding(8.dp).background(Color(0x80000000)).padding(horizontal = 6.dp, vertical = 2.dp),
        )
        Text(
            stringResource(R.string.full_hint),
            color = Color.LightGray,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.align(Alignment.BottomCenter).padding(8.dp),
        )
    }
}
