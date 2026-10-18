package app.thermalcam.ui

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.thermalcam.R
import app.thermalcam.data.Settings
import app.thermalcam.lepton.CameraProblem
import app.thermalcam.lepton.Gain
import app.thermalcam.thermal.OverlayMode
import app.thermalcam.thermal.Palette
import app.thermalcam.ui.theme.ThermalColors
import app.thermalcam.vm.Phase
import app.thermalcam.vm.ThermalUi
import app.thermalcam.vm.ThermalViewModel
import app.thermalcam.vm.UiMessage
import app.thermalcam.vm.gainLabel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import app.thermalcam.thermal.Alignment as Align

/** シャッターを押し続ける時間（ms）。輪が一周したら撮る */
private const val HOLD_MS = 600

/**
 * カメラの画面。ふつうのカメラアプリの作法に合わせる:
 *  上 = 温度と色の帯 / 中 = 熱画像 / 下 = アイコンの道具の列と、写真・シャッター・温度校正の列。
 * 横画面は画像を高さいっぱいにし、右の欄に同じものを縦に並べる。
 */
@Composable
fun MainScreen(vm: ThermalViewModel, onOpenPhotos: () -> Unit, modifier: Modifier = Modifier) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val image by vm.image.collectAsStateWithLifecycle()
    val lastPhoto by vm.lastPhoto.collectAsStateWithLifecycle()
    val snack = remember { SnackbarHostState() }
    val context = LocalContext.current
    LaunchedEffect(Unit) { vm.refreshLastPhoto() } // 写真の画面で消したかもしれないので戻るたびに読み直す

    // 可視カメラの許可は「重ねる」を選んだときに求める（CLAUDE.md B1）
    var pendingOverlay by rememberSaveable { mutableStateOf<String?>(null) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { _ ->
        // 許可されなかったときは setOverlay が「許可が必要」の知らせを出す
        pendingOverlay?.let { vm.setOverlay(OverlayMode.valueOf(it)) }
        pendingOverlay = null
    }
    val chooseOverlay: (OverlayMode) -> Unit = { mode ->
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        if (mode == OverlayMode.OFF || granted) vm.setOverlay(mode)
        else { pendingOverlay = mode.name; permission.launch(Manifest.permission.CAMERA) }
    }
    // つまみは「調整」を押したときだけ出す（常に出すと不意に触れてずれるため）
    var adjusting by rememberSaveable { mutableStateOf(false) }
    var memory by rememberSaveable { mutableStateOf(false) }

    val msgText = when (val m = ui.message) {
        is UiMessage.Saved -> stringResource(R.string.saved, m.where)
        UiMessage.SaveFailed -> stringResource(R.string.save_failed)
        UiMessage.CameraPermissionDenied -> stringResource(R.string.camera_denied)
        UiMessage.VisibleFailed -> stringResource(R.string.visible_failed)
        is UiMessage.AlignFlat -> stringResource(R.string.align_flat, m.edge)
        is UiMessage.AlignWeak -> stringResource(R.string.align_weak, m.score)
        UiMessage.AlignNoVisible -> stringResource(R.string.align_no_visible)
        UiMessage.CaptureHint -> null // シャッターの下の文字で知らせる（下の LaunchedEffect）
        is UiMessage.PresetSaved -> stringResource(R.string.memory_saved, m.number)
        is UiMessage.PresetLoaded -> stringResource(R.string.memory_loaded, m.number)
        null -> null
    }
    // 短く触れたときの「長押しです」は、知らせの帯ではなくシャッターの下の文字を一瞬橙にして出す（帯がシャッターを隠すため）
    var shutterHint by remember { mutableStateOf(false) }
    LaunchedEffect(ui.message) {
        if (ui.message == UiMessage.CaptureHint) {
            vm.messageShown(); shutterHint = true; delay(1500); shutterHint = false
        } else if (msgText != null) {
            snack.showSnackbar(msgText); vm.messageShown()
        }
    }

    val tools: @Composable () -> Unit = {
        Tools(ui, vm, chooseOverlay, adjusting, onAdjust = { adjusting = !adjusting }, onMemory = { memory = true })
    }
    val shutterRow: @Composable () -> Unit = { ShutterRow(ui, vm, lastPhoto, shutterHint, onOpenPhotos) }

    // 知らせの帯は画面の上の方に出す（下に出すとシャッターや道具を隠して押せなくなる）
    Scaffold(modifier, containerColor = ThermalColors.screen) { pad ->
        BoxWithConstraints(Modifier.fillMaxSize().padding(pad)) {
            // 画像の縦横比（縦持ちなら 3:4、横持ちなら 4:3）
            val ratio = image?.let { it.width.toFloat() / it.height } ?: (3f / 4f)
            if (maxWidth > maxHeight) {
                Row(Modifier.fillMaxSize()) {
                    ImagePane(ui, image, vm, Modifier.fillMaxHeight().aspectRatio(ratio, matchHeightConstraintsFirst = true))
                    Column(Modifier.weight(1f).fillMaxHeight().padding(horizontal = 12.dp)) {
                        Column(
                            Modifier.weight(1f).verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            TopInfo(ui, Modifier.padding(top = 4.dp))
                            tools()
                            if (adjusting) AdjustPanel(ui, vm)
                        }
                        shutterRow()
                    }
                }
            } else {
                // 縦: 画像が残りの高さを全部使う（道具とシャッターは必要な高さだけ）。調整を開いている間は画像が少し小さくなる
                Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                    TopInfo(ui, Modifier.widthIn(max = 600.dp).padding(horizontal = 12.dp, vertical = 4.dp))
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        ImagePane(ui, image, vm, Modifier.aspectRatio(ratio))
                    }
                    Column(
                        Modifier.widthIn(max = 600.dp).fillMaxWidth().padding(horizontal = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        if (adjusting) AdjustPanel(ui, vm, Modifier.padding(top = 6.dp))
                        tools()
                    }
                    Box(Modifier.widthIn(max = 600.dp)) { shutterRow() }
                }
            }
            TopSnackbar(snack, Modifier.align(Alignment.TopCenter).padding(top = 96.dp))
        }
    }
    if (memory) MemoryDialog(ui, vm, onClose = { memory = false })
}

