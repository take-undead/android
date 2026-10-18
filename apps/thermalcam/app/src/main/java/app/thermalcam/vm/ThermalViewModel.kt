package app.thermalcam.vm

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.core.content.ContextCompat
import androidx.core.graphics.scale
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.thermalcam.R
import app.thermalcam.data.PhotoSaver
import app.thermalcam.data.PhotoStore
import app.thermalcam.data.Settings
import app.thermalcam.data.SettingsStore
import app.thermalcam.data.Smooth
import app.thermalcam.lepton.CameraCommand
import app.thermalcam.lepton.CameraEvent
import app.thermalcam.lepton.CameraProblem
import app.thermalcam.lepton.Gain
import app.thermalcam.lepton.LeptonCamera
import app.thermalcam.thermal.Alignment
import app.thermalcam.thermal.AutoAlign
import app.thermalcam.thermal.Luma
import app.thermalcam.thermal.Marker
import app.thermalcam.thermal.Orientation
import app.thermalcam.thermal.OverlayMode
import app.thermalcam.data.BaseCamera
import app.thermalcam.thermal.Palette
import app.thermalcam.thermal.PhotoComposer
import app.thermalcam.thermal.Renderer
import app.thermalcam.thermal.Stats
import app.thermalcam.thermal.ThermalFrame
import app.thermalcam.visible.VisibleCamera
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class Phase { STOPPED, STARTING, RUNNING, PROBLEM }


/** 1 回だけ見せる知らせ。文言は画面側で strings.xml から引く */
sealed interface UiMessage {
    data class Saved(val where: String) : UiMessage
    data class SavedImage(val where: String) : UiMessage   // 暗所カメラの写真（温度の CSV は無い）
    data object SaveFailed : UiMessage
    data object CameraPermissionDenied : UiMessage
    data object VisibleFailed : UiMessage
    data class AlignFlat(val edge: Float) : UiMessage
    data class AlignWeak(val score: Float) : UiMessage
    data object AlignNoVisible : UiMessage
    data object CaptureHint : UiMessage
    data class ShotFailed(val reason: String) : UiMessage
    data class PresetSaved(val number: Int) : UiMessage
    data class PresetLoaded(val number: Int) : UiMessage
}

data class ThermalUi(
    val phase: Phase = Phase.STOPPED,
    val problem: CameraProblem? = null,
    val problemDetail: String = "",
    val settings: Settings = Settings(),
    val lo: Float = 20f,
    val hi: Float = 40f,
    val spot: Marker? = null,
    val max: Marker? = null,
    val min: Marker? = null,
    val fpaC: Float? = null,
    val ffcRunning: Boolean = false,
    val ffcDesired: Boolean = false,
    val alignShown: Alignment? = null,
    val edgeShown: Float? = null,        // 輪郭の強さを動かしている途中の値（つまみの表示用）   // 位置合わせを動かしている途中の値（スライダーの表示用）
    val visibleActive: Boolean = false,
    val saving: Boolean = false,
    val shots: Int = 0,                  // 撮影した回数（画面を一瞬光らせる合図）
    val autoAligning: Boolean = false,
    val proposalScore: Float? = null,    // 自動位置合わせの提案を見せている間の一致度（採用かやめるかを待つ）
    val presets: List<Settings?> = List(SettingsStore.PRESET_COUNT) { null },
    val sensorSpot: Float? = null,       // Lepton 本体のスポットメーター（点の周り 3×3 画素）の温度
    val emissivityActual: Float? = null, // Lepton 本体から読み戻した放射率
    val message: UiMessage? = null,
)

class ThermalViewModel(app: Application) : AndroidViewModel(app) {
    companion object {
        /**
         * 熱センサーの並び → 画面の向き（縦持ち）。実機で確かめた値（作業記録を参照）。
         * 写真の向きや重ね合わせの計算はすべてこれを通す。
         */
        val THERMAL_ORIENTATION = Orientation(rotation = 270)
        // 可視カメラの番号は設定の BaseCamera（SUB = 2: LeptonService.getVisCamId() がこの端末で返す値、MAIN = 0）
        private const val AUTO_SMOOTH = 0.25f    // 自動範囲の追従の速さ（毎フレームの揺れで色がちらつかないように）
        private const val AUTO_MIN_SPAN = 2f     // 自動範囲の最小の幅（℃）。これより狭いと雑音が色の模様に見える
    }

