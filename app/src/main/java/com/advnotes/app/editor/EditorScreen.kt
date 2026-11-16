package com.advnotes.app.editor

import android.graphics.Bitmap
import android.graphics.RectF
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.AutoFixNormal
import androidx.compose.material.icons.filled.BorderColor
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Draw
import androidx.compose.material.icons.filled.Gesture
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.automirrored.filled.NoteAdd
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Widgets
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconToggleButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import com.advnotes.app.AdvNotesApp
import com.advnotes.app.ai.SolveMode
import com.advnotes.app.data.ProviderType
import com.advnotes.app.export.PdfExporter
import com.advnotes.app.ink.EraserMode
import com.advnotes.app.ink.InkCanvasView
import com.advnotes.app.ink.InputPrefs
import com.advnotes.app.ink.SelectionInfo
import com.advnotes.app.ink.ShapeKind
import com.advnotes.app.ink.Tool
import com.advnotes.app.ink.ToolState
import com.advnotes.app.model.Page
import com.advnotes.app.model.TextBox
import com.advnotes.app.ui.ColorDot
import com.advnotes.app.ui.HIGHLIGHTER_COLORS
import com.advnotes.app.ui.PEN_COLORS
import com.advnotes.app.ui.PaperPicker
import com.advnotes.app.tools.NotebookTool
import com.advnotes.app.tools.ToolHost
import com.advnotes.app.tools.ToolPanel
import com.advnotes.app.tools.ToolStorage
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private data class TextRequest(val x: Float, val y: Float, val existing: TextBox?)

