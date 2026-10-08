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
import androidx.compose.material.icons.automirrored.filled.ArrowForward
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.IntOffset
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.foundation.text.KeyboardOptions
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
import kotlin.math.abs
import kotlin.math.hypot
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
    private var hidden: Set<Item> = emptySet()
    private var page = -1

    fun update(w: Int, h: Int, k: Float, items: List<Item>, erased: Set<Item>, hidden: Set<Item>, page: Int, scrollY: Float, bg: String, bitmap: (String) -> Bitmap?): Pair<Bitmap, Float> {
        val viewH = h / k
        var b = bmp
        var dirty = false
        if (b == null || b.width != w || b.height != 2 * h) {
            b = Bitmap.createBitmap(w, 2 * h, Bitmap.Config.ARGB_8888); bmp = b; cv = android.graphics.Canvas(b); dirty = true
        }
        if (dirty || this.items !== items || this.erased !== erased || this.hidden !== hidden || this.page != page || this.k != k ||
            scrollY < top || scrollY + viewH > top + 2 * viewH) {
            top = (scrollY - viewH / 2).coerceAtLeast(0f)
            cv!!.save(); cv!!.scale(k, k)
            drawPage(cv!!, items, bg, top, 2 * viewH, bitmap, erased, hidden)
            cv!!.restore()
            this.items = items; this.erased = erased; this.hidden = hidden; this.page = page; this.k = k
        }
        return b to top
    }

    /** A new stroke was committed on the page [old] -> [new]: paint just that stroke instead of re-rendering everything. */
    fun append(old: List<Item>, new: List<Item>, s: Stroke) {
        val c = cv ?: return
        if (items !== old || erased.isNotEmpty() || hidden.isNotEmpty()) return
        c.save(); c.scale(k, k); c.translate(0f, -top); drawStroke(c, s); c.restore()
        items = new
    }
}

/** Thickness is in page units (1000 per page width). */
private class Tool(val name: String, val icon: Int, val min: Float, val max: Float, val def: Float)

private const val ERASER = 3
private const val SHAPE = 4 // shape tool; also selects, moves and resizes shapes and images
private const val TEXT = 5  // text box tool: tap or drag on the paper to type; also moves, resizes and rotates text boxes
private val SEL = 0xFF1B4FD8.toInt() // selection frame: always drawn over white paper
private val TOOLS = listOf(
    Tool("Pen", R.drawable.ic_ink_pen, 0.5f, 24f, 3f),
    Tool("Highlighter", R.drawable.ic_ink_highlighter, 6f, 70f, 20f),
    Tool("Calligraphic pen", R.drawable.ic_nib_pen, 2f, 40f, 8f),
    Tool("Object eraser", R.drawable.ic_ink_eraser, 4f, 40f, 12f),
    Tool("Shape", 0, 1f, 30f, 3f), // icon follows the chosen shape; thickness is the outline
    Tool("Text box", 0, 14f, 160f, 36f), // "thickness" is the font size
)
private val PALETTE = listOf(
    "Black" to 0xFF000000, "Blue" to 0xFF1565C0, "Red" to 0xFFC62828, "Green" to 0xFF2E7D32, "Orange" to 0xFFEF6C00,
    "Purple" to 0xFF6A1B9A, "Yellow" to 0xFFFFEB3B, "Teal" to 0xFF00ACC1, "Pink" to 0xFFE91E63,
    "White" to 0xFFFFFFFF, "Light gray" to 0xFFBDBDBD, "Dark gray" to 0xFF616161,
).map { it.first to it.second.toInt() }
private val DEFAULT_COLORS = intArrayOf(0xFF000000.toInt(), 0xFFFFEB3B.toInt(), 0xFF1565C0.toInt(), 0xFF000000.toInt(), 0xFF000000.toInt(), 0xFF000000.toInt()) // 3 = eraser (unused), 4 = shape outline, 5 = text

private fun shapeIcon(kind: Int): ImageVector = when (kind) {
    0 -> Icons.Default.CropSquare
    1 -> Icons.Default.RadioButtonUnchecked
    2 -> Icons.Default.ChangeHistory
    3 -> Icons.Default.HorizontalRule
    4 -> Icons.AutoMirrored.Filled.ArrowForward
    else -> Icons.Default.StarBorder // 5 (kind 6, the burst, has its own drawn glyph)
}

