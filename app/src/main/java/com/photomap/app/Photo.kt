package com.photomap.app

import android.net.Uri

/** 一張帶有拍攝位置的相片。 */
data class Photo(
    val id: Long,
    val uri: Uri,
    val lat: Double,
    val lon: Double,
)
