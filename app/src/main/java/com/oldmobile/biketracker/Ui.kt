package com.oldmobile.biketracker

import android.annotation.SuppressLint
import android.graphics.*
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

object C {
    const val BG = 0xFF000000.toInt()
    const val FG = 0xFFFFFFFF.toInt()
    const val ACCENT = 0xFFFFD400.toInt()
    const val LABEL = 0xFF8A8A8A.toInt()
    const val REC = 0xFF2ECC40.toInt()
    const val ALERT = 0xFFFF4136.toInt()
    const val DIM = 0xFF1C1C1C.toInt()
    const val LINE = 0xFF333333.toInt()
}

/**
 * Base for all canvas screens: black background, buttons as hit rectangles registered while
 * drawing, 1 s long-press buttons with a fill bar, vertical drag scroll, horizontal swipe.
 * No animations: the only repaint besides the 1 Hz tick is the hold progress bar.
 */
abstract class Screen(val act: MainActivity) : View(act) {
    private val density = resources.displayMetrics.density
    private val scaled = resources.displayMetrics.scaledDensity
    fun dp(v: Float) = v * density
    fun sp(v: Float) = v * scaled

    class Btn(val r: RectF, val holdMs: Long, val action: () -> Unit)

    private val btns = ArrayList<Btn>()
    private var pressed: Btn? = null
    private var pressAt = 0L
    private var downX = 0f; private var downY = 0f
    private var startScroll = 0f
    private var dragging = false
    protected var scrollable = false
    protected var scrollY = 0f
    protected var contentH = 0f
    protected var fixedBottom = 0f
    private var drawOffset = 0f

