package app.mt5mon.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.mt5mon.R
import app.mt5mon.ui.theme.Mt5Colors
import app.mt5mon.vm.ChatViewModel

/** Markdown の ** と ` と行頭の # を消す（PicoCalc と同じ） */
private fun plain(text: String): String = text.lines().joinToString("\n") { it.replace("**", "").replace("`", "").trimStart('#', ' ') }

@Composable
fun ChatScreen(vm: ChatViewModel = viewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    var input by rememberSaveable { mutableStateOf("") }
    var askNew by rememberSaveable { mutableStateOf(false) }
    val listState = rememberLazyListState()

    // 送れなかった文章を入力欄に戻す
    LaunchedEffect(ui.restoreInput) {
        if (ui.restoreInput.isNotEmpty() && input.isEmpty()) {
            input = ui.restoreInput
            vm.consumeRestore()
        }
    }
    // 新しい発言が来たら一番下へ
    LaunchedEffect(ui.msgs.size, ui.busy) {
        val last = ui.msgs.size - 1 + (if (ui.busy) 1 else 0)
        if (last >= 0) listState.animateScrollToItem(last)
    }

    Column(Modifier.fillMaxSize().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.chat_title), color = Mt5Colors.sym, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(8.dp))
            val (stText, stColor) = when {
                ui.fetchErr.isNotEmpty() -> stringResource(R.string.chat_state_err, ui.fetchErr) to Mt5Colors.err
                ui.busy -> stringResource(R.string.chat_state_busy, ui.busySec) to Mt5Colors.me
                else -> stringResource(R.string.chat_state_idle) to Mt5Colors.ok
            }
            Text(stText, color = stColor, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { askNew = true }) { Text(stringResource(R.string.chat_new)) }
        }
        HorizontalDivider(color = Mt5Colors.frame)
        SelectionContainer(Modifier.weight(1f)) {
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
                items(ui.msgs, key = { it.num }) { m ->
                    val me = m.who == "me"
                    val err = m.who == "err"
                    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                        Text(
                            "${m.t}  " + when { me -> stringResource(R.string.chat_me); err -> stringResource(R.string.chat_note); else -> stringResource(R.string.chat_ai) },
                            color = if (me) Mt5Colors.me else if (err) Mt5Colors.warn else Mt5Colors.sym,
                            style = MaterialTheme.typography.labelMedium,
                        )
                        Text(plain(m.text), color = if (me) Mt5Colors.me else if (err) Mt5Colors.warn else Mt5Colors.text)
                    }
                }
                if (ui.busy) item { Text(stringResource(R.string.chat_thinking, ui.busySec), color = Mt5Colors.dim, modifier = Modifier.padding(vertical = 6.dp)) }
            }
        }
        if (ui.note.isNotEmpty()) Text(ui.note, color = when (ui.noteOk) { true -> Mt5Colors.ok; false -> Mt5Colors.err; null -> Mt5Colors.dim }, modifier = Modifier.padding(horizontal = 12.dp), style = MaterialTheme.typography.bodySmall)
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.Bottom) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text(stringResource(R.string.chat_hint)) },
                maxLines = 5,
                enabled = !ui.sending,
            )
            IconButton(onClick = { vm.send(input); input = "" }, enabled = input.isNotBlank() && !ui.sending && !ui.busy) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = stringResource(R.string.send))
            }
        }
    }
    if (askNew) AlertDialog(
        onDismissRequest = { askNew = false },
        title = { Text(stringResource(R.string.chat_new)) },
        text = { Text(stringResource(R.string.chat_new_confirm)) },
        confirmButton = { TextButton(onClick = { askNew = false; vm.newSession() }) { Text(stringResource(R.string.ok)) } },
        dismissButton = { TextButton(onClick = { askNew = false }) { Text(stringResource(R.string.cancel)) } },
    )
}
