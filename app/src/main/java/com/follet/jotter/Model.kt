package com.follet.jotter

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.util.UUID

/** Pages are a fixed 1000 units wide (scaled to the screen) and infinitely tall. */
const val PAGE_W = 1000f

/** Vertical extent in page units, used to skip off-screen items and to size exports. */
sealed class Item {
    abstract val minY: Float
    abstract val maxY: Float
}

/** tool: 0 pen, 1 highlighter, 2 calligraphic pen. pts = x, y, pressure triples in page units. */
class Stroke(val tool: Int, val color: Int, val width: Float, val pts: FloatArray) : Item() {
    val n get() = pts.size / 3
    val varies = (2 until pts.size step 3).any { pts[it] != 1f }
    override val minY = (1 until pts.size step 3).minOf { pts[it] } - width
    override val maxY = (1 until pts.size step 3).maxOf { pts[it] } + width
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

/** rot: radians, clockwise, around the image centre. */
class Img(val file: String, val x: Float, val y: Float, val w: Float, val h: Float, val rot: Float = 0f) : Item() {
    private val reach get() = if (rot == 0f) h / 2 else kotlin.math.hypot(w, h) / 2 // half height of the (rotated) bounds, conservatively
    override val minY get() = y + h / 2 - reach
    override val maxY get() = y + h / 2 + reach
}

/**
 * kind: 0 rectangle, 1 ellipse, 2 triangle, 3 line, 4 arrow, 5 five-point star, 6 burst (spiky ellipse). Defined by two corner points (for lines: the two ends).
 * color is the border (ARGB, 0 = no border), fill is ARGB (0 = no fill). rot (radians, clockwise, around the box centre) turns
 * boxes; lines are turned by moving their ends. Immutable; editing makes a copy so undo can keep the old one.
 */
class Shape(
    val kind: Int, val color: Int, val fill: Int, val width: Float,
    val x1: Float, val y1: Float, val x2: Float, val y2: Float, val rot: Float = 0f,
) : Item() {
    val left get() = minOf(x1, x2)
    val right get() = maxOf(x1, x2)
    val top get() = minOf(y1, y2)
    val bottom get() = maxOf(y1, y2)
    val isLine get() = kind == 3 || kind == 4
    private val reach get() = // half height of the (rotated) bounds, conservatively, plus border and arrow heads
        (if (rot == 0f || isLine) (bottom - top) / 2 else kotlin.math.hypot(right - left, bottom - top) / 2) + width + (if (kind == 4) 40f else 0f)
    override val minY get() = (top + bottom) / 2 - reach
    override val maxY get() = (top + bottom) / 2 + reach
    fun copy(
        kind: Int = this.kind, color: Int = this.color, fill: Int = this.fill, width: Float = this.width,
        x1: Float = this.x1, y1: Float = this.y1, x2: Float = this.x2, y2: Float = this.y2, rot: Float = this.rot,
    ) = Shape(kind, color, fill, width, x1, y1, x2, y2, rot)
}

/**
 * Font families a text box can use: a system family name, or "asset:<name>" for a font bundled in assets/fonts, and the label shown
 * to the user. The position in this list is what a saved text box stores, so keep the order.
 */
val FONTS = listOf(
    "asset:OpenSans" to "Open Sans", "serif" to "Serif", "monospace" to "Monospace", "cursive" to "Handwriting", "asset:GochiHand" to "Gochi Hand",
)

/** Loads the bundled fonts once. Set up from [Store], used from the UI thread and the export thread. */
object BundledFonts {
    @Volatile var assets: android.content.res.AssetManager? = null
    private val cache = HashMap<String, android.graphics.Typeface>()

