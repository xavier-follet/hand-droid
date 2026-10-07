package com.follet.jotter

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import kotlin.math.ceil
import kotlin.math.hypot

val BACKGROUNDS = listOf(
    "blank" to "Blank",
    "college:40" to "College ruled – narrow",
    "college:45" to "College ruled",
    "college:55" to "College ruled – wide",
    "cornell" to "Cornell",
    "cornellruled" to "Cornell ruled",
    "grid:20" to "Grid – small",
    "grid:32" to "Grid",
    "grid:45" to "Grid – large",
    "cross" to "Cross grid",
    "dot" to "Dot grid",
    "seyes" to "Séyès",
    "seyesm" to "Séyès with margin",
)

private const val LINE = 0xFFCBD6EA.toInt()
private const val LINE_DARK = 0xFFA9B9D6.toInt()
private const val RED = 0xFFE59A9A.toInt()
private const val SEYES = 50.8f // 8 mm in page units (1000 units ~ 160 mm)

private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }

/** Draws the paper pattern for the page-space band y0..y1. */
fun drawBackground(c: Canvas, id: String, y0: Float, y1: Float, w: Float) {
    val size = id.substringAfter(':', "").toFloatOrNull() ?: 0f
    p.style = Paint.Style.STROKE
    fun hlines(step: Float, from: Float, color: Int, sw: Float = 0.9f) {
        p.color = color; p.strokeWidth = sw
        var y = from + ceil((maxOf(y0, from) - from) / step) * step
        while (y <= y1) { c.drawLine(0f, y, w, y, p); y += step }
    }
    fun vlines(step: Float, color: Int, from: Float = 0f, sw: Float = 0.9f, x0: Float = step) {
        p.color = color; p.strokeWidth = sw
        var x = x0
        while (x < w) { c.drawLine(x, maxOf(y0, from), x, y1, p); x += step }
    }
    when (id.substringBefore(':')) {
        "college" -> { hlines(size, size, LINE); p.color = RED; p.strokeWidth = 1.6f; c.drawLine(90f, y0, 90f, y1, p) }
        "cornell", "cornellruled" -> {
            hlines(1e9f, 90f, LINE_DARK, 2f) // one header rule
            p.color = LINE_DARK; p.strokeWidth = 2f; c.drawLine(w * 0.3f, maxOf(y0, 90f), w * 0.3f, y1, p)
            if (id == "cornellruled") hlines(40f, 130f, LINE)
        }
        "grid" -> { hlines(size, 0f, LINE); vlines(size, LINE) }
        "cross", "dot" -> {
            val s = 32f
            p.color = LINE_DARK; p.strokeWidth = 1.4f
            var y = maxOf(s, ceil(y0 / s) * s) // first row one step down, like the left margin, so it is not cut by the page edge
            while (y <= y1) {
                var x = s
                while (x < w) {
                    if (id == "dot") { p.style = Paint.Style.FILL; c.drawCircle(x, y, 2f, p) }
                    else { c.drawLine(x - 5, y, x + 5, y, p); c.drawLine(x, y - 5, x, y + 5, p) }
                    x += s
                }
                y += s
            }
        }
        "seyes", "seyesm" -> {
            val m = if (id == "seyesm") SEYES * 4 else 0f   // with margin: left margin, 4 grid squares
            val top = if (id == "seyesm") SEYES * 3 else 0f // ...and a top margin of 3 squares
            hlines(SEYES / 4, top, 0xFFD3DCEE.toInt(), 1f); hlines(SEYES, top, LINE_DARK, 1.4f) // no horizontals in the top margin
            vlines(SEYES, LINE_DARK, x0 = if (id == "seyesm") m + SEYES else SEYES) // verticals run all the way up, none in the left margin
            if (id == "seyesm") { p.style = Paint.Style.STROKE; p.color = RED; p.strokeWidth = 1.8f; c.drawLine(m, y0, m, y1, p) }
        }
    }
}

