package app.thermalcam.ui

import android.Manifest
import androidx.compose.ui.viewinterop.AndroidView
import android.view.TextureView
import android.graphics.SurfaceTexture
import android.graphics.RectF
import android.graphics.Paint
import android.graphics.Matrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.ColorMatrix
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.thermalcam.R
import app.thermalcam.night.NightView
import app.thermalcam.ui.theme.ThermalColors
import app.thermalcam.vm.Denoise
import app.thermalcam.vm.NightLens
import app.thermalcam.vm.NightUi
import app.thermalcam.vm.NightViewModel
import app.thermalcam.vm.UiMessage
import kotlinx.coroutines.delay
import java.util.Locale

/**
 * 普通のカメラ（メイン・広角・前面）の画面。暗い所向けに露出を長く・感度を高くできる（赤外線の暗視ではない）。
 * 上 = 露出の表示 / 中 = 映像 / 下 = 道具（カメラ・ノイズ除去・表示）とつまみ（明るさ・暗部）、切り替えとシャッター
 */
@Composable
fun NightScreen(vm: NightViewModel, screenDegrees: Int, onThermal: () -> Unit, modifier: Modifier = Modifier) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snack = remember { SnackbarHostState() }
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    // カメラの許可（無ければここで求める）。画面が見えている間だけカメラを動かす
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> if (ok) vm.start() }
    LaunchedEffect(screenDegrees) { vm.setScreenRotation(screenDegrees) }
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_START -> {
                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) vm.start()
                    else permission.launch(Manifest.permission.CAMERA)
                }
                Lifecycle.Event.ON_STOP -> vm.stop()
                else -> Unit
            }
        }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs); vm.stop() }
    }

    var hint by remember { mutableStateOf(false) }
    val msgText = when (val m = ui.message) {
        is UiMessage.SavedImage -> stringResource(R.string.saved_image, m.where)
        UiMessage.SaveFailed -> stringResource(R.string.save_failed)
        UiMessage.CameraPermissionDenied -> stringResource(R.string.night_permission)
        is UiMessage.ShotFailed -> stringResource(R.string.shot_failed, m.reason)
        else -> null
    }
    LaunchedEffect(ui.message) {
        if (ui.message == UiMessage.CaptureHint) { vm.messageShown(); hint = true; delay(1500); hint = false }
        else if (msgText != null) { snack.showSnackbar(msgText); vm.messageShown() }
    }

    val flash = remember { Animatable(0f) }
    LaunchedEffect(ui.shots) { if (ui.shots > 0) { flash.snapTo(0.7f); flash.animateTo(0f, tween(350)) } }

    val imagePane: @Composable (Modifier) -> Unit = { m ->
        Box(m.background(ThermalColors.viewBackground), contentAlignment = Alignment.Center) {
            CameraPreview(ui, vm, screenDegrees, Modifier.fillMaxSize())
            val status = when {
                ui.failed != null -> stringResource(R.string.night_failed, ui.failed ?: "")
                !ui.previewing -> stringResource(R.string.night_starting)
                ui.saving && ui.maxQuality -> stringResource(R.string.night_saving_max)
                ui.saving -> stringResource(R.string.night_saving, ui.denoise.shots)
                else -> null
            }
            if (status != null) {
                Text(status, color = Color.White, style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(8.dp)
                        .background(ThermalColors.overlayBackground, RoundedCornerShape(50)).padding(horizontal = 14.dp, vertical = 6.dp))
            }
            if (flash.value > 0f) Box(Modifier.fillMaxSize().background(Color.White.copy(alpha = flash.value)))
        }
    }
    val bottom: @Composable () -> Unit = {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            NightTools(ui, vm)
            NightSliders(ui, vm)
            ModeBar(CameraMode.NIGHT, { if (it == CameraMode.THERMAL) onThermal() }, Modifier.padding(top = 4.dp))
            Column(Modifier.padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Shutter(enabled = ui.running && ui.previewing && !ui.saving, onCapture = vm::capture, onShortTap = vm::showCaptureHint)
                Text(stringResource(if (ui.saving) R.string.saving else if (hint) R.string.capture_hint else R.string.night_capture),
                    style = MaterialTheme.typography.labelSmall, color = if (hint) ThermalColors.accent else ThermalColors.subtle)
            }
        }
    }

    Scaffold(modifier, containerColor = ThermalColors.screen) { pad ->
        BoxWithConstraints(Modifier.fillMaxSize().padding(pad)) {
            // 映像はセンサーの向き（横長）で来る。縦持ちでは 3:4、横持ちでは 4:3 で出す
            val landscape = maxWidth > maxHeight
            val ratio = if (ui.previewWidth > 0) {
                if (landscape) ui.previewWidth.toFloat() / ui.previewHeight else ui.previewHeight.toFloat() / ui.previewWidth
            } else if (landscape) 4f / 3f else 3f / 4f
            if (maxWidth > maxHeight) {
                Row(Modifier.fillMaxSize()) {
                    imagePane(Modifier.fillMaxHeight().aspectRatio(ratio, matchHeightConstraintsFirst = true))
                    Column(Modifier.weight(1f).fillMaxHeight().padding(horizontal = 12.dp).verticalScroll(rememberScrollState())) {
                        NightHeader(ui)
                        bottom()
                    }
                }
            } else {
                Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                    NightHeader(ui, Modifier.widthIn(max = 600.dp).padding(horizontal = 12.dp, vertical = 6.dp))
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { imagePane(Modifier.aspectRatio(ratio)) }
                    Box(Modifier.widthIn(max = 600.dp).padding(horizontal = 8.dp)) { bottom() }
                }
            }
            SnackbarHost(snack, Modifier.align(Alignment.TopCenter).padding(top = 64.dp))
        }
    }
}

