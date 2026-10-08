package com.follet.jotter

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Path
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

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

private val paints = ThreadLocal.withInitial { Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND } }
private val p: Paint get() = paints.get()!! // one per thread: drawing happens on the UI thread and for PDF export on another

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

val SHAPE_NAMES = listOf("Rectangle", "Ellipse", "Triangle", "Line", "Arrow", "Star", "Burst") // index = Shape.kind

/** Outline vertices (page units, before rotation) of the polygon-like kinds: 2 triangle, 5 star, 6 burst (spiky "price label" ellipse). */
fun polygon(s: Shape): FloatArray {
    val l = s.left; val t = s.top; val w = s.right - s.left; val h = s.bottom - s.top
    return when (s.kind) {
        2 -> floatArrayOf(l + w / 2, t, l + w, t + h, l, t + h)
        5 -> FloatArray(20).also { out ->
            for (i in 0 until 10) { // alternate outer / inner points, starting at the top
                val a = -PI / 2 + i * PI / 5; val f = if (i % 2 == 0) 1.0 else 0.4
                // a unit star spans x -0.951..0.951 and y -1..0.809; stretch that to fill the box
                out[i * 2] = (l + w * (cos(a) * f + 0.951) / 1.902).toFloat()
                out[i * 2 + 1] = (t + h * (sin(a) * f + 1.0) / 1.809).toFloat()
            }
        }
        else -> FloatArray(64).also { out ->
            for (i in 0 until 32) { // 16 spikes: alternate full radius and 80%
                val a = i * PI / 16; val f = if (i % 2 == 0) 1.0 else 0.8
                out[i * 2] = (l + w / 2 + w / 2 * cos(a) * f).toFloat()
                out[i * 2 + 1] = (t + h / 2 + h / 2 * sin(a) * f).toFloat()
            }
        }
    }
}

private fun polyPath(pts: FloatArray) =
    Path().apply { moveTo(pts[0], pts[1]); for (i in 1 until pts.size / 2) lineTo(pts[i * 2], pts[i * 2 + 1]); close() }

private fun deg(rad: Float) = Math.toDegrees(rad.toDouble()).toFloat()

/** Rotates (px, py) by [a] radians (clockwise on screen) around (cx, cy). */
private fun rotPt(px: Float, py: Float, cx: Float, cy: Float, a: Float): Pair<Float, Float> {
    val cs = cos(a); val sn = sin(a); val dx = px - cx; val dy = py - cy
    return (cx + dx * cs - dy * sn) to (cy + dx * sn + dy * cs)
}

fun drawShape(c: Canvas, s: Shape, dim: Boolean = false) {
    val l = s.left; val t = s.top; val r = s.right; val b = s.bottom
    val turned = s.rot != 0f && !s.isLine
    if (turned) { c.save(); c.rotate(deg(s.rot), (l + r) / 2, (t + b) / 2) }
    p.strokeJoin = Paint.Join.ROUND
    if (s.fill != 0 && !s.isLine) {
        p.style = Paint.Style.FILL; p.color = s.fill; if (dim) p.alpha = p.alpha / 4
        when (s.kind) { 0 -> c.drawRect(l, t, r, b, p); 1 -> c.drawOval(l, t, r, b, p); else -> c.drawPath(polyPath(polygon(s)), p) }
    }
    if (s.color != 0 || s.isLine) { // border (lines always have one)
        p.style = Paint.Style.STROKE; p.color = if (s.color == 0) 0xFF000000.toInt() else s.color; p.strokeWidth = s.width
        if (dim) p.alpha = p.alpha / 4
        when (s.kind) {
            0 -> c.drawRect(l, t, r, b, p)
            1 -> c.drawOval(l, t, r, b, p)
            3, 4 -> {
                c.drawLine(s.x1, s.y1, s.x2, s.y2, p)
                if (s.kind == 4) {
                    val ang = atan2(s.y2 - s.y1, s.x2 - s.x1); val head = maxOf(18f, s.width * 5f)
                    for (dd in floatArrayOf(0.5f, -0.5f)) c.drawLine(s.x2, s.y2, s.x2 - head * cos(ang + dd), s.y2 - head * sin(ang + dd), p)
                }
            }
            else -> c.drawPath(polyPath(polygon(s)), p)
        }
    }
    p.alpha = 255
    if (turned) c.restore()
}

private fun drawImg(c: Canvas, bm: Bitmap, im: Img, alpha: Int = 255) {
    p.alpha = alpha
    if (im.rot != 0f) { c.save(); c.rotate(deg(im.rot), im.x + im.w / 2, im.y + im.h / 2) }
    c.drawBitmap(bm, null, RectF(im.x, im.y, im.x + im.w, im.y + im.h), p)
    if (im.rot != 0f) c.restore()
    p.alpha = 255
}

/** Draws one item in page units (used for the item being dragged). */
fun drawItem(c: Canvas, item: Item, bitmap: (String) -> Bitmap?) {
    when (item) {
        is Stroke -> drawStroke(c, item)
        is Shape -> drawShape(c, item)
        is Img -> bitmap(item.file)?.let { drawImg(c, it, item) }
    }
}

