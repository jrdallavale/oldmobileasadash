package com.oldmobile.biketracker

import android.graphics.*
import kotlin.math.max
import kotlin.math.min

/** Status bar shared by home and ride screens. Hold 3 s anywhere on it for manual SOS. */
fun Screen.statusBar(c: Canvas, s: Snap, sos: Boolean) {
    val h = dp(44f)
    val y = h / 2 + sp(7f)
    fp.color = 0xFF0A0A0A.toInt(); c.drawRect(0f, 0f, width.toFloat(), h, fp)
    var x = dp(12f)
    if (s.mode == Snap.MODE_RECORDING) {
        fp.color = if (s.paused) C.ACCENT else C.REC
        c.drawCircle(x + dp(7f), h / 2, dp(7f), fp)
        x += dp(22f)
        if (s.paused) { text(c, "PAUSED", x, y, 16f, C.ACCENT, bold = true); x += textWidth("PAUSED", 16f, true) + dp(14f) }
    }
    val gps = when {
        s.acc == null -> "GPS –"
        !s.gpsOk -> "GPS ±${s.acc.toInt()} m"
        else -> "GPS ±${s.acc.toInt()} m"
    }
    text(c, gps, x, y, 16f, if (s.gpsOk) C.FG else C.ALERT)
    text(c, Fmt.clock(System.currentTimeMillis()), width / 2f, y, 18f, C.FG, Paint.Align.CENTER, true)
    val tempCol = when (s.thermal) { 2 -> C.ALERT; 1 -> C.ACCENT; else -> C.FG }
    val batt = if (s.battPct >= 0) "${s.battPct}%" + (if (s.charging) "+" else "") else "–"
    val temp = if (s.battTemp.isNaN()) "" else "${Fmt.int(s.battTemp)} °C"
    text(c, temp, width - dp(12f), y, 16f, tempCol, Paint.Align.RIGHT)
    text(c, batt, width - dp(20f) - textWidth(temp, 16f), y, 16f, if (s.lowBattery) C.ALERT else C.FG, Paint.Align.RIGHT)
    if (sos) hit(RectF(0f, 0f, width.toFloat(), h), 3000) { RideService.send(act, RideService.ACTION_SOS) }
}

/** Banners for heat and low battery under the status bar. Returns the height used. */
fun Screen.warnings(c: Canvas, s: Snap, top: Float): Float {
    val msg: String; val col: Int
    when {
        s.thermal == 2 -> { msg = "BATTERY ${Fmt.int(s.battTemp)} °C — DISCONNECT POWER BANK OR SHADE THE PHONE"; col = C.ALERT }
        s.thermal == 1 -> { msg = "Battery ${Fmt.int(s.battTemp)} °C — screen dimmed"; col = C.ACCENT }
        s.lowBattery -> { msg = "Battery ${s.battPct}% and not charging — check the power bank"; col = C.ALERT }
        else -> return 0f
    }
    val h = dp(32f)
    fp.color = col; c.drawRect(0f, top, width.toFloat(), top + h, fp)
    text(c, msg, width / 2f, top + h / 2 + sp(6f), 15f, C.BG, Paint.Align.CENTER, true)
    return h
}

