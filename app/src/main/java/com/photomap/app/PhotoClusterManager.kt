package com.photomap.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import androidx.core.content.ContextCompat
import org.osmdroid.events.DelayedMapListener
import org.osmdroid.events.MapListener
import org.osmdroid.events.ScrollEvent
import org.osmdroid.events.ZoomEvent
import org.osmdroid.util.GeoPoint
import org.osmdroid.util.TileSystem
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.FolderOverlay
import org.osmdroid.views.overlay.Marker
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.tan

/**
 * 地圖上一個點：一張或多張靠在一起的相片。
 * [cover] 是標記上顯示的那張相片，標記就放在它真正的拍攝位置上。
 */
class PhotoCluster(val photos: List<Photo>, val cover: Photo)

/**
 * 把相片依目前縮放等級分組（畫面上太近的合成一個點），
 * 並以相片縮圖 + 數量標記畫在地圖上。
 */
class PhotoClusterManager(
    private val map: MapView,
    private val thumbs: ThumbnailLoader,
    private val onClusterClick: (PhotoCluster) -> Unit,
) {
    private class Pt(val photo: Photo, val x: Double, val y: Double)

    private val context: Context = map.context
    private val density = context.resources.displayMetrics.density
    private val cellPx = 72 * density
    private val iconSize = 52 * density
    private val border = 3 * density
    private val corner = 8 * density
    private val badgeR = 10 * density
    private val pointerW = 14 * density
    private val pointerH = 9 * density

    private val folder = FolderOverlay()
    private var points: List<Pt> = emptyList()
    private var markersByCover = HashMap<Long, MutableList<Pair<Marker, Int>>>()

    private val framePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x55000000
        style = Paint.Style.STROKE
        strokeWidth = density
    }
    private val placeholderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFB0BEC5.toInt() }
    private val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.badge)
    }
    private val badgeStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 11 * density
        typeface = Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.CENTER
    }

    init {
        map.overlays.add(folder)
        map.addMapListener(DelayedMapListener(object : MapListener {
            override fun onScroll(event: ScrollEvent?): Boolean { refresh(); return false }
            override fun onZoom(event: ZoomEvent?): Boolean { refresh(); return false }
        }, 120))
    }

    fun setPhotos(photos: List<Photo>) {
        points = photos.map { Pt(it, mercX(it.lon), mercY(it.lat)) }
        refresh()
    }

    fun refresh() {
        if (map.width == 0 || map.height == 0) {
            map.post { refresh() }
            return
        }
        val zoom = map.zoomLevelDouble
        val cell = cellPx / TileSystem.MapSize(zoom)

        // 1. 依格子分組
        val groups = LinkedHashMap<Long, MutableList<Pt>>()
        for (p in points) {
            val gx = floor(p.x / cell).toLong()
            val gy = floor(p.y / cell).toLong()
            groups.getOrPut((gx shl 32) xor (gy and 0xffffffffL)) { ArrayList() } += p
        }

        // 2. 只畫出畫面附近的點
        val bb = map.boundingBox
        val wrap = bb.lonWest > bb.lonEast
        val x0 = mercX(bb.lonWest); val x1 = mercX(bb.lonEast)
        val y0 = mercY(bb.latNorth); val y1 = mercY(bb.latSouth)
        val mx = (x1 - x0) * 0.5; val my = (y1 - y0) * 0.5

        val clusters = ArrayList<PhotoCluster>()
        for (g in groups.values) {
            var sx = 0.0; var sy = 0.0
            for (p in g) { sx += p.x; sy += p.y }
            val cx = sx / g.size; val cy = sy / g.size
            // 標記放在最接近群組中心的那張真實相片上，而不是平均位置
            val center = g.minBy { (it.x - cx) * (it.x - cx) + (it.y - cy) * (it.y - cy) }
            if (!wrap && (center.x < x0 - mx || center.x > x1 + mx || center.y < y0 - my || center.y > y1 + my)) continue
            clusters += PhotoCluster(g.map { it.photo }, center.photo)
        }
        clusters.sortBy { it.photos.size } // 大的群組畫在最上面

        // 3. 建立標記
        folder.items.clear()
        val newMarkers = HashMap<Long, MutableList<Pair<Marker, Int>>>()
        for (c in clusters) {
            val count = c.photos.size
            val marker = Marker(map).apply {
                position = GeoPoint(c.cover.lat, c.cover.lon)
                val icon = makeIcon(thumbs.get(c.cover.id), count)
                setIcon(BitmapDrawable(context.resources, icon))
                setAnchor(iconAnchorU(icon), iconAnchorV(icon))
                setOnMarkerClickListener { _, _ -> onClusterClick(c); true }
            }
            folder.add(marker)
            newMarkers.getOrPut(c.cover.id) { ArrayList() } += marker to count
            if (thumbs.get(c.cover.id) == null) {
                thumbs.load(c.cover) { bmp -> if (bmp != null) onThumbLoaded(c.cover.id, bmp) }
            }
        }
        markersByCover = newMarkers
        map.invalidate()
    }

    private fun onThumbLoaded(id: Long, bmp: Bitmap) {
        val list = markersByCover[id] ?: return
        for ((marker, count) in list) {
            marker.setIcon(BitmapDrawable(context.resources, makeIcon(bmp, count)))
        }
        map.invalidate()
    }

    // 對齊點 = 下方尖角的尖端，也就是相片的拍攝位置
    private fun iconAnchorU(icon: Bitmap) = (iconSize / 2) / icon.width
    private fun iconAnchorV(icon: Bitmap) = tipY() / icon.height
    private fun tipY() = badgeR + iconSize + pointerH

    /** 畫出「白框圓角縮圖 + 下方尖角（指向拍攝點）+ 右上角數量」的圖釘式標記。 */
    private fun makeIcon(thumb: Bitmap?, count: Int): Bitmap {
        val w = (iconSize + badgeR * 1.6f).toInt()
        val h = (tipY() + 2 * density).toInt()
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)

        val frame = RectF(0f, badgeR, iconSize, badgeR + iconSize)
        val cx = iconSize / 2
        val shape = Path().apply {
            addRoundRect(frame, corner, corner, Path.Direction.CW)
            val pointer = Path().apply {
                moveTo(cx - pointerW / 2, frame.bottom - corner)
                lineTo(cx + pointerW / 2, frame.bottom - corner)
                lineTo(cx, tipY())
                close()
            }
            op(pointer, Path.Op.UNION)
        }
        canvas.drawPath(shape, framePaint)
        canvas.drawPath(shape, outlinePaint)

        val inner = RectF(frame.left + border, frame.top + border, frame.right - border, frame.bottom - border)
        val innerCorner = corner - border / 2
        if (thumb != null) {
            val shader = BitmapShader(thumb, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            val m = Matrix()
            m.setRectToRect(
                RectF(0f, 0f, thumb.width.toFloat(), thumb.height.toFloat()), inner, Matrix.ScaleToFit.FILL,
            )
            shader.setLocalMatrix(m)
            val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { this.shader = shader }
            canvas.drawRoundRect(inner, innerCorner, innerCorner, p)
        } else {
            canvas.drawRoundRect(inner, innerCorner, innerCorner, placeholderPaint)
        }

        if (count > 1) {
            val text = if (count > 999) "999+" else count.toString()
            val pillW = max(badgeR * 2, textPaint.measureText(text) + 10 * density)
            val right = w - density
            val pill = RectF(right - pillW, density, right, density + badgeR * 2 - density)
            canvas.drawRoundRect(pill, badgeR, badgeR, badgePaint)
            canvas.drawRoundRect(pill, badgeR, badgeR, badgeStroke)
            val baseline = pill.centerY() - (textPaint.descent() + textPaint.ascent()) / 2
            canvas.drawText(text, pill.centerX(), baseline, textPaint)
        }
        return bmp
    }

    companion object {
        private const val MAX_LAT = 85.05112878

        fun mercX(lon: Double) = (lon + 180.0) / 360.0
        fun mercY(lat: Double): Double {
            val r = Math.toRadians(lat.coerceIn(-MAX_LAT, MAX_LAT))
            return (1.0 - ln(tan(r) + 1.0 / cos(r)) / PI) / 2.0
        }
    }
}