private data class HwRequest(val strokes: List<com.advnotes.app.model.Stroke>, val image: Bitmap?)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(notebookId: String, onBack: () -> Unit, onSettings: () -> Unit) {
    val vm: EditorViewModel = viewModel()
    val app = AdvNotesApp.instance
    LaunchedEffect(notebookId) { vm.open(notebookId) }
    val ui by vm.ui.collectAsState()
    val tool by vm.tool.collectAsState()
    val settings by app.settings.state.collectAsState()
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var canvas by remember { mutableStateOf<InkCanvasView?>(null) }
    var selInfo by remember { mutableStateOf<SelectionInfo?>(null) }
    var textReq by remember { mutableStateOf<TextRequest?>(null) }
    var aiImage by remember { mutableStateOf<Bitmap?>(null) }
    var aiBounds by remember { mutableStateOf<RectF?>(null) }
    var hwRequest by remember { mutableStateOf<HwRequest?>(null) }
    val panel by vm.panel.collectAsState()
    val tools by app.tools.tools.collectAsState()
    var panelFraction by remember { mutableStateOf(0.38f) }
    var pendingPick by remember { mutableStateOf<((android.net.Uri?) -> Unit)?>(null) }
    val toolPickLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri -> pendingPick?.invoke(uri); pendingPick = null }
    val hosts = remember { HashMap<String, ToolHost>() }
    val hostFor: (NotebookTool) -> ToolHost = { t ->
        hosts.getOrPut(t.id) {
            object : ToolHost {
                override val context get() = ctx
                override val toolId = t.id
                override val notebookTitle get() = vm.ui.value.notebook?.title.orEmpty()
                override val storage = ToolStorage(ctx, t.id)
                override fun insertText(text: String) {
                    val v = canvas ?: return
                    val sel = v.selectionBoundsPage()
                    if (sel != null) vm.insertAnswer(text, sel, below = true)
                    else { val r = v.visiblePageRect(); vm.insertTextAt(text, r.left + 30f, r.top + 40f) }
                    Toast.makeText(ctx, "Added to page", Toast.LENGTH_SHORT).show()
                }
                override fun selectionImage() = canvas?.selectionBitmap()
                override fun pageImage() = canvas?.let { it.regionBitmap(it.visiblePageRect()) }
                override fun ai() = com.advnotes.app.ai.AiClients.forProvider(app.settings.state.value.provider, app.settings, app.chatGpt, app.local)
                override fun aiLabel(): String {
                    val p = app.settings.state.value.provider
                    val m = if (p == com.advnotes.app.data.ProviderType.LOCAL) app.settings.state.value.localModel else app.settings.model(p)
                    return "${p.label} · ${m ?: "no model"}"
                }
                override fun toast(message: String) = Toast.makeText(ctx, message, Toast.LENGTH_SHORT).show()
                override fun pickFile(mimeTypes: List<String>, onResult: (android.net.Uri?) -> Unit) {
                    pendingPick = onResult
                    toolPickLauncher.launch(mimeTypes.toTypedArray())
                }
            }
        }
    }
    var showOptions by remember { mutableStateOf(false) }
    var showPaper by remember { mutableStateOf(false) }
    var showRename by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }

    val insertLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenMultipleDocuments()
    ) { uris -> if (uris.isNotEmpty()) vm.insertPagesFrom(uris) { msg -> msg?.let { Toast.makeText(ctx, it, Toast.LENGTH_LONG).show() } } }

    val leave = { vm.onLeave(); onBack() }
    BackHandler { leave() }

    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_STOP) vm.onLeave() }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }

    val nb = ui.notebook
    val page = nb?.pages?.getOrNull(ui.pageIndex)

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = leave) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                title = {
                    Text(
                        nb?.title ?: "",
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(end = 4.dp),
                        style = MaterialTheme.typography.titleMedium,
                    )
                },
                actions = {
                    IconButton(onClick = { if (panel.open.isEmpty() && !panel.visible) vm.showPicker() else vm.togglePanel() }) {
                        Icon(Icons.Default.Widgets, "Tools")
                    }
                    IconButton(onClick = vm::undo, enabled = ui.canUndo) { Icon(Icons.AutoMirrored.Filled.Undo, "Undo") }
                    IconButton(onClick = vm::redo, enabled = ui.canRedo) { Icon(Icons.AutoMirrored.Filled.Redo, "Redo") }
                    IconButton(onClick = { vm.goTo(ui.pageIndex - 1) }, enabled = ui.pageIndex > 0) {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Previous page")
                    }
                    Text("${ui.pageIndex + 1}/${nb?.pages?.size ?: 1}", style = MaterialTheme.typography.labelLarge)
                    IconButton(onClick = { vm.goTo(ui.pageIndex + 1) }, enabled = nb != null && ui.pageIndex < nb.pages.size - 1) {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Next page")
                    }
                    IconButton(onClick = vm::addPage) { Icon(Icons.AutoMirrored.Filled.NoteAdd, "Add page") }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "More") }
                        DropdownMenu(menu, { menu = false }) {
                            DropdownMenuItem(text = { Text("Paper style…") }, onClick = { menu = false; showPaper = true })
                            DropdownMenuItem(text = { Text("Rename…") }, onClick = { menu = false; showRename = true })
                            DropdownMenuItem(text = { Text("Insert pages from file…") }, onClick = { menu = false; insertLauncher.launch(arrayOf("*/*")) })
                            DropdownMenuItem(text = { Text("Fit page to screen") }, onClick = { menu = false; canvas?.fitPage() })
                            DropdownMenuItem(text = { Text("Export PDF") }, onClick = {
                                menu = false
                                scope.launch {
                                    vm.saveNow()
                                    vm.ui.value.notebook?.let { PdfExporter.share(ctx, PdfExporter.export(ctx, it, settings.pressureCurve)) }
                                }
                            })
                            DropdownMenuItem(text = { Text("Delete this page") }, onClick = { menu = false; vm.deletePage() })
                            DropdownMenuItem(text = { Text("Settings") }, onClick = { menu = false; onSettings() })
                        }
                    }
                },
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            ToolBar(tool, onChange = vm::setTool, onOptions = { showOptions = true })
            HorizontalDivider()
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val outerW = maxWidth
            val wide = outerW >= 720.dp
            val panelW = (outerW * panelFraction).coerceIn(300.dp, outerW - 240.dp)
            Row(Modifier.fillMaxSize()) {
            BoxWithConstraints(Modifier.weight(1f).fillMaxHeight()) {
                val boxW = constraints.maxWidth
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { c ->
                        InkCanvasView(c).also { v ->
                            v.listener = object : InkCanvasView.Listener {
                                override fun onCommit(page: Page) = vm.commit(page)
                                override fun onSelectionChanged(info: SelectionInfo?) { selInfo = info }
                                override fun onTextRequest(x: Float, y: Float, existing: TextBox?) { textReq = TextRequest(x, y, existing) }
                                override fun onStylusSeen() {
                                    if (app.settings.state.value.fingerMode == com.advnotes.app.data.FingerMode.AUTO) {
                                        Toast.makeText(c, "Pen detected – fingers now scroll and zoom", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                            canvas = v
                        }
                    },
                    update = { v ->
                        if (nb != null && page != null) {
                            val pageChanged = v.tag != page.id
                            v.notebookId = nb.id
                            v.paperStyle = nb.paper
                            v.paperColor = nb.paperColor
                            v.page = page
                            if (pageChanged) { v.tag = page.id; v.clearSelection(); v.scrollToTop() }
                        }
                        v.tool = tool
                        v.prefs = InputPrefs(settings.fingerMode, settings.penButton, settings.pressureCurve, settings.holdToShape)
                    },
                )
                selInfo?.let { s ->
                    val density = LocalDensity.current
                    val barH = with(density) { 52.dp.toPx() }
                    val barW = with(density) { (if (s.count > 0) 360 else 220).dp.toPx() }
                    val y = if (s.top - barH - 12 > 0) s.top - barH - 12 else s.bottom + 12
                    val x = (s.left).coerceIn(8f, (boxW - barW - 8).coerceAtLeast(8f))
                    SelectionBar(
                        modifier = Modifier.offset { IntOffset(x.roundToInt(), y.roundToInt()) },
                        hasItems = s.count > 0,
                        onToText = {
                            val v = canvas ?: return@SelectionBar
                            hwRequest = HwRequest(v.selectedStrokes(), v.selectionBitmap())
                        },
                        onSolve = {
                            val v = canvas ?: return@SelectionBar
                            aiImage = v.selectionBitmap()
                            aiBounds = v.selectionBoundsPage()
                        },
                        onDuplicate = { canvas?.duplicateSelection() },
                        onColor = { canvas?.recolorSelection(it) },
                        onDelete = { canvas?.deleteSelection() },
                        onClose = { canvas?.clearSelection() },
                    )
                }
            }
            if (panel.visible && wide) {
                // drag the divider to resize the panel
                val totalPx = with(LocalDensity.current) { outerW.toPx() }
                Box(
                    Modifier.fillMaxHeight().width(10.dp)
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .pointerInput(totalPx) {
                            detectHorizontalDragGestures { _, dx -> panelFraction = (panelFraction - dx / totalPx).coerceIn(0.25f, 0.7f) }
                        },
                    contentAlignment = Alignment.Center,
                ) { Box(Modifier.size(3.dp, 36.dp).background(MaterialTheme.colorScheme.outline, RoundedCornerShape(2.dp))) }
                ToolPanel(panel, tools, hostFor, vm::openTool, vm::closeTool, vm::showPicker, vm::closePanel, Modifier.width(panelW).fillMaxHeight())
            }
            }
            if (panel.visible && !wide) {
                ToolPanel(
                    panel, tools, hostFor, vm::openTool, vm::closeTool, vm::showPicker, vm::closePanel,
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth().fillMaxHeight(0.55f),
                )
            }
            }
        }
    }

    if (showOptions) ToolOptionsDialog(tool, vm::setTool) { showOptions = false }

    if (showPaper && nb != null) {
        var whole by remember { mutableStateOf(true) }
        var style by remember { mutableStateOf(page?.paper ?: nb.paper) }
        AlertDialog(
            onDismissRequest = { showPaper = false },
            title = { Text("Paper style") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    PaperPicker(style) { style = it }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(whole, { whole = true }); Text("All pages")
                        Spacer(Modifier.width(12.dp))
                        RadioButton(!whole, { whole = false }); Text("This page only")
                    }
                }
            },
            confirmButton = { TextButton(onClick = { vm.setPaper(style, whole); showPaper = false }) { Text("Apply") } },
            dismissButton = { TextButton(onClick = { showPaper = false }) { Text("Cancel") } },
        )
    }

    if (showRename && nb != null) {
        var t by remember { mutableStateOf(nb.title) }
        AlertDialog(
            onDismissRequest = { showRename = false },
            title = { Text("Rename notebook") },
            text = { OutlinedTextField(t, { t = it }, singleLine = true) },
            confirmButton = { TextButton(onClick = { vm.rename(t.trim().ifEmpty { "Untitled" }); showRename = false }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { showRename = false }) { Text("Cancel") } },
        )
    }

    textReq?.let { req ->
        TextDialog(req.existing, tool.penColor, onDismiss = { textReq = null }) { text, size, color ->
            vm.upsertText(req.x, req.y, req.existing, text, size, color)
            textReq = null
        }
    }

    hwRequest?.let { req ->
        HandwritingSheet(
            req = req,
            onReplace = { text, size -> canvas?.replaceSelectionWithText(text, size); hwRequest = null },
            onAddBelow = { text -> vm.insertAnswer(text, canvas?.selectionBoundsPage(), below = true); canvas?.clearSelection(); hwRequest = null },
            onDismiss = { hwRequest = null },
        )
    }

    aiImage?.let { bmp ->
        AiSheet(
            vm = vm,
            image = bmp,
            onInsert = { text -> vm.insertAnswer(text, aiBounds); canvas?.clearSelection(); aiImage = null },
            onDismiss = { vm.cancelAi(); aiImage = null },
            onSettings = { vm.cancelAi(); aiImage = null; onSettings() },
        )
    }
}