// ---- selecting, moving, resizing and rotating shapes and images ----

/** Unrotated box of a box-like shape or image: left, top, right, bottom, rotation. Lines have none. */
fun frameOf(item: Item): FloatArray? = when {
    item is Shape && !item.isLine -> floatArrayOf(item.left, item.top, item.right, item.bottom, item.rot)
    item is Img -> floatArrayOf(item.x, item.y, item.x + item.w, item.y + item.h, item.rot)
    else -> null
}

/**
 * Handles in page units. Boxes: 0..3 = corners TL, TR, BL, BR (turned with the box) and 4 = the rotate handle, which sits
 * [rotOff] beyond the bottom-right corner. Lines: the two ends.
 */
fun handles(item: Item, rotOff: Float): List<Pair<Float, Float>> {
    if (item is Shape && item.isLine) return listOf(item.x1 to item.y1, item.x2 to item.y2)
    val f = frameOf(item) ?: return emptyList()
    val cx = (f[0] + f[2]) / 2; val cy = (f[1] + f[3]) / 2; val a = f[4]
    val corners = listOf(f[0] to f[1], f[2] to f[1], f[0] to f[3], f[2] to f[3]).map { rotPt(it.first, it.second, cx, cy, a) }
    val dx = f[2] - cx; val dy = f[3] - cy; val len = hypot(dx, dy).coerceAtLeast(1f)
    return corners + rotPt(f[2] + dx / len * rotOff, f[3] + dy / len * rotOff, cx, cy, a)
}

/** Index of the handle nearest to the point, within [r], or -1. */
fun handleAt(item: Item, x: Float, y: Float, r: Float, rotOff: Float): Int {
    var best = -1; var bd = r
    handles(item, rotOff).forEachIndexed { i, h -> val d = hypot(x - h.first, y - h.second); if (d <= bd) { bd = d; best = i } }
    return best
}

/** Is the point inside the frame of a selected box-like shape or image? (Lines have no frame.) */
fun insideBox(item: Item, x: Float, y: Float): Boolean {
    val f = frameOf(item) ?: return false
    val q = rotPt(x, y, (f[0] + f[2]) / 2, (f[1] + f[3]) / 2, -f[4])
    return q.first in f[0]..f[2] && q.second in f[1]..f[3]
}

fun moved(item: Item, dx: Float, dy: Float): Item = when (item) {
    is Shape -> item.copy(x1 = item.x1 + dx, y1 = item.y1 + dy, x2 = item.x2 + dx, y2 = item.y2 + dy)
    is Img -> Img(item.file, item.x + dx, item.y + dy, item.w, item.h, item.rot)
    else -> item
}

/**
 * Drags handle [h] to (x, y). The opposite corner stays where it is on the page, whatever the rotation.
 * Images keep their aspect ratio; line ends just follow the point.
 */
fun resized(item: Item, h: Int, x: Float, y: Float): Item {
    if (item is Shape && item.isLine) return if (h == 0) item.copy(x1 = x, y1 = y) else item.copy(x2 = x, y2 = y)
    val f = frameOf(item) ?: return item
    val cx = (f[0] + f[2]) / 2; val cy = (f[1] + f[3]) / 2; val a = f[4]
    val o = rotPt(if (h == 0 || h == 2) f[2] else f[0], if (h == 0 || h == 1) f[3] else f[1], cx, cy, a) // fixed corner, on the page
    val v = rotPt(x, y, o.first, o.second, -a) // cursor in the item's own (unrotated) frame, relative to the fixed corner
    var vx = v.first - o.first; var vy = v.second - o.second
    if (item is Img) {
        val w = maxOf(30f, abs(vx))
        vx = (if (h == 0 || h == 2) -1f else 1f) * w; vy = (if (h == 0 || h == 1) -1f else 1f) * w * item.h / item.w
    }
    val moved = rotPt(o.first + vx, o.second + vy, o.first, o.second, a) // dragged corner back on the page
    val ncx = (o.first + moved.first) / 2; val ncy = (o.second + moved.second) / 2
    val hw = abs(vx) / 2; val hh = abs(vy) / 2
    return when (item) {
        is Shape -> item.copy(x1 = ncx - hw, y1 = ncy - hh, x2 = ncx + hw, y2 = ncy + hh)
        is Img -> Img(item.file, ncx - hw, ncy - hh, 2 * hw, 2 * hh, a)
        else -> item
    }
}

/** Turns the item by the angle the cursor has swept around its centre since the gesture started at (x0, y0). */
fun rotated(item: Item, x0: Float, y0: Float, x: Float, y: Float): Item {
    val f = frameOf(item) ?: return item
    val cx = (f[0] + f[2]) / 2; val cy = (f[1] + f[3]) / 2
    var a = f[4] + atan2(y - cy, x - cx) - atan2(y0 - cy, x0 - cx)
    val step = (PI / 12).toFloat() // snap to 15° when within ~3°, so upright is easy to get back to
    val near = kotlin.math.round(a / step) * step
    if (abs(a - near) < 0.05f) a = near
    return when (item) {
        is Shape -> item.copy(rot = a)
        is Img -> Img(item.file, item.x, item.y, item.w, item.h, a)
        else -> item
    }
}

