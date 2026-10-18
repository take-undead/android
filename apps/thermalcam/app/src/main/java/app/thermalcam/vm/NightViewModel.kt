package app.thermalcam.vm

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.thermalcam.data.PhotoSaver
import app.thermalcam.night.Exposure
import app.thermalcam.night.NightCamera
import app.thermalcam.night.NightEvent
import app.thermalcam.night.NightLimits
import app.thermalcam.night.NightProcessor
import app.thermalcam.night.NightView
import app.thermalcam.night.ShotRequest
import app.thermalcam.night.YuvFrame
import app.thermalcam.thermal.Orientation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.pow

/**
 * 使えるカメラ。レンズとの対応は、レンズを 1 つずつ指でふさいで暗くなる番号を測って確かめた（2026-10-18）:
 *  ID 0 メイン = 背面の一番大きいレンズ / ID 2 サブ = 背面の 3 つ目のレンズ（500 万画素） / ID 1 前面 = 液晶側。
 *  ID 3 も開けるが映像が ID 2 とほぼ同じ（相関 0.981）なので選択肢に入れない。
 *  背面の残り 2 つ（一番大きいレンズの隣と 4 つ目）はどの番号でも暗くならない。カメラサービスが知るのは 4 台だけで、アプリからは使えない
 */
enum class NightLens(val cameraId: String) { MAIN("0"), WIDE("2"), FRONT("1") }

/** ノイズ除去の強さ: 画面で新しい 1 枚を混ぜる重み（小さいほど強く平均する）と、撮影で重ねる枚数 */
enum class Denoise(val weight: Float, val shots: Int) { OFF(1f, 1), LOW(0.35f, 3), HIGH(0.15f, 6) }

data class NightUi(
    val lens: NightLens = NightLens.MAIN,
    val brightness: Float = 0f,          // 0 = 自動、0 より大きいと手動（右ほど長い露出・高い感度）
    val lift: Float = 0.2f,              // 暗部を明るく（0〜1）。明るい部屋で 0.5 だと白飛びしたので控えめに
    val denoise: Denoise = Denoise.LOW,
    val view: NightView = NightView.COLOR,
    val torch: Boolean = false,          // ライト（フラッシュの点けっぱなし）
    val maxQuality: Boolean = false,     // 最高画質（カメラの JPEG を最大の大きさで 1 枚。重ねない）
    val limits: NightLimits? = null,
    val previewWidth: Int = 0,           // 画面に流している映像の大きさ（センサーの向きのまま。横長）
    val previewHeight: Int = 0,
    val previewing: Boolean = false,     // 映像が出始めた
    val actualExposureNs: Long = 0,
    val actualIso: Int = 0,
    val running: Boolean = false,
    val failed: String? = null,
    val saving: Boolean = false,
    val shots: Int = 0,                  // 撮影の回数（画面を一瞬光らせる合図）
    val message: UiMessage? = null,
)

/**
 * 暗所カメラ（普通のカメラの高感度撮影）。赤外線の暗視ではない: 少しでも光がある所（街灯・月明かり）を明るく見る。
 * 真っ暗では写らない（このスマホに暗視カメラの指定が無いことを確認済み。CLAUDE.md の作業記録）
 */
class NightViewModel(app: Application) : AndroidViewModel(app) {
    private val _ui = MutableStateFlow(NightUi())
    val ui: StateFlow<NightUi> = _ui
    /** 映像を映す先（画面の TextureView）。画面が作られたら渡され、消えたら null */
    @Volatile private var texture: android.graphics.SurfaceTexture? = null
    private var surface: android.view.Surface? = null

    private val exposure = MutableStateFlow(Exposure(auto = true))
    private val shots = Channel<ShotRequest>(Channel.CONFLATED)
    private val torch = MutableStateFlow(false)
    private var job: Job? = null
    private var screenDegrees = 0
    private val burst = ArrayList<YuvFrame>()

    fun setScreenRotation(degrees: Int) { screenDegrees = degrees }

    /**
     * センサーの並び → 画面の向き。背面: センサーの向き − 画面の回転。
     * 前面: センサーの向き + 画面の回転 で回し、鏡のように左右を反転する（自撮りと同じ見え方）
     */
    private fun orient(): Orientation {
        val lim = _ui.value.limits
        val so = lim?.sensorOrientation ?: 90
        return if (lim?.front == true) Orientation((so + screenDegrees) % 360, mirror = true)
        else Orientation((so - screenDegrees + 360) % 360)
    }

