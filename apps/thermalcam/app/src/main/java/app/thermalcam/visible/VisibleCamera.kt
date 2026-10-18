package app.thermalcam.visible

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import app.thermalcam.thermal.Luma
import app.thermalcam.thermal.Orientation
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * 重ね合わせ用の可視カメラ。明るさ（Y）だけを 1280×960 で取る（この端末の ID 2 が YUV で出せる 4:3 の大きさ。dumpsys media.camera で確認）。
 * 熱画像に重なる範囲は約 0.644 倍なので約 620×820 画素分。描画（480×640）より少し細かい。
 * カメラの番号は LeptonService.visCamId()（この端末は 2。熱カメラと並んで付いている背面カメラ）。無ければ最初の背面カメラ。
 * Camera2 はコールバックと Handler を要求するので、callbackFlow で包み、流れを止めたらカメラとスレッドを閉じる（CLAUDE.md G9）。
 */
object VisibleCamera {
    private const val WIDTH = 1280
    private const val HEIGHT = 960
    /** 写す間隔の下限。熱画像の更新は 9 回/秒弱なので、それより速く写しても使われない */
    private const val MIN_COPY_INTERVAL_MS = 80L

    fun pickCamera(context: Context, preferredId: Int): String? {
        val cm = context.getSystemService(CameraManager::class.java)
        val ids = cm.cameraIdList
        if (preferredId >= 0 && preferredId.toString() in ids) return preferredId.toString()
        return ids.firstOrNull { cm.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK }
    }

    /** 画像の面を width × height の行ごとの並びに写す（pixelStride が 1 なら行ごとにまとめて写す） */
    private fun copyPlane(plane: android.media.Image.Plane, width: Int, height: Int): ByteArray {
        val buf = plane.buffer
        val rs = plane.rowStride
        val ps = plane.pixelStride
        val out = ByteArray(width * height)
        if (ps == 1) {
            for (y in 0 until height) { buf.position(y * rs); buf.get(out, y * width, width) }
        } else {
            for (y in 0 until height) for (x in 0 until width) out[y * width + x] = buf.get(y * rs + x * ps)
        }
        return out
    }

    /** 呼ぶ前に CAMERA の権限を確かめておくこと */
    @SuppressLint("MissingPermission") // 呼び出し側（ViewModel）で権限を確かめてから流す
    fun frames(context: Context, cameraId: String): Flow<Luma> = callbackFlow {
        val cm = context.getSystemService(CameraManager::class.java)
        val rotation = cm.getCameraCharacteristics(cameraId).get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
        val orient = Orientation(rotation)
        val thread = HandlerThread("visible").apply { start() }
        val handler = Handler(thread.looper)
        val reader = ImageReader.newInstance(WIDTH, HEIGHT, ImageFormat.YUV_420_888, 2)
        CameraGate.awaitPreviousClosed()
        val closedSignal = CameraGate.opening()
        var device: CameraDevice? = null
        var session: CameraCaptureSession? = null
        var lastCopy = 0L

        reader.setOnImageAvailableListener({ r ->
            val img = r.acquireLatestImage() ?: return@setOnImageAvailableListener
            img.use {
                val now = android.os.SystemClock.elapsedRealtime()
                if (now - lastCopy < MIN_COPY_INTERVAL_MS) return@use
                lastCopy = now
                val out = copyPlane(it.planes[0], WIDTH, HEIGHT)
                // 色差は半分の大きさ（カラー土台で色を出すため。白黒の重ね方では使わない）
                val u = copyPlane(it.planes[1], WIDTH / 2, HEIGHT / 2)
                val v = copyPlane(it.planes[2], WIDTH / 2, HEIGHT / 2)
                trySend(Luma(out, WIDTH, HEIGHT, orient, u, v))
            }
        }, handler)

        cm.openCamera(cameraId, object : CameraDevice.StateCallback() {
            override fun onOpened(d: CameraDevice) {
                device = d
                @Suppress("DEPRECATION") // 新しい SessionConfiguration は API 28 から。minSdk 26 のため旧版を使う
                d.createCaptureSession(listOf(reader.surface), object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(s: CameraCaptureSession) {
                        session = s
                        val req = d.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                            addTarget(reader.surface)
                            set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                        }.build()
                        runCatching { s.setRepeatingRequest(req, null, handler) }.onFailure { close(it) }
                    }
                    override fun onConfigureFailed(s: CameraCaptureSession) { close(IllegalStateException("configure failed")) }
                }, handler)
            }
            override fun onDisconnected(d: CameraDevice) { d.close(); close() }
            override fun onError(d: CameraDevice, error: Int) { d.close(); close(IllegalStateException("camera error $error")) }
            override fun onClosed(camera: CameraDevice) { closedSignal.complete(Unit) }
        }, handler)

        awaitClose {
            runCatching { session?.close() }
            runCatching { device?.close() }
            if (device == null) closedSignal.complete(Unit)
            reader.close()
            thread.quitSafely()
        }
    }
}