/** Icon for a shape kind. The burst is drawn from the same polygon the shape uses, so the icon matches what you get. */
@Composable
private fun ShapeGlyph(kind: Int) {
    if (kind != 6) { Icon(shapeIcon(kind), null); return }
    val color = LocalContentColor.current
    Canvas(Modifier.size(24.dp)) {
        val pad = 2.dp.toPx()
        val pts = polygon(Shape(6, 0, 0, 0f, pad, pad, size.width - pad, size.height - pad))
        val path = androidx.compose.ui.graphics.Path().apply {
            moveTo(pts[0], pts[1]); for (i in 1 until pts.size / 2) lineTo(pts[i * 2], pts[i * 2 + 1]); close()
        }
        drawPath(path, color, style = androidx.compose.ui.graphics.drawscope.Stroke(1.6.dp.toPx(), join = androidx.compose.ui.graphics.StrokeJoin.Round))
    }
}

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

/** Row(s) of colour choices: [noneLabel] (value 0), the palette, the saved custom colour, and a button to make a custom colour. */
@Composable
private fun ColorChoices(noneLabel: String, current: Int, custom: Int?, onPick: (Int) -> Unit, onCustom: () -> Unit) {
    val items = listOf<Pair<String, Int?>>(noneLabel to 0) + PALETTE +
        listOfNotNull(custom?.let { "Custom #%06X".format(it and 0xFFFFFF) to it }) + ("Custom colour" to null)
    items.chunked(6).forEach { row ->
        Row {
            row.forEach { (name, c) ->
                if (c == null) Btn(name, onCustom) { Icon(Icons.Default.Palette, null) }
                else Btn(name, { onPick(c) }, selected = current == c) {
                    if (c == 0) Icon(Icons.Default.Block, null)
                    else Swatch(c, 30) {
                        if (current == c) Icon(Icons.Default.Check, null, Modifier.size(20.dp),
                            tint = if (Color(c).luminance() > 0.5f) Color.Black else Color.White)
                    }
                }
            }
        }
    }
}

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
    var shapeKind by remember { mutableIntStateOf(store.prefs.getInt("shape", 0)) } // defaults to the square/rectangle
    var shapeFill by remember { mutableIntStateOf(store.prefs.getInt("fill", 0)) }  // 0 = no fill
    var selected by remember { mutableStateOf<Item?>(null) }   // shape or image with handles, while the shape tool is active
    var selFresh by remember { mutableStateOf(false) } // selection came from drawing it just now: popup choices are for the NEXT shape
    var preview by remember { mutableStateOf<Item?>(null) }    // the new/moved/resized item while dragging
    var hidden by remember { mutableStateOf<Set<Item>>(emptySet()) } // original of the item being dragged
    val selPaint = remember { android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG) }
    var colorMenu by remember { mutableStateOf(false) }
    var customDialog by remember { mutableStateOf(false) }
    var customFor by remember { mutableIntStateOf(0) } // 0 = tool colour, 1 = shape fill, 2 = shape border
    // The one custom colour slot, remembered across notes (older versions stored a comma list; keep its first entry).
    var custom by remember { mutableStateOf(store.prefs.getString("custom", "")!!.split(",").firstNotNullOfOrNull { it.toIntOrNull() }) }
    var renaming by remember { mutableStateOf(false) }
    var rev by remember { mutableIntStateOf(0) }
    var kPx by remember { mutableFloatStateOf(1f) } // screen px per page unit, so the slider preview shows true size
    val live = remember { ArrayList<Float>() }
    val cache = remember { PageCache() }
    var tick by remember { mutableIntStateOf(0) }
    var erased by remember { mutableStateOf(emptySet<Item>()) }
    // Text box tool: remembered style for new boxes, and the box being typed in. The page still holds the original (hidden while editing);
    // a brand-new box is only added to the page when you finish with some text in it.
    var tFont by remember { mutableIntStateOf(store.prefs.getInt("tfont", 0)) }
    var tFlags by remember { mutableIntStateOf(store.prefs.getInt("tflags", 0)) } // 1 bold, 2 italic, 4 underline
    var tAlign by remember { mutableIntStateOf(store.prefs.getInt("talign", 0)) }
    var editing by remember { mutableStateOf<TextBox?>(null) }
    var editOrig by remember { mutableStateOf<TextBox?>(null) }
    var viewH by remember { mutableIntStateOf(0) }

    fun commit(p: PageState, items: List<Item>) { p.undo.addLast(p.items); p.redo.clear(); p.items = items; rev++ }
    fun saveAll() {
        store.writePages(note, pages.map { it.items })
        store.saveThumb(note, pages[0].items)
        store.save(note.copy(title = title.trim(), modified = System.currentTimeMillis()))
    }
    fun newTextBox(x: Float, y: Float, w: Float) = TextBox("", colors[TEXT], widths[TEXT], tFlags, tFont, tAlign, x, y, w)

    fun startEdit(t: TextBox, existing: Boolean) {
        editing = t; editOrig = if (existing) t else null
        selected = null
        hidden = if (existing) setOf(t) else emptySet()
    }

    /** Ends typing: an empty box is dropped (an existing one is removed), otherwise the change is committed and the box stays selected. */
    fun finishEdit() {
        val e = editing ?: return
        val o = editOrig
        val p = pages[cur]
        editing = null; editOrig = null; hidden = emptySet()
        when {
            e.text.isBlank() -> { if (o != null) commit(p, p.items.filter { it !== o }); selected = null }
            o == null -> { commit(p, p.items + e); selected = e; selFresh = false }
            e !== o -> { commit(p, p.items.map { if (it === o) e else it }); selected = e; selFresh = false }
            else -> { selected = o; selFresh = false }
        }
    }

    fun goto(i: Int) { finishEdit(); cur = i; scrollY = 0f; selected = null }

    /** Applies a change to the selected shape (undoable), e.g. a new fill. */
    fun restyle(f: (Shape) -> Shape) {
        val s = selected as? Shape ?: return
        if (selFresh) return // only a shape you deliberately tapped is restyled
        val n = f(s); val p = pages[cur]; val old = p.items
        commit(p, old.map { if (it === s) n else it }); selected = n
    }
    /** Applies a style change to the box being typed in, or to the selected one (undoable). */
    fun restyleText(f: (TextBox) -> TextBox) {
        val e = editing
        if (e != null) { editing = f(e); return }
        val t = selected as? TextBox ?: return
        if (selFresh) return
        val n = f(t); val p = pages[cur]
        commit(p, p.items.map { if (it === t) n else it }); selected = n
    }
    fun pickColor(c: Int) {
        colors[tool] = c; store.prefs.edit().putInt("c$tool", c).apply()
        if (tool == SHAPE) restyle { it.copy(color = c) }
        if (tool == TEXT) restyleText { it.copy(color = c) }
    }
    fun setTextFont(f: Int) { tFont = f; store.prefs.edit().putInt("tfont", f).apply(); restyleText { it.copy(font = f) } }
    fun toggleTextFlag(bit: Int) {
        tFlags = tFlags xor bit; store.prefs.edit().putInt("tflags", tFlags).apply()
        val on = tFlags and bit != 0
        restyleText { it.copy(flags = if (on) it.flags or bit else it.flags and bit.inv()) }
    }
    fun setTextAlign(a: Int) { tAlign = a; store.prefs.edit().putInt("talign", a).apply(); restyleText { it.copy(align = a) } }
    fun setShapeKind(k: Int) { shapeKind = k; store.prefs.edit().putInt("shape", k).apply(); restyle { it.copy(kind = k) } }
    fun setShapeFill(c: Int) { shapeFill = c; store.prefs.edit().putInt("fill", c).apply(); restyle { it.copy(fill = c) } }

    LaunchedEffect(rev) { if (rev > 0) { delay(700); saveAll() } }
    val isNew = remember { note.created == note.modified } // never saved by an edit yet
    // Leaving a brand-new note that was not touched at all (rev stays 0) deletes it instead of leaving an empty note behind.
    DisposableEffect(Unit) { onDispose { finishEdit(); if (isNew && rev == 0) store.delete(note) else if (rev > 0) saveAll(); store.exporter.flushNow() } } // viewing alone never rewrites a note // ponytail: saves on the UI thread, move off it if notes grow huge
    BackHandler(onBack = onBack)
    BackHandler(enabled = editing != null) { finishEdit() } // back ends typing first

    val pick = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { store.addImage(it) }?.let { (name, aspect) ->
            val img = Img(name, 80f, scrollY + 80f, 400f, 400f * aspect)
            commit(pages[cur], pages[cur].items + img)
            tool = SHAPE; selected = img // the shape tool moves and resizes images, so hand it over right away
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
            finishEdit()
            val p = pages[cur]
            p.undo.removeLastOrNull()?.let { p.redo.addLast(p.items); p.items = it; selected = null; rev++ }
        }, enabled = canUndo) { Icon(Icons.AutoMirrored.Filled.Undo, null) }
        Btn("Redo", {
            finishEdit()
            val p = pages[cur]
            p.redo.removeLastOrNull()?.let { p.undo.addLast(p.items); p.items = it; selected = null; rev++ }
        }, enabled = canRedo) { Icon(Icons.AutoMirrored.Filled.Redo, null) }
        Spacer(Modifier.size(8.dp))
        TOOLS.forEachIndexed { i, t ->
            Box {
                Btn(t.name, { finishEdit(); if (tool != i) selected = null; tool = i }, selected = tool == i,
                    longLabel = when (i) { SHAPE -> "Set shape, fill and outline"; TEXT -> "Set font, size and style"; else -> "Set ${t.name.lowercase()} thickness" },
                    onLongClick = { if (tool != i) { finishEdit(); selected = null }; tool = i; thicknessFor = i }) {
                    when (i) {
                        SHAPE -> ShapeGlyph(shapeKind)
                        TEXT -> Icon(Icons.Default.TextFields, null)
                        else -> Icon(painterResource(t.icon), null)
                    }
                }
                DropdownMenu(thicknessFor == i, { thicknessFor = -1 }) {
                    if (i == SHAPE) Column(Modifier.width(368.dp).padding(horizontal = 16.dp, vertical = 8.dp)) {
                        Text("Shape", style = MaterialTheme.typography.titleSmall)
                        Row {
                            SHAPE_NAMES.forEachIndexed { idx, name ->
                                Btn(name, { setShapeKind(idx) }, selected = shapeKind == idx) { ShapeGlyph(idx) }
                            }
                        }
                        Text("Fill", Modifier.padding(top = 8.dp), style = MaterialTheme.typography.titleSmall)
                        ColorChoices("No fill", shapeFill, custom, { setShapeFill(it) }) { thicknessFor = -1; customFor = 1; customDialog = true }
                        Text("Border", Modifier.padding(top = 8.dp), style = MaterialTheme.typography.titleSmall)
                        ColorChoices("No border", colors[SHAPE], custom, { pickColor(it) }) { thicknessFor = -1; customFor = 2; customDialog = true }
                        Text("Border thickness: ${"%.1f".format(widths[SHAPE])}", Modifier.padding(top = 8.dp), style = MaterialTheme.typography.titleSmall)
                        Slider(
                            widths[SHAPE], { widths[SHAPE] = it }, Modifier.semantics { contentDescription = "Shape border thickness" },
                            valueRange = t.min..t.max,
                            onValueChangeFinished = {
                                store.prefs.edit().putFloat("w$SHAPE", widths[SHAPE]).apply()
                                restyle { it.copy(width = widths[SHAPE]) }
                            },
                        )
                    } else if (i == TEXT) Column(Modifier.width(368.dp).padding(horizontal = 16.dp, vertical = 8.dp)) {
                        Text("Font", style = MaterialTheme.typography.titleSmall)
                        Row {
                            FONTS.forEachIndexed { idx, (_, label) ->
                                Btn(label, { setTextFont(idx) }, selected = tFont == idx) {
                                    Text("Aa", fontFamily = FontFamily(fontFace(idx, 0)), fontSize = 20.sp)
                                }
                            }
                        }
                        Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Btn("Bold", { toggleTextFlag(1) }, selected = tFlags and 1 != 0, state = if (tFlags and 1 != 0) "On" else "Off") { Icon(Icons.Default.FormatBold, null) }
                            Btn("Italic", { toggleTextFlag(2) }, selected = tFlags and 2 != 0, state = if (tFlags and 2 != 0) "On" else "Off") { Icon(Icons.Default.FormatItalic, null) }
                            Btn("Underline", { toggleTextFlag(4) }, selected = tFlags and 4 != 0, state = if (tFlags and 4 != 0) "On" else "Off") { Icon(Icons.Default.FormatUnderlined, null) }
                            Spacer(Modifier.width(12.dp))
                            Btn("Align left", { setTextAlign(0) }, selected = tAlign == 0) { Icon(Icons.Default.FormatAlignLeft, null) }
                            Btn("Align centre", { setTextAlign(1) }, selected = tAlign == 1) { Icon(Icons.Default.FormatAlignCenter, null) }
                            Btn("Align right", { setTextAlign(2) }, selected = tAlign == 2) { Icon(Icons.Default.FormatAlignRight, null) }
                        }
                        Text("Size: ${widths[TEXT].roundToInt()}", Modifier.padding(top = 8.dp), style = MaterialTheme.typography.titleSmall)
                        Slider(
                            widths[TEXT], { widths[TEXT] = it; editing?.let { e -> editing = e.copy(size = it) } },
                            Modifier.semantics { contentDescription = "Text size" }, valueRange = t.min..t.max,
                            onValueChangeFinished = {
                                store.prefs.edit().putFloat("w$TEXT", widths[TEXT]).apply()
                                if (editing == null) restyleText { it.copy(size = widths[TEXT]) }
                            },
                        )
                        Text("The colour button sets the text colour.", style = MaterialTheme.typography.bodySmall)
                        // true-size preview on white paper
                        Box(Modifier.fillMaxWidth().padding(top = 8.dp).height(90.dp).clip(RoundedCornerShape(8.dp)).background(Color.White)
                            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp)).padding(8.dp)) {
                            Text(
                                "Sample text", color = Color(colors[TEXT]), fontFamily = FontFamily(fontFace(tFont, tFlags)),
                                fontSize = with(LocalDensity.current) { (widths[TEXT] * kPx).toSp() },
                                textDecoration = if (tFlags and 4 != 0) TextDecoration.Underline else null,
                                textAlign = when (tAlign) { 1 -> TextAlign.Center; 2 -> TextAlign.End; else -> TextAlign.Start },
                                modifier = Modifier.fillMaxWidth(), maxLines = 2,
                            )
                        }
                    } else Column(Modifier.width(300.dp).padding(horizontal = 16.dp, vertical = 8.dp)) {
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
            Btn(if (colors[tool] == 0) "Colour: no border" else "Colour: ${PALETTE.firstOrNull { it.second == colors[tool] }?.first ?: "custom"}", { colorMenu = true },
                enabled = tool != ERASER) { if (colors[tool] == 0) Icon(Icons.Default.Block, null) else Swatch(colors[tool], 26) }
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
        Box(Modifier.fillMaxSize()) {
        Canvas(
            Modifier.fillMaxSize().clipToBounds().onSizeChanged { kPx = it.width / PAGE_W; viewH = it.height }
                .semantics { contentDescription = "Drawing area, page ${cur + 1} of ${pages.size}" }
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val k = size.width / PAGE_W
                        val first = awaitFirstDown(requireUnconsumed = false)
                        val touch = first.type == PointerType.Touch
                        var drawing = !touch || store.fingerDraw

                        val shapeTool = tool == SHAPE || tool == TEXT
                        // shape tool: 1 = move, 2 = resize through a handle, 3 = draw a new shape, 4 = rotate
                        var mode = 0
                        var orig: Item? = null
                        var handle = -1
                        var x0 = 0f; var y0 = 0f
                        var dragged = false
                        val slop = 8.dp.toPx() / k

                        fun add(ch: PointerInputChange, pos: Offset) {
                            val x = pos.x / k; val y = pos.y / k + scrollY
                            if (shapeTool) {
                                if (!dragged && hypot(x - x0, y - y0) > slop) {
                                    dragged = true
                                    orig?.let { hidden = setOf(it) } // the original is hidden while its live copy moves
                                }
                                if (dragged) preview = when (mode) {
                                    1 -> moved(orig!!, x - x0, y - y0)
                                    2 -> resized(orig!!, handle, x, y)
                                    4 -> rotated(orig!!, x0, y0, x, y)
                                    else -> if (tool == TEXT) newTextBox(minOf(x0, x), minOf(y0, y), maxOf(60f, abs(x - x0)))
                                        else Shape(shapeKind, colors[SHAPE], shapeFill, widths[SHAPE], x0, y0, x, y)
                                }
                            } else if (tool == ERASER) {
                                val r = widths[ERASER]
                                val hit = pages[cur].items.filter { it !in erased && hits(it, x, y, r) }
                                if (hit.isNotEmpty()) erased = erased + hit
                            } else {
                                live += x; live += y
                                live += if (pressure && !touch && tool == 0) ch.pressure.coerceIn(0.05f, 1f) else 1f
                            }
                            tick++
                        }
                        fun cancel() { live.clear(); erased = emptySet(); preview = null; if (editing == null && hidden.isNotEmpty()) hidden = emptySet(); tick++ }

                        if (editing != null) finishEdit() // touching the paper ends typing
                        if (drawing && shapeTool) {
                            x0 = first.position.x / k; y0 = first.position.y / k + scrollY
                            val sel = selected
                            val grabbed = if (sel != null) handleAt(sel, x0, y0, 24.dp.toPx() / k, 44.dp.toPx() / k) else -1
                            if (sel != null && grabbed >= 0) { mode = if (grabbed == 4) 4 else 2; orig = sel; handle = grabbed } // 4 = rotate handle
                            else if (sel != null && insideBox(sel, x0, y0)) { mode = 1; orig = sel } // drag anywhere inside the frame to move
                            else {
                                val hit = hitItem(pages[cur].items, x0, y0, 12.dp.toPx() / k)
                                if (hit != null) { mode = 1; orig = hit; selected = hit; selFresh = false } else { mode = 3; selected = null }
                            }
                            tick++
                        } else if (drawing) add(first, first.position)
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
                            if (shapeTool) {
                                val pv = preview
                                if (tool == TEXT && mode == 3) { // tap = a default-width box here, drag = a box of that width; either way start typing
                                    if (dragged && pv is TextBox) startEdit(pv, false)
                                    else if (!dragged) startEdit(newTextBox((x0).coerceAtMost(PAGE_W - 460f).coerceAtLeast(0f), y0 - widths[TEXT] / 2, 420f), false)
                                } else if (dragged && pv != null) {
                                    val n = normalised(pv)
                                    val old = p.items
                                    commit(p, if (mode == 3) old + n else old.map { if (it === orig) n else it })
                                    selected = n
                                    if (mode == 3) selFresh = true
                                } else if (!dragged && mode != 3) {
                                    selFresh = false // tapping the selected shape again: now it is "chosen"
                                    val o = orig
                                    if (tool == TEXT && o is TextBox) startEdit(o, true) // tapping a text box with the text tool edits it
                                }
                            } else if (tool == ERASER) { if (erased.isNotEmpty()) commit(p, p.items.filter { it !in erased }) }
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
                val (bmp, top) = cache.update(w, h, k, pages[cur].items, erased, hidden, cur, scrollY, note.bg, store::bitmap)
                n.drawBitmap(bmp, 0f, (top - scrollY) * k, null)
                if (live.isNotEmpty() && tool != ERASER) {
                    n.save(); n.scale(k, k); n.translate(0f, -scrollY)
                    drawStroke(n, Stroke(tool, colors[tool], widths[tool], live.toFloatArray()))
                    n.restore()
                }
                val pv = preview
                if (pv != null) { // the shape/image being drawn, moved or resized
                    n.save(); n.scale(k, k); n.translate(0f, -scrollY)
                    drawItem(n, pv, store::bitmap)
                    n.restore()
                }
                val framed = pv ?: selected
                if ((tool == SHAPE || tool == TEXT) && editing == null && framed != null) {
                    val dp = density
                    val hs = handles(framed, 44.dp.toPx() / k)
                    if (hs.isNotEmpty()) {
                        selPaint.style = android.graphics.Paint.Style.STROKE; selPaint.color = SEL; selPaint.strokeWidth = 1.5f * dp
                        val fr = frameOf(framed)
                        if (fr != null) { // boxes: dashed frame turned with the item, and a link from the corner to the rotate handle
                            n.save()
                            n.rotate(Math.toDegrees(fr[4].toDouble()).toFloat(), (fr[0] + fr[2]) / 2 * k, ((fr[1] + fr[3]) / 2 - scrollY) * k)
                            selPaint.pathEffect = android.graphics.DashPathEffect(floatArrayOf(8f * dp, 6f * dp), 0f)
                            n.drawRect(fr[0] * k, (fr[1] - scrollY) * k, fr[2] * k, (fr[3] - scrollY) * k, selPaint)
                            selPaint.pathEffect = null
                            n.restore()
                            n.drawLine(hs[3].first * k, (hs[3].second - scrollY) * k, hs[4].first * k, (hs[4].second - scrollY) * k, selPaint)
                        }
                        hs.forEachIndexed { idx, (hx, hy) ->
                            val cx = hx * k; val cy = (hy - scrollY) * k
                            val rotate = fr != null && idx == 4 // filled blue with a small arc, so it reads as "turn"
                            selPaint.style = android.graphics.Paint.Style.FILL; selPaint.color = if (rotate) SEL else 0xFFFFFFFF.toInt()
                            n.drawCircle(cx, cy, 9f * dp, selPaint)
                            selPaint.style = android.graphics.Paint.Style.STROKE; selPaint.color = if (rotate) 0xFFFFFFFF.toInt() else SEL
                            n.drawCircle(cx, cy, 9f * dp, selPaint)
                            if (rotate) n.drawArc(cx - 4.5f * dp, cy - 4.5f * dp, cx + 4.5f * dp, cy + 4.5f * dp, -60f, 270f, false, selPaint)
                        }
                    }
                }
            }
        }
        editing?.let { e -> // the text box being typed in: a real text field laid over the paper, same font, size, colour and rotation
            val d = LocalDensity.current
            val focus = remember { FocusRequester() }
            val keyboard = LocalSoftwareKeyboardController.current
            var tf by remember { mutableStateOf(TextFieldValue(e.text, TextRange(e.text.length))) }
            LaunchedEffect(Unit) { delay(120); runCatching { focus.requestFocus() }; keyboard?.show() }
            // keep the box above the keyboard while typing
            LaunchedEffect(viewH, e.h, e.y) {
                if (viewH > 0) {
                    val bottom = (e.y + e.h - scrollY) * kPx
                    val room = viewH - 24 * d.density
                    if (bottom > room) scrollY += (bottom - room) / kPx
                    if (e.y - scrollY < 0f) scrollY = e.y
                }
            }
            BasicTextField(
                tf, { tf = it; if (it.text != editing?.text) editing = editing?.copy(text = it.text) },
                Modifier.offset { IntOffset((e.x * kPx).roundToInt(), ((e.y - scrollY) * kPx).roundToInt()) }
                    .width(with(d) { (e.w * kPx).toDp() })
                    .graphicsLayer { rotationZ = Math.toDegrees(e.rot.toDouble()).toFloat() }
                    .border(1.5.dp, Color(SEL))
                    .focusRequester(focus)
                    .semantics { contentDescription = "Text box" },
                textStyle = TextStyle(
                    fontFamily = FontFamily(fontFace(e.font, e.flags)), fontSize = with(d) { (e.size * kPx).toSp() }, color = Color(e.color),
                    textAlign = when (e.align) { 1 -> TextAlign.Center; 2 -> TextAlign.End; else -> TextAlign.Start },
                    textDecoration = if (e.flags and 4 != 0) TextDecoration.Underline else null,
                ),
                cursorBrush = SolidColor(Color(e.color)),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences), // capital first letter, like every other name box
            )
        }
        }
    }

    if (customDialog) ColorDialog(
        when (customFor) {
            1 -> if (shapeFill != 0) shapeFill else custom ?: 0xFF000000.toInt()
            else -> if (colors[tool] != 0) colors[tool] else custom ?: 0xFF000000.toInt()
        },
        { c ->
            if (customFor == 1) setShapeFill(c) else pickColor(c)
            if (PALETTE.none { it.second == c }) { // a palette colour never occupies the custom slot
                custom = c
                store.prefs.edit().putString("custom", c.toString()).apply()
            }
            customDialog = false; customFor = 0
        },
        { customDialog = false; customFor = 0 },
    )

    if (renaming) {
        var t by remember { mutableStateOf(title) }
        AlertDialog(
            onDismissRequest = { renaming = false }, title = { Text("Note title") },
            text = { OutlinedTextField(t, { t = it }, Modifier.autoFocus(), label = { Text("Title") }, singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences)) },
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
    DisposableEffect(Unit) { onDispose { if (isNew && rev == 0) store.delete(note) else if (rev > 0) saveAll() } } // viewing alone never rewrites a note
    BackHandler(onBack = onBack)

    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            TextField(title, { title = it; rev++ }, Modifier.weight(1f), label = { Text("Title") }, singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences))
        }
        TextField(body, { body = it; rev++ }, Modifier.fillMaxSize(), label = { Text("Note") },
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences))
    }
}