    val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.create("sans-serif", Typeface.NORMAL); fontFeatureSettings = "tnum" }
    val fp = Paint(Paint.ANTI_ALIAS_FLAG)
    val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

    /** Called once per second by the activity. */
    open fun tick() = invalidate()
    open fun onSwipe(dir: Int) {}
    open fun onBack(): Boolean = false

    abstract fun render(c: Canvas)
    open fun drawFixed(c: Canvas) {}

    override fun onDraw(c: Canvas) {
        btns.clear()
        c.drawColor(C.BG)
        if (scrollable) {
            val maxScroll = max(0f, contentH - (height - fixedBottom))
            scrollY = scrollY.coerceIn(0f, maxScroll)
            c.save(); c.clipRect(0f, 0f, width.toFloat(), height - fixedBottom); c.translate(0f, -scrollY)
            drawOffset = -scrollY
            render(c)
            c.restore()
            drawOffset = 0f
            if (maxScroll > 0) { // scroll indicator
                val visible = height - fixedBottom
                val barH = max(dp(24f), visible * visible / contentH)
                val y = scrollY / maxScroll * (visible - barH)
                fp.color = C.LINE; c.drawRect(width - dp(4f), y, width.toFloat(), y + barH, fp)
            }
        } else render(c)
        drawFixed(c)
        pressed?.let { b ->
            if (b.holdMs > 0) {
                val f = min(1f, (System.currentTimeMillis() - pressAt) / b.holdMs.toFloat())
                fp.color = 0x66FFFFFF; c.drawRect(b.r.left, b.r.bottom - dp(8f), b.r.left + b.r.width() * f, b.r.bottom, fp)
                postInvalidateDelayed(40)
            }
        }
    }

    fun text(c: Canvas, s: String, x: Float, y: Float, sizeSp: Float, color: Int = C.FG, align: Paint.Align = Paint.Align.LEFT, bold: Boolean = false) {
        tp.textSize = sp(sizeSp); tp.color = color; tp.textAlign = align; tp.isFakeBoldText = false
        tp.typeface = if (bold) BOLD else REGULAR
        c.drawText(s, x, y, tp)
    }

    fun textWidth(s: String, sizeSp: Float, bold: Boolean = false): Float {
        tp.textSize = sp(sizeSp); tp.typeface = if (bold) BOLD else REGULAR
        return tp.measureText(s)
    }

    /** Register a hit area without drawing. Rect in the current (possibly scrolled) canvas space. */
    fun hit(r: RectF, holdMs: Long = 0, action: () -> Unit) {
        btns.add(Btn(RectF(r.left, r.top + drawOffset, r.right, r.bottom + drawOffset), holdMs, action))
    }

    fun button(c: Canvas, r: RectF, label: String, fill: Int = C.DIM, textColor: Int = C.FG, holdMs: Long = 0, sizeSp: Float = 20f, action: () -> Unit) {
        fp.color = fill
        c.drawRoundRect(r, dp(10f), dp(10f), fp)
        val fm = tp.apply { textSize = sp(sizeSp) }.fontMetrics
        text(c, label, r.centerX(), r.centerY() - (fm.ascent + fm.descent) / 2, sizeSp, textColor, Paint.Align.CENTER, true)
        hit(r, holdMs, action)
    }

    private val holdCheck = Runnable {
        val b = pressed ?: return@Runnable
        pressed = null
        invalidate()
        b.action()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x; downY = e.y; startScroll = scrollY; dragging = false
                pressed = btns.lastOrNull { it.r.contains(e.x, e.y) }
                pressAt = System.currentTimeMillis()
                pressed?.let { if (it.holdMs > 0) { postDelayed(holdCheck, it.holdMs); invalidate() } }
            }
            MotionEvent.ACTION_MOVE -> {
                val dy = e.y - downY; val dx = e.x - downX
                if (!dragging && (abs(dy) > dp(16f) || abs(dx) > dp(16f))) {
                    dragging = true
                    if (pressed?.holdMs ?: 0 > 0 && pressed!!.r.contains(e.x, e.y) && abs(dy) < dp(40f)) dragging = false
                    else cancelPress()
                }
                if (dragging && scrollable && abs(dy) > abs(dx)) { scrollY = startScroll - dy; invalidate() }
                pressed?.let { if (!it.r.contains(e.x, e.y)) cancelPress() }
            }
            MotionEvent.ACTION_UP -> {
                val dx = e.x - downX; val dy = e.y - downY
                if (dragging && abs(dx) > dp(80f) && abs(dx) > abs(dy) * 1.5f) onSwipe(if (dx < 0) 1 else -1)
                val b = pressed
                if (!dragging && b != null && b.holdMs == 0L && b.r.contains(e.x, e.y)) { pressed = null; b.action() }
                cancelPress()
            }
            MotionEvent.ACTION_CANCEL -> cancelPress()
        }
        return true
    }

    private fun cancelPress() {
        removeCallbacks(holdCheck)
        if (pressed != null) { pressed = null; invalidate() }
    }

    // --- charts ---
    fun chart(c: Canvas, r: RectF, xs: FloatArray, ys: FloatArray, n: Int, color: Int, title: String, unit: String, fill: Boolean = false) {
        fp.color = 0xFF0A0A0A.toInt(); c.drawRect(r, fp)
        text(c, title, r.left + dp(6f), r.top + sp(16f), 13f, C.LABEL)
        if (n < 2) { text(c, "no data", r.centerX(), r.centerY(), 13f, C.LABEL, Paint.Align.CENTER); return }
        var x0 = xs[0]; var x1 = xs[n - 1]
        var y0 = Float.MAX_VALUE; var y1 = -Float.MAX_VALUE
        for (i in 0 until n) { if (ys[i] < y0) y0 = ys[i]; if (ys[i] > y1) y1 = ys[i] }
        if (x1 <= x0) x1 = x0 + 1
        if (y1 - y0 < 5) { val m = (y0 + y1) / 2; y0 = m - 2.5f; y1 = m + 2.5f }
        val top = r.top + sp(22f); val bot = r.bottom - dp(4f)
        val path = Path()
        for (i in 0 until n) {
            val px = r.left + (xs[i] - x0) / (x1 - x0) * r.width()
            val py = bot - (ys[i] - y0) / (y1 - y0) * (bot - top)
            if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
        }
        if (fill) {
            val fpath = Path(path); fpath.lineTo(r.right, bot); fpath.lineTo(r.left, bot); fpath.close()
            fp.color = (color and 0x00FFFFFF) or 0x33000000; c.drawPath(fpath, fp)
        }
        stroke.color = color; stroke.strokeWidth = dp(2f); c.drawPath(path, stroke)
        text(c, "${Fmt.int(y1.toDouble())} $unit", r.right - dp(6f), r.top + sp(16f), 13f, C.LABEL, Paint.Align.RIGHT)
        text(c, "${Fmt.int(y0.toDouble())}", r.right - dp(6f), bot - dp(4f), 13f, C.LABEL, Paint.Align.RIGHT)
    }

    companion object {
        val REGULAR: Typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        val BOLD: Typeface = Typeface.create("sans-serif", Typeface.BOLD)
        val CONDENSED: Typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
    }
}