// ---------------------------------------------------------------- 1. GPS lock / home
class HomeScreen(act: MainActivity) : Screen(act) {
    override fun render(c: Canvas) {
        val s = Live.snap
        statusBar(c, s, sos = true)
        val top = dp(44f) + warnings(c, s, dp(44f))
        val w = width.toFloat(); val h = height.toFloat()
        val bottomH = dp(80f)
        val midY = (top + h - bottomH) / 2

        // accuracy on the left
        val acc = s.acc
        val big = if (acc == null || System.currentTimeMillis() - s.fixWall > 5000) "––" else "±${acc.toInt()}"
        text(c, "GPS ACCURACY", w * 0.25f, top + dp(40f), 14f, C.LABEL, Paint.Align.CENTER)
        text(c, big, w * 0.25f, midY + sp(30f), 96f, if (s.gpsOk) C.REC else C.ACCENT, Paint.Align.CENTER, true)
        text(c, "m", w * 0.25f, midY + sp(64f), 14f, C.LABEL, Paint.Align.CENTER)
        text(c, if (s.gpsOk) "GPS locked — hold START" else "waiting for 20 m or better…", w * 0.25f, h - bottomH - dp(16f), 15f, C.LABEL, Paint.Align.CENTER)

        // START, hold 1 s
        val r = min(w * 0.18f, (h - bottomH - top) * 0.42f)
        val cx = w * 0.70f
        val ok = s.gpsOk
        fp.color = if (ok) C.REC else C.DIM
        c.drawCircle(cx, midY, r, fp)
        text(c, "START", cx, midY + sp(14f), 40f, if (ok) C.BG else C.LABEL, Paint.Align.CENTER, true)
        text(c, "hold 1 s", cx, midY + sp(40f), 14f, if (ok) C.BG else C.LABEL, Paint.Align.CENTER)
        if (ok) hit(RectF(cx - r, midY - r, cx + r, midY + r), 1000) { act.startRide() }

        // bottom row
        val bw = (w - dp(48f)) / 3
        val by = h - bottomH + dp(8f)
        button(c, RectF(dp(12f), by, dp(12f) + bw, h - dp(8f)), "HISTORY") { act.show(HistoryScreen(act)) }
        button(c, RectF(dp(24f) + bw, by, dp(24f) + bw * 2, h - dp(8f)), "SETTINGS") { act.showSettings() }
        val unsaved = act.app.db.unsaved()
        if (unsaved.isNotEmpty()) button(c, RectF(dp(36f) + bw * 2, by, w - dp(12f), h - dp(8f)), "RECOVER RIDE", C.ACCENT, C.BG) {
            act.show(ReportScreen(act, unsaved.first().id, summary = true))
        } else text(c, "hold status bar 3 s = SOS", dp(36f) + bw * 2.5f, by + dp(40f), 13f, C.LABEL, Paint.Align.CENTER)
    }
}

// ---------------------------------------------------------------- 2-4. Ride pages
class RideScreen(act: MainActivity) : Screen(act) {
    var page = 0
    private val map = MapRenderer(this)
    private var zoom = act.app.prefs.mapZoom

    override fun onSwipe(dir: Int) { page = (page + dir).coerceIn(0, 2); invalidate() }
    override fun onBack() = true // never leave a ride by accident

    override fun render(c: Canvas) {
        val s = Live.snap
        statusBar(c, s, sos = true)
        val top = dp(44f) + warnings(c, s, dp(44f))
        val bottomH = dp(76f)
        val body = RectF(0f, top, width.toFloat(), height - bottomH)
        when (page) {
            0 -> pageMain(c, s, body)
            1 -> pageMap(c, s, body)
            else -> pageProfile(c, s, body)
        }
        bottomBar(c, s, height - bottomH)
    }

    private fun bottomBar(c: Canvas, s: Snap, y: Float) {
        val w = width.toFloat(); val h = height.toFloat()
        val lap = RectF(dp(8f), y + dp(6f), w * 0.40f, h - dp(6f))
        fp.color = C.DIM; c.drawRoundRect(lap, dp(10f), dp(10f), fp)
        text(c, "LAP ${s.lapN}", lap.left + dp(16f), lap.centerY() + sp(8f), 24f, C.ACCENT, bold = true)
        text(c, Fmt.dist(s.lapDist), lap.left + dp(120f), lap.centerY() + sp(8f), 22f, C.FG)
        text(c, "tap = lap", lap.right - dp(14f), lap.centerY() + sp(6f), 14f, C.LABEL, Paint.Align.RIGHT)
        hit(lap) { RideService.send(act, RideService.ACTION_LAP) }
        // page dots (also tappable)
        val dx = w * 0.5f
        for (i in 0..2) {
            fp.color = if (i == page) C.ACCENT else C.LINE
            c.drawCircle(dx - dp(28f) + i * dp(28f), (y + h) / 2, dp(8f), fp)
        }
        hit(RectF(w * 0.41f, y, w * 0.69f, h)) { page = (page + 1) % 3; invalidate() }
        button(c, RectF(w * 0.70f, y + dp(6f), w - dp(8f), h - dp(6f)), "STOP · hold 1 s", C.ALERT, C.FG, 1000, 22f) { act.stopRide() }
    }