/** Boxes drawn or dragged "backwards" get their corners sorted so handle numbering stays TL, TR, BL, BR. */
fun normalised(item: Item): Item =
    if (item is Shape && !item.isLine) item.copy(x1 = item.left, y1 = item.top, x2 = item.right, y2 = item.bottom) else item

private fun segDist(x: Float, y: Float, ax: Float, ay: Float, bx: Float, by: Float): Float {
    val dx = bx - ax; val dy = by - ay
    val len2 = dx * dx + dy * dy
    val t = if (len2 == 0f) 0f else (((x - ax) * dx + (y - ay) * dy) / len2).coerceIn(0f, 1f)
    return hypot(x - (ax + t * dx), y - (ay + t * dy))
}

/** Filled shapes hit anywhere inside; open shapes only near their outline, so a new shape can be drawn inside one. */
fun shapeHit(s: Shape, px: Float, py: Float, tol: Float): Boolean {
    val t = tol + (if (s.color == 0 && !s.isLine) 0f else s.width) / 2
    // work in the shape's own, unrotated frame
    val q = if (s.rot != 0f && !s.isLine) rotPt(px, py, (s.left + s.right) / 2, (s.top + s.bottom) / 2, -s.rot) else px to py
    val x = q.first; val y = q.second
    if (s.isLine) return segDist(x, y, s.x1, s.y1, s.x2, s.y2) <= t
    if (s.kind == 2 || s.kind >= 5) { // triangle, star, burst: near an edge, or (when filled) inside
        val pts = polygon(s); val n = pts.size / 2
        for (i in 0 until n) {
            val j = (i + 1) % n
            if (segDist(x, y, pts[i * 2], pts[i * 2 + 1], pts[j * 2], pts[j * 2 + 1]) <= t) return true
        }
        if (s.fill == 0) return false
        var inside = false // even-odd ray cast
        var j = n - 1
        for (i in 0 until n) {
            val yi = pts[i * 2 + 1]; val yj = pts[j * 2 + 1]
            if ((yi > y) != (yj > y) && x < (pts[j * 2] - pts[i * 2]) * (y - yi) / (yj - yi) + pts[i * 2]) inside = !inside
            j = i
        }
        return inside
    }
    if (s.kind == 1) {
        val cx = (s.left + s.right) / 2; val cy = (s.top + s.bottom) / 2
        fun inside(a: Float, b: Float) = a > 0 && b > 0 && ((x - cx) / a).let { it * it } + ((y - cy) / b).let { it * it } <= 1f
        val a = (s.right - s.left) / 2; val b = (s.bottom - s.top) / 2
        return inside(a + t, b + t) && (s.fill != 0 || !inside(a - t, b - t))
    }
    if (!(x in s.left - t..s.right + t && y in s.top - t..s.bottom + t)) return false
    return s.fill != 0 || !(x in s.left + t..s.right - t && y in s.top + t..s.bottom - t)
}

/** Topmost shape or image under the point, if any. */
fun hitItem(items: List<Item>, x: Float, y: Float, tol: Float): Item? = items.asReversed().firstOrNull {
    when (it) {
        is Img -> insideBox(it, x, y)
        is Shape -> shapeHit(it, x, y, tol)
        else -> false
    }
}

/** Draws background + items for the band scrollY..scrollY+viewH. Canvas must already be scaled to page units. */
fun drawPage(
    c: Canvas, items: List<Item>, bg: String, scrollY: Float, viewH: Float,
    bitmap: (String) -> Bitmap?, dimmed: Set<Item> = emptySet(), hidden: Set<Item> = emptySet(),
) {
    c.save()
    c.clipRect(0f, 0f, PAGE_W, viewH)
    c.drawColor(0xFFFFFFFF.toInt())
    c.translate(0f, -scrollY)
    drawBackground(c, bg, scrollY, scrollY + viewH, PAGE_W)
    for (it in items) {
        if (it in hidden) continue // being dragged: drawn separately
        when (it) {
            is Stroke -> if (it.maxY >= scrollY && it.minY <= scrollY + viewH) drawStroke(c, it, it in dimmed)
            is Shape -> if (it.maxY >= scrollY && it.minY <= scrollY + viewH) drawShape(c, it, it in dimmed)
            is Img -> bitmap(it.file)?.let { b -> drawImg(c, b, it, if (it in dimmed) 64 else 255) }
        }
    }
    c.restore()
}

/** Whole page, cropped to its content, for sharing. */
fun renderExport(items: List<Item>, bg: String, bitmap: (String) -> Bitmap?): Bitmap {
    val bottom = items.maxOfOrNull { it.maxY } ?: 0f
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
    is Img -> rotPt(x, y, item.x + item.w / 2, item.y + item.h / 2, -item.rot).let { q ->
        q.first >= item.x - r && q.first <= item.x + item.w + r && q.second >= item.y - r && q.second <= item.y + item.h + r
    }
    is Shape -> shapeHit(item, x, y, r)
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
