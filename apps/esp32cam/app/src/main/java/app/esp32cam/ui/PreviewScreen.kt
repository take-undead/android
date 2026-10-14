package app.esp32cam.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.esp32cam.R
import app.esp32cam.data.ApiError
import app.esp32cam.ui.theme.CamColors
import app.esp32cam.vm.CamerasViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 1 枚分の読み込み状態 */
private data class Loaded(val bytes: ByteArray, val bitmap: Bitmap)

/** 写真のプレビュー。左右スワイプで一覧の前後へ。右上の保存で端末に保存 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class) // TopAppBar は material3 1.4 でも experimental
@Composable
fun PreviewScreen(vm: CamerasViewModel, startIndex: Int, onBack: () -> Unit) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val names = ui.photos
    val camera = ui.photosCamera.takeIf { it >= 0 } ?: ui.selected
    val context = LocalContext.current
    val resources = LocalResources.current // 文字列は設定変更に追従する LocalResources から引く（Lint の指摘）
    val scope = rememberCoroutineScope()
    // 一覧が空（対象を切り替えた等）なら戻る。描画中に戻らず、描画の後で行う
    LaunchedEffect(names.isEmpty()) { if (names.isEmpty()) onBack() }
    if (names.isEmpty()) return
    val pager = rememberPagerState(initialPage = startIndex.coerceIn(0, names.size - 1)) { names.size }
    // ページごとの読み込み結果（先読みされたページも含めて持つ。表示中のページの分を題名と保存に使う）
    val loadedPages = remember { mutableStateMapOf<Int, Loaded>() }
    val current = loadedPages[pager.currentPage]
    var saving by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            val size = current?.bitmap?.let { "（${it.width}×${it.height}）" } ?: ""
            TopAppBar(
                title = { Text("${names[pager.currentPage]}$size ${pager.currentPage + 1} / ${names.size}", style = MaterialTheme.typography.titleSmall, maxLines = 2) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back)) } },
                actions = {
                    IconButton(enabled = current != null && !saving, onClick = {
                        val c = current ?: return@IconButton
                        val name = names[pager.currentPage]
                        saving = true
                        scope.launch {
                            try {
                                val where = vm.savePhoto(camera, name, c.bytes)
                                Toast.makeText(context, resources.getString(R.string.saved_to, where), Toast.LENGTH_LONG).show()
                            } catch (e: ApiError) {
                                Toast.makeText(context, e.message, Toast.LENGTH_LONG).show()
                            } finally { saving = false }
                        }
                    }) { Icon(Icons.Filled.Share, contentDescription = stringResource(R.string.save_to_device)) }
                },
            )
        },
    ) { inner ->
        HorizontalPager(state = pager, modifier = Modifier.fillMaxSize().padding(inner).background(CamColors.video)) { page ->
            PhotoPage(vm, camera, names[page], onLoaded = { loadedPages[page] = it })
        }
    }
}

@Composable
private fun PhotoPage(vm: CamerasViewModel, camera: Int, name: String, onLoaded: (Loaded) -> Unit) {
    var loaded by remember(name) { mutableStateOf<Loaded?>(null) }
    var error by remember(name) { mutableStateOf("") }
    LaunchedEffect(name) {
        try {
            val bytes = vm.loadPhoto(camera, name)
            val bmp = withContext(Dispatchers.Default) { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }
            if (bmp == null) error = "写真を表示できませんでした。" else { loaded = Loaded(bytes, bmp); onLoaded(Loaded(bytes, bmp)) }
        } catch (e: ApiError) {
            error = e.message ?: ""
        }
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val l = loaded
        when {
            l != null -> Image(l.bitmap.asImageBitmap(), contentDescription = name, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            error.isNotEmpty() -> Column { Text(error, color = CamColors.err, modifier = Modifier.padding(16.dp)) }
            else -> CircularProgressIndicator()
        }
    }
}