    private fun pageMain(c: Canvas, s: Snap, r: RectF) {
        val split = r.left + r.width() * 0.48f
        // speed
        val spd = Fmt.speed(s.speed)
        tp.typeface = CONDENSED
        val size = 140f
        tp.textSize = sp(size); tp.color = C.FG; tp.textAlign = Paint.Align.CENTER
        val fm = tp.fontMetrics
        val cy = r.centerY() - (fm.ascent + fm.descent) / 2 - dp(14f)
        c.drawText(spd, (r.left + split) / 2, cy, tp)
        text(c, "km/h", (r.left + split) / 2, cy + sp(30f), 18f, C.LABEL, Paint.Align.CENTER)
        text(c, "avg ${Fmt.speed(s.avg)}   max ${Fmt.speed(s.max)}", (r.left + split) / 2, r.bottom - dp(10f), 16f, C.LABEL, Paint.Align.CENTER)
        stroke.color = C.LINE; stroke.strokeWidth = dp(1f)
        c.drawLine(split, r.top + dp(8f), split, r.bottom - dp(8f), stroke)

        // 2x2 grid
        val gw = (r.right - split) / 2; val gh = r.height() / 2
        val hrField = if (s.hr > 0) Triple("HEART RATE", "${s.hr}", "bpm · Z${s.hrZone}") else
            Triple("GRADE", s.grade?.let { Fmt.one(it) } ?: "–", "%")
        val fields = listOf(
            Triple("DISTANCE", Fmt.distValue(s.dist), Fmt.distUnit(s.dist)),
            Triple("MOVING TIME", Fmt.dur(s.movingMs), "elapsed ${Fmt.dur(s.elapsedMs)}"),
            Triple("ALTITUDE", if (s.alt.isNaN()) "–" else Fmt.int(s.alt), "m · ↑${Fmt.int(s.climb)} ↓${Fmt.int(s.descent)}"),
            hrField,
        )
        fields.forEachIndexed { i, f ->
            val x = split + (i % 2) * gw; val y = r.top + (i / 2) * gh
            text(c, f.first, x + dp(14f), y + sp(22f), 14f, C.LABEL)
            var valSize = 48f
            while (valSize > 24f && textWidth(f.second, valSize, true) > gw - dp(24f)) valSize -= 2f
            text(c, f.second, x + dp(14f), y + gh * 0.5f + sp(valSize) * 0.38f, valSize, C.FG, bold = true)
            text(c, f.third, x + dp(14f), y + gh - dp(12f), 14f, if (i == 3 && s.hr > 0) zoneColor(s.hrZone) else C.LABEL)
        }
        c.drawLine(split, r.centerY(), r.right, r.centerY(), stroke)
        c.drawLine(split + gw, r.top + dp(8f), split + gw, r.bottom - dp(8f), stroke)
    }

    private fun zoneColor(z: Int) = when (z) { 5 -> C.ALERT; 4 -> 0xFFFF851B.toInt(); 3 -> C.ACCENT; 2 -> C.REC; else -> C.LABEL }