// ----------------------------------------------------------------------------------------
// Toolbar
// ----------------------------------------------------------------------------------------

@Composable
private fun ToolBar(tool: ToolState, onChange: (ToolState) -> Unit, onOptions: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        fun pick(t: Tool) { if (tool.tool == t) onOptions() else onChange(tool.copy(tool = t)) }
        ToolButton(Icons.Default.Draw, "Pen", tool.tool == Tool.PEN) { pick(Tool.PEN) }
        ToolButton(Icons.Default.BorderColor, "Highlighter", tool.tool == Tool.HIGHLIGHTER) { pick(Tool.HIGHLIGHTER) }
        ToolButton(Icons.Default.AutoFixNormal, "Eraser", tool.tool == Tool.ERASER) { pick(Tool.ERASER) }
        ToolButton(Icons.Default.Gesture, "Lasso", tool.tool == Tool.LASSO) { pick(Tool.LASSO) }
        ToolButton(Icons.Default.Category, "Shapes", tool.tool == Tool.SHAPE) { pick(Tool.SHAPE) }
        ToolButton(Icons.Default.TextFields, "Text", tool.tool == Tool.TEXT) { pick(Tool.TEXT) }
        VerticalDivider(Modifier.height(32.dp).padding(horizontal = 6.dp))
        when (tool.tool) {
            Tool.PEN, Tool.SHAPE, Tool.TEXT -> PEN_COLORS.forEach { c ->
                ColorDot(c, c == tool.penColor) { onChange(tool.copy(penColor = c)) }
            }
            Tool.HIGHLIGHTER -> HIGHLIGHTER_COLORS.forEach { c ->
                ColorDot(c, c == tool.hlColor) { onChange(tool.copy(hlColor = c)) }
            }
            Tool.ERASER -> EraserMode.entries.forEach { m ->
                FilterChip(m == tool.eraserMode, { onChange(tool.copy(eraserMode = m)) },
                    label = { Text(if (m == EraserMode.STROKE) "Whole stroke" else "Partial") })
            }
            Tool.LASSO -> Text(
                "Circle ink to select · then move, resize, recolour or solve with AI",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (tool.tool != Tool.LASSO) {
            IconButton(onClick = onOptions) { Icon(Icons.Default.Tune, "Tool options") }
        }
    }
}

@Composable
private fun ToolButton(icon: ImageVector, label: String, selected: Boolean, onClick: () -> Unit) {
    FilledTonalIconToggleButton(checked = selected, onCheckedChange = { onClick() }) { Icon(icon, label) }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun ToolOptionsDialog(tool: ToolState, onChange: (ToolState) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
        title = {
            Text(
                when (tool.tool) {
                    Tool.PEN -> "Pen"; Tool.HIGHLIGHTER -> "Highlighter"; Tool.ERASER -> "Eraser"
                    Tool.LASSO -> "Lasso"; Tool.SHAPE -> "Shapes"; Tool.TEXT -> "Text"
                }
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                when (tool.tool) {
                    Tool.PEN, Tool.SHAPE, Tool.TEXT -> {
                        Text("Thickness: ${"%.1f".format(tool.penWidth)}")
                        Slider(tool.penWidth, { onChange(tool.copy(penWidth = it)) }, valueRange = 1f..20f)
                        if (tool.tool == Tool.PEN) Text(
                            "Tip: draw a line or shape and hold the pen still for a moment to snap it to a perfect shape.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Tool.HIGHLIGHTER -> {
                        Text("Thickness: ${tool.hlWidth.roundToInt()}")
                        Slider(tool.hlWidth, { onChange(tool.copy(hlWidth = it)) }, valueRange = 8f..60f)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Always straight lines", Modifier.weight(1f))
                            Switch(tool.hlStraight, { onChange(tool.copy(hlStraight = it)) })
                        }
                    }
                    Tool.ERASER -> {
                        Text("Size: ${tool.eraserSize.roundToInt()} px")
                        Slider(tool.eraserSize, { onChange(tool.copy(eraserSize = it)) }, valueRange = 8f..120f)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            EraserMode.entries.forEach { m ->
                                FilterChip(m == tool.eraserMode, { onChange(tool.copy(eraserMode = m)) },
                                    label = { Text(if (m == EraserMode.STROKE) "Whole stroke" else "Partial") })
                            }
                        }
                    }
                    Tool.LASSO -> Unit
                }
                if (tool.tool == Tool.SHAPE) {
                    Text("Shape", style = MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ShapeKind.entries.filter { it != ShapeKind.POLYGON }.forEach { k ->
                            FilterChip(k == tool.shape, { onChange(tool.copy(shape = k)) }, label = { Text(k.label) })
                        }
                    }
                }
            }
        },
    )
}