    /** 開くのに失敗したときのやり直しの回数（サーマル側の可視カメラが閉じ終わる前に開くと error 2 になるため） */
    private var retries = 0
    /** 画面が見えていてカメラを動かしたい間だけ true（やり直しを待つ間に画面を閉じたら開かない） */
    @Volatile private var wanted = false

    /** 切り替えの時間を測る（開発版のときだけ記録に出す。CLAUDE.md B7: リリース版ではログを出さない） */
    private var requestedAt = 0L
    private var firstFrameLogged = true
    private fun logTiming(msg: String) {
        val app = getApplication<Application>()
        if (app.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0) android.util.Log.d("ThermalCamTiming", msg)
    }

    fun start() {
        wanted = true
        if (job?.isActive == true || texture == null) return
        retries = 0
        launchSession()
    }

    /** 画面の TextureView の受け取り口ができた / 消えた */
    fun setTexture(st: android.graphics.SurfaceTexture?) {
        texture = st
        if (st == null) { job?.cancel(); job = null; surface?.release(); surface = null }
        else if (wanted) start()
    }

    private fun launchSession() {
        val app = getApplication<Application>()
        if (ContextCompat.checkSelfPermission(app, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            _ui.update { it.copy(message = UiMessage.CameraPermissionDenied) }
            return
        }
        val st = texture ?: return
        burst.clear()
        if (firstFrameLogged) { requestedAt = android.os.SystemClock.elapsedRealtime(); firstFrameLogged = false }
        logTiming("open ${_ui.value.lens} +${android.os.SystemClock.elapsedRealtime() - requestedAt}ms")
        // 映像の大きさをカメラに合わせて決めてから、受け取り口（Surface）を作る
        val ps = NightCamera.previewSize(app, _ui.value.lens.cameraId)
        st.setDefaultBufferSize(ps.width, ps.height)
        surface?.release()
        val sf = android.view.Surface(st).also { surface = it }
        _ui.update { it.copy(previewWidth = ps.width, previewHeight = ps.height, previewing = false) }
        job = viewModelScope.launch(Dispatchers.Default) {
            _ui.update { it.copy(running = true, failed = null) }
            runCatching {
                NightCamera.session(app, _ui.value.lens.cameraId, sf, exposure, torch, _ui.value.maxQuality, shots).collect { e ->
                    when (e) {
                        is NightEvent.Actual -> {
                            if (!firstFrameLogged) {
                                firstFrameLogged = true
                                logTiming("first frame ${_ui.value.lens} +${android.os.SystemClock.elapsedRealtime() - requestedAt}ms")
                            }
                            _ui.update { it.copy(actualExposureNs = e.exposureNs, actualIso = e.iso, previewing = true) }
                        }
                        is NightEvent.Opened -> { logTiming("opened +${android.os.SystemClock.elapsedRealtime() - requestedAt}ms"); _ui.update { it.copy(limits = e.limits) }; updateExposure() }
                        is NightEvent.Shot -> onShot(e)
                        is NightEvent.Jpeg -> onJpeg(e.bytes)
                        is NightEvent.ShotFailed -> _ui.update { it.copy(saving = false, message = UiMessage.ShotFailed(e.reason)) }
                        is NightEvent.Failed -> {
                            // error 1（使用中）・2（同時に使える数を超えた）は、少し待てば開けることが多い。最大 5 回やり直す
                            if ((e.reason == "error 1" || e.reason == "error 2") && retries < 5) {
                                retries++
                                viewModelScope.launch { delay(600); if (wanted && job?.isActive != true) launchSession() }
                            } else {
                                _ui.update { it.copy(failed = e.reason, saving = false) }
                            }
                        }
                    }
                }
            }.onFailure { t ->
                // 止めた（カメラの切り替え・画面を閉じた）ときの中断は失敗ではない
                if (t is kotlinx.coroutines.CancellationException) throw t
                _ui.update { it.copy(failed = t.message ?: t.toString()) }
            }
            _ui.update { it.copy(running = false) }
        }
    }

    /** 止める。画面を閉じたときはライトも消す（戻ったときに勝手に点かないように）。開き直すときは keepTorch で残す */
    fun stop(keepTorch: Boolean = false) {
        wanted = false
        job?.cancel(); job = null
        if (!keepTorch) torch.value = false
        _ui.update { it.copy(running = false, saving = false, torch = torch.value) }
    }

    private fun restart() { stop(keepTorch = true); start() }

    fun setLens(l: NightLens) {
        if (l == _ui.value.lens) return
        requestedAt = android.os.SystemClock.elapsedRealtime(); firstFrameLogged = false
        logTiming("setLens $l")
        torch.value = false // カメラを替えたらライトは消す（前面にはライトが無い）
        _ui.update { it.copy(lens = l, limits = null, torch = false) }
        restart()
    }

    /** 明るさ: 0 = 自動。0 より大きいと、露出時間と感度を対数で上げる（1 = そのカメラの最大） */
    fun setBrightness(v: Float) {
        _ui.update { it.copy(brightness = v.coerceIn(0f, 1f)) }
        updateExposure()
    }

    private fun updateExposure() {
        val u = _ui.value
        val lim = u.limits ?: return
        if (u.brightness <= 0.001f) { exposure.value = Exposure(auto = true); return }
        val minExp = 33_000_000.0 // 1/30 秒から
        val minIso = maxOf(lim.minIso, 200).toDouble()
        val t = u.brightness.toDouble()
        val exp = (minExp * (lim.maxExposureNs / minExp).pow(t)).toLong()
        val iso = (minIso * (lim.maxIso / minIso).pow(t)).toInt()
        exposure.value = Exposure(auto = false, exposureNs = exp, iso = iso)
    }

    /** ライト: フラッシュがあるカメラ（メイン・広角）だけ */
    fun setTorch(on: Boolean) {
        val ok = on && _ui.value.limits?.flash == true
        torch.value = ok
        _ui.update { it.copy(torch = ok) }
    }

    /** 画質を変えるとカメラを開き直す（撮影用の受け取り口を作り直すため） */
    fun setMaxQuality(on: Boolean) {
        if (on == _ui.value.maxQuality) return
        _ui.update { it.copy(maxQuality = on) }
        restart()
    }

    fun setLift(v: Float) = _ui.update { it.copy(lift = v.coerceIn(0f, 1f)) }
    fun setDenoise(d: Denoise) = _ui.update { it.copy(denoise = d) }
    fun setView(v: NightView) = _ui.update { it.copy(view = v) }
    fun messageShown() = _ui.update { it.copy(message = null) }
    fun showCaptureHint() = _ui.update { it.copy(message = UiMessage.CaptureHint) }

    /** 撮影: ノイズ除去の強さに応じた枚数を連写し、平均して保存する */
    fun capture() {
        val u = _ui.value
        if (u.saving || !u.running) return
        burst.clear()
        _ui.update { it.copy(saving = true, shots = it.shots + 1) }
        shots.trySend(ShotRequest(if (u.maxQuality) 1 else u.denoise.shots, jpegOrientation()))
        // 写真が届かないまま「保存中」で止まらないように、15 秒で打ち切って知らせる
        val n = _ui.value.shots
        viewModelScope.launch {
            delay(15_000)
            if (_ui.value.saving && _ui.value.shots == n) _ui.update { it.copy(saving = false, message = UiMessage.ShotFailed("timeout 15s")) }
        }
    }

    /** 写真に書く向き（Camera2 の決まり: 背面 = センサーの向き − 画面の回転、前面 = センサーの向き + 画面の回転） */
    private fun jpegOrientation(): Int {
        val lim = _ui.value.limits ?: return 0
        return if (lim.front) (lim.sensorOrientation + screenDegrees) % 360 else (lim.sensorOrientation - screenDegrees + 360) % 360
    }

    private fun onJpeg(bytes: ByteArray) {
        viewModelScope.launch(Dispatchers.IO) {
            val app = getApplication<Application>()
            val name = "photo_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val msg = runCatching { PhotoSaver.saveJpegBytes(app, name, bytes) }
                .fold({ UiMessage.SavedImage(it) }, { UiMessage.SaveFailed })
            _ui.update { it.copy(saving = false, message = msg) }
        }
    }

    private fun onShot(e: NightEvent.Shot) {
        burst.add(e.frame)
        if (burst.size < e.count) return
        val frames = burst.toList(); burst.clear()
        val u = _ui.value
        val o = orient()
        viewModelScope.launch(Dispatchers.Default) {
            val app = getApplication<Application>()
            val name = "night_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val msg = runCatching {
                PhotoSaver.saveImage(app, name, NightProcessor.average(frames, o, u.lift, u.view))
            }.fold({ UiMessage.SavedImage(it) }, { UiMessage.SaveFailed })
            _ui.update { it.copy(saving = false, message = msg) }
        }
    }

    override fun onCleared() = stop()
}
