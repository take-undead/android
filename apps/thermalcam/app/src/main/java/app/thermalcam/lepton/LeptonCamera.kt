package app.thermalcam.lepton

import app.thermalcam.thermal.ThermalFrame
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * ゲイン（測れる範囲）。データシート: 高 = −10〜140℃（±5℃ か 5%）、低 = −10〜450℃（±10℃ か 10%）、自動 = 場面で切り替え。
 * TLinear の分解能 0.01K は最大 655.35K（382℃）までなので、低・自動では 0.1K にする。
 */
enum class Gain(val cci: Int, val kelvinPerCount: Float, val resolutionCci: Int) {
    HIGH(0, 0.01f, 1),
    LOW(1, 0.1f, 0),
    AUTO(2, 0.1f, 0),
}

sealed interface CameraCommand {
    data object RunFfc : CameraCommand
    data class SetGain(val gain: Gain) : CameraCommand
    /** 放射率（0.01〜1.0）を Lepton 本体に書く（RAD_FLUX_LINEAR_PARAMS の sceneEmissivity） */
    data class SetEmissivity(val emissivity: Float) : CameraCommand
}

enum class CameraProblem { NO_SERVICE, OPEN_FAILED, SETUP_FAILED, NO_FRAMES, IO_ERROR }

sealed interface CameraEvent {
    data object Starting : CameraEvent
    data class Running(val gain: Gain) : CameraEvent
    data class Frame(val frame: ThermalFrame) : CameraEvent
    data class Problem(val problem: CameraProblem, val detail: String = "") : CameraEvent
    /** Lepton 本体のスポットメーターが測った温度（℃）と、測った範囲（センサーの画素、行・列の始めと終わり） */
    data class SpotMeter(val celsius: Float, val roi: IntArray) : CameraEvent
    /** Lepton 本体に書いてある放射率（読み戻した値） */
    data class Emissivity(val emissivity: Float) : CameraEvent
}

/**
 * Lepton を開いて設定し、新しいフレームを流す。流れを止める（collect をやめる）と必ず release する。
 * 設定は FLIR のテストアプリ（com.flir.leptontestapp の mbDeviceSetup）と同じ VSYNC の設定に、TLinear と末尾テレメトリーを加えたもの。
 * VSYNC を設定しないとフレームが 1 枚も来ないことを実機で確認した（5 秒で 0 枚 → 設定後 131 枚）。
 */
object LeptonCamera {
    /**
     * セッションは同時に 1 つだけ。サービスは利用者を 1 人しか持たず、前のセッションの release が後から来ると
     * 新しいセッションまで切られる（実機のログで確認: 画面の回転で開き直したとき、open に 6 秒かかる間に重なった）。
     */
    private val sessionLock = Mutex()

    /**
     * spotRoi: スポットメーターで測る範囲（センサーの画素 [開始行, 開始列, 終了行, 終了列]）を返す。1 秒ごとに読んで測る
     */
    fun session(
        clientName: String, initialGain: Gain, initialEmissivity: Float, commands: ReceiveChannel<CameraCommand>,
        spotRoi: () -> IntArray?,
    ): Flow<CameraEvent> = flow {
        emit(CameraEvent.Starting)
        sessionLock.withLock { runSession(clientName, initialGain, initialEmissivity, commands, spotRoi) }
    }.flowOn(Dispatchers.IO)

