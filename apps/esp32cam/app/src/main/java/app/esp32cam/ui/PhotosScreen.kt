package app.esp32cam.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.esp32cam.R
import app.esp32cam.vm.CamerasViewModel

/** 選んでいるカメラの SD カード内の写真一覧（新しい順）。開いたときに一覧を取りにいく */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class) // TopAppBar は material3 1.4 でも experimental
@Composable
fun PhotosScreen(vm: CamerasViewModel, onBack: () -> Unit, onOpen: (Int) -> Unit) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    LaunchedEffect(ui.selected) { if (ui.photosCamera != ui.selected) vm.refreshPhotos() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.photos_title, ui.selected + 1)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back)) } },
                actions = { IconButton(onClick = vm::refreshPhotos, enabled = !ui.busy) { Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.refresh)) } },
            )
        },
    ) { inner ->
        Column(Modifier.fillMaxSize().padding(inner)) {
            Text(ui.status, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
            if (ui.photos.isEmpty() && !ui.busy) Text(stringResource(R.string.no_photos), modifier = Modifier.padding(12.dp))
            LazyColumn(Modifier.fillMaxSize()) {
                itemsIndexed(ui.photos, key = { _, n -> n }) { i, name ->
                    Text(name, modifier = Modifier.fillMaxWidth().clickable { onOpen(i) }.padding(horizontal = 16.dp, vertical = 12.dp))
                    HorizontalDivider()
                }
            }
        }
    }
}
