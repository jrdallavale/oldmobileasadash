package com.oldmobile.biketracker

import java.io.DataInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.floor

/**
 * SRTM 1 arc-second heights (tile S26W050, cropped to the ride area by tools/terrain_crop.py).
 * Bilinear interpolation between the four surrounding 30 m grid posts.
 */
class Terrain(input: InputStream) {
    private val north: Double
    private val west: Double
    private val rows: Int
    private val cols: Int
    private val step: Double
    private val h: ShortArray

    init {
        val d = DataInputStream(input.buffered(1 shl 16))
        require(d.readInt() == 0x424B5431) { "bad terrain file" }
        north = d.readDouble(); west = d.readDouble()
        rows = d.readInt(); cols = d.readInt(); step = d.readDouble()
        val bytes = ByteArray(rows * cols * 2)
        d.readFully(bytes)
        h = ShortArray(rows * cols)
        ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN).asShortBuffer().get(h)
    }

    /** Height in meters, or null outside the tile / on voids (then the caller uses GPS altitude). */
    fun height(lat: Double, lon: Double): Double? {
        val fr = (north - lat) / step
        val fc = (lon - west) / step
        if (fr < 0 || fc < 0 || fr > rows - 1 || fc > cols - 1) return null
        val r0 = floor(fr).toInt().coerceAtMost(rows - 2)
        val c0 = floor(fc).toInt().coerceAtMost(cols - 2)
        val dr = fr - r0; val dc = fc - c0
        val a = h[r0 * cols + c0]; val b = h[r0 * cols + c0 + 1]
        val c = h[(r0 + 1) * cols + c0]; val e = h[(r0 + 1) * cols + c0 + 1]
        if (a == VOID || b == VOID || c == VOID || e == VOID) return null
        val top = a + (b - a) * dc
        val bot = c + (e - c) * dc
        return top + (bot - top) * dr
    }

    companion object { private const val VOID: Short = -32768 }
}
