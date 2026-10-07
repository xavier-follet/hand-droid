package com.follet.jotter

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.util.UUID

/** Pages are a fixed 1000 units wide (scaled to the screen) and infinitely tall. */
const val PAGE_W = 1000f

sealed class Item

/** tool: 0 pen, 1 highlighter, 2 calligraphic pen. pts = x, y, pressure triples in page units. */
class Stroke(val tool: Int, val color: Int, val width: Float, val pts: FloatArray) : Item() {
    val n get() = pts.size / 3
    val varies = (2 until pts.size step 3).any { pts[it] != 1f }
    val minY = (1 until pts.size step 3).minOf { pts[it] } - width
    val maxY = (1 until pts.size step 3).maxOf { pts[it] } + width
    val path by lazy {
        android.graphics.Path().apply {
            moveTo(pts[0], pts[1])
            for (i in 1 until n) {
                val px = pts[(i - 1) * 3]; val py = pts[(i - 1) * 3 + 1]
                val x = pts[i * 3]; val y = pts[i * 3 + 1]
                quadTo(px, py, (px + x) / 2, (py + y) / 2)
            }
            lineTo(pts[(n - 1) * 3], pts[(n - 1) * 3 + 1])
        }
    }
    /** Calligraphic: each segment is swept by a flat nib at 45°, so width depends on direction. */
    val nib by lazy {
        val nx = width / 2 * 0.7071f; val ny = -width / 2 * 0.7071f
        android.graphics.Path().apply {
            for (i in 1 until n) {
                val x1 = pts[i * 3 - 3]; val y1 = pts[i * 3 - 2]; val x2 = pts[i * 3]; val y2 = pts[i * 3 + 1]
                val flip = nx * (y2 - y1) - ny * (x2 - x1) < 0 // keep every quad the same winding so overlaps never cancel
                val a = floatArrayOf(x1 - nx, y1 - ny, x1 + nx, y1 + ny, x2 + nx, y2 + ny, x2 - nx, y2 - ny)
                val o = if (flip) intArrayOf(6, 4, 2, 0) else intArrayOf(0, 2, 4, 6)
                moveTo(a[o[0]], a[o[0] + 1]); for (j in 1..3) lineTo(a[o[j]], a[o[j] + 1]); close()
            }
        }
    }
}

class Img(val file: String, val x: Float, val y: Float, val w: Float, val h: Float) : Item()

/** kind: "text" or "ink". bg: id from [BACKGROUNDS]. */
data class Note(
    val id: String, val title: String, val kind: String, val bg: String,
    val created: Long, val modified: Long, val archived: Boolean = false,
)

class Store(private val ctx: Context) {
    private val dir = File(ctx.filesDir, "notes").apply { mkdirs() }
    private val imgDir = File(ctx.filesDir, "img").apply { mkdirs() }
    val prefs = ctx.getSharedPreferences("jotter", 0)
    val notes = mutableStateListOf<Note>()

    /** Toolbox position: top, bottom, left or right. */
    var toolsPos by mutableStateOf(prefs.getString("pos", "top")!!); private set
    /** Theme: system, light or dark. */
    var theme by mutableStateOf(prefs.getString("theme", "system")!!); private set
    var fingerDraw by mutableStateOf(prefs.getBoolean("finger", true)); private set
    var lastBg by mutableStateOf(prefs.getString("bg", "college:45")!!); private set
    fun pickToolsPos(v: String) { toolsPos = v; prefs.edit().putString("pos", v).apply() }
    fun pickTheme(v: String) { theme = v; prefs.edit().putString("theme", v).apply() }
    fun pickFingerDraw(v: Boolean) { fingerDraw = v; prefs.edit().putBoolean("finger", v).apply() }

    init {
        notes += dir.listFiles { f -> f.extension == "meta" }.orEmpty().mapNotNull {
            runCatching {
                JSONObject(it.readText()).run {
                    Note(getString("id"), getString("title"), getString("kind"), getString("bg"),
                        getLong("created"), getLong("modified"), optBoolean("archived"))
                }
            }.getOrNull()
        }
    }

    fun create(kind: String, bg: String): Note {
        val now = System.currentTimeMillis()
        if (kind == "ink") { lastBg = bg; prefs.edit().putString("bg", bg).apply() }
        return Note(UUID.randomUUID().toString(), "", kind, bg, now, now).also { save(it) }
    }