@Composable
private fun SelectionBar(
    modifier: Modifier,
    hasItems: Boolean,
    onSolve: () -> Unit,
    onToText: () -> Unit,
    onDuplicate: () -> Unit,
    onColor: (Int) -> Unit,
    onDelete: () -> Unit,
    onClose: () -> Unit,
) {
    var colors by remember { mutableStateOf(false) }
    Surface(modifier, shape = RoundedCornerShape(26.dp), tonalElevation = 6.dp, shadowElevation = 6.dp) {
        Row(Modifier.padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onSolve) {
                Icon(Icons.Default.AutoAwesome, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Solve")
            }
            TextButton(onClick = onToText) { Text("To text") }
            if (hasItems) IconButton(onClick = onDuplicate) { Icon(Icons.Default.ContentCopy, "Duplicate") }
            if (hasItems) Box {
                IconButton(onClick = { colors = true }) { Icon(Icons.Default.Palette, "Colour") }
                DropdownMenu(colors, { colors = false }) {
                    Row(Modifier.padding(8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        PEN_COLORS.forEach { c -> ColorDot(c, false) { onColor(c); colors = false } }
                    }
                }
            }
            if (hasItems) IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, "Delete") }
            IconButton(onClick = onClose) { Icon(Icons.Default.Close, "Deselect") }
        }
    }
}

