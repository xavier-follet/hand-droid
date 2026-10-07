package com.follet.jotter

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.text.format.DateUtils
import androidx.compose.foundation.Image
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

private fun Note.label() = title.ifBlank { "Untitled" }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Home(store: Store, open: (Note) -> Unit, onSettings: () -> Unit) {
    val ctx = LocalContext.current
    var byName by remember { mutableStateOf(false) }
    var asc by remember { mutableStateOf(false) }
    var archived by remember { mutableStateOf(false) }
    var fab by remember { mutableStateOf(false) }
    var picker by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<Note?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    fun delete(n: Note) {
        store.trash(n)
        snackbar.currentSnackbarData?.dismiss() // the previous delete becomes final
        scope.launch {
            val r = snackbar.showSnackbar("Deleted “${n.label()}”", "Undo", duration = SnackbarDuration.Long)
            if (r == SnackbarResult.ActionPerformed) store.restore(n) else store.purge(n)
        }
    }

    fun sortBtn(name: Boolean) { if (byName == name) asc = !asc else { byName = name; asc = name } }
    val shown = store.notes.filter { it.archived == archived }.let { l ->
        val s = if (byName) l.sortedBy { it.label().lowercase() } else l.sortedBy { it.modified }
        if (asc) s else s.reversed()
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(if (archived) "Jotter – Archive" else "Jotter") },
                actions = {
                    for (name in listOf(true, false)) TextButton({ sortBtn(name) }, Modifier.defaultMinSize(48.dp, 48.dp).semantics {
                        contentDescription = (if (name) "Sort by name" else "Sort by date") +
                            if (byName == name) (if (asc) ", ascending" else ", descending") else ""
                    }, contentPadding = PaddingValues(horizontal = 8.dp)) {
                        Icon(if (name) Icons.Default.SortByAlpha else Icons.Default.Schedule, null)
                        if (byName == name) Icon(if (asc) Icons.Default.ArrowUpward else Icons.Default.ArrowDownward, null, Modifier.size(16.dp))
                    }
                    IconButton({ archived = !archived }) {
                        Icon(if (archived) Icons.Default.Unarchive else Icons.Default.Archive, if (archived) "Show notes" else "Show archive")
                    }
                    IconButton(onSettings) { Icon(Icons.Default.Settings, "Settings") }
                },
            )
        },
        floatingActionButton = {
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (fab) {
                    ExtendedFloatingActionButton({ fab = false; picker = true }) {
                        Icon(Icons.Default.Draw, null); Spacer(Modifier.width(8.dp)); Text("Handwriting note…")
                    }
                    ExtendedFloatingActionButton({ fab = false; open(store.create("ink", store.lastBg)) }) {
                        Icon(Icons.Default.Edit, null); Spacer(Modifier.width(8.dp))
                        Text("Handwriting: " + BACKGROUNDS.first { it.first == store.lastBg }.second)
                    }
                }
                FloatingActionButton({ fab = !fab }) { Icon(if (fab) Icons.Default.Close else Icons.Default.Add, "New") }
            }
        },
    ) { pad ->
        if (shown.isEmpty()) Box(Modifier.fillMaxSize().padding(pad), Alignment.Center) {
            Text(if (archived) "Nothing archived" else "Tap + to create a note")
        }
        LazyVerticalGrid(
            GridCells.Adaptive(220.dp), Modifier.padding(pad),
            contentPadding = PaddingValues(16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            items(shown, key = { it.id }) { n ->
                NoteCard(n, store, { open(n) },
                    onRename = { renaming = n }, onShare = { share(ctx, store, n) },
                    onArchive = { store.save(n.copy(archived = !n.archived)) }, onDelete = { delete(n) })
            }
        }
    }

    if (picker) BackgroundPicker({ picker = false }) { picker = false; open(store.create("ink", it)) }

    renaming?.let { n ->
        var t by remember { mutableStateOf(n.title) }
        AlertDialog(
            onDismissRequest = { renaming = null }, title = { Text("Rename") },
            text = { OutlinedTextField(t, { t = it }, singleLine = true) },
            confirmButton = { TextButton({ store.save(n.copy(title = t.trim())); renaming = null }) { Text("OK") } },
            dismissButton = { TextButton({ renaming = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun NoteCard(
    n: Note, store: Store, onClick: () -> Unit,
    onRename: () -> Unit, onShare: () -> Unit, onArchive: () -> Unit, onDelete: () -> Unit,
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
        }
    }
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
