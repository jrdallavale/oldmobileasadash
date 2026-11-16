package com.oldmobile.biketracker

import java.io.DataInputStream
import java.io.InputStream

/**
 * Offline vector map of the ride box, extracted from the Mapsforge "sul" map by tools/mapextract.
 * Coordinates are converted once to local meters (Area.x / Area.y) so drawing is a scale + offset.
 * Classes: 0 water area, 1 wood/park area, 2 river, 3 rail, 4 track/path/cycleway,
 * 5 minor road, 6 tertiary, 7 secondary, 8 primary/trunk/motorway.
 */
class MapData(input: InputStream) {
    val cls: ByteArray
    val start: IntArray      // first float index in xy
    val count: IntArray      // number of points
    val xy: FloatArray
    val bbox: FloatArray     // minX, minY, maxX, maxY per feature
    val labelX: FloatArray
    val labelY: FloatArray
    val labelRank: ByteArray
    val labelText: Array<String>

    init {
        val d = DataInputStream(input.buffered(1 shl 16))
        require(d.readInt() == 0x424B4D31) { "bad map file" }
        val minLat = d.readDouble(); val minLon = d.readDouble(); d.readDouble(); d.readDouble()
        val n = d.readInt()
        cls = ByteArray(n); start = IntArray(n); count = IntArray(n); bbox = FloatArray(n * 4)
        var buf = FloatArray(200_000)
        var used = 0
        for (i in 0 until n) {
            cls[i] = d.readByte()
            val k = d.readInt()
            if (used + k * 2 > buf.size) buf = buf.copyOf(maxOf(buf.size * 2, used + k * 2))
            start[i] = used; count[i] = k
            var x0 = Float.MAX_VALUE; var y0 = Float.MAX_VALUE; var x1 = -Float.MAX_VALUE; var y1 = -Float.MAX_VALUE
            for (j in 0 until k) {
                val lat = minLat + d.readUnsignedShort() / 1e5
                val lon = minLon + d.readUnsignedShort() / 1e5
                val x = Area.x(lon).toFloat(); val y = Area.y(lat).toFloat()
                buf[used++] = x; buf[used++] = y
                if (x < x0) x0 = x; if (x > x1) x1 = x; if (y < y0) y0 = y; if (y > y1) y1 = y
            }
            bbox[i * 4] = x0; bbox[i * 4 + 1] = y0; bbox[i * 4 + 2] = x1; bbox[i * 4 + 3] = y1
        }
        xy = buf.copyOf(used)
        val ln = d.readInt()
        labelX = FloatArray(ln); labelY = FloatArray(ln); labelRank = ByteArray(ln)
        labelText = Array(ln) {
            val lat = minLat + d.readUnsignedShort() / 1e5
            val lon = minLon + d.readUnsignedShort() / 1e5
            labelY[it] = Area.y(lat).toFloat(); labelX[it] = Area.x(lon).toFloat()
            labelRank[it] = d.readByte()
            d.readUTF()
        }
    }

    val size get() = cls.size
}