    private fun pageMap(c: Canvas, s: Snap, r: RectF) {
        val valid = !s.lat.isNaN()
        val cx = if (valid) Area.x(s.lon) else 0.0
        val cy = if (valid) Area.y(s.lat) else 0.0
        map.draw(c, r, cx, cy, zoom, valid, s.bearing, Live.track)
        val bs = dp(72f)
        button(c, RectF(r.right - bs - dp(10f), r.top + dp(10f), r.right - dp(10f), r.top + dp(10f) + bs), "+", 0xCC1C1C1C.toInt(), C.FG, sizeSp = 36f) { setZoom(zoom + 1) }
        button(c, RectF(r.right - bs - dp(10f), r.top + dp(20f) + bs, r.right - dp(10f), r.top + dp(20f) + bs * 2), "−", 0xCC1C1C1C.toInt(), C.FG, sizeSp = 36f) { setZoom(zoom - 1) }
        text(c, "${Fmt.speed(s.speed)} km/h", r.left + dp(12f), r.top + sp(30f), 28f, C.FG, bold = true)
        text(c, Fmt.dist(s.dist), r.left + dp(12f), r.top + sp(58f), 20f, C.FG)
        text(c, "z$zoom", r.right - dp(12f), r.bottom - dp(10f), 13f, C.LABEL, Paint.Align.RIGHT)
    }

    private fun setZoom(z: Int) { zoom = z.coerceIn(11, 18); act.app.prefs.mapZoom = zoom; invalidate() }

    private fun pageProfile(c: Canvas, s: Snap, r: RectF) {
        val chartR = RectF(r.left + dp(8f), r.top + dp(8f), r.left + r.width() * 0.62f, r.bottom - dp(8f))
        val t = Live.track
        synchronized(t) { chart(c, chartR, t.dist.data, t.alt.data, t.dist.size, C.ACCENT, "ALTITUDE PROFILE", "m", fill = true) }
        val x = chartR.right + dp(16f)
        text(c, "ALT ${if (s.alt.isNaN()) "–" else Fmt.int(s.alt)} m   GRADE ${s.grade?.let { Fmt.one(it) } ?: "–"} %", x, r.top + sp(26f), 18f, C.FG, bold = true)
        text(c, "↑ ${Fmt.int(s.climb)} m   ↓ ${Fmt.int(s.descent)} m", x, r.top + sp(50f), 16f, C.LABEL)
        val cw = floatArrayOf(0f, dp(44f), dp(124f), dp(190f))
        listOf("KM", "TIME", "km/h", "↑m").forEachIndexed { i, t -> text(c, t, x + cw[i], r.top + sp(82f), 14f, C.LABEL) }
        val rowH = sp(26f)
        val maxRows = ((r.bottom - (r.top + sp(82f))) / rowH).toInt() - 1
        val list = s.splits.takeLast(max(0, maxRows))
        list.forEachIndexed { i, sp0 ->
            val y = r.top + sp(82f) + rowH * (i + 1)
            val kmh = if (sp0.movingMs > 0) 3600.0 / (sp0.movingMs / 1000.0) else 0.0
            listOf("${sp0.km}", Fmt.mmss(sp0.movingMs), Fmt.one(kmh), Fmt.int(sp0.climb)).forEachIndexed { j, t -> text(c, t, x + cw[j], y, 18f, C.FG) }
        }
        if (list.isEmpty()) text(c, "first split at 1 km", x, r.top + sp(110f), 16f, C.LABEL)
    }
}

// ---------------------------------------------------------------- 5 & 7. Summary / ride detail
class ReportScreen(act: MainActivity, private val rideId: Long, private val summary: Boolean) : Screen(act) {
    @Volatile private var report: Report? = null

    init {
        scrollable = true
        fixedBottom = dp(80f)
        Thread {
            val db = act.app.db
            val ride = db.ride(rideId)
            if (ride != null) report = Report(ride, db.points(rideId), db.laps(rideId), act.app.prefs.riderKg + act.app.prefs.bikeKg)
            post { invalidate() }
        }.start()
    }

    override fun tick() {} // static content
    override fun onBack(): Boolean { if (summary) return true; act.show(HistoryScreen(act)); return true }