    private val store = SettingsStore(app)
    private val _ui = MutableStateFlow(ThermalUi())
    val ui: StateFlow<ThermalUi> = _ui

    /** 画像は別に持つ（毎フレームの更新で画面全体の状態を作り直さないため） */
    private val _image = MutableStateFlow<Bitmap?>(null)
    val image: StateFlow<Bitmap?> = _image

    /** 最後に撮った写真の小さい絵（シャッターの左の丸いボタン）。無ければ null */
    private val _lastPhoto = MutableStateFlow<Bitmap?>(null)
    val lastPhoto: StateFlow<Bitmap?> = _lastPhoto

    /** 保存してある写真の一番新しいものを読み直す（起動時・写真の画面から戻ったとき。削除されていることがある） */
    fun refreshLastPhoto() = viewModelScope.launch {
        val app = getApplication<Application>()
        val p = runCatching { PhotoStore.list(app).firstOrNull() }.getOrNull()
        _lastPhoto.value = p?.let { PhotoStore.load(app, it.uri, 160) }
    }

    private val commands = Channel<CameraCommand>(Channel.UNLIMITED)
    private var cameraJob: Job? = null
    private var visibleJob: Job? = null
    @Volatile private var luma: Luma? = null
    @Volatile private var lastFrame: ThermalFrame? = null
    private var spotPos = 0.5f to 0.5f       // 点の位置（縦持ちの枠での 0〜1。既定は中央）

    /**
     * 縦持ちの枠 → 今の画面の向き。カメラアプリと同じ考え方で、画面の回転 d 度に対して 360 − d 度回して出す。
     * 描画・点・印・位置合わせはすべて縦持ちの枠で計算し、最後にこれで回す（横にしても映像が傾かない）。
     */
    @Volatile private var screen = Orientation(0)

    fun setScreenRotation(degrees: Int) {
        screen = Orientation((360 - degrees) % 360)
    }
    private var autoLo = Float.NaN
    private var autoHi = Float.NaN
    // 指で動かしている間の値（保存は指を離したとき。DataStore に毎回書かないため）
    @Volatile private var alignLive: Alignment? = null
    @Volatile private var rangeLive: Pair<Float, Float>? = null
    @Volatile private var edgeLive: Float? = null

    init {
        viewModelScope.launch {
            store.settings.collect { s ->
                val before = _ui.value.settings
                _ui.update { it.copy(settings = s) }
                if (before.baseCamera != s.baseCamera) {
                    // 土台のカメラが変わったら可視カメラを開き直す（前のが閉じるのを CameraGate が待つ）
                    visibleJob?.cancel(); visibleJob = null; luma = null
                    alignLive = null; _ui.update { it.copy(alignShown = null, proposalScore = null) }
                }
                if (before.overlay != s.overlay || before.baseCamera != s.baseCamera) updateVisible()
            }
        }
        viewModelScope.launch { store.presets.collect { p -> _ui.update { it.copy(presets = p) } } }
    }

    // ---- カメラの開始・停止（画面が見えている間だけ動かす。電池と、他のアプリがカメラを使えるように）----

    /** 暗所カメラを使っている間は熱カメラを止めておく（画面が裏から戻っても動かさない） */
    private var paused = false

    fun setPaused(p: Boolean) {
        paused = p
        if (p) stop() else start()
    }

    fun start() {
        if (paused || cameraJob?.isActive == true) return
        cameraJob = viewModelScope.launch(Dispatchers.Default) {
            // 保存した設定を読み終わってから開く（読む前に開くと、測定範囲などが既定値のままカメラに入る）
            val s0 = store.settings.first()
            smoothed = null
            LeptonCamera.session(getApplication<Application>().packageName, s0.gain, s0.emissivity, commands, ::spotRoi).collect { e ->
                when (e) {
                    CameraEvent.Starting -> _ui.update { it.copy(phase = Phase.STARTING, problem = null) }
                    is CameraEvent.Running -> _ui.update { it.copy(phase = Phase.RUNNING, problem = null) }
                    is CameraEvent.Problem -> _ui.update { it.copy(phase = Phase.PROBLEM, problem = e.problem, problemDetail = e.detail) }
                    is CameraEvent.Frame -> onFrame(e.frame)
                    is CameraEvent.SpotMeter -> _ui.update { it.copy(sensorSpot = e.celsius) }
                    is CameraEvent.Emissivity -> _ui.update { it.copy(emissivityActual = e.emissivity) }
                }
            }
        }
        updateVisible()
    }

