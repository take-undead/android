package app.thermalcam.lepton

import android.annotation.SuppressLint
import android.os.Binder
import android.os.IBinder
import android.os.Parcel

/**
 * 端末のシステムサービス flir.lepton_camera_service（system_server の com.android.server.lepton.LeptonService）を直接呼ぶ。
 *
 * 呼び方の根拠（2026-10-18、BL8800Pro の framework.jar / services.jar と FLIR のテストアプリを逆コンパイルして確認）:
 * - インターフェース名 com.flir.sdk.internal.ILeptonCameraService、トランザクション番号は AIDL の Stub と同じ。
 * - open(token, 名前) は呼び出し元の権限を確かめず、Lepton の電源と SPI を入れる。戻り値 0 が成功。
 * - release() で SPI と電源を切る。token が死んだとき（アプリが落ちたとき）もサービスが release する。
 * - FLIR の非公開ライブラリ（liblepton_sdk.so）は使わない。I2C のコマンドは [Cci] で自前に組み立てる。
 */
class LeptonService private constructor(private val binder: IBinder) {
    private val token = Binder()

    fun open(clientName: String): Int = call(TX_OPEN, { writeStrongBinder(token); writeString(clientName) }) { readInt() }
    fun release() = call(TX_RELEASE, {}) { }
    /** 0 = まだ, 1 = 次のフレームあり, それ以外 = 異常 */
    fun frameStatus(): Int = call(TX_FRAME_STATUS, {}) { readInt() }
    fun i2cWrite(address: Int, data: ByteArray): Int = call(TX_I2C_WRITE, { writeInt(address); writeByteArray(data) }) { readInt() }
    fun i2cRead(address: Int, words: Int): ByteArray? = call(TX_I2C_READ, { writeInt(address); writeInt(words) }) { createByteArray() }
    fun readFrame(): ByteArray? = call(TX_READ_FRAME, {}) { createByteArray() }
    fun visCamId(): Int = call(TX_VIS_CAM_ID, {}) { readInt() }
    fun modelId(): String? = call(TX_MODEL_ID, {}) { readString() }

    private inline fun <T> call(code: Int, write: Parcel.() -> Unit, read: Parcel.() -> T): T {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(DESCRIPTOR)
            data.write()
            binder.transact(code, data, reply, 0)
            reply.readException()
            return reply.read()
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    companion object {
        const val SERVICE_NAME = "flir.lepton_camera_service"
        private const val DESCRIPTOR = "com.flir.sdk.internal.ILeptonCameraService"
        private const val TX_OPEN = IBinder.FIRST_CALL_TRANSACTION + 0
        private const val TX_RELEASE = IBinder.FIRST_CALL_TRANSACTION + 1
        private const val TX_FRAME_STATUS = IBinder.FIRST_CALL_TRANSACTION + 2
        private const val TX_I2C_WRITE = IBinder.FIRST_CALL_TRANSACTION + 3
        private const val TX_I2C_READ = IBinder.FIRST_CALL_TRANSACTION + 4
        private const val TX_READ_FRAME = IBinder.FIRST_CALL_TRANSACTION + 5
        private const val TX_VIS_CAM_ID = IBinder.FIRST_CALL_TRANSACTION + 8
        private const val TX_MODEL_ID = IBinder.FIRST_CALL_TRANSACTION + 9

        /**
         * サービスの IBinder を得る。公開 API では得られないため ServiceManager.getService を反射で呼ぶ
         * （FLIR 純正アプリ・テストアプリも同じ方法。Play ストア版の MyFLIR も普通のアプリとしてこれで動いている）。
         * 端末にサービスが無い・呼べないときは null。
         */
        @SuppressLint("PrivateApi", "DiscouragedPrivateApi") // 上の理由。代わりの公開 API が無い
        fun connect(): LeptonService? = try {
            val sm = Class.forName("android.os.ServiceManager")
            val binder = sm.getMethod("getService", String::class.java).invoke(null, SERVICE_NAME) as IBinder?
            binder?.let { LeptonService(it) }
        } catch (e: ReflectiveOperationException) {
            null
        }
    }
}
