package com.photomap.app

import android.content.Context
import android.graphics.Bitmap
import android.media.ThumbnailUtils
import android.os.Build
import android.provider.MediaStore
import android.util.LruCache
import android.util.Size
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 非同步載入並快取相片的正方形縮圖。所有方法都要在主執行緒呼叫。 */
class ThumbnailLoader(
    private val context: Context,
    private val scope: CoroutineScope,
) {
    private val sizePx = (96 * context.resources.displayMetrics.density).toInt()

    private val cache = object : LruCache<Long, Bitmap>(
        (Runtime.getRuntime().maxMemory() / 1024 / 8).toInt(),
    ) {
        override fun sizeOf(key: Long, value: Bitmap): Int = value.byteCount / 1024
    }

    private val inFlight = HashMap<Long, MutableList<(Bitmap?) -> Unit>>()
    private val io = Dispatchers.IO.limitedParallelism(4)

    fun get(id: Long): Bitmap? = cache.get(id)

    fun load(photo: Photo, callback: (Bitmap?) -> Unit) {
        cache.get(photo.id)?.let { callback(it); return }
        inFlight[photo.id]?.let { it += callback; return }
        inFlight[photo.id] = mutableListOf(callback)
        scope.launch {
            val bmp = withContext(io) { decode(photo) }
            if (bmp != null) cache.put(photo.id, bmp)
            inFlight.remove(photo.id)?.forEach { it(bmp) }
        }
    }

    @Suppress("DEPRECATION")
    private fun decode(photo: Photo): Bitmap? = try {
        val raw = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            context.contentResolver.loadThumbnail(photo.uri, Size(sizePx, sizePx), null)
        } else {
            MediaStore.Images.Thumbnails.getThumbnail(
                context.contentResolver, photo.id, MediaStore.Images.Thumbnails.MINI_KIND, null,
            )
        }
        raw?.let {
            ThumbnailUtils.extractThumbnail(it, sizePx, sizePx, ThumbnailUtils.OPTIONS_RECYCLE_INPUT)
        }
    } catch (e: Exception) {
        null
    }
}