    fun stop() {
        cameraJob?.cancel(); cameraJob = null
        visibleJob?.cancel(); visibleJob = null
        luma = null
        _ui.update { it.copy(phase = Phase.STOPPED, visibleActive = false) }
    }

    fun retry() { stop(); start() }

    /** ざらつき減らし: 前までの平均（℃）。測定範囲を変えたとき・止めたときは捨てる */
    @Volatile private var smoothed: FloatArray? = null

    private fun onFrame(raw: ThermalFrame) {
        val s = _ui.value.settings
        val f = if (s.smooth == Smooth.OFF) raw.also { smoothed = null } else {
            val prev = smoothed
            val avg = if (prev == null || prev.size != raw.celsius.size) raw.celsius.copyOf()
            else FloatArray(prev.size) { i -> prev[i] + (raw.celsius[i] - prev[i]) * s.smooth.weight }
            smoothed = avg
            ThermalFrame(avg, raw.frameCounter, raw.fpaC, raw.housingC, raw.statusBits)
        }
        lastFrame = f
        val st = Renderer.stats(f)
        val auto = autoRange(st) // 固定中も追い続ける（自動に戻したときに飛ばないように）
        val (lo, hi) = rangeLive ?: if (s.autoRange) auto else (s.rangeLo to s.rangeHi)
        val l = if (s.overlay != OverlayMode.OFF) luma else null
        val a = alignLive ?: s.activeAlign
        // カラー土台のときは画面の枠が可視画像なので、印の座標を位置合わせで熱画像 → 可視画像に写す
        colorBase = s.overlay == OverlayMode.COLOR && l != null
        lastAlign = a
        val portrait = Renderer.render(f, lo, hi, s.palette, THERMAL_ORIENTATION, l, s.overlay, a, edgeLive ?: s.edgeLevel)
        _image.value = toScreen(portrait)
        _ui.update {
            it.copy(
                phase = Phase.RUNNING, problem = null,
                lo = lo, hi = hi,
                spot = spotMarker(f),
                max = marker(st.maxX, st.maxY, st.max),
                min = marker(st.minX, st.minY, st.min),
                fpaC = f.fpaC, ffcRunning = f.ffcRunning, ffcDesired = f.ffcDesired,
            )
        }
    }

    private fun autoRange(st: Stats): Pair<Float, Float> {
        if (autoLo.isNaN()) { autoLo = st.min; autoHi = st.max }
        autoLo += (st.min - autoLo) * AUTO_SMOOTH
        autoHi += (st.max - autoHi) * AUTO_SMOOTH
        var lo = autoLo; var hi = autoHi
        if (hi - lo < AUTO_MIN_SPAN) { val c = (hi + lo) / 2; lo = c - AUTO_MIN_SPAN / 2; hi = c + AUTO_MIN_SPAN / 2 }
        return lo to hi
    }

    private val tmp = FloatArray(2)

    /** 今の画面の枠が可視画像（カラー土台）か。印と点の座標の変換に使う */
    @Volatile private var colorBase = false
    @Volatile private var lastAlign = Alignment()

    /** 熱画像の縦持ちの座標 → 画面の枠の座標（カラー土台なら位置合わせで可視画像の座標に） */
    private fun frameToImage(dx: Float, dy: Float, out: FloatArray) {
        if (!colorBase) { out[0] = dx; out[1] = dy; return }
        val a = lastAlign
        out[0] = 0.5f + (dx - 0.5f) * a.scale + a.offsetX
        out[1] = 0.5f + (dy - 0.5f) * a.scale + a.offsetY
    }

    /** 画面の枠の座標 → 熱画像の縦持ちの座標（frameToImage の逆） */
    private fun imageToFrame(ix: Float, iy: Float, out: FloatArray) {
        if (!colorBase) { out[0] = ix; out[1] = iy; return }
        val a = lastAlign
        out[0] = 0.5f + ((ix - 0.5f) - a.offsetX) / a.scale
        out[1] = 0.5f + ((iy - 0.5f) - a.offsetY) / a.scale
    }