/** 画面の上の方に重ねる知らせの帯 */
@Composable
private fun TopSnackbar(snack: SnackbarHostState, modifier: Modifier = Modifier) {
    SnackbarHost(snack, modifier)
}

/** 熱画像。印だけ重ねる。下の端に状態（起動中・校正中など）の帯、撮影の瞬間は一瞬白く光らせる */
@Composable
private fun ImagePane(ui: ThermalUi, image: Bitmap?, vm: ThermalViewModel, modifier: Modifier = Modifier) {
    val flash = remember { Animatable(0f) }
    LaunchedEffect(ui.shots) {
        if (ui.shots > 0) { flash.snapTo(0.7f); flash.animateTo(0f, tween(350)) }
    }
    Box(modifier) {
        ThermalView(
            image = image, spot = ui.spot, max = ui.max, min = ui.min,
            onTap = vm::setSpot,
            modifier = Modifier.fillMaxSize(),
        )
        Status(ui, vm, Modifier.align(Alignment.BottomCenter).padding(8.dp))
        if (flash.value > 0f) Box(Modifier.fillMaxSize().background(Color.White.copy(alpha = flash.value)))
    }
}

/** 上の欄: 点の温度（大きく）と最高・最低、センサー温度、その下に横向きの色の帯 */
@Composable
private fun TopInfo(ui: ThermalUi, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(
                ui.spot?.let { stringResource(R.string.celsius, it.celsius) } ?: "—",
                style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.SemiBold, color = Color.White,
            )
            Column(Modifier.padding(bottom = 4.dp)) {
                if (ui.max != null) Text(stringResource(R.string.max_short, ui.max.celsius),
                    color = ThermalColors.maxMark, style = MaterialTheme.typography.titleSmall)
                if (ui.min != null) Text(stringResource(R.string.min_short, ui.min.celsius),
                    color = ThermalColors.minMark, style = MaterialTheme.typography.titleSmall)
            }
            Spacer(Modifier.weight(1f))
            ui.fpaC?.let {
                Text(stringResource(R.string.status_sensor, stringResource(R.string.celsius, it)),
                    style = MaterialTheme.typography.labelSmall, color = ThermalColors.subtle, modifier = Modifier.padding(bottom = 6.dp))
            }
        }
        ColorBar(ui, Modifier.fillMaxWidth())
    }
}