    @Synchronized fun get(name: String, flags: Int): android.graphics.Typeface = cache.getOrPut("$name/${flags and 3}") {
        val am = assets
        val bold = flags and 1 != 0; val italic = flags and 2 != 0
        runCatching {
            when (name) {
                // Open Sans ships as two variable fonts (upright and italic) with a weight axis; Android 8+ can set it
                "OpenSans" -> android.graphics.Typeface.Builder(am!!, if (italic) "fonts/OpenSans-Italic.ttf" else "fonts/OpenSans-Roman.ttf")
                    .setFontVariationSettings("'wght' ${if (bold) 700 else 400}, 'wdth' 100").build()!!
                // single-weight font: bold and italic are synthesised by Android
                else -> android.graphics.Typeface.create(
                    android.graphics.Typeface.createFromAsset(am!!, "fonts/$name-Regular.ttf"),
                    if (bold && italic) android.graphics.Typeface.BOLD_ITALIC else if (bold) android.graphics.Typeface.BOLD else if (italic) android.graphics.Typeface.ITALIC else android.graphics.Typeface.NORMAL,
                )
            }
        }.getOrElse { android.graphics.Typeface.create("sans-serif", if (bold && italic) android.graphics.Typeface.BOLD_ITALIC else if (bold) android.graphics.Typeface.BOLD else if (italic) android.graphics.Typeface.ITALIC else android.graphics.Typeface.NORMAL) }
    }
}

fun fontFace(font: Int, flags: Int): android.graphics.Typeface {
    val family = FONTS[font.coerceIn(0, FONTS.lastIndex)].first
    if (family.startsWith("asset:")) return BundledFonts.get(family.removePrefix("asset:"), flags)
    return android.graphics.Typeface.create(
        family,
        when (flags and 3) { 1 -> android.graphics.Typeface.BOLD; 2 -> android.graphics.Typeface.ITALIC; 3 -> android.graphics.Typeface.BOLD_ITALIC; else -> android.graphics.Typeface.NORMAL },
    )
}

/**
 * A text box on the page. [flags]: 1 bold, 2 italic, 4 underline. [align]: 0 left, 1 centre, 2 right. [size] (font size) and
 * [w] (box width, the text wraps inside it) are in page units; the height follows from the text. [rot] is in radians, clockwise,
 * around the box centre. Immutable, like the other items.
 */
class TextBox(
    val text: String, val color: Int, val size: Float, val flags: Int, val font: Int, val align: Int,
    val x: Float, val y: Float, val w: Float, val rot: Float = 0f,
) : Item() {
    val layout: android.text.StaticLayout by lazy {
        val face = fontFace(font, flags); val underline = flags and 4 != 0; val argb = color; val px = size // read here: inside apply{} "flags" and "color" would be the Paint's own
        val paint = android.text.TextPaint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            textSize = px; color = argb; typeface = face; isUnderlineText = underline
        }
        android.text.StaticLayout.Builder.obtain(text, 0, text.length, paint, w.toInt().coerceAtLeast(1))
            .setAlignment(when (align) { 1 -> android.text.Layout.Alignment.ALIGN_CENTER; 2 -> android.text.Layout.Alignment.ALIGN_OPPOSITE; else -> android.text.Layout.Alignment.ALIGN_NORMAL })
            .setIncludePad(false).build()
    }
    val h get() = layout.height.toFloat().coerceAtLeast(size)
    private val reach get() = if (rot == 0f) h / 2 else kotlin.math.hypot(w, h) / 2
    override val minY get() = y + h / 2 - reach
    override val maxY get() = y + h / 2 + reach
    fun copy(
        text: String = this.text, color: Int = this.color, size: Float = this.size, flags: Int = this.flags, font: Int = this.font,
        align: Int = this.align, x: Float = this.x, y: Float = this.y, w: Float = this.w, rot: Float = this.rot,
    ) = TextBox(text, color, size, flags, font, align, x, y, w, rot)
}

/** kind: "text" or "ink". bg: id from [BACKGROUNDS]. */
data class Note(
    val id: String, val title: String, val kind: String, val bg: String,
    val created: Long, val modified: Long, val archived: Boolean = false,
    val folder: String = "", // id of the folder it is in, "" = none
    val starred: Boolean = false,
)

/** A flat list of folders: no folders inside folders. */
data class Folder(val id: String, val name: String)

class Store(private val ctx: Context) {
    init { BundledFonts.assets = ctx.assets }
    private val dir = File(ctx.filesDir, "notes").apply { mkdirs() }
    private val imgDir = File(ctx.filesDir, "img").apply { mkdirs() }
    val prefs = ctx.getSharedPreferences("jotter", 0)
    val notes = mutableStateListOf<Note>()
    val exporter = Exporter(ctx, this)

    val folders = mutableStateListOf<Folder>()
    private val foldersFile = File(ctx.filesDir, "folders.json")
    init { loadFolders() }

    private fun loadFolders() {
        folders.clear()
        runCatching { JSONArray(foldersFile.readText()) }.getOrNull()?.let { a ->
            for (i in 0 until a.length()) a.getJSONObject(i).let { folders += Folder(it.getString("id"), it.getString("name")) }
        }
    }

    private fun saveFolders() {
        foldersFile.writeText(JSONArray().apply { folders.forEach { put(JSONObject().put("id", it.id).put("name", it.name)) } }.toString())
        exporter.touch(null)
    }

    fun addFolder(name: String): Folder = Folder(UUID.randomUUID().toString(), name).also { folders += it; saveFolders() }

    fun renameFolder(f: Folder, name: String) {
        val i = folders.indexOfFirst { it.id == f.id }
        if (i >= 0) { folders[i] = f.copy(name = name); saveFolders() }
    }

