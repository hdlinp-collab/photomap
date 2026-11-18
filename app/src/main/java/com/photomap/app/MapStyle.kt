package com.photomap.app

import org.osmdroid.tileprovider.tilesource.ITileSource
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.MapTileIndex

/** 可選的底圖。[gcj] 表示這張地圖使用 GCJ-02 座標，相片要先轉換才對得上。 */
enum class MapStyle(
    val labelRes: Int,
    val creditRes: Int,
    val maxZoom: Double,
    val gcj: Boolean,
) {
    AMAP(R.string.style_amap, R.string.credit_amap, 18.0, gcj = true),
    OSM(R.string.style_osm, R.string.osm_credit, 19.0, gcj = false);

    fun tileSource(): ITileSource = when (this) {
        OSM -> TileSourceFactory.MAPNIK
        AMAP -> AMapTileSource
    }
}

/** 高德地圖道路圖圖磚（GCJ-02）。 */
private object AMapTileSource : OnlineTileSourceBase(
    "AMap", 3, 18, 256, ".png",
    arrayOf(
        "https://webrd01.is.autonavi.com/appmaptile",
        "https://webrd02.is.autonavi.com/appmaptile",
        "https://webrd03.is.autonavi.com/appmaptile",
        "https://webrd04.is.autonavi.com/appmaptile",
    ),
) {
    override fun getTileURLString(pMapTileIndex: Long): String =
        baseUrl + "?lang=zh_cn&size=1&scale=1&style=8" +
            "&x=" + MapTileIndex.getX(pMapTileIndex) +
            "&y=" + MapTileIndex.getY(pMapTileIndex) +
            "&z=" + MapTileIndex.getZoom(pMapTileIndex)
}
