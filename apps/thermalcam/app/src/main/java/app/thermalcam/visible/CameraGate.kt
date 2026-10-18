package app.thermalcam.visible

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 普通のカメラは、前のカメラが閉じ終わる前に次を開くと失敗する（error 2。2026-10-18 実機で確認）。
 * カメラを開く前に前のカメラが閉じ終わる（CameraDevice.StateCallback.onClosed）のを待つための合図。
 * 熱画像の重ね合わせ用（VisibleCamera）とカメラ画面（NightCamera）の両方で使う
 */
object CameraGate {
    @Volatile private var closed: CompletableDeferred<Unit> = CompletableDeferred(Unit)

    /** 前のカメラが閉じ終わるまで待つ（最大 2 秒） */
    suspend fun awaitPreviousClosed() { withTimeoutOrNull(2000) { closed.await() } }

    /** これから開くカメラの「閉じ終わった」合図を作る。onClosed で complete する */
    fun opening(): CompletableDeferred<Unit> = CompletableDeferred<Unit>().also { closed = it }
}