/**
 * カメラの映像（TextureView に直接流す）。カメラ側が縦持ちの向きに回して渡すので、横持ちのときだけ回し直す
 * （Android の Camera2Basic サンプルの configureTransform と同じ計算）。
 * 白黒・緑・暗部の持ち上げは、描画の段階で色の変換（ColorMatrix）をかける（アプリで 1 枚ずつ計算しない）
 */
@Composable
private fun CameraPreview(ui: NightUi, vm: NightViewModel, screenDegrees: Int, modifier: Modifier = Modifier) {
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            TextureView(ctx).apply {
                surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                    override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) = vm.setTexture(st)
                    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) = Unit
                    override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean { vm.setTexture(null); return true }
                    override fun onSurfaceTextureUpdated(st: SurfaceTexture) = Unit
                }
            }
        },
        update = { tv ->
            // 横持ちのときの回し直し
            val w = tv.width.toFloat(); val h = tv.height.toFloat()
            if (w > 0 && h > 0 && ui.previewWidth > 0) {
                val m = Matrix()
                val cx = w / 2; val cy = h / 2
                if (screenDegrees == 90 || screenDegrees == 270) {
                    val view = RectF(0f, 0f, w, h)
                    val buf = RectF(0f, 0f, ui.previewHeight.toFloat(), ui.previewWidth.toFloat())
                    buf.offset(cx - buf.centerX(), cy - buf.centerY())
                    m.setRectToRect(view, buf, Matrix.ScaleToFit.FILL)
                    val scale = maxOf(h / ui.previewHeight, w / ui.previewWidth)
                    m.postScale(scale, scale, cx, cy)
                    m.postRotate(if (screenDegrees == 90) -90f else 90f, cx, cy)
                } else if (screenDegrees == 180) {
                    m.postRotate(180f, cx, cy)
                }
                tv.setTransform(m)
            }
            tv.setLayerPaint(Paint().apply { colorFilter = ColorMatrixColorFilter(viewMatrix(ui.view, ui.lift)) })
        },
    )
}

/**
 * 表示の色の変換。暗部（lift）は明るさの倍率 1〜2.5 倍で近似する（写真はべき乗の曲線で補正するので少し違って見える）
 */
private fun viewMatrix(view: NightView, lift: Float): ColorMatrix {
    val g = 1f + 1.5f * lift.coerceIn(0f, 1f)
    val m = when (view) {
        NightView.COLOR -> floatArrayOf(g, 0f, 0f, 0f, 0f, 0f, g, 0f, 0f, 0f, 0f, 0f, g, 0f, 0f, 0f, 0f, 0f, 1f, 0f)
        NightView.MONO -> {
            val r = 0.299f * g; val gg = 0.587f * g; val b = 0.114f * g
            floatArrayOf(r, gg, b, 0f, 0f, r, gg, b, 0f, 0f, r, gg, b, 0f, 0f, 0f, 0f, 0f, 1f, 0f)
        }
        NightView.GREEN -> {
            val r = 0.299f * g; val gg = 0.587f * g; val b = 0.114f * g
            floatArrayOf(r * 0.15f, gg * 0.15f, b * 0.15f, 0f, 0f, r, gg, b, 0f, 0f, r * 0.3f, gg * 0.3f, b * 0.3f, 0f, 0f, 0f, 0f, 0f, 1f, 0f)
        }
    }
    return ColorMatrix(m)
}