/** 状態（起動中・校正中・異常）を画像の下の端に小さな帯で出す。何も無いときは出さない */
@Composable
private fun Status(ui: ThermalUi, vm: ThermalViewModel, modifier: Modifier = Modifier) {
    val status: Pair<String, Color> = when {
        ui.phase == Phase.STARTING -> stringResource(R.string.status_starting) to Color.White
        ui.phase == Phase.PROBLEM -> problemText(ui.problem, ui.problemDetail) to ThermalColors.err
        ui.ffcRunning -> stringResource(R.string.status_ffc) to ThermalColors.warn
        ui.ffcDesired -> stringResource(R.string.status_ffc_desired) to ThermalColors.warn
        else -> null
    } ?: return
    Row(
        modifier.background(ThermalColors.overlayBackground, RoundedCornerShape(50)).padding(horizontal = 14.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(status.first, color = status.second, style = MaterialTheme.typography.bodySmall)
        if (ui.phase == Phase.PROBLEM) {
            Text(stringResource(R.string.retry), color = ThermalColors.accent, style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.clickable(onClick = vm::retry))
        }
    }
}

@Composable
private fun problemText(p: CameraProblem?, d: String) = when (p) {
    CameraProblem.NO_SERVICE -> stringResource(R.string.problem_no_service)
    CameraProblem.OPEN_FAILED -> stringResource(R.string.problem_open, d)
    CameraProblem.SETUP_FAILED -> stringResource(R.string.problem_setup, d)
    CameraProblem.NO_FRAMES, null -> stringResource(R.string.problem_no_frames)
    CameraProblem.IO_ERROR -> stringResource(R.string.problem_io, d)
}

/** 横向きの色の帯: 左が低温、右が高温。両端に温度、中央の温度は帯の上 */
@Composable
private fun ColorBar(ui: ThermalUi, modifier: Modifier = Modifier) {
    val lut = ui.settings.palette.lut
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.celsius, ui.lo), style = MaterialTheme.typography.labelMedium, color = ThermalColors.subtle)
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp))) {
                val n = size.width.toInt().coerceAtLeast(1)
                for (i in 0 until n) {
                    drawRect(Color(lut[i * 255 / maxOf(1, n - 1)]), topLeft = Offset(i.toFloat(), 0f), size = Size(1.5f, size.height))
                }
            }
            Text(stringResource(R.string.celsius, (ui.lo + ui.hi) / 2), style = MaterialTheme.typography.labelSmall, color = Color.White,
                modifier = Modifier.background(ThermalColors.overlayBackground, RoundedCornerShape(4.dp)).padding(horizontal = 4.dp))
        }
        Text(stringResource(R.string.celsius, ui.hi), style = MaterialTheme.typography.labelMedium, color = ThermalColors.subtle)
    }
}

/**
 * アイコンと短い名前の道具ボタン。名前には今の状態を出す（例: アイアン・〜140℃・白線）。
 * active のときはアクセント色（範囲を固定中・調整を開いている・重ねている など）
 */
