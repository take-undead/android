package app.thermalcam.ui

import android.graphics.Bitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.AlertDialog
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.border
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.thermalcam.R
import app.thermalcam.data.Photo
import app.thermalcam.data.PhotoStore
import app.thermalcam.ui.theme.ThermalColors
import app.thermalcam.vm.PhotosViewModel
import java.text.SimpleDateFormat
import java.util.Date


/** 上の行: 戻る・題名 */
@Composable
private fun TopRow(title: String, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = onBack) { Text(stringResource(R.string.back)) }
        Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** 写真を読んで出す（読み込みは裏で。CLAUDE.md D3） */
@Composable
private fun PhotoImage(photo: Photo, maxSize: Int, scale: ContentScale, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val bmp by produceState<Bitmap?>(null, photo.uri) { value = PhotoStore.load(context, photo.uri, maxSize) }
    Box(modifier.background(ThermalColors.viewBackground), contentAlignment = Alignment.Center) {
        bmp?.let {
            Image(it.asImageBitmap(), contentDescription = photo.name, contentScale = scale, modifier = Modifier.fillMaxSize())
        }
    }
}

/**
 * 写真の一覧（新しい順）。タップで大きく表示。
 * 長押しで選択を始め、選択中はタップで選ぶ / 外す。「削除」は確認してから消す
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PhotosScreen(vm: PhotosViewModel, onBack: () -> Unit, onOpen: (Int) -> Unit, modifier: Modifier = Modifier) {
    val photos by vm.photos.collectAsStateWithLifecycle()
    val selected by vm.selected.collectAsStateWithLifecycle()
    val result by vm.result.collectAsStateWithLifecycle()
    var confirm by rememberSaveable { mutableStateOf(false) }
    val snack = remember { SnackbarHostState() }
    LaunchedEffect(Unit) { vm.refresh() }

    val resultText = result?.let { r ->
        if (r.failed == 0) stringResource(R.string.deleted, r.deleted) else stringResource(R.string.delete_failed, r.failed)
    }
    LaunchedEffect(result) { if (resultText != null) { snack.showSnackbar(resultText); vm.resultShown() } }
    // 選択中に「戻る」を押したら、画面を閉じずに選択をやめる
    BackHandler(enabled = selected.isNotEmpty()) { vm.clearSelection() }

    Scaffold(modifier, snackbarHost = { SnackbarHost(snack) }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            if (selected.isEmpty()) {
                TopRow(stringResource(R.string.photos_title, photos?.size ?: 0), onBack)
            } else {
                Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = vm::clearSelection) { Text(stringResource(R.string.cancel)) }
                    Text(stringResource(R.string.select_count, selected.size), style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f))
                    TextButton(onClick = vm::selectAll) { Text(stringResource(R.string.select_all)) }
                    TextButton(onClick = { confirm = true }) { Text(stringResource(R.string.delete), color = ThermalColors.err) }
                }
            }
            val list = photos
            when {
                list == null -> Unit
                list.isEmpty() -> Text(stringResource(R.string.photos_empty), Modifier.padding(16.dp))
                else -> {
                    if (selected.isEmpty()) {
                        Text(stringResource(R.string.select_hint), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 12.dp))
                    }
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(110.dp),
                        contentPadding = PaddingValues(4.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        itemsIndexed(list, key = { _, p -> p.uri.toString() }) { i, p ->
                            val on = p.uri in selected
                            Box(
                                Modifier.aspectRatio(0.85f)
                                    .border(if (on) 3.dp else 0.dp, if (on) MaterialTheme.colorScheme.primary else Color.Transparent)
                                    .combinedClickable(
                                        onClick = { if (selected.isEmpty()) onOpen(i) else vm.toggle(p.uri) },
                                        onLongClick = { vm.toggle(p.uri) },
                                    ),
                            ) {
                                PhotoImage(p, 320, ContentScale.Crop, Modifier.fillMaxSize())
                                if (on) {
                                    Text("✓", color = Color.White, style = MaterialTheme.typography.titleMedium,
                                        modifier = Modifier.align(Alignment.TopEnd).padding(4.dp)
                                            .background(MaterialTheme.colorScheme.primary, CircleShape).padding(horizontal = 7.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text(stringResource(R.string.delete_confirm_title, selected.size)) },
            text = { Text(stringResource(R.string.delete_confirm_text)) },
            confirmButton = {
                TextButton(onClick = { confirm = false; vm.deleteSelected() }) { Text(stringResource(R.string.delete), color = ThermalColors.err) }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

/** 写真を大きく表示。左右にめくって前後の写真へ */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PhotoViewerScreen(vm: PhotosViewModel, start: Int, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val photos by vm.photos.collectAsStateWithLifecycle()
    val list = photos.orEmpty()
    val pager = rememberPagerState(initialPage = start.coerceIn(0, maxOf(0, list.size - 1))) { list.size }
    val locale = LocalConfiguration.current.locales[0]
    val fmt = remember(locale) { SimpleDateFormat("yyyy/MM/dd HH:mm:ss", locale) }
    Scaffold(modifier) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            val p = list.getOrNull(pager.currentPage)
            TopRow(
                if (p == null) "" else stringResource(R.string.photo_title, pager.currentPage + 1, list.size, fmt.format(Date(p.takenMs))),
                onBack,
            )
            HorizontalPager(pager, Modifier.fillMaxWidth().weight(1f), key = { list[it].uri.toString() }) { page ->
                PhotoImage(list[page], 2048, ContentScale.Fit, Modifier.fillMaxSize())
            }
        }
    }
}