/** 上の欄: 「暗所カメラ」と、いまの露出（シャッター速度・感度）。真っ暗では写らないことも書く */
@Composable
private fun NightHeader(ui: NightUi, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(stringResource(R.string.night_title), style = MaterialTheme.typography.titleLarge, color = Color.White)
            Spacer(Modifier.weight(1f))
            if (ui.actualExposureNs > 0) {
                val sec = ui.actualExposureNs / 1e9
                val speed = if (sec >= 0.3) String.format(Locale.US, "%.1f s", sec) else "1/${(1 / sec).toInt()} s"
                Text(stringResource(R.string.night_exposure, speed, ui.actualIso), style = MaterialTheme.typography.labelLarge, color = ThermalColors.subtle)
            }
        }
        Text(stringResource(R.string.night_note), style = MaterialTheme.typography.labelSmall, color = ThermalColors.subtle)
    }
}

/** 道具の列: カメラ（メイン / 広角）・ノイズ除去・表示（カラー / 白黒 / 緑） */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NightTools(ui: NightUi, vm: NightViewModel) {
    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
        ToolMenu(R.drawable.ic_lens, stringResource(lensLabel(ui.lens)),
            NightLens.entries.map { Triple(stringResource(lensLong(it)), it == ui.lens) { vm.setLens(it) } })
        ToolMenu(R.drawable.ic_tune, stringResource(denoiseLabel(ui.denoise)),
            Denoise.entries.map { Triple(stringResource(denoiseLong(it)), it == ui.denoise) { vm.setDenoise(it) } })
        ToolMenu(R.drawable.ic_palette, stringResource(viewLabel(ui.view)),
            NightView.entries.map { Triple(stringResource(viewLabel(it)), it == ui.view) { vm.setView(it) } })
        val lim = ui.limits
        ToolMenu(R.drawable.ic_quality, stringResource(if (ui.maxQuality) R.string.quality_max else R.string.quality_std),
            listOf(
                Triple(stringResource(R.string.quality_std_long), !ui.maxQuality) { vm.setMaxQuality(false) },
                Triple(
                    lim?.let { stringResource(R.string.quality_max_long, it.maxJpeg.width, it.maxJpeg.height, it.maxJpeg.width.toLong() * it.maxJpeg.height / 10_000) }
                        ?: stringResource(R.string.quality_max),
                    ui.maxQuality,
                ) { vm.setMaxQuality(true) },
            ),
            active = ui.maxQuality)
        Tool(R.drawable.ic_torch, stringResource(R.string.torch), { vm.setTorch(!ui.torch) },
            active = ui.torch, enabled = lim?.flash == true)
    }
}

/** つまみ: 明るさ（左端 = 自動）・暗部を明るく */
@Composable
private fun NightSliders(ui: NightUi, vm: NightViewModel) {
    Column(Modifier.fillMaxWidth().background(ThermalColors.panel, RoundedCornerShape(16.dp)).padding(horizontal = 12.dp, vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(if (ui.brightness <= 0.001f) R.string.night_bright_auto else R.string.night_bright),
                Modifier.width(88.dp), style = MaterialTheme.typography.bodySmall, maxLines = 1,
                color = if (ui.brightness <= 0.001f) ThermalColors.accent else ThermalColors.subtle)
            Slider(ui.brightness, vm::setBrightness, Modifier.weight(1f).height(30.dp))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.night_lift), Modifier.width(88.dp), style = MaterialTheme.typography.bodySmall, maxLines = 1, color = ThermalColors.subtle)
            Slider(ui.lift, vm::setLift, Modifier.weight(1f).height(30.dp))
        }
    }
}

private fun lensLabel(l: NightLens) = when (l) {
    NightLens.MAIN -> R.string.night_lens_main; NightLens.WIDE -> R.string.night_lens_wide; NightLens.FRONT -> R.string.night_lens_front
}
private fun lensLong(l: NightLens) = when (l) {
    NightLens.MAIN -> R.string.night_lens_main_long; NightLens.WIDE -> R.string.night_lens_wide_long
    NightLens.FRONT -> R.string.night_lens_front_long
}
private fun denoiseLabel(d: Denoise) = when (d) { Denoise.OFF -> R.string.night_dn_off; Denoise.LOW -> R.string.night_dn_low; Denoise.HIGH -> R.string.night_dn_high }
private fun denoiseLong(d: Denoise) = when (d) { Denoise.OFF -> R.string.night_dn_off_long; Denoise.LOW -> R.string.night_dn_low_long; Denoise.HIGH -> R.string.night_dn_high_long }
private fun viewLabel(v: NightView) = when (v) { NightView.COLOR -> R.string.night_view_color; NightView.MONO -> R.string.night_view_mono; NightView.GREEN -> R.string.night_view_green }
