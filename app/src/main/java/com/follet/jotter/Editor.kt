package com.follet.jotter

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.ViewConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlin.math.roundToInt
import kotlin.math.sin

private class PageState(items: List<Item>) {
    var items by mutableStateOf(items)
    val undo = ArrayDeque<List<Item>>()
    val redo = ArrayDeque<List<Item>>()
}

/**
 * Committed page content (paper + strokes) rendered once into a bitmap covering two viewport heights, so each frame only
 * blits it and draws the stroke in progress. Cost per frame no longer grows with what is already written.
 */
private class PageCache {
    private var bmp: Bitmap? = null
    private var cv: android.graphics.Canvas? = null
    private var top = 0f // page-y (units) of the bitmap's first row
    private var k = 0f
    private var items: List<Item>? = null
    private var erased: Set<Item> = emptySet()
    private var page = -1

    fun update(w: Int, h: Int, k: Float, items: List<Item>, erased: Set<Item>, page: Int, scrollY: Float, bg: String, bitmap: (String) -> Bitmap?): Pair<Bitmap, Float> {
        val viewH = h / k
        var b = bmp
        var dirty = false
        if (b == null || b.width != w || b.height != 2 * h) {
            b = Bitmap.createBitmap(w, 2 * h, Bitmap.Config.ARGB_8888); bmp = b; cv = android.graphics.Canvas(b); dirty = true
        }
        if (dirty || this.items !== items || this.erased !== erased || this.page != page || this.k != k ||
            scrollY < top || scrollY + viewH > top + 2 * viewH) {
            top = (scrollY - viewH / 2).coerceAtLeast(0f)
            cv!!.save(); cv!!.scale(k, k)
            drawPage(cv!!, items, bg, top, 2 * viewH, bitmap, erased)
            cv!!.restore()
            this.items = items; this.erased = erased; this.page = page; this.k = k
        }
        return b to top
    }

    /** A new stroke was committed on the page [old] -> [new]: paint just that stroke instead of re-rendering everything. */
    fun append(old: List<Item>, new: List<Item>, s: Stroke) {
        val c = cv ?: return
        if (items !== old || erased.isNotEmpty()) return
        c.save(); c.scale(k, k); c.translate(0f, -top); drawStroke(c, s); c.restore()
        items = new
    }
}

/** Thickness is in page units (1000 per page width). */
private class Tool(val name: String, val icon: Int, val min: Float, val max: Float, val def: Float)

private const val ERASER = 3
private val TOOLS = listOf(
    Tool("Pen", R.drawable.ic_ink_pen, 0.5f, 24f, 3f),
    Tool("Highlighter", R.drawable.ic_ink_highlighter, 6f, 70f, 20f),
    Tool("Calligraphic pen", R.drawable.ic_nib_pen, 2f, 40f, 8f),
    Tool("Object eraser", R.drawable.ic_ink_eraser, 4f, 40f, 12f),
)
private val PALETTE = listOf(
    "Black" to 0xFF000000, "Blue" to 0xFF1565C0, "Red" to 0xFFC62828, "Green" to 0xFF2E7D32, "Orange" to 0xFFEF6C00,
    "Purple" to 0xFF6A1B9A, "Yellow" to 0xFFFFEB3B, "Teal" to 0xFF00ACC1, "Pink" to 0xFFE91E63,
).map { it.first to it.second.toInt() }
private val DEFAULT_COLORS = intArrayOf(0xFF000000.toInt(), 0xFFFFEB3B.toInt(), 0xFF1565C0.toInt())

