package app.mt5mon.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.mt5mon.R
import app.mt5mon.data.Notice
import app.mt5mon.ui.theme.Mt5Colors
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

/** 本文は最大幅を決めて中央に置く（CLAUDE.md 事前の注意事項 #5）。タブレットで 1 行が長くなりすぎない */
@Composable
fun ContentColumn(modifier: Modifier = Modifier, maxWidth: Int = 720, content: @Composable () -> Unit) {
    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Column(Modifier.widthIn(max = maxWidth.dp).fillMaxWidth()) { content() }
    }
}

/** 円（3 桁ごとにカンマ）。sign なら + も付ける */
fun fmtMoney(v: Double, sign: Boolean = false): String {
    val n = v.roundToLong()
    val s = String.format(Locale.US, "%,d", abs(n))
    return when {
        n < 0 -> "-$s"
        sign && n > 0 -> "+$s"
        else -> s
    }
}

fun fmtPrice(v: Double, digits: Int): String = String.format(Locale.US, "%.${digits}f", v)

/** "YYYY-MM-DD HH:MM:SS" の時刻部分だけ */
fun timePart(s: String): String = if (s.length >= 19) s.substring(11) else s

/** "YYYY-MM-DD HH:MM" の "MM-DD HH:MM" */
fun shortTime(s: String): String = if (s.length >= 16) s.substring(5) else s

/** VPS からの知らせ（スプレッド警報 → Claude 監督の調査結果）のポップアップ */
@Composable
fun NoticeDialog(n: Notice, secondsLeft: Int, onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        containerColor = Mt5Colors.noticeBg,
        title = { Text(stringResource(if (n.move) R.string.notice_title_move else R.string.notice_title), color = Mt5Colors.sym) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                // 2 行目: スプレッド警報は「値(≧しきい値)」、値動き警報は「向き 幅p/秒(≧しきい値)」（picoclac の notice.c と同じ）
                val value = if (n.move) {
                    val arrow = if (n.moveDir > 0) "↑" else if (n.moveDir < 0) "↓" else ""
                    stringResource(R.string.notice_value_move, arrow, n.movePips, n.moveSec, n.thr)
                } else stringResource(R.string.notice_value_spread, n.spread, n.thr)
                val result = if (n.seqAfter != n.seqBefore) stringResource(R.string.notice_result_changed, n.seqAfter) else stringResource(R.string.notice_result_same)
                Text(
                    stringResource(R.string.notice_head, shortTime(n.alertJst), value, timePart(n.doneJst), result),
                    color = Mt5Colors.head, style = MaterialTheme.typography.bodySmall,
                )
                Text(n.text, color = Mt5Colors.text, modifier = Modifier.padding(top = 8.dp))
                Text(
                    stringResource(R.string.notice_done, timePart(n.doneJst)),
                    color = Mt5Colors.dim, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp),
                )
                if (secondsLeft >= 0) Text(stringResource(R.string.notice_auto_close, secondsLeft), color = Mt5Colors.dim, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text(stringResource(R.string.close)) } },
    )
}