@Composable
private fun Tool(icon: Int, label: String, onClick: () -> Unit, modifier: Modifier = Modifier, active: Boolean = false, enabled: Boolean = true) {
    val color = when {
        !enabled -> ThermalColors.subtle.copy(alpha = 0.5f)
        active -> ThermalColors.accent
        else -> Color.White
    }
    Column(
        modifier.widthIn(min = 52.dp).clip(RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Icon(painterResource(icon), contentDescription = null, tint = color, modifier = Modifier.size(24.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = color, maxLines = 1)
    }
}

/** 押すと選択肢が出る道具ボタン。今の選択には ✓ を付ける */
@Composable
private fun ToolMenu(icon: Int, label: String, items: List<Triple<String, Boolean, () -> Unit>>, active: Boolean = false) {
    var open by remember { mutableStateOf(false) }
    Box {
        Tool(icon, label, { open = true }, active = active)
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            items.forEach { (text, selected, action) ->
                DropdownMenuItem(
                    text = { Text(text, color = if (selected) ThermalColors.accent else Color.Unspecified) },
                    trailingIcon = { if (selected) Text("✓", color = ThermalColors.accent) },
                    onClick = { open = false; action() },
                )
            }
        }
    }
}

/** 道具の列: 色・範囲・測定・重ね・調整・記憶 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Tools(
    ui: ThermalUi, vm: ThermalViewModel, chooseOverlay: (OverlayMode) -> Unit,
    adjusting: Boolean, onAdjust: () -> Unit, onMemory: () -> Unit,
) {
    val s = ui.settings
    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        ToolMenu(R.drawable.ic_palette, stringResource(paletteLabel(s.palette)),
            Palette.entries.map { Triple(stringResource(paletteLabel(it)), it == s.palette) { vm.setPalette(it); Unit } })
        Tool(R.drawable.ic_range, stringResource(if (s.autoRange) R.string.tool_range_auto else R.string.tool_range_fixed),
            { vm.setAutoRange(!s.autoRange) }, active = !s.autoRange)
        ToolMenu(R.drawable.ic_gain, stringResource(gainShort(s.gain)),
            Gain.entries.map { Triple(stringResource(gainLabel(it)), it == s.gain) { vm.setGain(it); Unit } })
        ToolMenu(R.drawable.ic_layers, stringResource(overlayTool(s.overlay)),
            OverlayMode.entries.map { Triple(stringResource(overlayLabel(it)), it == s.overlay) { chooseOverlay(it) } },
            active = s.overlay != OverlayMode.OFF)
        Tool(R.drawable.ic_tune, stringResource(R.string.adjust), onAdjust, active = adjusting)
        Tool(R.drawable.ic_memory, stringResource(R.string.memory), onMemory)
    }
}

/** 下の列: 左 = 最後の写真（押すと一覧）、中 = シャッター、右 = 温度校正 */
@Composable
private fun ShutterRow(ui: ThermalUi, vm: ThermalViewModel, lastPhoto: Bitmap?, hint: Boolean, onOpenPhotos: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        val photosLabel = stringResource(R.string.photos)
        Box(
            Modifier.size(52.dp).clip(CircleShape).background(ThermalColors.panel)
                .border(2.dp, Color.White.copy(alpha = 0.8f), CircleShape)
                .clickable(role = Role.Button, onClick = onOpenPhotos)
                .semantics { contentDescription = photosLabel },
            contentAlignment = Alignment.Center,
        ) {
            if (lastPhoto != null) {
                Image(lastPhoto.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            } else {
                Icon(painterResource(R.drawable.ic_photos), contentDescription = null, tint = Color.White, modifier = Modifier.size(24.dp))
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Shutter(enabled = ui.phase == Phase.RUNNING && !ui.saving, onCapture = vm::capture, onShortTap = vm::showCaptureHint)
            Text(stringResource(if (ui.saving) R.string.saving else if (hint) R.string.capture_hint else R.string.capture_long),
                style = MaterialTheme.typography.labelSmall, color = if (hint) ThermalColors.accent else ThermalColors.subtle)
        }
        Tool(R.drawable.ic_calibrate, stringResource(R.string.ffc), vm::runFfc, enabled = ui.phase == Phase.RUNNING)
    }
}

/**
 * シャッター: 白い輪と中の円。押している間、輪に沿ってアクセント色が一周し、一周したら撮る（HOLD_MS）。
 * 途中で離したら撮らずに「長押しです」と知らせる（触れただけで撮れてしまうため。利用者の要望）。撮ったら端末を短く震わせる
 */
@Composable
private fun Shutter(enabled: Boolean, onCapture: () -> Unit, onShortTap: () -> Unit) {
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

/** 調整の板: 表示範囲・輪郭の強さ・位置合わせ（自動の提案を含む） */
@Composable
private fun AdjustPanel(ui: ThermalUi, vm: ThermalViewModel, modifier: Modifier = Modifier) {
    val s = ui.settings
    Column(
        modifier.fillMaxWidth().background(ThermalColors.panel, RoundedCornerShape(16.dp)).padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        val limit = if (s.gain == Gain.HIGH) -10f..140f else -10f..450f
        SliderRow(R.string.range_label) {
            RangeSlider(
                value = ui.lo.coerceIn(limit)..ui.hi.coerceIn(limit),
                onValueChange = { r -> if (r.endInclusive - r.start >= 0.5f) vm.setManualRangeLive(r.start, r.endInclusive) },
                onValueChangeFinished = { vm.commitManualRange() },
                valueRange = limit,
                modifier = Modifier.fillMaxWidth().height(30.dp),
            )
        }
        if (s.overlay == OverlayMode.EDGES || s.overlay == OverlayMode.LINES) {
            SliderRow(R.string.edge_level) {
                Slider(
                    value = ui.edgeShown ?: s.edgeLevel, onValueChange = vm::setEdgeLevelLive,
                    onValueChangeFinished = { vm.commitEdgeLevel() }, modifier = Modifier.fillMaxWidth().height(30.dp),
                )
            }
        }
        if (s.overlay != OverlayMode.OFF) {
            AlignSliders(ui.alignShown ?: s.align, vm)
            val score = ui.proposalScore
            if (score != null) {
                // 自動の提案を見せている間: 輪郭を見て、採用するかやめるかを選ぶ
                Text(stringResource(R.string.proposal, score), style = MaterialTheme.typography.bodySmall, color = ThermalColors.warn)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PanelButton(stringResource(R.string.proposal_accept), { vm.acceptProposal() })
                    PanelButton(stringResource(R.string.proposal_reject), vm::rejectProposal)
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PanelButton(stringResource(if (ui.autoAligning) R.string.auto_align_busy else R.string.auto_align), vm::autoAlign,
                        enabled = ui.phase == Phase.RUNNING && ui.visibleActive && !ui.autoAligning)
                    PanelButton(stringResource(R.string.align_reset), { vm.resetAlign() })
                }
            }
        }
    }
}

/** 調整の板や記憶の中で使う小さなボタン */
@Composable
private fun PanelButton(text: String, onClick: () -> Unit, enabled: Boolean = true) {
    FilledTonalButton(
        onClick = onClick, enabled = enabled,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
        modifier = Modifier.height(32.dp),
    ) { Text(text, style = MaterialTheme.typography.labelMedium, maxLines = 1) }
}

/** 記憶 1〜5: 呼び出す・今の設定を記憶する */
@Composable
private fun MemoryDialog(ui: ThermalUi, vm: ThermalViewModel, onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(stringResource(R.string.memory_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.memory_hint), style = MaterialTheme.typography.bodySmall)
                ui.presets.forEachIndexed { i, p ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("${i + 1}", style = MaterialTheme.typography.titleMedium, modifier = Modifier.width(16.dp))
                        Text(p?.let { presetSummary(it) } ?: stringResource(R.string.memory_empty),
                            style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                        PanelButton(stringResource(R.string.memory_load), { vm.loadPreset(i); onClose() }, enabled = p != null)
                        PanelButton(stringResource(if (p == null) R.string.memory_save else R.string.memory_overwrite), { vm.savePreset(i); onClose() })
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text(stringResource(R.string.close)) } },
    )
}

/** 記憶の中身の短い説明: 色 / 測定範囲 / 重ね / 表示範囲 */
@Composable
private fun presetSummary(p: Settings): String = listOf(
    stringResource(paletteLabel(p.palette)),
    stringResource(gainShort(p.gain)),
    stringResource(overlayShort(p.overlay)),
    if (p.autoRange) stringResource(R.string.range_auto) else stringResource(R.string.range_summary_fixed, p.rangeLo, p.rangeHi),
).joinToString(" / ")

/** 左に短い名前、右につまみ */
@Composable
private fun SliderRow(label: Int, slider: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(label), Modifier.width(44.dp), style = MaterialTheme.typography.bodySmall, color = ThermalColors.subtle)
        Box(Modifier.weight(1f)) { slider() }
    }
}