    override fun render(c: Canvas) {
        val r = report
        if (r == null) { text(c, "loading…", width / 2f, height / 2f, 18f, C.LABEL, Paint.Align.CENTER); return }
        val w = width.toFloat()
        val ride = r.ride
        var y = dp(16f) + sp(24f)
        text(c, (if (summary) "RIDE SUMMARY  " else "RIDE  ") + "${Fmt.date(ride.start)}  ${Fmt.hm(ride.start)}–${Fmt.hm(ride.end)}", dp(16f), y, 22f, C.ACCENT, bold = true)
        y += dp(12f)

        // totals grid, 4 columns
        val items = mutableListOf(
            "DISTANCE" to Fmt.dist(ride.dist),
            "MOVING" to Fmt.dur(ride.movingMs),
            "ELAPSED" to Fmt.dur(ride.elapsedMs),
            "STOPPED" to Fmt.dur(r.stoppedMs),
            "AVG SPEED" to "${Fmt.speed(ride.avgSpeed)} km/h",
            "MAX SPEED" to "${Fmt.speed(ride.maxSpeed)} km/h",
            "CLIMB" to "${Fmt.int(ride.climb)} m",
            "DESCENT" to "${Fmt.int(ride.descent)} m",
            "MAX GRADE" to "${Fmt.one(ride.maxGrade)} %",
            "ALT MIN/MAX" to "${Fmt.int(r.minAlt)}/${Fmt.int(r.maxAlt)} m",
            "ENERGY" to "${Fmt.int(r.energyKj)} kJ",
            "LAPS" to "${max(1, r.laps.size)}",
        )
        if (r.hasHr) { items.add("AVG HR" to "${ride.avgHr} bpm"); items.add("MAX HR" to "${ride.maxHr} bpm") }
        val colW = (w - dp(32f)) / 4
        items.forEachIndexed { i, (k, v) ->
            val x = dp(16f) + (i % 4) * colW
            val yy = y + (i / 4) * dp(64f)
            text(c, k, x, yy + sp(18f), 13f, C.LABEL)
            text(c, v, x, yy + sp(48f), 26f, C.FG, bold = true)
        }
        y += ((items.size + 3) / 4) * dp(64f) + dp(12f)

        // charts
        val ch = dp(150f)
        val cw = (w - dp(40f)) / 2
        chart(c, RectF(dp(16f), y, dp(16f) + cw, y + ch), r.chartDist, r.chartSpeed, r.chartDist.size, C.FG, "SPEED", "km/h")
        chart(c, RectF(dp(24f) + cw, y, w - dp(16f), y + ch), r.chartDist, r.chartAlt, r.chartDist.size, C.ACCENT, "ALTITUDE PROFILE", "m", true)
        y += ch + dp(12f)
        if (r.hasHr) {
            chart(c, RectF(dp(16f), y, dp(16f) + cw, y + ch), r.chartDist, r.chartHr, r.chartDist.size, C.ALERT, "HEART RATE", "bpm")
            // time in zone
            val zx = dp(24f) + cw
            text(c, "TIME IN ZONE (max HR ${act.app.prefs.maxHr})", zx, y + sp(16f), 13f, C.LABEL)
            val total = max(1L, ride.zoneMs.sum())
            for (z in 1..5) {
                val yy = y + sp(16f) + z * dp(24f)
                val f = ride.zoneMs[z].toFloat() / total
                fp.color = C.DIM; c.drawRect(zx + dp(40f), yy - dp(14f), w - dp(110f), yy + dp(2f), fp)
                fp.color = when (z) { 5 -> C.ALERT; 4 -> 0xFFFF851B.toInt(); 3 -> C.ACCENT; 2 -> C.REC; else -> C.LABEL }
                c.drawRect(zx + dp(40f), yy - dp(14f), zx + dp(40f) + (w - dp(150f) - zx) * f, yy + dp(2f), fp)
                text(c, "Z$z", zx, yy, 15f, C.FG)
                text(c, Fmt.dur(ride.zoneMs[z]), w - dp(16f), yy, 15f, C.FG, Paint.Align.RIGHT)
            }
            y += ch + dp(12f)
        }

        // splits and laps side by side
        text(c, "SPLITS PER KM", dp(16f), y + sp(16f), 13f, C.LABEL)
        text(c, "LAPS", dp(24f) + cw, y + sp(16f), 13f, C.LABEL)
        val rowH = sp(26f)
        var sy = y + sp(16f)
        for (s in r.splits) {
            sy += rowH
            val kmh = if (s.movingMs > 0) 3600.0 / (s.movingMs / 1000.0) else 0.0
            listOf("km ${s.km}", Fmt.mmss(s.movingMs), "${Fmt.one(kmh)} km/h", "↑${Fmt.int(s.climb)} m").forEachIndexed { j, t ->
                text(c, t, dp(16f) + j * cw / 4, sy, 17f, C.FG) }
        }
        if (r.splits.isEmpty()) { sy += rowH; text(c, "under 1 km", dp(16f), sy, 17f, C.LABEL) }
        var ly = y + sp(16f)
        for (l in r.laps) {
            ly += rowH
            listOf("lap ${l.n}", Fmt.dist(l.dist), Fmt.dur(l.movingMs)).forEachIndexed { j, t ->
                text(c, t, dp(24f) + cw + j * cw / 3, ly, 17f, C.FG) }
        }
        if (r.laps.isEmpty()) { ly += rowH; text(c, "no laps marked", dp(24f) + cw, ly, 17f, C.LABEL) }
        y = max(sy, ly) + dp(24f)
        contentH = y
    }