    /**
     * Removes the folder. Its notes are kept (moved out of any folder) unless [withNotes]; then the visible ones are trashed and
     * returned so the caller can offer Undo. Archived notes are never deleted this way.
     */
    fun deleteFolder(f: Folder, withNotes: Boolean = false): List<Note> {
        val inside = notes.filter { it.folder == f.id }
        val doomed = if (withNotes) inside.filter { !it.archived } else emptyList()
        doomed.forEach { trash(it) }
        (inside - doomed.toSet()).forEach { save(it.copy(folder = "")) }
        folders.removeAll { it.id == f.id }
        saveFolders()
        return doomed
    }

    /** Undo of [deleteFolder] with notes. */
    fun restoreFolder(f: Folder, notes: List<Note>) {
        if (folders.none { it.id == f.id }) folders += f
        saveFolders()
        notes.forEach { restore(it) }
    }

    /** Toolbox position: top, bottom, left or right. */
    var toolsPos by mutableStateOf(prefs.getString("pos", "top")!!); private set
    /** Theme: system, light or dark. */
    var theme by mutableStateOf(prefs.getString("theme", "system")!!); private set
    var fingerDraw by mutableStateOf(prefs.getBoolean("finger", true)); private set
    var lastBg by mutableStateOf(prefs.getString("bg", "college:45")!!); private set
    fun pickToolsPos(v: String) { toolsPos = v; prefs.edit().putString("pos", v).apply() }
    fun pickTheme(v: String) { theme = v; prefs.edit().putString("theme", v).apply() }
    fun pickFingerDraw(v: Boolean) { fingerDraw = v; prefs.edit().putBoolean("finger", v).apply() }

    init { reload() }

    fun reload() {
        notes.clear()
        notes += dir.listFiles { f -> f.extension == "meta" }.orEmpty().mapNotNull {
            runCatching {
                JSONObject(it.readText()).run {
                    Note(getString("id"), getString("title"), getString("kind"), getString("bg"),
                        getLong("created"), getLong("modified"), optBoolean("archived"), optString("folder"), optBoolean("starred"))
                }
            }.getOrNull()
        }
    }

    fun create(kind: String, bg: String, folder: String = ""): Note {
        val now = System.currentTimeMillis()
        if (kind == "ink") { lastBg = bg; prefs.edit().putString("bg", bg).apply() }
        return Note(UUID.randomUUID().toString(), "", kind, bg, now, now, folder = folder).also { save(it) }
    }

    fun save(n: Note) {
        File(dir, "${n.id}.meta").writeText(JSONObject().put("id", n.id).put("title", n.title)
            .put("kind", n.kind).put("bg", n.bg).put("created", n.created)
            .put("modified", n.modified).put("archived", n.archived).put("folder", n.folder).put("starred", n.starred).toString())
        val i = notes.indexOfFirst { it.id == n.id }
        if (i >= 0) notes[i] = n else notes += n
        exporter.touch(n.id)
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
        exporter.touch(null)
    }
    fun restore(n: Note) {
        trashDir.listFiles { f -> f.name.startsWith(n.id) }?.forEach { it.renameTo(File(dir, it.name)) }
        if (notes.none { it.id == n.id }) notes += n
        exporter.touch(null)
    }
    fun purge(n: Note) { trashDir.listFiles { f -> f.name.startsWith(n.id) }?.forEach { it.delete() } }

    /** All drawings (metadata, strokes, thumbnails, text) and their images as one zip, the .jotter backup. */
    fun backupTo(out: java.io.OutputStream) {
        java.util.zip.ZipOutputStream(out.buffered()).use { z ->
            fun add(prefix: String, f: File) {
                z.putNextEntry(java.util.zip.ZipEntry(prefix + f.name)); f.inputStream().use { it.copyTo(z) }; z.closeEntry()
            }
            dir.listFiles { f -> f.isFile && f.extension in setOf("meta", "body", "txt", "png") }.orEmpty().forEach { add("notes/", it) }
            imgDir.listFiles { f -> f.isFile }.orEmpty().forEach { add("img/", it) }
            if (foldersFile.exists()) { z.putNextEntry(java.util.zip.ZipEntry("folders.json")); foldersFile.inputStream().use { it.copyTo(z) }; z.closeEntry() }
        }
    }

