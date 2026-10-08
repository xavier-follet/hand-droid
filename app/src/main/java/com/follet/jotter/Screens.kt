package com.follet.jotter

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.graphics.BitmapFactory
import android.text.format.DateUtils
import androidx.compose.foundation.Image
import kotlinx.coroutines.delay
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.border
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import kotlinx.coroutines.Dispatchers
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardCapitalization
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.Canvas
import androidx.core.content.FileProvider
import java.io.File

/** Focuses the field as soon as its dialog is on screen and raises the keyboard, so you can type straight away. */
@Composable
fun Modifier.autoFocus(): Modifier {
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) {
        delay(150) // the dialog window must be attached before it can take focus
        runCatching { focus.requestFocus() }
        keyboard?.show()
    }
    return this.focusRequester(focus)
}

private fun Note.label() = title.ifBlank { "Untitled" }

/** Compact search box for the top bar: filters the notes of the page you are on by title. */
@Composable
private fun SearchField(query: String, onChange: (String) -> Unit, modifier: Modifier = Modifier) {
    val c = MaterialTheme.colorScheme
    BasicTextField(
        query, onChange,
        modifier.heightIn(min = 44.dp).clip(RoundedCornerShape(22.dp)).background(c.surfaceVariant)
            .border(1.dp, c.outline, RoundedCornerShape(22.dp)).padding(start = 14.dp, end = 4.dp)
            .semantics { contentDescription = "Search notes" },
        singleLine = true, textStyle = TextStyle(fontSize = 16.sp, color = c.onSurface), cursorBrush = SolidColor(c.onSurface),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        decorationBox = { inner ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Search, null, tint = c.onSurfaceVariant)
                Spacer(Modifier.width(8.dp))
                Box(Modifier.weight(1f)) { if (query.isEmpty()) Text("Search", color = c.onSurfaceVariant, fontSize = 16.sp); inner() }
                if (query.isNotEmpty()) IconButton({ onChange("") }, Modifier.size(40.dp)) { Icon(Icons.Default.Close, "Clear search") }
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Home(store: Store, open: (Note) -> Unit, onSettings: () -> Unit) {
    val ctx = LocalContext.current
    var byName by remember { mutableStateOf(false) }
    var asc by remember { mutableStateOf(false) }
    var view by remember { mutableStateOf("all") } // "all" = start page, "archive", or a folder id
    var query by remember { mutableStateOf("") }
    var fab by remember { mutableStateOf(false) }
    var picker by remember { mutableStateOf(false) }
    var newFolder by remember { mutableStateOf(false) }
    var renamingFolder by remember { mutableStateOf(false) }
    var deletingFolder by remember { mutableStateOf(false) }
    var folderMenu by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<Note?>(null) }
    var moving by remember { mutableStateOf<Note?>(null) }
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    val folder = store.folders.firstOrNull { it.id == view }
    val v = if (view == "all" || view == "archive" || folder != null) view else "all" // a folder that vanished (restore, delete) falls back to the start page
    val sortedFolders = store.folders.sortedBy { it.name.lowercase() }
    fun go(to: String) { view = to; query = ""; scope.launch { drawer.close() } }

    // A second quick tap on the menu button lands on the scrim while the drawer is still sliding in, and the scrim closes it again.
    // A close that arrives within 500 ms of an open is therefore not a real close: put the drawer back.
    var openedAt by remember { mutableLongStateOf(0L) }
    fun openDrawer() { openedAt = SystemClock.uptimeMillis(); scope.launch { drawer.open() } }
    LaunchedEffect(drawer.targetValue) {
        if (drawer.targetValue == DrawerValue.Closed && SystemClock.uptimeMillis() - openedAt < 500) drawer.open()
    }

    BackHandler(enabled = v != "all") { go("all") } // back from a folder or the archive returns to the start page
    BackHandler(enabled = drawer.isOpen) { scope.launch { drawer.close() } }

    fun delete(n: Note) {
        store.trash(n)
        snackbar.currentSnackbarData?.dismiss() // the previous delete becomes final
        scope.launch {
            val r = snackbar.showSnackbar("Deleted “${n.label()}”", "Undo", duration = SnackbarDuration.Long)
            if (r == SnackbarResult.ActionPerformed) store.restore(n) else store.purge(n)
        }
    }

    fun sortBtn(name: Boolean) { if (byName == name) asc = !asc else { byName = name; asc = name } }
    val base = store.notes.filter { n ->
        when (v) { "archive" -> n.archived; "all" -> !n.archived; else -> !n.archived && n.folder == v }
    }
    val matched = if (query.isBlank()) base else base.filter { it.label().contains(query.trim(), ignoreCase = true) }
    val sorted = matched.let { l ->
        val s = if (byName) l.sortedBy { it.label().lowercase() } else l.sortedBy { it.modified }
        if (asc) s else s.reversed()
    }
    val shown = sorted.filter { it.starred } + sorted.filter { !it.starred } // starred first, whatever the sort order
    val newFolderId = folder?.id ?: "" // notes created inside a folder belong to it

    ModalNavigationDrawer(
        drawerState = drawer,
        drawerContent = {
            ModalDrawerSheet {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text("Jotter", Modifier.padding(horizontal = 28.dp, vertical = 20.dp), style = MaterialTheme.typography.headlineSmall)
                    val pad = NavigationDrawerItemDefaults.ItemPadding
                    NavigationDrawerItem({ Text("All notes") }, v == "all", { go("all") }, Modifier.padding(pad), icon = { Icon(Icons.Default.Notes, null) })
                    if (sortedFolders.isNotEmpty())
                        Text("Folders", Modifier.padding(start = 28.dp, top = 16.dp, bottom = 4.dp), style = MaterialTheme.typography.titleSmall)
                    sortedFolders.forEach { f ->
                        NavigationDrawerItem(
                            { Text(f.name, maxLines = 1, overflow = TextOverflow.Ellipsis) }, v == f.id, { go(f.id) }, Modifier.padding(pad),
                            icon = { Icon(Icons.Default.Folder, null) },
                            badge = { Text(store.notes.count { !it.archived && it.folder == f.id }.toString()) },
                        )
                    }
                    HorizontalDivider(Modifier.padding(vertical = 8.dp, horizontal = 28.dp))
                    NavigationDrawerItem({ Text("Archive") }, v == "archive", { go("archive") }, Modifier.padding(pad), icon = { Icon(Icons.Default.Archive, null) })
                }
            }
        },
    ) {
        Scaffold(
            snackbarHost = { SnackbarHost(snackbar) },
            topBar = {
                TopAppBar(
                    navigationIcon = {
                        if (v == "all") IconButton({ openDrawer() }) { Icon(Icons.Default.Menu, "Open menu") }
                        else IconButton({ go("all") }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back to all notes") }
                    },
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(when (v) { "all" -> "Jotter"; "archive" -> "Archive"; else -> folder?.name ?: "" },
                                Modifier.widthIn(max = 220.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Spacer(Modifier.width(16.dp))
                            SearchField(query, { query = it }, Modifier.weight(1f).widthIn(max = 420.dp))
                        }
                    },
                    actions = {
                        for (name in listOf(true, false)) TextButton({ sortBtn(name) }, Modifier.defaultMinSize(48.dp, 48.dp).semantics {
                            contentDescription = (if (name) "Sort by name" else "Sort by date") +
                                if (byName == name) (if (asc) ", ascending" else ", descending") else ""
                        }, contentPadding = PaddingValues(horizontal = 8.dp)) {
                            Icon(if (name) Icons.Default.SortByAlpha else Icons.Default.Schedule, null)
                            if (byName == name) Icon(if (asc) Icons.Default.ArrowUpward else Icons.Default.ArrowDownward, null, Modifier.size(16.dp))
                        }
                        if (folder != null) Box {
                            IconButton({ folderMenu = true }) { Icon(Icons.Default.MoreVert, "Folder options") }
                            DropdownMenu(folderMenu, { folderMenu = false }) {
                                DropdownMenuItem({ Text("Rename folder…") }, { folderMenu = false; renamingFolder = true })
                                DropdownMenuItem({ Text("Delete folder…") }, { folderMenu = false; deletingFolder = true })
                            }
                        }
                        IconButton(onSettings) { Icon(Icons.Default.Settings, "Settings") }
                    },
                )
            },
            floatingActionButton = {
                if (v != "archive") Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (fab) {
                        if (v == "all") ExtendedFloatingActionButton({ fab = false; newFolder = true }) { // no folders inside folders
                            Icon(Icons.Default.CreateNewFolder, null); Spacer(Modifier.width(8.dp)); Text("Folder")
                        }
                        ExtendedFloatingActionButton({ fab = false; picker = true }) {
                            Icon(Icons.Default.Draw, null); Spacer(Modifier.width(8.dp)); Text("Handwriting note…")
                        }
                        ExtendedFloatingActionButton({ fab = false; open(store.create("ink", store.lastBg, newFolderId)) }) {
                            Icon(Icons.Default.Edit, null); Spacer(Modifier.width(8.dp))
                            Text("Handwriting: " + BACKGROUNDS.first { it.first == store.lastBg }.second)
                        }
                    }
                    FloatingActionButton({ fab = !fab }) { Icon(if (fab) Icons.Default.Close else Icons.Default.Add, "New") }
                }
            },
        ) { pad ->
            if (shown.isEmpty()) Box(Modifier.fillMaxSize().padding(pad), Alignment.Center) {
                Text(
                    when {
                        query.isNotBlank() -> "No matches"
                        v == "archive" -> "Nothing archived"
                        v == "all" -> "Tap + to create a note"
                        else -> "This folder is empty"
                    },
                )
            }
            LazyVerticalGrid(
                GridCells.Adaptive(220.dp), Modifier.padding(pad),
                contentPadding = PaddingValues(16.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                items(shown, key = { it.id }) { n ->
                    NoteCard(n, store, { open(n) },
                        onRename = { renaming = n }, onShare = { share(ctx, store, n) },
                        onArchive = { store.save(n.copy(archived = !n.archived)) }, onDelete = { delete(n) },
                        onStar = { store.save(n.copy(starred = !n.starred)) }, onMove = { moving = n })
                }
            }
        }
    }

    if (picker) BackgroundPicker({ picker = false }) { picker = false; open(store.create("ink", it, newFolderId)) }

    renaming?.let { n ->
        var t by remember { mutableStateOf(n.title) }
        AlertDialog(
            onDismissRequest = { renaming = null }, title = { Text("Rename") },
            text = { OutlinedTextField(t, { t = it }, Modifier.autoFocus(), singleLine = true, keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences)) },
            confirmButton = { TextButton({ store.save(n.copy(title = t.trim())); renaming = null }) { Text("OK") } },
            dismissButton = { TextButton({ renaming = null }) { Text("Cancel") } },
        )
    }

    if (newFolder) {
        var t by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { newFolder = false }, title = { Text("New folder") },
            text = { OutlinedTextField(t, { t = it }, Modifier.autoFocus(), label = { Text("Folder name") }, singleLine = true, keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences)) },
            confirmButton = { TextButton({ val f = store.addFolder(t.trim()); newFolder = false; go(f.id) }, enabled = t.isNotBlank()) { Text("OK") } }, // opens the new folder
            dismissButton = { TextButton({ newFolder = false }) { Text("Cancel") } },
        )
    }

    if (renamingFolder && folder != null) {
        var t by remember { mutableStateOf(folder.name) }
        AlertDialog(
            onDismissRequest = { renamingFolder = false }, title = { Text("Rename folder") },
            text = { OutlinedTextField(t, { t = it }, Modifier.autoFocus(), label = { Text("Folder name") }, singleLine = true, keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences)) },
            confirmButton = { TextButton({ store.renameFolder(folder, t.trim()); renamingFolder = false }, enabled = t.isNotBlank()) { Text("OK") } },
            dismissButton = { TextButton({ renamingFolder = false }) { Text("Cancel") } },
        )
    }

    if (deletingFolder && folder != null) {
        val inFolder = store.notes.count { !it.archived && it.folder == folder.id }
        var withNotes by remember { mutableStateOf(false) } // never ticked by default
        AlertDialog(
            onDismissRequest = { deletingFolder = false }, title = { Text("Delete “${folder.name}”?") },
            text = {
                Column {
                    Text("The notes in it stay in All notes, unless you tick the box.")
                    if (inFolder > 0) Row(
                        Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(top = 8.dp)
                            .toggleable(withNotes, role = Role.Checkbox, onValueChange = { withNotes = it }),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(withNotes, null)
                        Spacer(Modifier.width(8.dp))
                        Text("Also delete the $inFolder note${if (inFolder == 1) "" else "s"} in it")
                    }
                }
            },
            confirmButton = {
                TextButton({
                    deletingFolder = false
                    val deleted = store.deleteFolder(folder, withNotes)
                    go("all")
                    if (deleted.isNotEmpty()) {
                        snackbar.currentSnackbarData?.dismiss() // the previous delete becomes final
                        scope.launch {
                            val r = snackbar.showSnackbar("Deleted “${folder.name}” and ${deleted.size} note${if (deleted.size == 1) "" else "s"}", "Undo", duration = SnackbarDuration.Long)
                            if (r == SnackbarResult.ActionPerformed) store.restoreFolder(folder, deleted) else deleted.forEach { store.purge(it) }
                        }
                    }
                }) { Text(if (withNotes) "Delete folder and notes" else "Delete folder") }
            },
            dismissButton = { TextButton({ deletingFolder = false }) { Text("Cancel") } },
        )
    }

    moving?.let { n ->
        AlertDialog(
            onDismissRequest = { moving = null }, title = { Text("Move “${n.label()}” to") },
            text = {
                Column(Modifier.selectableGroup().verticalScroll(rememberScrollState())) {
                    (listOf(Folder("", "No folder")) + sortedFolders).forEach { f ->
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 48.dp)
                                .selectable(n.folder == f.id, onClick = { store.save(n.copy(folder = f.id)); moving = null }, role = Role.RadioButton),
                            verticalAlignment = Alignment.CenterVertically,
                        ) { RadioButton(n.folder == f.id, null); Spacer(Modifier.width(12.dp)); Text(f.name) }
                    }
                    if (sortedFolders.isEmpty()) Text("No folders yet. Create one with + → Folder on the start page.", Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {},
            dismissButton = { TextButton({ moving = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun NoteCard(
    n: Note, store: Store, onClick: () -> Unit,
    onRename: () -> Unit, onShare: () -> Unit, onArchive: () -> Unit, onDelete: () -> Unit,
    onStar: () -> Unit, onMove: () -> Unit,
) {
    val ctx = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth().clickable(onClickLabel = "Open ${n.label()}", onClick = onClick)) {
        Box(Modifier.fillMaxWidth().aspectRatio(4f / 3f).background(Color.White)) {
            if (n.kind == "ink") {
                val thumb = remember(n.id, n.modified) {
                    BitmapFactory.decodeFile(store.thumbFile(n).path)?.asImageBitmap()
                }
                if (thumb != null) Image(thumb, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            } else {
                val preview = remember(n.id, n.modified) { store.readText(n).take(300) }
                Text(preview, Modifier.padding(12.dp), color = Color.DarkGray, maxLines = 8, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall)
            }
            // star: the touch target is the full 48dp
            Box(
                Modifier.align(Alignment.TopEnd).size(48.dp)
                    .toggleable(n.starred, role = Role.Checkbox, onValueChange = { onStar() })
                    .semantics { contentDescription = if (n.starred) "Starred: ${n.label()}" else "Star ${n.label()}" },
                Alignment.Center,
            ) {
                // just the star, no disc: a slightly larger black copy underneath gives it a rim, so it reads on white paper and on dark ink
                val shape = if (n.starred) Icons.Default.Star else Icons.Default.StarBorder
                Icon(shape, null, Modifier.size(31.dp), tint = Color.Black)
                Icon(shape, null, Modifier.size(24.dp), tint = if (n.starred) Color(0xFFFFC107) else Color.White)
            }
        }
        Row(Modifier.padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(n.label(), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                Text(DateUtils.formatDateTime(ctx, n.modified, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_ALL),
                    style = MaterialTheme.typography.bodySmall)
            }
            Box {
                IconButton({ menu = true }) { Icon(Icons.Default.MoreVert, "More options for ${n.label()}") }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem({ Text("Rename") }, { menu = false; onRename() })
                    DropdownMenuItem({ Text("Move to folder…") }, { menu = false; onMove() })
                    DropdownMenuItem({ Text("Share") }, { menu = false; onShare() })
                    DropdownMenuItem({ Text(if (n.archived) "Unarchive" else "Archive") }, { menu = false; onArchive() })
                    DropdownMenuItem({ Text("Delete") }, { menu = false; onDelete() })
                }
            }
        }
    }
}

@Composable
private fun BackgroundPicker(onDismiss: () -> Unit, onPick: (String) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text("Choose paper") },
        modifier = Modifier.widthIn(max = 720.dp),
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
        text = {
            LazyVerticalGrid(GridCells.Adaptive(130.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(BACKGROUNDS) { (id, label) ->
                    Column(Modifier.clickable { onPick(id) }, horizontalAlignment = Alignment.CenterHorizontally) {
                        Canvas(Modifier.fillMaxWidth().aspectRatio(1.25f).clip(RoundedCornerShape(6.dp))) {
                            drawIntoCanvas {
                                val k = size.width / 500f // preview shows 500 units of page width
                                it.nativeCanvas.scale(k, k)
                                drawPage(it.nativeCanvas, emptyList(), id, 0f, size.height / k, { null })
                            }
                        }
                        Text(label, style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun Choices(title: String, options: List<Pair<String, String>>, current: String, pick: (String) -> Unit) {
    Column(Modifier.selectableGroup()) {
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
        options.forEach { (id, label) ->
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(current == id, onClick = { pick(id) }, role = Role.RadioButton),
                verticalAlignment = Alignment.CenterVertically,
            ) { RadioButton(current == id, null); Spacer(Modifier.width(12.dp)); Text(label) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(store: Store, onBack: () -> Unit) {
    Scaffold(topBar = {
        TopAppBar(title = { Text("Settings") }, navigationIcon = {
            IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
        })
    }) { pad ->
        Column(Modifier.padding(pad).padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Choices("Toolbox position", listOf("top" to "Top", "bottom" to "Bottom", "left" to "Left", "right" to "Right"),
                store.toolsPos, store::pickToolsPos)
            Choices("Theme", listOf("system" to "Follow system", "light" to "Light", "dark" to "Dark"), store.theme, store::pickTheme)
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(store.fingerDraw, role = Role.Switch, onValueChange = store::pickFingerDraw),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Draw with finger", style = MaterialTheme.typography.titleMedium)
                    Text("Turn off to ignore touch for drawing (palm protection): only a stylus draws and a finger scrolls.")
                }
                Switch(store.fingerDraw, null)
            }
            ExportSettings(store)
        }
    }
}

@Composable
private fun ExportSettings(store: Store) {
    val ctx = LocalContext.current
    val ex = store.exporter
    val scope = rememberCoroutineScope()
    var restoreMsg by remember { mutableStateOf("") }
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> if (uri != null) ex.setFolder(uri) }
    val pickBackup = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            restoreMsg = "Restoring…"
            scope.launch(Dispatchers.IO) {
                restoreMsg = runCatching {
                    val (n, s) = ctx.contentResolver.openInputStream(uri)!!.use { store.restoreFrom(it) }
                    "Restored $n note${if (n == 1) "" else "s"}" + if (s > 0) ", skipped $s already up to date" else ""
                }.getOrElse { "Could not restore: ${it.message}" }
            }
        }
    }
    HorizontalDivider()
    Text("Export and backup", style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
    Text(
        "Every drawing is saved as a PDF (A4 width) and all drawings as ${Exporter.BACKUP_NAME} in a folder of your choice, for example in Google Drive. " +
            "It happens 10 seconds after you stop writing, when you leave a note and when the app goes to the background.",
    )
    Text(if (ex.folderName.isEmpty()) "No folder chosen: nothing is exported." else "Folder: ${ex.folderName}", style = MaterialTheme.typography.titleSmall)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton({ pickFolder.launch(null) }) { Text(if (ex.folderName.isEmpty()) "Choose folder…" else "Change folder…") }
        if (ex.folderName.isNotEmpty()) {
            OutlinedButton({ ex.exportAll() }) { Text("Export everything now") }
            TextButton({ ex.clearFolder() }) { Text("Stop exporting") }
        }
    }
    if (ex.status.isNotEmpty()) Text(ex.status)
    Text("Restore", style = MaterialTheme.typography.titleSmall)
    Text("Pick a .jotter backup. Its notes are added; a note you already have is replaced only if the backup's copy is newer.")
    OutlinedButton({ pickBackup.launch(arrayOf("*/*")) }) { Text("Restore from backup…") }
    if (restoreMsg.isNotEmpty()) Text(restoreMsg)
}

fun share(ctx: Context, store: Store, n: Note) {
    val i = Intent(Intent.ACTION_SEND)
    if (n.kind == "text") {
        i.type = "text/plain"
        i.putExtra(Intent.EXTRA_SUBJECT, n.title)
        i.putExtra(Intent.EXTRA_TEXT, store.readText(n))
    } else {
        val dir = File(ctx.cacheDir, "share").apply { deleteRecursively(); mkdirs() }
        val base = n.title.replace(Regex("[^\\p{L}\\p{N}-]"), "_").ifBlank { "note" }
        val uris = store.readPages(n).ifEmpty { listOf(emptyList()) }.mapIndexed { idx, items ->
            val f = File(dir, "$base-${idx + 1}.png")
            val b = renderExport(items, n.bg, store::bitmap)
            f.outputStream().use { b.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            b.recycle()
            FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", f)
        }
        i.action = Intent.ACTION_SEND_MULTIPLE
        i.type = "image/png"
        i.putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    ctx.startActivity(Intent.createChooser(i, null))
}
