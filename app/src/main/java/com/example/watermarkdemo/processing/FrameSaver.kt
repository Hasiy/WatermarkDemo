package com.example.watermarkdemo.processing

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.os.Environment
import android.provider.MediaStore
import timber.log.Timber

/** 把 Bitmap 以無損 PNG 存進 Pictures/WatermarkDemo，供桌面端拿原始幀驗證演算法。 */
object FrameSaver {

    private const val ALBUM_DIR = "WatermarkDemo"
    private const val PNG_QUALITY = 100

    /**
     * 有副作用：寫入系統媒體庫。
     *
     * @param name 不含副檔名的檔名
     * @return 成功回傳 true；失敗已寫 Log 並回傳 false
     */
    fun savePng(context: Context, bitmap: Bitmap, name: String): Boolean {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "$name.png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/$ALBUM_DIR")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        if (uri == null) {
            Timber.e("insert MediaStore failed: %s", name)
            return false
        }
        return try {
            val written = resolver.openOutputStream(uri)?.use {
                bitmap.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, it)
            } ?: false
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            written
        } catch (e: Exception) {
            Timber.e(e, "save png failed: %s", name)
            resolver.delete(uri, null, null)
            false
        }
    }
}
