package com.oldmobile.biketracker

import android.graphics.*
import kotlin.math.cos
import kotlin.math.pow

/** North-up dark map: grey roads, dark blue water, small labels, yellow track, white arrow. */
class MapRenderer(private val s: Screen) {
    private var seg = FloatArray(1 shl 16)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val path = Path()

    private val colors = intArrayOf(0xFF0B1F3A.toInt(), 0xFF0A140A.toInt(), 0xFF1B3A6B.toInt(), 0xFF3A3A3A.toInt(),
        0xFF383838.toInt(), 0xFF505050.toInt(), 0xFF6A6A6A.toInt(), 0xFF858585.toInt(), 0xFFA8A8A8.toInt())
    private val widths = floatArrayOf(0f, 0f, 2f, 1.5f, 1.5f, 2f, 3f, 4f, 5f)

    /** Meters per screen pixel for a web-mercator-like zoom level (256 dp tiles). */
    fun metersPerPx(zoom: Int) = 156_543.03 * cos(Math.toRadians(Area.CENTER_LAT)) / 2.0.pow(zoom) / s.dp(1f)

    fun draw(c: Canvas, r: RectF, cx: Double, cy: Double, zoom: Int, posValid: Boolean, bearing: Float, track: LiveTrack?) {
        val m = App.instance.map
        c.save(); c.clipRect(r)
        val mpp = metersPerPx(zoom).toFloat()
        val sx = r.centerX(); val sy = r.centerY()
        val halfW = r.width() / 2 * mpp; val halfH = r.height() / 2 * mpp
        val vx0 = (cx - halfW).toFloat(); val vx1 = (cx + halfW).toFloat()
        val vy0 = (cy - halfH).toFloat(); val vy1 = (cy + halfH).toFloat()
        fun px(x: Float) = sx + (x - cx.toFloat()) / mpp
        fun py(y: Float) = sy - (y - cy.toFloat()) / mpp

        if (m == null) {
            s.text(c, "loading map…", r.centerX(), r.centerY(), 16f, C.LABEL, Paint.Align.CENTER)
        } else {
            var curCls = -1; var n = 0
            fun flush() {
                if (n > 0 && curCls >= 2) {
                    line.color = colors[curCls]; line.strokeWidth = s.dp(widths[curCls])
                    c.drawLines(seg, 0, n, line)
                }
                n = 0
            }
            for (i in 0 until m.size) {
                val b = i * 4
                if (m.bbox[b] > vx1 || m.bbox[b + 2] < vx0 || m.bbox[b + 1] > vy1 || m.bbox[b + 3] < vy0) continue
                val cl = m.cls[i].toInt()
                if (cl != curCls) { flush(); curCls = cl }
                val st = m.start[i]; val k = m.count[i]
                if (cl <= 1) {
                    path.rewind()
                    for (j in 0 until k) {
                        val x = px(m.xy[st + j * 2]); val y = py(m.xy[st + j * 2 + 1])
                        if (j == 0) path.moveTo(x, y) else path.lineTo(x, y)
                    }
                    path.close(); fill.color = colors[cl]; c.drawPath(path, fill)
                } else {
                    if (n + k * 4 > seg.size) { flush(); if (k * 4 > seg.size) seg = FloatArray(k * 4 + 16) }
                    var lx = px(m.xy[st]); var ly = py(m.xy[st + 1])
                    for (j in 1 until k) {
                        val x = px(m.xy[st + j * 2]); val y = py(m.xy[st + j * 2 + 1])
                        seg[n++] = lx; seg[n++] = ly; seg[n++] = x; seg[n++] = y
                        lx = x; ly = y
                    }
                }
            }
            flush()
            // labels
            for (i in m.labelText.indices) {
                val rank = m.labelRank[i].toInt()
                if (rank == 1 && zoom < 13 || rank == 2 && zoom < 15) continue
                val x = px(m.labelX[i]); val y = py(m.labelY[i])
                if (!r.contains(x, y)) continue
                s.text(c, m.labelText[i], x, y, if (rank == 0) 15f else 12f, 0xFF9A9A9A.toInt(), Paint.Align.CENTER, rank == 0)
            }
        }

        // 10 km home ring
        line.color = 0x55FFD400; line.strokeWidth = s.dp(1.5f)
        c.drawCircle(px(0f), py(0f), (Area.RADIUS_M / mpp).toFloat(), line)

        // track
        if (track != null) synchronized(track) {
            val tn = track.x.size
            if (tn > 1) {
                path.rewind()
                for (i in 0 until tn) { val x = px(track.x[i]); val y = py(track.y[i]); if (i == 0) path.moveTo(x, y) else path.lineTo(x, y) }
                line.color = C.ACCENT; line.strokeWidth = s.dp(4f); line.strokeJoin = Paint.Join.ROUND
                c.drawPath(path, line)
            }
        }

        // position arrow
        if (posValid) {
            val ax = px(cx.toFloat()); val ay = py(cy.toFloat())
            c.save(); c.translate(ax, ay); c.rotate(bearing)
            val u = s.dp(14f)
            path.rewind(); path.moveTo(0f, -u * 1.4f); path.lineTo(u, u); path.lineTo(0f, u * 0.4f); path.lineTo(-u, u); path.close()
            fill.color = C.FG; c.drawPath(path, fill)
            line.color = C.BG; line.strokeWidth = s.dp(2f); c.drawPath(path, line)
            c.restore()
        }
        c.restore()
    }
}