    override fun drawFixed(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        fp.color = C.BG; c.drawRect(0f, h - fixedBottom, w, h, fp)
        val by = h - fixedBottom + dp(8f); val bb = h - dp(8f)
        if (summary) {
            button(c, RectF(dp(12f), by, w / 2 - dp(6f), bb), "SAVE", C.REC, C.BG, sizeSp = 26f) {
                act.app.db.setSaved(rideId); act.toast("Ride saved"); act.show(HomeScreen(act)); act.warmup()
            }
            button(c, RectF(w / 2 + dp(6f), by, w - dp(12f), bb), "DISCARD · hold 1 s", C.ALERT, C.FG, 1000, 22f) {
                act.app.db.delete(rideId); act.toast("Ride discarded"); act.show(HomeScreen(act)); act.warmup()
            }
        } else {
            val bw = (w - dp(60f)) / 4
            var x = dp(12f)
            button(c, RectF(x, by, x + bw, bb), "BACK") { act.show(HistoryScreen(act)) }; x += bw + dp(12f)
            button(c, RectF(x, by, x + bw, bb), "EXPORT FIT", C.ACCENT, C.BG) { report?.let { act.export(it, true) } }; x += bw + dp(12f)
            button(c, RectF(x, by, x + bw, bb), "EXPORT GPX", C.DIM, C.ACCENT) { report?.let { act.export(it, false) } }; x += bw + dp(12f)
            button(c, RectF(x, by, x + bw, bb), "DELETE · hold", C.ALERT, C.FG, 1000, 18f) {
                act.app.db.delete(rideId); act.toast("Ride deleted"); act.show(HistoryScreen(act))
            }
        }
    }
}

// ---------------------------------------------------------------- 6. History
class HistoryScreen(act: MainActivity) : Screen(act) {
    private val rides = act.app.db.rides()

    init { scrollable = true; fixedBottom = 0f }

    override fun tick() {}
    override fun onBack(): Boolean { act.show(HomeScreen(act)); return true }

    private fun totals(from: Long): Triple<Double, Long, Int> {
        val r = rides.filter { it.start >= from }
        return Triple(r.sumOf { it.dist }, r.sumOf { it.movingMs }, r.size)
    }

