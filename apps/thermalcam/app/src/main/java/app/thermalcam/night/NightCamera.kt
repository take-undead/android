package app.thermalcam.night

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Range
import android.util.Size
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import app.thermalcam.visible.CameraGate

/** YUV 1 枚（Y は w×h、U・V は (w/2)×(h/2)） */
class YuvFrame(val w: Int, val h: Int, val y: ByteArray, val u: ByteArray, val v: ByteArray)

/** 露出の指定。auto = 自動露出（毎秒 10 枚が下限なので 1 枚 0.1 秒まで）、それ以外は露出時間と感度を手で決める */
data class Exposure(val auto: Boolean, val exposureNs: Long = 0, val iso: Int = 0)

/** カメラの限界（手動露出のつまみの範囲に使う） */
data class NightLimits(
    val maxExposureNs: Long, val minIso: Int, val maxIso: Int, val sensorOrientation: Int, val front: Boolean,
    val flash: Boolean,          // フラッシュ（ライト）がある（メイン・広角。前面は無い）
    val maxJpeg: Size,           // 最高画質で撮れる大きさ（メイン 8000×6000、前面 4608×3456、広角 2560×1920）
)

/** 撮影の指示。count 枚を連写して重ねる（最高画質のときは 1 枚の JPEG）。jpegOrientation は写真に書く向き */
data class ShotRequest(val count: Int, val jpegOrientation: Int)

sealed interface NightEvent {
    data class Opened(val limits: NightLimits) : NightEvent
    /** 実際にかかった露出（自動のときも表示する） */
    data class Actual(val exposureNs: Long, val iso: Int) : NightEvent
    /** 撮影の 1 枚（連写の何枚目か） */
    data class Shot(val frame: YuvFrame, val index: Int, val count: Int) : NightEvent
    /** 最高画質の撮影（カメラが作った JPEG そのまま） */
    class Jpeg(val bytes: ByteArray) : NightEvent
    data class Failed(val reason: String) : NightEvent
    /** 撮影に失敗した（カメラは動いたまま） */
    data class ShotFailed(val reason: String) : NightEvent
}

/**
 * 普通のカメラ（4 台）を手動露出で使う。画面用に 1280×960 程度、撮影用に 2560×1920 程度の YUV を受け取る。
 * 端末の性能（2026-10-18、BL8800Pro の dumpsys media.camera で確認）:
 *  - ID 0（メイン）: 露出 0.16 秒・ISO 1100 まで、FULL・MANUAL_SENSOR
 *  - ID 2（広角）  : 露出 0.4 秒・ISO 2400 まで、FULL・MANUAL_SENSOR
 *  - 自動露出の速さの範囲は [10,10] と [15,15] だけなので、自動では 1 枚 0.1 秒より長くできない
 * Camera2 はコールバックと Handler を要求するので callbackFlow で包む（CLAUDE.md G9）。
 */
object NightCamera {
    private const val PREVIEW_W = 1600
    private const val STILL_W = 2560

    /** 画面に出す映像の大きさ（4:3 で幅 1600 以下の一番大きいもの）。TextureView の受け取り口の大きさに使う */
    fun previewSize(context: Context, cameraId: String): Size {
        val map = context.getSystemService(CameraManager::class.java).getCameraCharacteristics(cameraId)
            .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
        val sizes = map.getOutputSizes(android.graphics.SurfaceTexture::class.java).filter { it.width * 3 == it.height * 4 }
        return sizes.filter { it.width <= PREVIEW_W }.maxByOrNull { it.width } ?: sizes.minBy { it.width }
    }

    private fun pick43(map: android.hardware.camera2.params.StreamConfigurationMap, want: Int): Size {
        val sizes = map.getOutputSizes(ImageFormat.YUV_420_888).filter { it.width * 3 == it.height * 4 }
        // ID 1（前面）は 4608×3456 まで出せるが、撮影は他と同じ 2560×1920 程度にそろえる（処理の重さとメモリのため）
        return sizes.filter { it.width <= want }.maxByOrNull { it.width } ?: sizes.minBy { it.width }
    }

