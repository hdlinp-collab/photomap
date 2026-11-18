package com.photomap.app

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 中國大陸的地圖（高德、騰訊、Google 中國道路圖…）使用 GCJ-02「火星座標」，
 * 和 GPS / 相片 EXIF 的 WGS-84 座標差約數百公尺。這裡把 WGS-84 轉成 GCJ-02。
 */
object ChinaCoord {
    private const val A = 6378245.0
    private const val EE = 0.00669342162296594323

    /** 不在中國大陸境內的座標不需要轉換（台灣也不轉）。 */
    fun needsShift(lat: Double, lon: Double): Boolean {
        if (lon < 72.004 || lon > 137.8347 || lat < 0.8293 || lat > 55.8271) return false
        if (lon in 119.3..122.3 && lat in 21.8..25.4) return false // 台灣
        return true
    }

    /** 回傳 [lat, lon]。 */
    fun wgsToGcj(lat: Double, lon: Double): DoubleArray {
        if (!needsShift(lat, lon)) return doubleArrayOf(lat, lon)
        var dLat = transformLat(lon - 105.0, lat - 35.0)
        var dLon = transformLon(lon - 105.0, lat - 35.0)
        val radLat = lat / 180.0 * PI
        var magic = sin(radLat)
        magic = 1 - EE * magic * magic
        val sqrtMagic = sqrt(magic)
        dLat = (dLat * 180.0) / ((A * (1 - EE)) / (magic * sqrtMagic) * PI)
        dLon = (dLon * 180.0) / (A / sqrtMagic * cos(radLat) * PI)
        return doubleArrayOf(lat + dLat, lon + dLon)
    }

    private fun transformLat(x: Double, y: Double): Double {
        var ret = -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y + 0.1 * x * y + 0.2 * sqrt(abs(x))
        ret += (20.0 * sin(6.0 * x * PI) + 20.0 * sin(2.0 * x * PI)) * 2.0 / 3.0
        ret += (20.0 * sin(y * PI) + 40.0 * sin(y / 3.0 * PI)) * 2.0 / 3.0
        ret += (160.0 * sin(y / 12.0 * PI) + 320.0 * sin(y * PI / 30.0)) * 2.0 / 3.0
        return ret
    }

    private fun transformLon(x: Double, y: Double): Double {
        var ret = 300.0 + x + 2.0 * y + 0.1 * x * x + 0.1 * x * y + 0.1 * sqrt(abs(x))
        ret += (20.0 * sin(6.0 * x * PI) + 20.0 * sin(2.0 * x * PI)) * 2.0 / 3.0
        ret += (20.0 * sin(x * PI) + 40.0 * sin(x / 3.0 * PI)) * 2.0 / 3.0
        ret += (150.0 * sin(x / 12.0 * PI) + 300.0 * sin(x / 30.0 * PI)) * 2.0 / 3.0
        return ret
    }
}