    override fun render(c: Canvas) {
        val w = width.toFloat()
        button(c, RectF(dp(12f), dp(12f), dp(140f), dp(76f)), "BACK") { act.show(HomeScreen(act)) }
        text(c, "HISTORY", dp(160f), dp(54f), 26f, C.ACCENT, bold = true)

        val cal = java.util.Calendar.getInstance().apply {
            firstDayOfWeek = java.util.Calendar.MONDAY
            set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0); set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
        }
        val month = (cal.clone() as java.util.Calendar).apply { set(java.util.Calendar.DAY_OF_MONTH, 1) }.timeInMillis
        val week = (cal.clone() as java.util.Calendar).apply { set(java.util.Calendar.DAY_OF_WEEK, java.util.Calendar.MONDAY) }.timeInMillis
        val wk = totals(week); val mo = totals(month)
        var y = dp(96f)
        val half = (w - dp(40f)) / 2
        for ((i, t) in listOf("THIS WEEK" to wk, "THIS MONTH" to mo).withIndex()) {
            val x = dp(16f) + i * (half + dp(8f))
            fp.color = C.DIM; c.drawRoundRect(RectF(x, y, x + half, y + dp(80f)), dp(10f), dp(10f), fp)
            text(c, t.first, x + dp(14f), y + sp(22f), 13f, C.LABEL)
            text(c, "${Fmt.dist(t.second.first)}  ·  ${Fmt.dur(t.second.second)}  ·  ${t.second.third} ride" + (if (t.second.third == 1) "" else "s"), x + dp(14f), y + sp(56f), 19f, C.FG, bold = true)
        }
        y += dp(96f)
        if (rides.isEmpty()) text(c, "No saved rides yet.", dp(16f), y + sp(30f), 18f, C.LABEL)
        for (r in rides) {
            val row = RectF(dp(12f), y, w - dp(12f), y + dp(72f))
            fp.color = 0xFF0E0E0E.toInt(); c.drawRoundRect(row, dp(8f), dp(8f), fp)
            text(c, "${Fmt.date(r.start)}  ${Fmt.hm(r.start)}", row.left + dp(14f), row.centerY() + sp(8f), 20f, C.FG, bold = true)
            text(c, Fmt.dist(r.dist), row.left + row.width() * 0.34f, row.centerY() + sp(8f), 22f, C.ACCENT, bold = true)
            text(c, Fmt.dur(r.movingMs), row.left + row.width() * 0.52f, row.centerY() + sp(8f), 20f, C.FG)
            text(c, "${Fmt.speed(r.avgSpeed)} km/h", row.left + row.width() * 0.68f, row.centerY() + sp(8f), 18f, C.LABEL)
            text(c, "↑${Fmt.int(r.climb)} m", row.right - dp(14f), row.centerY() + sp(8f), 18f, C.LABEL, Paint.Align.RIGHT)
            hit(row) { act.show(ReportScreen(act, r.id, summary = false)) }
            y += dp(80f)
        }
        contentH = y + dp(16f)
    }
}

// ---------------------------------------------------------------- Crash alarm overlay
class CrashOverlay(act: MainActivity) : Screen(act) {
    override fun render(c: Canvas) {
        val a = Live.snap.alarm ?: return
        val w = width.toFloat(); val h = height.toFloat()
        c.drawColor(if (a.phase == Alarm.Phase.DONE) 0xFF5A0000.toInt() else C.ALERT)
        when (a.phase) {
            Alarm.Phase.COUNTDOWN -> {
                text(c, a.text, w / 2, dp(16f) + sp(30f), 30f, C.FG, Paint.Align.CENTER, true)
                text(c, "${a.secondsLeft}", w / 2, h * 0.40f, 84f, C.FG, Paint.Align.CENTER, true)
                text(c, "SMS and calls to your contacts when this reaches 0", w / 2, h * 0.47f, 16f, C.FG, Paint.Align.CENTER)
            }
            else -> {
                text(c, if (a.test) "TEST MODE" else "EMERGENCY", w / 2, h * 0.12f + sp(20f), 30f, C.FG, Paint.Align.CENTER, true)
                text(c, a.text, w / 2, h * 0.32f, 24f, C.FG, Paint.Align.CENTER, true)
            }
        }
        val label = when (a.phase) { Alarm.Phase.COUNTDOWN -> "CANCEL"; Alarm.Phase.SENDING -> "STOP CALLS"; Alarm.Phase.DONE -> "CLOSE" }
        button(c, RectF(dp(12f), h / 2, w - dp(12f), h - dp(12f)), label, C.FG, C.ALERT, sizeSp = 56f) {
            RideService.send(act, if (a.phase == Alarm.Phase.DONE) RideService.ACTION_ALARM_CLOSE else RideService.ACTION_CANCEL)
        }
    }
}
