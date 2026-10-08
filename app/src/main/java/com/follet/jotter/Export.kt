package com.follet.jotter

import android.content.Context
import android.content.Intent
import android.graphics.pdf.PdfDocument
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.Executors
import kotlin.math.ceil

/**
 * Keeps a copy of the drawings in a folder the user picked once (for example a Google Drive folder): one A4-wide PDF per
 * drawing, plus [BACKUP_NAME] holding all drawings so they can be restored. Runs 10 s after the last change, when a note is
 * left and when the app goes to the background. Everything happens on one background thread.
 */
class Exporter(private val ctx: Context, private val store: Store) {
    private val scope = CoroutineScope(SupervisorJob() + Executors.newSingleThreadExecutor().asCoroutineDispatcher())
    private val lock = Any()
    private val dirty = LinkedHashSet<String>() // note ids whose PDF is out of date
    private var changes = 0L                    // bumped on every change; the backup is current when backedUp == changes
    private var backedUp = 0L
    private var lastBackup = 0L
    private var idle: Job? = null

    /** Display name of the chosen folder, "" when none. */
    var folderName by mutableStateOf(store.prefs.getString("exportDirName", "") ?: ""); private set
    /** What happened last, for the settings screen. */
    var status by mutableStateOf(store.prefs.getString("exportStatus", "") ?: ""); private set

    private val enabled get() = store.prefs.getString("exportDir", null) != null

    fun setFolder(uri: Uri) {
        ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        val name = DocumentFile.fromTreeUri(ctx, uri)?.name ?: uri.lastPathSegment ?: "folder"
        store.prefs.edit().putString("exportDir", uri.toString()).putString("exportDirName", name).apply()
        folderName = name
        exportAll()
    }

    fun clearFolder() {
        store.prefs.getString("exportDir", null)?.let { runCatching { ctx.contentResolver.releasePersistableUriPermission(Uri.parse(it), Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) } }
        store.prefs.edit().remove("exportDir").remove("exportDirName").apply()
        folderName = ""
        report("")
    }

    /** Something changed: [id] needs a new PDF (null = only the backup). Starts or restarts the 10 s idle timer. */
    fun touch(id: String?) {
        if (!enabled) return
        synchronized(lock) { if (id != null) dirty += id; changes++ }
        schedule(10_000, false)
    }

    /** Exports now (leaving a note, app to background). */
    fun flushNow() {
        if (enabled) schedule(0, true)
    }

    /** Every drawing again, e.g. right after choosing the folder. */
    fun exportAll() {
        if (!enabled) return
        synchronized(lock) { dirty += store.notes.filter { it.kind == "ink" }.map { it.id }; changes++ }
        schedule(0, true)
    }

    private fun schedule(delayMs: Long, force: Boolean) {
        synchronized(lock) {
            idle?.cancel()
            idle = scope.launch { if (delayMs > 0) delay(delayMs); run(force) }
        }
    }

    private fun run(force: Boolean) {
        val ids: List<String>; val snap: Long
        synchronized(lock) { ids = dirty.toList(); dirty.clear(); snap = changes }
        val needBackup = snap != backedUp
        if (ids.isEmpty() && !needBackup) return
        val root = store.prefs.getString("exportDir", null)?.let { DocumentFile.fromTreeUri(ctx, Uri.parse(it)) }
        if (root == null || !root.exists() || !root.canWrite()) {
            synchronized(lock) { dirty += ids }
            report("Cannot write to the export folder. Choose it again in Settings.")
            return
        }
        var failed: Throwable? = null
        var exported = 0
        for (id in ids) runCatching { if (exportPdf(root, id)) exported++ }.onFailure { failed = it; synchronized(lock) { dirty += id } }
        if (needBackup) {
            val wait = 60_000 - (System.currentTimeMillis() - lastBackup)
            if (!force && wait > 0) schedule(wait, false) // the backup rewrites everything, so at most once a minute
            else runCatching { writeBackup(root); backedUp = snap; lastBackup = System.currentTimeMillis() }.onFailure { failed = it }
        }
        val f = failed
        if (f != null) {
            report("Export problem: ${f.message ?: f.javaClass.simpleName}. Will retry.")
            schedule(60_000, false)
        } else report("Last export ${DateFormat.getTimeInstance(DateFormat.SHORT).format(Date())}" + if (exported > 0) " ($exported PDF${if (exported == 1) "" else "s"})" else "")
    }

    private fun report(s: String) {
        status = s
        store.prefs.edit().putString("exportStatus", s).apply()
    }

    /** Returns false when there was nothing to export. */
    private fun exportPdf(root: DocumentFile, id: String): Boolean {
        val note = store.notes.firstOrNull { it.id == id } ?: return false
        if (note.kind != "ink") return false
        val pages = store.readPages(note)
        if (pages.all { it.isEmpty() }) return false
        val tmp = File(ctx.cacheDir, "export.pdf")
        renderPdf(pages, note.bg, tmp)
        val name = pdfName(note)
        copyInto(root, name, "application/pdf", tmp)
        val key = "expname_$id"
        val prev = store.prefs.getString(key, null)
        if (prev != null && prev != name) root.findFile(prev)?.delete() // renamed note: do not leave the old file behind
        store.prefs.edit().putString(key, name).apply()
        return true
    }

    private fun writeBackup(root: DocumentFile) {
        val tmp = File(ctx.cacheDir, "backup.jotter")
        tmp.outputStream().use { store.backupTo(it) }
        copyInto(root, BACKUP_NAME, "application/octet-stream", tmp)
    }

    /** Writes [file] as [name] in [root], replacing an existing file of that name. */
    private fun copyInto(root: DocumentFile, name: String, mime: String, file: File) {
        val existing = root.findFile(name)
        fun write(target: DocumentFile, mode: String) {
            ctx.contentResolver.openOutputStream(target.uri, mode)!!.use { out -> file.inputStream().use { it.copyTo(out) } }
        }
        if (existing == null) { write(root.createFile(mime, name) ?: error("cannot create $name"), "w"); return }
        try { write(existing, "wt") } catch (e: Exception) { // some providers cannot overwrite in place: replace the file instead
            existing.delete()
            write(root.createFile(mime, name) ?: throw e, "w")
        }
    }

    private fun pdfName(n: Note): String {
        val base = n.title.ifBlank { "Untitled" }.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").trim().take(80).trimEnd('.', ' ')
        return "${base.ifEmpty { "Untitled" }} (${n.id.take(6)}).pdf"
    }

    /**
     * A4 width (595 pt), every note page as one PDF page that ends where its last item ends: no page breaks inside a page.
     * Vector all the way: strokes, shapes and paper lines are drawn by the same code as on screen.
     */
    private fun renderPdf(pages: List<List<Item>>, bg: String, out: File) {
        val doc = PdfDocument()
        val s = 595f / PAGE_W
        pages.forEachIndexed { i, items ->
            val units = if (items.isEmpty()) PAGE_W * 297f / 210f else items.maxOf { it.maxY } + 8f
            val heightPt = ceil(units * s).toInt().coerceAtLeast(40)
            val page = doc.startPage(PdfDocument.PageInfo.Builder(595, heightPt, i + 1).create())
            page.canvas.scale(s, s)
            drawPage(page.canvas, items, bg, 0f, heightPt / s, store::bitmap)
            doc.finishPage(page)
        }
        out.outputStream().use { doc.writeTo(it) }
        doc.close()
    }

    companion object { const val BACKUP_NAME = "Jotter-backup.jotter" }
}