    private suspend fun kotlinx.coroutines.flow.FlowCollector<CameraEvent>.runSession(
        clientName: String, initialGain: Gain, initialEmissivity: Float, commands: ReceiveChannel<CameraCommand>,
        spotRoi: () -> IntArray?,
    ) {
        val svc = LeptonService.connect()
        if (svc == null) {
            emit(CameraEvent.Problem(CameraProblem.NO_SERVICE)); return
        }
        // 電源を入れて起動するまで約 6 秒かかる（実機のログ: setPower から spiState まで 6.2 秒）
        val opened = runCatching { svc.open(clientName) }
        if (opened.getOrNull() != 0) {
            emit(CameraEvent.Problem(CameraProblem.OPEN_FAILED, opened.exceptionOrNull()?.message ?: "code ${opened.getOrNull()}"))
            return
        }
        try {
            val cci = Cci(svc)
            var gain = initialGain
            try {
                cci.setInt(Cci.OEM_VSYNC_DELAY, 2)
                cci.setInt(Cci.OEM_GPIO_MODE, 5)               // VSYNC
                cci.setInt(Cci.SYS_TELEMETRY_ENABLE, 1)
                cci.setInt(Cci.SYS_TELEMETRY_LOCATION, 1)      // 末尾
                cci.setInt(Cci.RAD_ENABLE, 1)
                cci.setInt(Cci.RAD_TLINEAR_ENABLE, 1)
                applyGain(cci, gain)
            } catch (e: Exception) {
                emit(CameraEvent.Problem(CameraProblem.SETUP_FAILED, e.message ?: "")); return
            }
            emit(CameraEvent.Running(gain))
            runCatching { emit(CameraEvent.Emissivity(setEmissivity(cci, initialEmissivity))) }
                .onFailure { emit(CameraEvent.Problem(CameraProblem.IO_ERROR, "emissivity: ${it.message}")) }
            var lastSpot = 0L

            var lastCounter = -1L
            var lastNew = System.currentTimeMillis()
            var warned = false
            while (currentCoroutineContext().isActive) {
                commands.tryReceive().getOrNull()?.let { cmd ->
                    runCatching {
                        when (cmd) {
                            CameraCommand.RunFfc -> cci.run(Cci.SYS_RUN_FFC)
                            is CameraCommand.SetGain -> {
                                applyGain(cci, cmd.gain); gain = cmd.gain
                                cci.run(Cci.SYS_RUN_FFC) // ゲインを変えたら校正し直す
                            }
                            is CameraCommand.SetEmissivity -> emit(CameraEvent.Emissivity(setEmissivity(cci, cmd.emissivity)))
                        }
                    }.onSuccess { if (cmd is CameraCommand.SetGain) emit(CameraEvent.Running(gain)) }
                        .onFailure { emit(CameraEvent.Problem(CameraProblem.IO_ERROR, it.message ?: "")) }
                }
                when (svc.frameStatus()) {
                    1 -> {
                        val b = svc.readFrame()
                        if (b != null) {
                            val f = ThermalFrame.parse(b, gain.kelvinPerCount)
                            // 27Hz の流れのうち 2/3 は同じフレームの繰り返し（データシート）。カウンターが変わったものだけ流す
                            if (f != null && f.frameCounter != lastCounter) {
                                lastCounter = f.frameCounter
                                lastNew = System.currentTimeMillis()
                                warned = false
                                emit(CameraEvent.Frame(f))
                            }
                        }
                    }
                    0 -> delay(3)
                    else -> delay(20)
                }
                // 1 秒ごとに Lepton 本体のスポットメーターで点の周りを測る（アプリの計算と比べるため）
                val now = System.currentTimeMillis()
                val roi = spotRoi()
                if (roi != null && now - lastSpot > 1000) {
                    lastSpot = now
                    runCatching {
                        cci.set(Cci.RAD_SPOTMETER_ROI, roi)
                        val k = cci.get(Cci.RAD_SPOTMETER_KELVIN, 4)[0]
                        emit(CameraEvent.SpotMeter(k * gain.kelvinPerCount - 273.15f, roi))
                    }
                }
                if (!warned && System.currentTimeMillis() - lastNew > 4000) {
                    warned = true
                    emit(CameraEvent.Problem(CameraProblem.NO_FRAMES))
                }
            }
        } finally {
            withContext(NonCancellable) { runCatching { svc.release() } }
        }
    }

    /**
     * 放射率を書いて、読み戻した値を返す。RAD_FLUX_LINEAR_PARAMS は 8 ワード
     * （sceneEmissivity, TBkgK, tauWindow, TWindowK, tauAtm, TAtmK, reflWindow, TReflK。LEPTON_RAD.h）。
     * 放射率は 8192 = 1.0。他の 7 つは本体の値をそのまま残す（実機の既定: 放射率 1.0、背景 295.15K = 22℃）
     */
    private fun setEmissivity(cci: Cci, e: Float): Float {
        val p = cci.get(Cci.RAD_FLUX_LINEAR_PARAMS, 8)
        p[0] = (e.coerceIn(0.01f, 1f) * 8192).toInt().coerceIn(82, 8192)
        cci.set(Cci.RAD_FLUX_LINEAR_PARAMS, p)
        return cci.get(Cci.RAD_FLUX_LINEAR_PARAMS, 8)[0] / 8192f
    }

    private fun applyGain(cci: Cci, gain: Gain) {
        cci.setInt(Cci.SYS_GAIN_MODE, gain.cci)
        cci.setInt(Cci.RAD_TLINEAR_RESOLUTION, gain.resolutionCci)
    }
}
