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
}

enum class CameraProblem { NO_SERVICE, OPEN_FAILED, SETUP_FAILED, NO_FRAMES, IO_ERROR }

sealed interface CameraEvent {
    data object Starting : CameraEvent
    data class Running(val gain: Gain) : CameraEvent
    data class Frame(val frame: ThermalFrame) : CameraEvent
    data class Problem(val problem: CameraProblem, val detail: String = "") : CameraEvent
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

    fun session(clientName: String, initialGain: Gain, commands: ReceiveChannel<CameraCommand>): Flow<CameraEvent> = flow {
        emit(CameraEvent.Starting)
        sessionLock.withLock { runSession(clientName, initialGain, commands) }
    }.flowOn(Dispatchers.IO)

    private suspend fun kotlinx.coroutines.flow.FlowCollector<CameraEvent>.runSession(
        clientName: String, initialGain: Gain, commands: ReceiveChannel<CameraCommand>,
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
                if (!warned && System.currentTimeMillis() - lastNew > 4000) {
                    warned = true
                    emit(CameraEvent.Problem(CameraProblem.NO_FRAMES))
                }
            }
        } finally {
            withContext(NonCancellable) { runCatching { svc.release() } }
        }
    }

    private fun applyGain(cci: Cci, gain: Gain) {
        cci.setInt(Cci.SYS_GAIN_MODE, gain.cci)
        cci.setInt(Cci.RAD_TLINEAR_RESOLUTION, gain.resolutionCci)
    }
}