    /** Image（YUV_420_888）を詰めた配列に写す。行の余白・画素の間隔は端末ごとに違うので plane の値に従う */
    private fun copy(img: Image): YuvFrame {
        val w = img.width; val h = img.height
        val y = ByteArray(w * h)
        val p0 = img.planes[0]
        val b0 = p0.buffer
        if (p0.pixelStride == 1) {
            for (r in 0 until h) { b0.position(r * p0.rowStride); b0.get(y, r * w, w) }
        } else {
            for (r in 0 until h) for (c in 0 until w) y[r * w + c] = b0.get(r * p0.rowStride + c * p0.pixelStride)
        }
        val cw = w / 2; val ch = h / 2
        fun plane(i: Int): ByteArray {
            val p = img.planes[i]; val b = p.buffer
            val out = ByteArray(cw * ch)
            for (r in 0 until ch) for (c in 0 until cw) out[r * cw + c] = b.get(r * p.rowStride + c * p.pixelStride)
            return out
        }
        return YuvFrame(w, h, y, plane(1), plane(2))
    }

    /**
     * カメラを開き、映像を previewSurface（画面の TextureView）に直接流す（アプリで 1 枚ずつ計算すると毎秒 5.8 枚しか出なかったため）。exposure を変えるとすぐ反映する。shots に枚数を送ると、その枚数を連写して Shot で返す。
     * 呼ぶ前に CAMERA の権限を確かめておくこと
     */
    @SuppressLint("MissingPermission") // 呼び出し側（NightViewModel）で権限を確かめてから流す
    fun session(
        context: Context, cameraId: String, previewSurface: android.view.Surface, exposure: StateFlow<Exposure>, torch: StateFlow<Boolean>, maxQuality: Boolean,
        shots: ReceiveChannel<ShotRequest>,
    ): Flow<NightEvent> = callbackFlow {
        val cm = context.getSystemService(CameraManager::class.java)
        val ch = cm.getCameraCharacteristics(cameraId)
        val map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: run { close(); return@callbackFlow }
        val expRange = ch.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE) ?: Range(100_000L, 100_000_000L)
        val isoRange = ch.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE) ?: Range(100, 800)
        val limits = NightLimits(
            expRange.upper, isoRange.lower, isoRange.upper, ch.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90,
            front = ch.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT,
            flash = ch.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true,
            maxJpeg = map.getOutputSizes(ImageFormat.JPEG).filter { it.width * 3 == it.height * 4 }.maxBy { it.width.toLong() * it.height },
        )
        // ピントが固定のカメラ（ID 1・2・3。afAvailableModes が OFF だけ）には自動ピントを指示しない
        val canAf = ch.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)?.contains(CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE) == true
        val stillSize = pick43(map, STILL_W)
        CameraGate.awaitPreviousClosed()
        val closedSignal = CameraGate.opening()

        val thread = HandlerThread("night").apply { start() }
        val handler = Handler(thread.looper)
        // 最高画質: カメラの JPEG（最大の大きさ）を 1 枚。標準: YUV を連写して自分で重ねる。カメラを開くときにどちらかに決める
        val still = if (maxQuality) ImageReader.newInstance(limits.maxJpeg.width, limits.maxJpeg.height, ImageFormat.JPEG, 2)
        else ImageReader.newInstance(stillSize.width, stillSize.height, ImageFormat.YUV_420_888, 3)
        var device: CameraDevice? = null
        var session: CameraCaptureSession? = null
        var lastActual = Exposure(false, 33_000_000L, 400)
        var burstLeft = 0; var burstCount = 0

        still.setOnImageAvailableListener({ r ->
            r.acquireNextImage()?.use {
                if (maxQuality) {
                    val b = it.planes[0].buffer
                    trySend(NightEvent.Jpeg(ByteArray(b.remaining()).also { a -> b.get(a) }))
                } else if (burstLeft > 0) {
                    val idx = burstCount - burstLeft
                    burstLeft--
                    trySend(NightEvent.Shot(copy(it), idx, burstCount))
                }
            }
        }, handler)

        val resultCallback = object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureCompleted(s: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
                val e = result.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: return
                val iso = result.get(CaptureResult.SENSOR_SENSITIVITY) ?: return
                lastActual = Exposure(false, e, iso)
                trySend(NightEvent.Actual(e, iso))
            }
        }

        /** ライト: フラッシュを点けっぱなしにする（ある機種だけ） */
        fun CaptureRequest.Builder.applyTorch() {
            if (limits.flash) set(CaptureRequest.FLASH_MODE, if (torch.value) CaptureRequest.FLASH_MODE_TORCH else CaptureRequest.FLASH_MODE_OFF)
        }

        fun CaptureRequest.Builder.applyExposure(x: Exposure) {
            if (x.auto) {
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                // 毎秒 5〜30 枚: 明るければ 30 枚（短い露出でぶれにくい）、暗いと自動で遅くして明るさを稼ぐ
                // （この端末の選べる範囲: 10・15・15〜20・20・5〜30・30。dumpsys で確認）
                set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(5, 30))
            } else {
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                set(CaptureRequest.SENSOR_EXPOSURE_TIME, x.exposureNs)
                set(CaptureRequest.SENSOR_SENSITIVITY, x.iso)
                set(CaptureRequest.SENSOR_FRAME_DURATION, x.exposureNs)
            }
        }

        fun repeat(s: CameraCaptureSession, d: CameraDevice) {
            val req = d.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(previewSurface)
                if (canAf) set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_FAST)
                applyExposure(exposure.value)
                applyTorch()
            }.build()
            runCatching { s.setRepeatingRequest(req, resultCallback, handler) }
        }

        fun burst(s: CameraCaptureSession, d: CameraDevice, shot: ShotRequest) {
            // 自動のときも、そのとき実際にかかっていた露出に固定して連写する（枚ごとに明るさが変わらないように）
            val x = if (exposure.value.auto) lastActual else exposure.value
            val n = if (maxQuality) 1 else shot.count
            val reqs = List(n) {
                d.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                    addTarget(still.surface)
                    if (canAf) set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                    set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY)
                    applyExposure(x.copy(auto = false))
                    applyTorch()
                    if (maxQuality) {
                        set(CaptureRequest.JPEG_ORIENTATION, shot.jpegOrientation)
                        set(CaptureRequest.JPEG_QUALITY, 95.toByte())
                    }
                }.build()
            }
            burstCount = n; burstLeft = n
            // 撮影の 1 枚ごとの結果を受け取る（失敗したら理由を画面に出す。写真が届かない原因を調べるため）
            val stillCallback = object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureFailed(s: CameraCaptureSession, request: CaptureRequest, failure: android.hardware.camera2.CaptureFailure) {
                    trySend(NightEvent.ShotFailed("reason=${failure.reason} imageCaptured=${failure.wasImageCaptured()}"))
                }
                override fun onCaptureBufferLost(s: CameraCaptureSession, request: CaptureRequest, target: android.view.Surface, frameNumber: Long) {
                    trySend(NightEvent.ShotFailed("buffer lost frame=$frameNumber"))
                }
            }
            runCatching { s.captureBurst(reqs, stillCallback, handler) }.onFailure { burstLeft = 0; trySend(NightEvent.ShotFailed(it.message ?: it.toString())) }
        }

        cm.openCamera(cameraId, object : CameraDevice.StateCallback() {
            override fun onOpened(d: CameraDevice) {
                device = d
                @Suppress("DEPRECATION") // 新しい SessionConfiguration は API 28 から。minSdk 26 のため旧版を使う
                d.createCaptureSession(listOf(previewSurface, still.surface), object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(s: CameraCaptureSession) {
                        session = s
                        trySend(NightEvent.Opened(limits))
                        repeat(s, d)
                        launch { exposure.collect { session?.let { ss -> repeat(ss, d) } } }
                        launch { torch.collect { session?.let { ss -> repeat(ss, d) } } }
                        launch { for (n in shots) session?.let { ss -> burst(ss, d, n) } }
                    }
                    override fun onConfigureFailed(s: CameraCaptureSession) { trySend(NightEvent.Failed("configure")); close() }
                }, handler)
            }
            override fun onDisconnected(d: CameraDevice) { d.close(); close() }
            override fun onError(d: CameraDevice, error: Int) { d.close(); trySend(NightEvent.Failed("error $error")); close() }
            override fun onClosed(camera: CameraDevice) { closedSignal.complete(Unit) }
        }, handler)

        awaitClose {
            runCatching { session?.close() }
            runCatching { device?.close() }
            if (device == null) closedSignal.complete(Unit) // 開く前に止めた
            still.close()
            thread.quitSafely()
        }
    }
}
