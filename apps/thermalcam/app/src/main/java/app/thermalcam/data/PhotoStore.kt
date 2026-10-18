package app.thermalcam.data

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** 保存した写真 1 枚 */
data class Photo(val uri: Uri, val name: String, val takenMs: Long)

/**
 * このアプリが保存した写真（PhotoSaver と同じ場所）を読む。権限は要らない:
 *  - Android 10 以上: MediaStore の「ピクチャ/ThermalCam」。自分が入れた写真は権限なしで読める
 *    （アプリを消して入れ直すと、それより前の写真はこのアプリの物と見なされず一覧に出ない。端末のギャラリーでは見える）
 *  - それより前: アプリ専用の Pictures/ThermalCam
 */
object PhotoStore {
    suspend fun list(context: Context): List<Photo> = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val col = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            val projection = arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME, MediaStore.Images.Media.DATE_ADDED)
            val where = "${MediaStore.Images.Media.RELATIVE_PATH} = ?"
            val args = arrayOf("${Environment.DIRECTORY_PICTURES}/${PhotoSaver.ALBUM}/")
            context.contentResolver.query(col, projection, where, args, "${MediaStore.Images.Media.DATE_ADDED} DESC")?.use { c ->
                val iId = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val iName = c.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                val iDate = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
                buildList {
                    while (c.moveToNext()) {
                        add(Photo(ContentUris.withAppendedId(col, c.getLong(iId)), c.getString(iName), c.getLong(iDate) * 1000))
                    }
                }
            } ?: emptyList()
        } else {
            val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_PICTURES), PhotoSaver.ALBUM)
            (dir.listFiles { f -> f.name.endsWith(".jpg") } ?: emptyArray())
                .sortedByDescending { it.lastModified() }
                .map { Photo(Uri.fromFile(it), it.name, it.lastModified()) }
        }
    }

    /**
     * 写真と、同じ名前の CSV（ドキュメント/ThermalCam）を消す。消せなかった枚数を返す。
     * 自分が入れたファイルなので権限も確認画面も要らない（Android 10 以上の MediaStore の決まり）
     */
    suspend fun delete(context: Context, photos: List<Photo>): Int = withContext(Dispatchers.IO) {
        val r = context.contentResolver
        var failed = 0
        for (p in photos) {
            val csvName = p.name.removeSuffix(".jpg") + ".csv"
            val ok = runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val n = r.delete(p.uri, null, null)
                    r.delete(
                        MediaStore.Files.getContentUri("external"),
                        "${MediaStore.MediaColumns.DISPLAY_NAME} = ? AND ${MediaStore.MediaColumns.RELATIVE_PATH} = ?",
                        arrayOf(csvName, "${Environment.DIRECTORY_DOCUMENTS}/${PhotoSaver.ALBUM}/"),
                    )
                    n > 0
                } else {
                    val jpg = File(requireNotNull(p.uri.path))
                    File(File(context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS), PhotoSaver.ALBUM), csvName).delete()
                    jpg.delete()
                }
            }.getOrDefault(false)
            if (!ok) failed++
        }
        failed
    }

    /** 長い辺が maxSize 程度になるように縮めて読む（一覧の小さい絵は小さく読んでメモリを使わない） */
    suspend fun load(context: Context, uri: Uri, maxSize: Int): Bitmap? = withContext(Dispatchers.IO) {
        runCatching {
            val r = context.contentResolver
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            r.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSize) sample *= 2
            r.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
        }.getOrNull()
    }
}