    private fun toScreen(b: Bitmap): Bitmap {
        val r = screen.rotation
        if (r == 0) return b
        return Bitmap.createBitmap(b, 0, 0, b.width, b.height, Matrix().apply { postRotate(r.toFloat()) }, false)
    }

    /** 熱画像の縦持ちの座標 → 画面の座標 */
    private fun screenMarker(px: Float, py: Float, c: Float): Marker {
        frameToImage(px, py, tmp)
        screen.toDisplay(tmp[0], tmp[1], tmp)
        return Marker(tmp[0], tmp[1], c)
    }

    private fun marker(nx: Int, ny: Int, c: Float): Marker {
        THERMAL_ORIENTATION.toDisplay((nx + 0.5f) / ThermalFrame.WIDTH, (ny + 0.5f) / ThermalFrame.HEIGHT, tmp)
        return screenMarker(tmp[0], tmp[1], c)
    }

    private fun nativePixel(dx: Float, dy: Float): Pair<Int, Int> {
        THERMAL_ORIENTATION.toNative(dx, dy, tmp)
        return (tmp[0] * ThermalFrame.WIDTH).toInt().coerceIn(0, ThermalFrame.WIDTH - 1) to
            (tmp[1] * ThermalFrame.HEIGHT).toInt().coerceIn(0, ThermalFrame.HEIGHT - 1)
    }

    private fun spotMarker(f: ThermalFrame): Marker {
        val (x, y) = nativePixel(spotPos.first, spotPos.second)
        return screenMarker(spotPos.first, spotPos.second, Renderer.spot(f, x, y))
    }

    // ---- 操作 ----

    /** 画面をタップした位置に点を動かす（画面の 0〜1） */
    fun setSpot(x: Float, y: Float) {
        screen.toNative(x.coerceIn(0f, 1f), y.coerceIn(0f, 1f), tmp)
        imageToFrame(tmp[0], tmp[1], tmp) // カラー土台で熱画像の外をタップしたら、一番近い端に寄せる
        spotPos = tmp[0].coerceIn(0f, 0.999f) to tmp[1].coerceIn(0f, 0.999f)
        lastFrame?.let { f -> _ui.update { it.copy(spot = spotMarker(f)) } }
    }

    /** スポットメーターで測る範囲: 点の画素を中心に 3×3（センサーの行・列。[開始行, 開始列, 終了行, 終了列]） */
    private fun spotRoi(): IntArray? {
        val (x, y) = nativePixel(spotPos.first, spotPos.second)
        val c0 = (x - 1).coerceIn(0, ThermalFrame.WIDTH - 3); val r0 = (y - 1).coerceIn(0, ThermalFrame.HEIGHT - 3)
        return intArrayOf(r0, c0, r0 + 2, c0 + 2)
    }

    fun setSmooth(v: Smooth) = viewModelScope.launch { smoothed = null; store.setSmooth(v) }

    /** 放射率を変えて Lepton 本体に書く（0.05〜1.0） */
    fun setEmissivity(e: Float) = viewModelScope.launch {
        val v = e.coerceIn(0.05f, 1f)
        store.setEmissivity(v)
        commands.send(CameraCommand.SetEmissivity(v))
    }

    fun setPalette(p: Palette) = viewModelScope.launch { store.setPalette(p) }

    fun setGain(g: Gain) = viewModelScope.launch {
        store.setGain(g)
        commands.send(CameraCommand.SetGain(g))
        autoLo = Float.NaN
        smoothed = null
    }

    fun runFfc() { commands.trySend(CameraCommand.RunFfc) }

    /** 自動 ↔ 固定。固定にしたときは今の範囲をそのまま固定する */
    fun setAutoRange(auto: Boolean) = viewModelScope.launch {
        val u = _ui.value
        store.setRange(auto, u.lo, u.hi)
    }

    /** 範囲のスライダーを動かしている間 */
    fun setManualRangeLive(lo: Float, hi: Float) {
        rangeLive = lo to hi
        _ui.update { it.copy(lo = lo, hi = hi) }
    }

    /** 指を離したら保存する */
    fun commitManualRange() = viewModelScope.launch {
        val r = rangeLive ?: return@launch
        store.setRange(false, r.first, r.second)
        rangeLive = null
    }