fun drawStroke(c: Canvas, s: Stroke, dim: Boolean = false) {
    p.color = s.color
    p.alpha = (if (s.tool == 1) 0x88 else 0xFF) / (if (dim) 4 else 1)
    if (s.n == 1) {
        p.style = Paint.Style.FILL; c.drawCircle(s.pts[0], s.pts[1], s.width / 2, p); return
    }
    if (s.tool == 2) {
        p.style = Paint.Style.FILL_AND_STROKE; p.strokeWidth = maxOf(0.5f, s.width * 0.1f) // hairline keeps strokes parallel to the nib visible
        c.drawPath(s.nib, p); return
    }
    p.style = Paint.Style.STROKE
    if (!s.varies) { p.strokeWidth = s.width; c.drawPath(s.path, p); return }
    for (i in 1 until s.n) {
        p.strokeWidth = s.width * (0.2f + 0.8f * (s.pts[i * 3 + 2] + s.pts[i * 3 - 1]) / 2)
        c.drawLine(s.pts[i * 3 - 3], s.pts[i * 3 - 2], s.pts[i * 3], s.pts[i * 3 + 1], p)
    }
}

/** Draws background + items for the band scrollY..scrollY+viewH. Canvas must already be scaled to page units. */
fun drawPage(
    c: Canvas, items: List<Item>, bg: String, scrollY: Float, viewH: Float,
    bitmap: (String) -> Bitmap?, dimmed: Set<Item> = emptySet(),
) {
    c.save()
    c.clipRect(0f, 0f, PAGE_W, viewH)
    c.drawColor(0xFFFFFFFF.toInt())
    c.translate(0f, -scrollY)
    drawBackground(c, bg, scrollY, scrollY + viewH, PAGE_W)
    for (it in items) when (it) {
        is Stroke -> if (it.maxY >= scrollY && it.minY <= scrollY + viewH) drawStroke(c, it, it in dimmed)
        is Img -> bitmap(it.file)?.let { b ->
            p.alpha = if (it in dimmed) 64 else 255
            c.drawBitmap(b, null, RectF(it.x, it.y, it.x + it.w, it.y + it.h), p)
            p.alpha = 255
        }
    }
    c.restore()
}

/** Whole page, cropped to its content, for sharing. */
fun renderExport(items: List<Item>, bg: String, bitmap: (String) -> Bitmap?): Bitmap {
    val bottom = items.maxOfOrNull { if (it is Stroke) it.maxY else (it as Img).y + it.h } ?: 0f
    // ponytail: capped at 6000 units (9000 px) to bound memory; tile the export if longer pages matter
    val h = (bottom + 60f).coerceIn(1000f, 6000f)
    val b = Bitmap.createBitmap((PAGE_W * 1.5f).toInt(), (h * 1.5f).toInt(), Bitmap.Config.ARGB_8888)
    val c = Canvas(b)
    c.scale(1.5f, 1.5f)
    drawPage(c, items, bg, 0f, h, bitmap)
    return b
}

/** Does an eraser circle of radius r at (x, y) touch the item? */
fun hits(item: Item, x: Float, y: Float, r: Float): Boolean = when (item) {
    is Img -> x >= item.x - r && x <= item.x + item.w + r && y >= item.y - r && y <= item.y + item.h + r
    is Stroke -> {
        val rr = r + item.width / 2
        y in item.minY - r..item.maxY + r && (0 until item.n).any { i ->
            val ax = item.pts[i * 3]; val ay = item.pts[i * 3 + 1]
            if (i == 0) hypot(x - ax, y - ay) <= rr
            else {
                val bx = item.pts[i * 3 - 3]; val by = item.pts[i * 3 - 2]
                val dx = ax - bx; val dy = ay - by
                val len2 = dx * dx + dy * dy
                val t = if (len2 == 0f) 0f else (((x - bx) * dx + (y - by) * dy) / len2).coerceIn(0f, 1f)
                hypot(x - (bx + t * dx), y - (by + t * dy)) <= rr
            }
        }
    }
}