/** 位置合わせのつまみ（輪郭が熱画像と重なるように合わせる。近くの物ほどずれやすい） */
@Composable
private fun AlignSliders(a: Align, vm: ThermalViewModel) {
    @Composable
    fun row(label: Int, value: Float, range: ClosedFloatingPointRange<Float>, set: (Float) -> Align) {
        SliderRow(label) {
            Slider(value = value, onValueChange = { vm.setAlignLive(set(it)) }, onValueChangeFinished = { vm.commitAlign() },
                valueRange = range, modifier = Modifier.fillMaxWidth().height(30.dp))
        }
    }
    row(R.string.align_scale, a.scale, 0.3f..1.2f) { a.copy(scale = it) }
    row(R.string.align_x, a.offsetX, -0.4f..0.4f) { a.copy(offsetX = it) }
    row(R.string.align_y, a.offsetY, -0.4f..0.4f) { a.copy(offsetY = it) }
}

private fun paletteLabel(p: Palette) = when (p) {
    Palette.IRON -> R.string.palette_iron
    Palette.RAINBOW -> R.string.palette_rainbow
    Palette.WHITE_HOT -> R.string.palette_white_hot
    Palette.BLACK_HOT -> R.string.palette_black_hot
}

private fun gainShort(g: Gain) = when (g) {
    Gain.HIGH -> R.string.gain_short_high
    Gain.LOW -> R.string.gain_short_low
    Gain.AUTO -> R.string.gain_short_auto
}

private fun overlayLabel(o: OverlayMode) = when (o) {
    OverlayMode.OFF -> R.string.overlay_off
    OverlayMode.EDGES -> R.string.overlay_edges
    OverlayMode.LINES -> R.string.overlay_lines
    OverlayMode.BLEND -> R.string.overlay_blend
}

private fun overlayShort(o: OverlayMode) = when (o) {
    OverlayMode.OFF -> R.string.overlay_short_off
    OverlayMode.EDGES -> R.string.overlay_short_edges
    OverlayMode.LINES -> R.string.overlay_short_lines
    OverlayMode.BLEND -> R.string.overlay_short_blend
}

private fun overlayTool(o: OverlayMode) = when (o) {
    OverlayMode.OFF -> R.string.tool_overlay_off
    OverlayMode.EDGES -> R.string.tool_overlay_edges
    OverlayMode.LINES -> R.string.tool_overlay_lines
    OverlayMode.BLEND -> R.string.tool_overlay_blend
}
