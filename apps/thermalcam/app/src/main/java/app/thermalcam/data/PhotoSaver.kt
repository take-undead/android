package app.thermalcam.data

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import app.thermalcam.thermal.ThermalFrame
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.OutputStream
import java.util.Locale

/**
 * 写真（JPEG）と全画素の温度（CSV、画面の縦持ちの向きで 60 列 × 80 行、℃）を保存する。権限は要らない（esp32cam の PhotoSaver と同じ考え方）:
 *  - Android 10 以上: MediaStore で 写真は「ピクチャ/ThermalCam」、CSV は「ドキュメント/ThermalCam」
 *  - それより前: アプリ専用のフォルダ（Android/data/app.thermalcam/files/…/ThermalCam）
 * 戻り値は保存した写真の場所の説明
 */
object PhotoSaver {
    const val ALBUM = "ThermalCam"

    class SaveError(message: String) : Exception(message)

    suspend fun save(context: Context, baseName: String, image: Bitmap, frame: ThermalFrame, orientedRows: List<FloatArray>): String =
        withContext(Dispatchers.IO) {
            val where = write(context, "$baseName.jpg", "image/jpeg", Environment.DIRECTORY_PICTURES, MediaStore.Images.Media.EXTERNAL_CONTENT_URI) {
                image.compress(Bitmap.CompressFormat.JPEG, 95, it)
            }
            val csvUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) MediaStore.Files.getContentUri("external") else null
            write(context, "$baseName.csv", "text/csv", Environment.DIRECTORY_DOCUMENTS, csvUri) { out ->
                val sb = StringBuilder()
                // 画面で見たときと同じ向きの行・列で書く。1 行目に FPA 温度などの注記を # で入れる
                sb.append(String.format(Locale.US, "# ThermalCam %s, FPA %.2f C, frame %d, unit C\n", baseName, frame.fpaC, frame.frameCounter))
                for (row in orientedRows) {
                    row.joinTo(sb, ",") { String.format(Locale.US, "%.2f", it) }
                    sb.append('\n')
                }
                out.write(sb.toString().toByteArray())
            }
            where
        }

    /** 写真だけを保存する（暗所カメラ用。温度の CSV は無い） */
    suspend fun saveImage(context: Context, baseName: String, image: Bitmap): String = withContext(Dispatchers.IO) {
        write(context, "$baseName.jpg", "image/jpeg", Environment.DIRECTORY_PICTURES, MediaStore.Images.Media.EXTERNAL_CONTENT_URI) {
            image.compress(Bitmap.CompressFormat.JPEG, 92, it)
        }
    }

    /** カメラが作った JPEG をそのまま保存する（最高画質） */
    suspend fun saveJpegBytes(context: Context, baseName: String, jpeg: ByteArray): String = withContext(Dispatchers.IO) {
        write(context, "$baseName.jpg", "image/jpeg", Environment.DIRECTORY_PICTURES, MediaStore.Images.Media.EXTERNAL_CONTENT_URI) { it.write(jpeg) }
    }

    private fun write(context: Context, name: String, mime: String, dirType: String, collection: Uri?, body: (OutputStream) -> Unit): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && collection != null) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, "$dirType/$ALBUM")
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(collection, values) ?: throw SaveError(name)
            resolver.openOutputStream(uri)?.use(body) ?: throw SaveError(name)
            return "$dirType/$ALBUM/$name"
        }
        val dir = File(context.getExternalFilesDir(dirType), ALBUM)
        if (!dir.exists() && !dir.mkdirs()) throw SaveError(dir.toString())
        File(dir, name).outputStream().use(body)
        return "Android/data/${context.packageName}/files/$dirType/$ALBUM/$name"
    }
}