    /**
     * Adds the notes of a .jotter backup. A note that already exists is replaced only when the backup's copy is newer.
     * Returns (restored, skipped).
     */
    fun restoreFrom(input: java.io.InputStream): Pair<Int, Int> {
        val tmp = File(ctx.cacheDir, "restore").apply { deleteRecursively(); mkdirs() }
        java.util.zip.ZipInputStream(input.buffered()).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                if (e.name == "folders.json") { File(tmp, "folders.json").outputStream().use { z.copyTo(it) }; continue }
                val parts = e.name.split('/')
                if (e.isDirectory || parts.size != 2 || parts[0] !in setOf("notes", "img") || parts[1].isEmpty() || parts[1].startsWith(".")) continue
                File(tmp, parts[0]).mkdirs()
                File(File(tmp, parts[0]), parts[1]).outputStream().use { z.copyTo(it) } // flat names only, so nothing can escape the folder
            }
        }
        var restored = 0; var skipped = 0
        val idOk = Regex("[0-9a-fA-F-]{36}")
        File(tmp, "notes").listFiles { f -> f.extension == "meta" }.orEmpty().forEach { m ->
            val meta = runCatching { JSONObject(m.readText()) }.getOrNull() ?: return@forEach
            val id = meta.optString("id")
            if (!idOk.matches(id)) return@forEach
            val have = notes.firstOrNull { it.id == id }
            if (have != null && have.modified >= meta.optLong("modified")) { skipped++; return@forEach }
            File(tmp, "notes").listFiles { f -> f.name.startsWith("$id.") }.orEmpty().forEach { it.copyTo(File(dir, it.name), overwrite = true) }
            restored++
        }
        File(tmp, "img").listFiles().orEmpty().forEach { val t = File(imgDir, it.name); if (!t.exists()) it.copyTo(t) }
        runCatching { JSONArray(File(tmp, "folders.json").readText()) }.getOrNull()?.let { a -> // folders the backup has and we lack
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i); val id = o.getString("id")
                if (idOk.matches(id) && folders.none { it.id == id }) folders += Folder(id, o.getString("name"))
            }
            saveFolders()
        }
        tmp.deleteRecursively()
        reload()
        exporter.touch(null)
        return restored to skipped
    }

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
                        when (i.readByte().toInt()) {
                            0 -> {
                                val tool = i.readByte().toInt(); val color = i.readInt(); val w = i.readFloat()
                                Stroke(tool, color, w, FloatArray(i.readInt()) { i.readFloat() })
                            }
                            1 -> Img(i.readUTF(), i.readFloat(), i.readFloat(), i.readFloat(), i.readFloat())
                            4 -> Img(i.readUTF(), i.readFloat(), i.readFloat(), i.readFloat(), i.readFloat(), i.readFloat())
                            5 -> TextBox(String(ByteArray(i.readInt()).also { b -> i.readFully(b) }, Charsets.UTF_8), i.readInt(), i.readFloat(),
                                i.readByte().toInt(), i.readByte().toInt(), i.readByte().toInt(), i.readFloat(), i.readFloat(), i.readFloat(), i.readFloat())
                            2 -> Shape(i.readByte().toInt(), i.readInt(), i.readInt(), i.readFloat(),
                                i.readFloat(), i.readFloat(), i.readFloat(), i.readFloat())
                            else -> Shape(i.readByte().toInt(), i.readInt(), i.readInt(), i.readFloat(),
                                i.readFloat(), i.readFloat(), i.readFloat(), i.readFloat(), i.readFloat())
                        }
                    }
                }
            }
        }.getOrElse { e -> // never let an unreadable body be silently overwritten by an empty page: keep a copy
            android.util.Log.e("Jotter", "could not read ${f.name}", e)
            runCatching { f.copyTo(File(dir, "${n.id}.bad"), overwrite = true) }
            emptyList()
        }
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
                        o.writeByte(4); o.writeUTF(item.file)
                        o.writeFloat(item.x); o.writeFloat(item.y); o.writeFloat(item.w); o.writeFloat(item.h); o.writeFloat(item.rot)
                    }
                    is TextBox -> {
                        val b = item.text.toByteArray(Charsets.UTF_8)
                        o.writeByte(5); o.writeInt(b.size); o.write(b); o.writeInt(item.color); o.writeFloat(item.size)
                        o.writeByte(item.flags); o.writeByte(item.font); o.writeByte(item.align)
                        o.writeFloat(item.x); o.writeFloat(item.y); o.writeFloat(item.w); o.writeFloat(item.rot)
                    }
                    is Shape -> {
                        o.writeByte(3); o.writeByte(item.kind); o.writeInt(item.color); o.writeInt(item.fill); o.writeFloat(item.width)
                        o.writeFloat(item.x1); o.writeFloat(item.y1); o.writeFloat(item.x2); o.writeFloat(item.y2); o.writeFloat(item.rot)
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
    @Synchronized // also called from the export thread
    fun bitmap(name: String): Bitmap? = bitmaps.getOrPut(name) {
        val f = File(imgDir, name)
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, o)
        BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply {
            inSampleSize = maxOf(1, maxOf(o.outWidth, o.outHeight) / 2000)
        })
    }
}
