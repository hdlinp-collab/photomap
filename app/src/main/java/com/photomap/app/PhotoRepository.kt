package com.photomap.app

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.File
import kotlin.math.abs

data class ScanState(
    val photos: List<Photo>,
    val scanned: Int,
    val total: Int,
    val done: Boolean,
)

/**
 * 掃描手機裡的相片，從 EXIF 讀出 GPS 位置。
 * 讀過的結果會快取到檔案，下次開啟只需要處理新增或修改過的相片。
 */
class PhotoRepository(private val context: Context) {

    private class MediaItem(val id: Long, val modified: Long)

    private class CacheEntry(
        val modified: Long,
        val lat: Double,
        val lon: Double,
        /** 讀取時是否拿得到未被抹除位置的原始檔。 */
        val original: Boolean,
    )

    private val cacheFile = File(context.filesDir, "gps_cache_v1.tsv")

    fun scan(): Flow<ScanState> = flow {
        val canReadOriginal = canReadOriginal()
        val cache = readCache()
        val newCache = HashMap<Long, CacheEntry>()
        val items = queryMediaStore()
        val result = ArrayList<Photo>()
        var lastEmit = 0L

        items.forEachIndexed { index, item ->
            currentCoroutineContext().ensureActive()
            val cached = cache[item.id]
            val entry = if (
                cached != null &&
                cached.modified == item.modified &&
                (cached.original || !canReadOriginal)
            ) {
                cached
            } else {
                readLocation(item.id, item.modified, canReadOriginal)
            }
            newCache[item.id] = entry
            if (!entry.lat.isNaN()) {
                result += Photo(item.id, contentUri(item.id), entry.lat, entry.lon)
            }
            val now = SystemClock.uptimeMillis()
            if (now - lastEmit > 400) {
                lastEmit = now
                emit(ScanState(ArrayList(result), index + 1, items.size, done = false))
            }
        }

        writeCache(newCache)
        emit(ScanState(result, items.size, items.size, done = true))
    }.flowOn(Dispatchers.IO)

    private fun canReadOriginal(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.ACCESS_MEDIA_LOCATION,
            ) == PackageManager.PERMISSION_GRANTED

    private fun contentUri(id: Long): Uri =
        ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)

    private fun queryMediaStore(): List<MediaItem> {
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DATE_MODIFIED,
        )
        val items = ArrayList<MediaItem>()
        context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection,
            null,
            null,
            "${MediaStore.Images.Media.DATE_TAKEN} DESC",
        )?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val modCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_MODIFIED)
            while (c.moveToNext()) {
                items += MediaItem(c.getLong(idCol), c.getLong(modCol))
            }
        }
        return items
    }

    private fun readLocation(id: Long, modified: Long, canReadOriginal: Boolean): CacheEntry {
        var uri = contentUri(id)
        var original = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && canReadOriginal) {
            uri = MediaStore.setRequireOriginal(uri)
            original = true
        }
        val latLon = try {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                ExifInterface(pfd.fileDescriptor).latLong
            }
        } catch (e: Exception) {
            null
        }
        return if (latLon != null && isValid(latLon[0], latLon[1])) {
            CacheEntry(modified, latLon[0], latLon[1], original)
        } else {
            CacheEntry(modified, Double.NaN, Double.NaN, original)
        }
    }

    private fun isValid(lat: Double, lon: Double): Boolean =
        !lat.isNaN() && !lon.isNaN() &&
            abs(lat) <= 90 && abs(lon) <= 180 &&
            !(lat == 0.0 && lon == 0.0) // 被系統抹除的位置會變成 0,0

    private fun readCache(): Map<Long, CacheEntry> {
        val map = HashMap<Long, CacheEntry>()
        if (!cacheFile.exists()) return map
        try {
            cacheFile.forEachLine { line ->
                val p = line.split('\t')
                if (p.size == 5) {
                    map[p[0].toLong()] = CacheEntry(
                        modified = p[1].toLong(),
                        lat = p[2].toDouble(),
                        lon = p[3].toDouble(),
                        original = p[4] == "1",
                    )
                }
            }
        } catch (e: Exception) {
            map.clear()
        }
        return map
    }

    private fun writeCache(map: Map<Long, CacheEntry>) {
        try {
            val tmp = File(cacheFile.path + ".tmp")
            tmp.bufferedWriter().use { w ->
                for ((id, e) in map) {
                    w.append(id.toString()).append('\t')
                        .append(e.modified.toString()).append('\t')
                        .append(e.lat.toString()).append('\t')
                        .append(e.lon.toString()).append('\t')
                        .append(if (e.original) "1" else "0").append('\n')
                }
            }
            tmp.renameTo(cacheFile)
        } catch (_: Exception) {
        }
    }
}
