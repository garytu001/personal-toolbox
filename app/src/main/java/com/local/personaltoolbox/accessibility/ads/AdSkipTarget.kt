package com.local.personaltoolbox.accessibility.ads

internal data class TapBounds(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width get() = right - left
    val height get() = bottom - top
    fun contains(point: TapPoint) = point.x >= left && point.x < right && point.y >= top && point.y < bottom
}
internal data class TapPoint(val x: Float, val y: Float)
internal data class AdSkipTarget(val windowId: Int, val identity: String, val bounds: TapBounds)