    fun save(n: Note) {
        File(dir, "${n.id}.meta").writeText(JSONObject().put("id", n.id).put("title", n.title)
            .put("kind", n.kind).put("bg", n.bg).put("created", n.created)
            .put("modified", n.modified).put("archived", n.archived).toString())
        val i = notes.indexOfFirst { it.id == n.id }
        if (i >= 0) notes[i] = n else notes += n
    }

    fun delete(n: Note) {
        dir.listFiles { f -> f.name.startsWith(n.id) }?.forEach { it.delete() }
        notes.removeAll { it.id == n.id }
    }

    // Deleting moves a note's files to trash so a snackbar "Undo" can bring it back; leftovers from earlier runs are purged at start.
    private val trashDir = File(ctx.filesDir, "trash").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
    fun trash(n: Note) {
        dir.listFiles { f -> f.name.startsWith(n.id) }?.forEach { it.renameTo(File(trashDir, it.name)) }
        notes.removeAll { it.id == n.id }
    }
    fun restore(n: Note) {
        trashDir.listFiles { f -> f.name.startsWith(n.id) }?.forEach { it.renameTo(File(dir, it.name)) }
        if (notes.none { it.id == n.id }) notes += n
    }
    fun purge(n: Note) { trashDir.listFiles { f -> f.name.startsWith(n.id) }?.forEach { it.delete() } }

    fun thumbFile(n: Note) = File(dir, "${n.id}.png")

    fun readText(n: Note) = File(dir, "${n.id}.txt").takeIf { it.exists() }?.readText() ?: ""
    fun writeText(n: Note, s: String) = File(dir, "${n.id}.txt").writeText(s)

    fun readPages(n: Note): List<List<Item>> {
        val f = File(dir, "${n.id}.body")
        if (!f.exists()) return emptyList()
        return runCatching {
            DataInputStream(f.inputStream().buffered()).use { i ->
                List(i.readInt()) {
                    List(i.readInt()) {
                        if (i.readByte().toInt() == 0) {
                            val tool = i.readByte().toInt(); val color = i.readInt(); val w = i.readFloat()
                            Stroke(tool, color, w, FloatArray(i.readInt()) { i.readFloat() })
                        } else Img(i.readUTF(), i.readFloat(), i.readFloat(), i.readFloat(), i.readFloat())
                    }
                }
            }
        }.getOrDefault(emptyList())
    }

    fun writePages(n: Note, pages: List<List<Item>>) {
        val tmp = File(dir, "${n.id}.tmp")
        DataOutputStream(tmp.outputStream().buffered()).use { o ->
            o.writeInt(pages.size)
            for (p in pages) {
                o.writeInt(p.size)
                for (item in p) when (item) {
                    is Stroke -> {
                        o.writeByte(0); o.writeByte(item.tool); o.writeInt(item.color)
                        o.writeFloat(item.width); o.writeInt(item.pts.size)
                        item.pts.forEach { o.writeFloat(it) }
                    }
                    is Img -> {
                        o.writeByte(1); o.writeUTF(item.file)
                        o.writeFloat(item.x); o.writeFloat(item.y); o.writeFloat(item.w); o.writeFloat(item.h)
                    }
                }
            }
        }
        tmp.renameTo(File(dir, "${n.id}.body")) // atomic enough: a crash mid-write keeps the old body
    }

    fun saveThumb(n: Note, items: List<Item>) {
        val b = Bitmap.createBitmap(500, 375, Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(b)
        c.scale(0.5f, 0.5f)
        drawPage(c, items, n.bg, 0f, 750f, ::bitmap)
        thumbFile(n).outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 90, it) }
        b.recycle()
    }

    /** Copies a picked image into app storage; returns file name and aspect (h/w). */
    fun addImage(uri: Uri): Pair<String, Float>? = runCatching {
        val name = UUID.randomUUID().toString() + ".img"
        ctx.contentResolver.openInputStream(uri)!!.use { i -> File(imgDir, name).outputStream().use { i.copyTo(it) } }
        val b = bitmap(name)!!
        name to b.height.toFloat() / b.width
    }.getOrNull()

    private val bitmaps = HashMap<String, Bitmap?>()
    fun bitmap(name: String): Bitmap? = bitmaps.getOrPut(name) {
        val f = File(imgDir, name)
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, o)
        BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply {
            inSampleSize = maxOf(1, maxOf(o.outWidth, o.outHeight) / 2000)
        })
    }
}