/** 48dp touch target. Tap = [onClick]; optional long press = [onLongClick]. [selected] shows with fill AND outline, not colour alone. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Btn(
    label: String, onClick: () -> Unit, selected: Boolean = false, enabled: Boolean = true, state: String? = null,
    longLabel: String? = null, onLongClick: (() -> Unit)? = null, content: @Composable () -> Unit,
) {
    val shape = RoundedCornerShape(10.dp)
    val c = MaterialTheme.colorScheme
    Box(
        Modifier.size(48.dp).padding(2.dp).alpha(if (enabled) 1f else 0.38f).clip(shape)
            .then(if (selected) Modifier.background(c.primaryContainer).border(2.dp, c.primary, shape) else Modifier)
            .combinedClickable(
                enabled = enabled, role = Role.Button, onLongClickLabel = longLabel, onLongClick = onLongClick, onClick = onClick,
            )
            .semantics { contentDescription = label; this.selected = selected; if (state != null) stateDescription = state },
        Alignment.Center,
    ) { content() }
}

@Composable
private fun Swatch(color: Int, size: Int, modifier: Modifier = Modifier, content: @Composable () -> Unit = {}) =
    Box(modifier.size(size.dp).background(Color(color), CircleShape).border(1.5.dp, MaterialTheme.colorScheme.outline, CircleShape), Alignment.Center) { content() }

/** Red / green / blue sliders (0-255, value shown as text) with a preview and hex readout. */
@Composable
private fun ColorDialog(initial: Int, onPick: (Int) -> Unit, onDismiss: () -> Unit) {
    var r by remember { mutableIntStateOf(android.graphics.Color.red(initial)) }
    var g by remember { mutableIntStateOf(android.graphics.Color.green(initial)) }
    var b by remember { mutableIntStateOf(android.graphics.Color.blue(initial)) }
    val color = android.graphics.Color.rgb(r, g, b)
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text("Custom colour") },
        text = {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Swatch(color, 56)
                    Spacer(Modifier.width(16.dp))
                    Text("#%06X".format(color and 0xFFFFFF), style = MaterialTheme.typography.titleMedium)
                }
                for ((name, value, set) in listOf(Triple("Red", r, { v: Int -> r = v }), Triple("Green", g, { v: Int -> g = v }), Triple("Blue", b, { v: Int -> b = v }))) {
                    Text("$name: $value", Modifier.padding(top = 12.dp))
                    Slider(value.toFloat(), { set(it.roundToInt()) }, Modifier.semantics { contentDescription = name }, valueRange = 0f..255f)
                }
            }
        },
        confirmButton = { TextButton({ onPick(color) }) { Text("Use") } },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun InkEditor(store: Store, note: Note, onBack: () -> Unit) {
    val pos = store.toolsPos
    val horizontal = pos == "top" || pos == "bottom"
    var title by remember { mutableStateOf(note.title) }
    val pages = remember {
        mutableStateListOf<PageState>().apply {
            store.readPages(note).ifEmpty { listOf(emptyList()) }.forEach { add(PageState(it)) }
        }
    }
    var cur by remember { mutableIntStateOf(0) }
    var scrollY by remember { mutableFloatStateOf(0f) }
    var tool by remember { mutableIntStateOf(0) }
    // Tool thickness, colours, pressure and finger drawing are remembered across notes (SharedPreferences).
    val widths = remember { mutableStateListOf<Float>().apply { TOOLS.forEachIndexed { i, t -> add(store.prefs.getFloat("w$i", t.def)) } } }
    val colors = remember { mutableStateListOf<Int>().apply { DEFAULT_COLORS.forEachIndexed { i, d -> add(store.prefs.getInt("c$i", d)) } } }
    var pressure by remember { mutableStateOf(store.prefs.getBoolean("pressure", true)) }
    var thicknessFor by remember { mutableIntStateOf(-1) } // tool whose slider popup is open
    var colorMenu by remember { mutableStateOf(false) }
    var customDialog by remember { mutableStateOf(false) }
    // The one custom colour slot, remembered across notes (older versions stored a comma list; keep its first entry).
    var custom by remember { mutableStateOf(store.prefs.getString("custom", "")!!.split(",").firstNotNullOfOrNull { it.toIntOrNull() }) }
    fun pickColor(c: Int) {
        colors[tool] = c; store.prefs.edit().putInt("c$tool", c).apply()
    }
    var renaming by remember { mutableStateOf(false) }
    var rev by remember { mutableIntStateOf(0) }
    var kPx by remember { mutableFloatStateOf(1f) } // screen px per page unit, so the slider preview shows true size
    val live = remember { ArrayList<Float>() }
    val cache = remember { PageCache() }
    var tick by remember { mutableIntStateOf(0) }
    var erased by remember { mutableStateOf(emptySet<Item>()) }

    fun commit(p: PageState, items: List<Item>) { p.undo.addLast(p.items); p.redo.clear(); p.items = items; rev++ }
    fun saveAll() {
        store.writePages(note, pages.map { it.items })
        store.saveThumb(note, pages[0].items)
        store.save(note.copy(title = title.trim(), modified = System.currentTimeMillis()))
    }
    fun goto(i: Int) { cur = i; scrollY = 0f }

    LaunchedEffect(rev) { if (rev > 0) { delay(700); saveAll() } }
    val isNew = remember { note.created == note.modified } // never saved by an edit yet
    // Leaving a brand-new note that was not touched at all (rev stays 0) deletes it instead of leaving an empty note behind.
    DisposableEffect(Unit) { onDispose { if (isNew && rev == 0) store.delete(note) else saveAll() } } // ponytail: saves on the UI thread, move off it if notes grow huge
    BackHandler(onBack = onBack)

    val pick = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { store.addImage(it) }?.let { (name, aspect) ->
            commit(pages[cur], pages[cur].items + Img(name, 80f, scrollY + 80f, 400f, 400f * aspect))
        }
    }

    val kebab = @Composable {
        var open by remember { mutableStateOf(false) }
        Box {
            Btn("More options", { open = true }) { Icon(Icons.Default.MoreVert, null) }
            DropdownMenu(open, { open = false }) {
                DropdownMenuItem({ Text("Rename…") }, { open = false; renaming = true },
                    leadingIcon = { Icon(Icons.Default.Edit, null) })
                DropdownMenuItem(
                    { Text("Draw with finger") }, { store.pickFingerDraw(!store.fingerDraw) },
                    Modifier.semantics { stateDescription = if (store.fingerDraw) "On" else "Off" },
                    leadingIcon = { Icon(Icons.Default.TouchApp, null) },
                    trailingIcon = { Switch(store.fingerDraw, null) },
                )
                DropdownMenuItem(
                    { Text("Pressure sensitivity") }, { pressure = !pressure; store.prefs.edit().putBoolean("pressure", pressure).apply() },
                    Modifier.semantics { stateDescription = if (pressure) "On" else "Off" },
                    leadingIcon = { Icon(Icons.Default.LineWeight, null) },
                    trailingIcon = { Switch(pressure, null) },
                )
            }
        }
    }

    val buttons = @Composable {
        val canUndo = rev >= 0 && pages[cur].undo.isNotEmpty()
        val canRedo = rev >= 0 && pages[cur].redo.isNotEmpty()
        Btn("Back", onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) }
        if (!horizontal) kebab()
        Btn("Undo", {
            val p = pages[cur]
            p.undo.removeLastOrNull()?.let { p.redo.addLast(p.items); p.items = it; rev++ }
        }, enabled = canUndo) { Icon(Icons.AutoMirrored.Filled.Undo, null) }
        Btn("Redo", {
            val p = pages[cur]
            p.redo.removeLastOrNull()?.let { p.undo.addLast(p.items); p.items = it; rev++ }
        }, enabled = canRedo) { Icon(Icons.AutoMirrored.Filled.Redo, null) }
        Spacer(Modifier.size(8.dp))
        TOOLS.forEachIndexed { i, t ->
            Box {
                Btn(t.name, { tool = i }, selected = tool == i, longLabel = "Set ${t.name.lowercase()} thickness",
                    onLongClick = { tool = i; thicknessFor = i }) {
                    Icon(painterResource(t.icon), null)
                }
                DropdownMenu(thicknessFor == i, { thicknessFor = -1 }) {
                    Column(Modifier.width(300.dp).padding(horizontal = 16.dp, vertical = 8.dp)) {
                        Text("${t.name} thickness: ${"%.1f".format(widths[i])}", style = MaterialTheme.typography.titleSmall)
                        Slider(
                            widths[i], { widths[i] = it }, Modifier.semantics { contentDescription = "${t.name} thickness" },
                            valueRange = t.min..t.max,
                            onValueChangeFinished = { store.prefs.edit().putFloat("w$i", widths[i]).apply() },
                        )
                        // true-size preview on white paper
                        Canvas(Modifier.fillMaxWidth().height(110.dp).clip(RoundedCornerShape(8.dp)).background(Color.White)
                            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp))) {
                            val k = kPx
                            val w = size.width / k; val h = size.height / k
                            drawIntoCanvas { cv ->
                                val n = cv.nativeCanvas
                                n.save(); n.scale(k, k)
                                if (i == ERASER) {
                                    val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                                        style = android.graphics.Paint.Style.STROKE; strokeWidth = 2f; color = 0xFF444444.toInt()
                                    }
                                    n.drawCircle(w / 2, h / 2, widths[i], p)
                                } else {
                                    val pts = FloatArray(3 * 40) { j ->
                                        val s = j / 3
                                        when (j % 3) {
                                            0 -> w * 0.1f + w * 0.8f * s / 39
                                            1 -> h / 2 + minOf(h * 0.25f, 22f) * sin(s / 39f * 6.28f * 1.2f)
                                            else -> 1f
                                        }
                                    }
                                    drawStroke(n, Stroke(i, colors[i], widths[i], pts))
                                }
                                n.restore()
                            }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.size(8.dp))
        Box {
            Btn("Colour: ${PALETTE.firstOrNull { it.second == colors[minOf(tool, 2)] }?.first ?: "custom"}", { colorMenu = true },
                enabled = tool != ERASER) { Swatch(colors[minOf(tool, 2)], 26) }
            DropdownMenu(colorMenu, { colorMenu = false }) {
                PALETTE.chunked(3).forEach { row ->
                    Row(Modifier.padding(horizontal = 8.dp)) {
                        row.forEach { (name, c) ->
                            Btn(name, { pickColor(c); colorMenu = false }, selected = colors[tool] == c) {
                                Swatch(c, 30) {
                                    if (colors[tool] == c) Icon(Icons.Default.Check, null, Modifier.size(20.dp),
                                        tint = if (Color(c).luminance() > 0.5f) Color.Black else Color.White)
                                }
                            }
                        }
                    }
                }
                Row(Modifier.padding(horizontal = 8.dp)) {
                    custom?.let { c ->
                        Btn("Custom #%06X".format(c and 0xFFFFFF), { pickColor(c); colorMenu = false }, selected = colors[tool] == c) {
                            Swatch(c, 30) {
                                if (colors[tool] == c) Icon(Icons.Default.Check, null, Modifier.size(20.dp),
                                    tint = if (Color(c).luminance() > 0.5f) Color.Black else Color.White)
                            }
                        }
                    }
                    Btn("Custom colour", { colorMenu = false; customDialog = true }) { Icon(Icons.Default.Palette, null) }
                }
            }
        }
        Btn("Insert image", { pick.launch("image/*") }) { Icon(Icons.Default.Image, null) }
        Spacer(Modifier.size(8.dp))
        Btn("Previous page", { goto(cur - 1) }, enabled = cur > 0) { Icon(Icons.Default.ChevronLeft, null) }
        Text("${cur + 1}/${pages.size}", Modifier.padding(horizontal = 4.dp).semantics { contentDescription = "Page ${cur + 1} of ${pages.size}" },
            fontSize = 14.sp)
        Btn(if (cur == pages.lastIndex) "Add page" else "Next page", {
            if (cur == pages.lastIndex) { pages += PageState(emptyList()); rev++ }
            goto(cur + 1)
        }) { Icon(Icons.Default.ChevronRight, null) }
        if (horizontal) kebab()
    }

    // Tap-and-hold on tools: 250 ms instead of the system default 400 ms, unless the user lengthened it in Accessibility settings.
    val vc = LocalViewConfiguration.current
    val quick = remember(vc) {
        object : ViewConfiguration by vc {
            override val longPressTimeoutMillis get() = if (vc.longPressTimeoutMillis <= 400) 250L else vc.longPressTimeoutMillis
        }
    }

    val bar = @Composable {
        CompositionLocalProvider(LocalViewConfiguration provides quick) { Surface(color = MaterialTheme.colorScheme.surface, contentColor = MaterialTheme.colorScheme.onSurface) {
            if (horizontal) Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 4.dp),
                Arrangement.Center, Alignment.CenterVertically,
            ) { buttons() } else Column(
                Modifier.fillMaxHeight().width(56.dp).verticalScroll(rememberScrollState()).padding(vertical = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) { buttons() }
        } }
    }

    val surface = @Composable {
        Canvas(
            Modifier.fillMaxSize().clipToBounds().onSizeChanged { kPx = it.width / PAGE_W }
                .semantics { contentDescription = "Drawing area, page ${cur + 1} of ${pages.size}" }
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val k = size.width / PAGE_W
                        val first = awaitFirstDown(requireUnconsumed = false)
                        val touch = first.type == PointerType.Touch
                        var drawing = !touch || store.fingerDraw

                        fun add(ch: PointerInputChange, pos: Offset) {
                            val x = pos.x / k; val y = pos.y / k + scrollY
                            if (tool == ERASER) {
                                val r = widths[ERASER]
                                val hit = pages[cur].items.filter { it !in erased && hits(it, x, y, r) }
                                if (hit.isNotEmpty()) erased = erased + hit
                            } else {
                                live += x; live += y
                                live += if (pressure && !touch && tool == 0) ch.pressure.coerceIn(0.05f, 1f) else 1f
                            }
                            tick++
                        }
                        fun cancel() { live.clear(); erased = emptySet(); tick++ }

                        if (drawing) add(first, first.position)
                        while (true) {
                            val ev = awaitPointerEvent()
                            val down = ev.changes.count { it.pressed }
                            if (down == 0) break
                            if (drawing && touch && down > 1) { cancel(); drawing = false } // second finger: scroll instead
                            if (drawing) {
                                val ch = ev.changes.firstOrNull { it.id == first.id } ?: break
                                @OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
                                ch.historical.forEach { add(ch, it.position) }
                                add(ch, ch.position)
                            } else {
                                scrollY = (scrollY - ev.calculatePan().y / k).coerceAtLeast(0f)
                            }
                            ev.changes.forEach { it.consume() }
                        }
                        if (drawing) {
                            val p = pages[cur]
                            if (tool == ERASER) { if (erased.isNotEmpty()) commit(p, p.items.filter { it !in erased }) }
                            else if (live.isNotEmpty()) {
                                val stroke = Stroke(tool, colors[tool], widths[tool], live.toFloatArray())
                                val old = p.items
                                commit(p, old + stroke)
                                cache.append(old, p.items, stroke)
                            }
                            cancel()
                        }
                    }
                },
        ) {
            tick // subscribe to live-stroke changes
            drawIntoCanvas { cv ->
                val n = cv.nativeCanvas
                val k = size.width / PAGE_W
                val w = size.width.toInt(); val h = size.height.toInt()
                if (w <= 0 || h <= 0) return@drawIntoCanvas
                val (bmp, top) = cache.update(w, h, k, pages[cur].items, erased, cur, scrollY, note.bg, store::bitmap)
                n.drawBitmap(bmp, 0f, (top - scrollY) * k, null)
                if (live.isNotEmpty() && tool != ERASER) {
                    n.save(); n.scale(k, k); n.translate(0f, -scrollY)
                    drawStroke(n, Stroke(tool, colors[tool], widths[tool], live.toFloatArray()))
                    n.restore()
                }
            }
        }
    }

    if (customDialog) ColorDialog(colors[minOf(tool, 2)], { c ->
        pickColor(c)
        if (PALETTE.none { it.second == c }) { // a palette colour never occupies the custom slot
            custom = c
            store.prefs.edit().putString("custom", c.toString()).apply()
        }
        customDialog = false
    }, { customDialog = false })

    if (renaming) {
        var t by remember { mutableStateOf(title) }
        AlertDialog(
            onDismissRequest = { renaming = false }, title = { Text("Note title") },
            text = { OutlinedTextField(t, { t = it }, label = { Text("Title") }, singleLine = true) },
            confirmButton = { TextButton({ title = t; rev++; renaming = false }) { Text("OK") } },
            dismissButton = { TextButton({ renaming = false }) { Text("Cancel") } },
        )
    }

    val root = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).safeDrawingPadding()
    when (pos) {
        "top" -> Column(root) { bar(); HorizontalDivider(); Box(Modifier.weight(1f)) { surface() } }
        "bottom" -> Column(root) { Box(Modifier.weight(1f)) { surface() }; HorizontalDivider(); bar() }
        "left" -> Row(root) { bar(); VerticalDivider(); Box(Modifier.weight(1f)) { surface() } }
        else -> Row(root) { Box(Modifier.weight(1f)) { surface() }; VerticalDivider(); bar() }
    }
}

@Composable
fun TextEditor(store: Store, note: Note, onBack: () -> Unit) {
    var title by remember { mutableStateOf(note.title) }
    var body by remember { mutableStateOf(store.readText(note)) }
    var rev by remember { mutableIntStateOf(0) }
    fun saveAll() {
        store.writeText(note, body)
        store.save(note.copy(title = title.trim(), modified = System.currentTimeMillis()))
    }
    LaunchedEffect(rev) { if (rev > 0) { delay(700); saveAll() } }
    val isNew = remember { note.created == note.modified }
    DisposableEffect(Unit) { onDispose { if (isNew && rev == 0) store.delete(note) else saveAll() } }
    BackHandler(onBack = onBack)

    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            TextField(title, { title = it; rev++ }, Modifier.weight(1f), label = { Text("Title") }, singleLine = true)
        }
        TextField(body, { body = it; rev++ }, Modifier.fillMaxSize(), label = { Text("Note") })
    }
}
