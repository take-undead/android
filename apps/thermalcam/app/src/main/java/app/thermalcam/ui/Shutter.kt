package app.thermalcam.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.thermalcam.R
import app.thermalcam.ui.theme.ThermalColors
import kotlinx.coroutines.launch

/** シャッターを押し続ける時間（ms）。輪が一周したら撮る */
private const val HOLD_MS = 600

/** カメラの切り替え（カメラアプリの「写真 | 動画」と同じ作り）。選んでいる方が橙で、下に点 */
enum class CameraMode { THERMAL, NIGHT }

@Composable
fun ModeBar(mode: CameraMode, onSelect: (CameraMode) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(28.dp), verticalAlignment = Alignment.CenterVertically) {
        for ((m, label) in listOf(CameraMode.THERMAL to R.string.mode_thermal, CameraMode.NIGHT to R.string.mode_night)) {
            val on = m == mode
            Column(
                Modifier.clickable(enabled = !on, role = Role.Tab) { onSelect(m) }.padding(horizontal = 6.dp, vertical = 2.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(stringResource(label), style = MaterialTheme.typography.labelLarge,
                    color = if (on) ThermalColors.accent else ThermalColors.subtle)
                Spacer(Modifier.height(3.dp))
                Spacer(Modifier.size(5.dp).background(if (on) ThermalColors.accent else Color.Transparent, CircleShape))
            }
        }
    }
}

/**
 * シャッター: 白い輪と中の円。押している間、輪に沿ってアクセント色が一周し、一周したら撮る（HOLD_MS）。
 * 途中で離したら撮らずに「長押しです」と知らせる（触れただけで撮れてしまうため。利用者の要望）。撮ったら端末を短く震わせる
 */
@Composable
fun Shutter(enabled: Boolean, onCapture: () -> Unit, onShortTap: () -> Unit) {
    val progress = remember { Animatable(0f) }
    val pressed = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val label = stringResource(R.string.capture_long)
    val accent = ThermalColors.accent
    Canvas(
        Modifier.size(76.dp)
            .semantics { contentDescription = label; role = Role.Button }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                awaitEachGesture {
                    awaitFirstDown()
                    val anim = scope.launch {
                        pressed.animateTo(1f, tween(120))
                        progress.snapTo(0f)
                        progress.animateTo(1f, tween(HOLD_MS - 120, easing = LinearEasing))
                    }
                    val up = withTimeoutOrNull(HOLD_MS.toLong()) { waitForUpOrCancellation() }
                    if (up == null) {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onCapture()
                        waitForUpOrCancellation()
                    } else {
                        onShortTap()
                    }
                    anim.cancel()
                    scope.launch { progress.animateTo(0f, tween(200)) }
                    scope.launch { pressed.animateTo(0f, tween(150)) }
                }
            },
    ) {
        val r = size.minDimension / 2
        val ring = 4.dp.toPx()
        drawCircle(Color.White.copy(alpha = if (enabled) 1f else 0.4f), radius = r - ring / 2, style = Stroke(ring))
        val inner = (r - ring - 5.dp.toPx()) * (1f - 0.08f * pressed.value)
        drawCircle(if (enabled) Color.White else Color.White.copy(alpha = 0.3f), radius = inner)
        drawCircle(accent, radius = inner * 0.32f * pressed.value)
        if (progress.value > 0f) {
            drawArc(
                accent, startAngle = -90f, sweepAngle = 360f * progress.value, useCenter = false,
                topLeft = Offset(ring / 2, ring / 2), size = Size(size.width - ring, size.height - ring),
                style = Stroke(ring, cap = StrokeCap.Round),
            )
        }
    }
}