@Composable
private fun TextDialog(existing: TextBox?, defaultColor: Int, onDismiss: () -> Unit, onSave: (String, Float, Int) -> Unit) {
    var text by remember { mutableStateOf(existing?.text ?: "") }
    var size by remember { mutableStateOf(existing?.size ?: 22f) }
    var color by remember { mutableStateOf(existing?.color ?: defaultColor) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "Add text" else "Edit text") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(text, { text = it }, modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp))
                Text("Size: ${size.roundToInt()}")
                Slider(size, { size = it }, valueRange = 12f..60f)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    PEN_COLORS.forEach { c -> ColorDot(c, c == color) { color = c } }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(text, size, color) }) { Text("Save") } },
        dismissButton = {
            Row {
                if (existing != null) TextButton(onClick = { onSave("", size, color) }) { Text("Delete") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

// ----------------------------------------------------------------------------------------
// AI solver sheet
// ----------------------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun AiSheet(
    vm: EditorViewModel,
    image: Bitmap,
    onInsert: (String) -> Unit,
    onDismiss: () -> Unit,
    onSettings: () -> Unit,
) {
    val app = AdvNotesApp.instance
    val settings by app.settings.state.collectAsState()
    val ai by vm.ai.collectAsState()
    var mode by remember { mutableStateOf(SolveMode.SOLVE) }
    var subject by remember { mutableStateOf(vm.lastSubject) }
    var extra by remember { mutableStateOf("") }
    val clipboard = LocalClipboardManager.current
    val provider = settings.provider
    val modelLabel = when (provider) {
        ProviderType.LOCAL -> settings.localModel ?: "no model selected"
        else -> app.settings.model(provider).ifBlank { "no model set" }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.AutoAwesome, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text("Solver", style = MaterialTheme.typography.titleMedium)
                    Text("${provider.label} · $modelLabel", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                TextButton(onClick = onSettings) { Text("Change") }
            }
            Surface(shape = RoundedCornerShape(12.dp), tonalElevation = 1.dp) {
                Image(
                    image.asImageBitmap(), "Selection",
                    Modifier.fillMaxWidth().heightIn(max = 160.dp).padding(8.dp),
                )
            }
            Text("Subject", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                com.advnotes.app.ai.Subject.entries.forEach { sub -> FilterChip(sub == subject, { subject = sub }, label = { Text(sub.label) }) }
            }
            Text("What to do", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SolveMode.entries.forEach { m -> FilterChip(m == mode, { mode = m }, label = { Text(m.label) }) }
            }
            OutlinedTextField(
                extra, { extra = it }, modifier = Modifier.fillMaxWidth(),
                label = { Text("Extra instructions (optional)") },
                placeholder = { Text("e.g. solve for x, use radians, answer in kJ/mol") },
            )
            val running = ai is AiState.Running
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { vm.solve(image, mode, extra, subject) }, enabled = !running) {
                    Text(if (ai is AiState.Done || ai is AiState.Error) "Ask again" else "Solve")
                }
                if (running) OutlinedButton(onClick = vm::cancelAi) { Text("Stop") }
            }
            when (val s = ai) {
                AiState.Idle -> Unit
                is AiState.Running -> {
                    if (s.text.isEmpty()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(10.dp))
                            Text("Thinking…")
                        }
                    } else {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        AnswerText(s.text)
                    }
                }
                is AiState.Done -> {
                    AnswerText(s.text)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { onInsert(s.text) }) { Text("Write on page") }
                        OutlinedButton(onClick = { clipboard.setText(AnnotatedString(s.text)) }) { Text("Copy") }
                    }
                }
                is AiState.Error -> Text(s.message, color = MaterialTheme.colorScheme.error)
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun AnswerText(text: String) {
    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
        SelectionContainer {
            Text(text, Modifier.fillMaxWidth().padding(14.dp), style = MaterialTheme.typography.bodyLarge)
        }
    }
}