    /** 輪郭の強さのつまみを動かしている間（指を離したら commitEdgeLevel で保存） */
    fun setEdgeLevelLive(v: Float) {
        edgeLive = v.coerceIn(0f, 1f)
        _ui.update { it.copy(edgeShown = edgeLive) }
    }

    fun commitEdgeLevel() = viewModelScope.launch {
        val v = edgeLive ?: return@launch
        store.setEdgeLevel(v)
        edgeLive = null
        _ui.update { it.copy(edgeShown = null) }
    }

    /** 重ね合わせの土台にする可視カメラ（サブ / メイン）。開き直しと位置合わせの切り替えは設定の流れで行う */
    fun setBaseCamera(cam: BaseCamera) = viewModelScope.launch { store.setBaseCamera(cam) }

    fun setOverlay(mode: OverlayMode) = viewModelScope.launch {
        if (mode != OverlayMode.OFF && !hasCameraPermission()) {
            _ui.update { it.copy(message = UiMessage.CameraPermissionDenied) }
            return@launch
        }
        store.setOverlay(mode)
    }

    /** 今の位置合わせ（動かしている途中ならその値） */
    fun currentAlign(): Alignment = alignLive ?: _ui.value.settings.activeAlign

    /** 位置合わせを動かしている間（スライダー・指でのドラッグとつまみ） */
    fun setAlignLive(a: Alignment) {
        alignLive = Alignment(a.scale.coerceIn(0.3f, 1.2f), a.offsetX.coerceIn(-0.4f, 0.4f), a.offsetY.coerceIn(-0.4f, 0.4f))
        _ui.update { it.copy(alignShown = alignLive) }
    }

    fun commitAlign() = viewModelScope.launch {
        val a = alignLive ?: return@launch
        store.setAlign(a, _ui.value.settings.baseCamera)
        alignLive = null
        _ui.update { it.copy(alignShown = null, proposalScore = null) }
    }

    fun resetAlign() = viewModelScope.launch {
        val cam = _ui.value.settings.baseCamera
        alignLive = null; store.setAlign(cam.defaultAlign, cam); _ui.update { it.copy(alignShown = null, proposalScore = null) }
    }

    /** 自動位置合わせ: 今の熱画像と可視画像の輪郭が一番重なる位置を探して「提案」として見せる */
    fun autoAlign() {
        val f = lastFrame
        val l = luma
        if (f == null || l == null) { _ui.update { it.copy(message = UiMessage.AlignNoVisible) }; return }
        if (_ui.value.autoAligning) return
        _ui.update { it.copy(autoAligning = true) }
        viewModelScope.launch(Dispatchers.Default) {
            when (val r = AutoAlign.run(f, THERMAL_ORIENTATION, l, currentAlign())) {
                // 提案は画面に出すだけで保存しない。採用（acceptProposal）で保存、やめる（rejectProposal）で元に戻す
                is AutoAlign.Outcome.Ok -> {
                    alignLive = r.align
                    _ui.update { it.copy(autoAligning = false, proposalScore = r.score, alignShown = r.align) }
                }
                is AutoAlign.Outcome.FlatScene -> _ui.update { it.copy(autoAligning = false, message = UiMessage.AlignFlat(r.edge)) }
                is AutoAlign.Outcome.WeakMatch -> _ui.update { it.copy(autoAligning = false, message = UiMessage.AlignWeak(r.score)) }
            }
        }
    }

    /** 提案を採用して保存する */
    fun acceptProposal() = commitAlign()

    /** 提案をやめて元の位置に戻す */
    fun rejectProposal() {
        alignLive = null
        _ui.update { it.copy(proposalScore = null, alignShown = null) }
    }

    // ---- 記憶（1〜5）----

    /** 今の設定（色・範囲・測定範囲・重ね方・位置合わせ）を index 番に記憶する */
    fun savePreset(index: Int) = viewModelScope.launch {
        val u = _ui.value
        // 自動範囲のときも、そのときの範囲を入れておく（固定に切り替えたときの手がかり）
        val s = if (u.settings.autoRange) u.settings.copy(rangeLo = u.lo, rangeHi = u.hi) else u.settings
        store.savePreset(index, s)
        _ui.update { it.copy(message = UiMessage.PresetSaved(index + 1)) }
    }

