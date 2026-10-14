package app.esp32cam.data

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 写真を端末に保存する（元の Python の「PC に保存」）。権限は要らない:
 *  - Android 10 以上: MediaStore で「ピクチャ/ESP32CAM」に入れる（ギャラリーに出る）
 *  - それより前: アプリ専用の Pictures フォルダ（Android/data/app.esp32cam/files/Pictures/ESP32CAM）
 * 戻り値は保存した場所の説明
 */
object PhotoSaver {
    private const val ALBUM = "ESP32CAM"

    suspend fun save(context: Context, cameraIndex: Int, name: String, jpeg: ByteArray): String = withContext(Dispatchers.IO) {
        // "202609/20260902_114233.jpg" → "cam1_20260902_114233.jpg"
        val fileName = "cam${cameraIndex + 1}_" + name.substringAfterLast('/')
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/" + ALBUM)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: throw ApiError("保存先を作れませんでした。")
            resolver.openOutputStream(uri)?.use { it.write(jpeg) } ?: throw ApiError("保存先を開けませんでした。")
            "ピクチャ/$ALBUM/$fileName"
        } else {
            val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_PICTURES), ALBUM)
            if (!dir.exists() && !dir.mkdirs()) throw ApiError("保存先を作れませんでした: $dir")
            File(dir, fileName).writeBytes(jpeg)
            "Android/data/${context.packageName}/files/Pictures/$ALBUM/$fileName"
        }
    }
}