// ----------------------------------------------------------------------------------------
// Handwriting → text sheet
// ----------------------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HandwritingSheet(
    req: HwRequest,
    onReplace: (String, Float) -> Unit,
    onAddBelow: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val app = AdvNotesApp.instance
    val settings by app.settings.state.collectAsState()
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val hasInk = req.strokes.isNotEmpty()
    var lang by remember { mutableStateOf(com.advnotes.app.ai.Handwriting.byName(settings.hwLanguage) ?: com.advnotes.app.ai.Handwriting.LANGUAGES[1]) }
    var engine by remember {
        mutableStateOf(
            if (!hasInk) com.advnotes.app.ai.HwEngine.AI
            else runCatching { com.advnotes.app.ai.HwEngine.valueOf(settings.hwEngine) }.getOrDefault(com.advnotes.app.ai.HwEngine.ON_DEVICE)
        )
    }
    var text by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var job by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var langMenu by remember { mutableStateOf(false) }

    fun run() {
        job?.cancel(); error = null; text = ""
        app.settings.update { it.copy(hwLanguage = lang.name, hwEngine = engine.name) }
        job = scope.launch {
            try {
                text = if (engine == com.advnotes.app.ai.HwEngine.ON_DEVICE) {
                    com.advnotes.app.ai.Handwriting.recognizeOnDevice(req.strokes, lang) { status = it }
                } else {
                    status = "Asking ${settings.provider.label}…"
                    val client = com.advnotes.app.ai.AiClients.forProvider(settings.provider, app.settings, app.chatGpt, app.local)
                    com.advnotes.app.ai.Handwriting.recognizeAi(client, req.image ?: error("No image"), lang) { text = it }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message ?: e.toString()
            }
            status = null
        }
    }
    LaunchedEffect(Unit) { run() }

    ModalBottomSheet(onDismissRequest = { job?.cancel(); onDismiss() }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Handwriting to text", style = MaterialTheme.typography.titleMedium)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box {
                    OutlinedButton(onClick = { langMenu = true }) { Text(lang.name) }
                    DropdownMenu(langMenu, { langMenu = false }, Modifier.heightIn(max = 400.dp)) {
                        com.advnotes.app.ai.Handwriting.LANGUAGES.forEach { l ->
                            DropdownMenuItem(text = { Text(l.name) }, onClick = {
                                langMenu = false; lang = l
                                if (l.tag == null) engine = com.advnotes.app.ai.HwEngine.AI
                                run()
                            })
                        }
                    }
                }
                com.advnotes.app.ai.HwEngine.entries.forEach { e ->
                    val enabled = e == com.advnotes.app.ai.HwEngine.AI || (hasInk && lang.tag != null)
                    FilterChip(engine == e, { engine = e; run() }, label = { Text(e.label) }, enabled = enabled)
                }
            }
            Text(
                if (engine == com.advnotes.app.ai.HwEngine.ON_DEVICE) "Recognised on this device from your pen strokes (Google ML Kit). Works offline after the language model downloads."
                else "Sent as an image to ${settings.provider.label}. Also reads printed text and mixed languages.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            status?.let {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp); Spacer(Modifier.width(10.dp)); Text(it)
                }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            OutlinedTextField(
                text, { text = it }, modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp),
                label = { Text("Text (you can edit it)") },
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (hasInk) Button(enabled = text.isNotBlank(), onClick = { onReplace(text, 24f) }) { Text("Replace ink") }
                OutlinedButton(enabled = text.isNotBlank(), onClick = { onAddBelow(text) }) { Text(if (hasInk) "Keep ink, add text" else "Add to page") }
                TextButton(enabled = text.isNotBlank(), onClick = { clipboard.setText(AnnotatedString(text)) }) { Text("Copy") }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}