    /** index 番の記憶を呼び出す。測定範囲が変わるならカメラにも指示する */
    fun loadPreset(index: Int) = viewModelScope.launch {
        var p = _ui.value.presets.getOrNull(index) ?: return@launch
        var msg: UiMessage = UiMessage.PresetLoaded(index + 1)
        if (p.overlay != OverlayMode.OFF && !hasCameraPermission()) {
            p = p.copy(overlay = OverlayMode.OFF); msg = UiMessage.CameraPermissionDenied
        }
        val gainChanged = p.gain != _ui.value.settings.gain
        alignLive = null; rangeLive = null; edgeLive = null
        _ui.update { it.copy(proposalScore = null, edgeShown = null) }
        store.setAll(p)
        if (gainChanged) { commands.send(CameraCommand.SetGain(p.gain)); autoLo = Float.NaN }
        commands.send(CameraCommand.SetEmissivity(p.emissivity))
        smoothed = null
        _ui.update { it.copy(alignShown = null, message = msg) }
    }

    fun messageShown() = _ui.update { it.copy(message = null) }

    private fun hasCameraPermission() =
        ContextCompat.checkSelfPermission(getApplication(), Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun updateVisible() {
        val want = cameraJob?.isActive == true && _ui.value.settings.overlay != OverlayMode.OFF && hasCameraPermission()
        if (!want) {
            visibleJob?.cancel(); visibleJob = null; luma = null
            _ui.update { it.copy(visibleActive = false) }
            return
        }
        if (visibleJob?.isActive == true) return
        val app = getApplication<Application>()
        val id = runCatching { VisibleCamera.pickCamera(app, _ui.value.settings.baseCamera.cameraId) }.getOrNull()
        if (id == null) { _ui.update { it.copy(message = UiMessage.VisibleFailed) }; return }
        visibleJob = viewModelScope.launch(Dispatchers.Default) {
            _ui.update { it.copy(visibleActive = true) }
            runCatching { VisibleCamera.frames(app, id).collect { luma = it } }
                .onFailure { _ui.update { u -> u.copy(message = UiMessage.VisibleFailed) } }
            luma = null
            _ui.update { it.copy(visibleActive = false) }
        }
    }

    // ---- 撮影 ----

    /** 撮影ボタンに短く触れたとき（撮らない） */
    fun showCaptureHint() = _ui.update { it.copy(message = UiMessage.CaptureHint) }

    fun capture() {
        val f = lastFrame ?: return
        val img = _image.value ?: return
        val u = _ui.value
        if (u.saving) return
        _ui.update { it.copy(saving = true, shots = it.shots + 1) }
        viewModelScope.launch(Dispatchers.Default) {
            val app = getApplication<Application>()
            val name = "thermal_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val photo = PhotoComposer.compose(
                image = img, lo = u.lo, hi = u.hi, palette = u.settings.palette,
                spot = u.spot, max = u.max, min = u.min,
                footer = app.getString(R.string.photo_footer, name.removePrefix("thermal_"), app.getString(gainLabel(u.settings.gain))),
            )
            val msg = runCatching { PhotoSaver.save(app, name, photo, f, orientedRows(f)) }
                .fold({ UiMessage.Saved(it) }, { UiMessage.SaveFailed })
            if (msg is UiMessage.Saved) {
                _lastPhoto.value = photo.scale(160, 160 * photo.height / photo.width)
            }
            _ui.update { it.copy(saving = false, message = msg) }
        }
    }

    /** CSV 用: 画面で見たときと同じ向きに並べた温度（センサーの画素そのまま、補間しない） */
    private fun orientedRows(f: ThermalFrame): List<FloatArray> {
        val o = THERMAL_ORIENTATION
        val w = if (o.swapsAxes) ThermalFrame.HEIGHT else ThermalFrame.WIDTH
        val h = if (o.swapsAxes) ThermalFrame.WIDTH else ThermalFrame.HEIGHT
        return List(h) { y -> FloatArray(w) { x -> val (nx, ny) = nativePixel((x + 0.5f) / w, (y + 0.5f) / h); f[nx, ny] } }
    }

    override fun onCleared() = stop()
}

fun gainLabel(g: Gain): Int = when (g) {
    Gain.HIGH -> R.string.gain_high
    Gain.LOW -> R.string.gain_low
    Gain.AUTO -> R.string.gain_auto
}
