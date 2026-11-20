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
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.material.icons.filled.HorizontalRule
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.foundation.clickable
import androidx.compose.runtime.key
import androidx.compose.material.icons.filled.Draw
import androidx.compose.material.icons.filled.Gesture
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.automirrored.filled.NoteAdd
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.ViewSidebar
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
import androidx.compose.ui.graphics.toArgb
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
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.material.icons.filled.DragIndicator
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
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
    var phonePanelFraction by remember { mutableStateOf(0.62f) }
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
    var showPaper by remember { mutableStateOf(false) }
    var showRename by remember { mutableStateOf(false) }
    var showNav by remember { mutableStateOf(false) }
    var showPages by remember { mutableStateOf(false) }
    val canvases = remember { arrayOfNulls<InkCanvasView>(2) }
    var activeSlot by remember { mutableStateOf(0) }
    var lastSwipe by remember { mutableStateOf(0) }
    var menu by remember { mutableStateOf(false) }
    var barHidden by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }

    val insertLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenMultipleDocuments()
    ) { uris -> if (uris.isNotEmpty()) vm.insertPagesFrom(uris) { msg -> msg?.let { Toast.makeText(ctx, it, Toast.LENGTH_LONG).show() } } }

    val imageLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        val v = canvas ?: return@rememberLauncherForActivityResult
        if (uri == null) return@rememberLauncherForActivityResult
        vm.insertImage(uri, v.visiblePageRect()) { id ->
            if (id == null) Toast.makeText(ctx, "Couldn't open that image", Toast.LENGTH_SHORT).show()
            else { canvas?.pendingSelectImage = id; vm.setTool(tool.copy(tool = Tool.LASSO)) }
        }
    }

    // Keep the screen on while a notebook is open.
    val hostView = androidx.compose.ui.platform.LocalView.current
    DisposableEffect(hostView) {
        hostView.keepScreenOn = true
        onDispose { hostView.keepScreenOn = false }
    }

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
    val nav = settings.pageNav
    val twoPage = nav == "TWO_PAGE"
    val step = if (twoPage) 2 else 1
    val spreadStart = if (twoPage) ui.pageIndex - ui.pageIndex % 2 else ui.pageIndex
    LaunchedEffect(twoPage) {
        if (!twoPage && activeSlot != 0) { activeSlot = 0; selInfo = null; canvas = canvases[0] }
    }

    Scaffold(
        topBar = {
            if (!barHidden) TopAppBar(
                navigationIcon = { IconButton(onClick = leave) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                title = {
                    Row(
                        Modifier.clip(RoundedCornerShape(8.dp)).clickable(enabled = nb != null) { showRename = true }.padding(vertical = 6.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            nb?.title ?: "",
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Icon(Icons.Default.Edit, "Rename notebook", Modifier.padding(start = 4.dp).size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                actions = {
                    IconButton(onClick = { barHidden = true }) { Icon(Icons.Default.KeyboardArrowUp, "Hide top bar") }
                    IconButton(onClick = { showPages = !showPages }) { Icon(Icons.Default.ViewSidebar, "Page list") }
                    IconButton(onClick = { if (panel.open.isEmpty() && !panel.visible) vm.showPicker() else vm.togglePanel() }) {
                        Icon(Icons.Default.Widgets, "Tools")
                    }
                    if (nav == "ARROWS") IconButton(onClick = { vm.goTo(ui.pageIndex - 1) }, enabled = ui.pageIndex > 0) {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Previous page")
                    }
                    Text("${ui.pageIndex + 1}/${nb?.pages?.size ?: 1}", style = MaterialTheme.typography.labelLarge,
                        modifier = if (nav == "ARROWS") Modifier else Modifier.padding(horizontal = 8.dp))
                    if (nav == "ARROWS") IconButton(onClick = { vm.goTo(ui.pageIndex + 1) }, enabled = nb != null && ui.pageIndex < nb.pages.size - 1) {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Next page")
                    }
                    if (nav == "ARROWS" && nb?.lockPages != true) IconButton(onClick = vm::addPage) { Icon(Icons.AutoMirrored.Filled.NoteAdd, "Add page") }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "More") }
                        DropdownMenu(menu, { menu = false }) {
                            DropdownMenuItem(text = { Text("Paper style…") }, onClick = { menu = false; showPaper = true })
                            DropdownMenuItem(text = { Text("Page navigation…") }, onClick = { menu = false; showNav = true })
                            DropdownMenuItem(
                                text = { Text(if (nb?.lockPages == true) "Allow adding pages" else "Don't allow adding pages") },
                                onClick = { menu = false; vm.setLockPages(nb?.lockPages != true) },
                            )
                            DropdownMenuItem(text = { Text("Rename…") }, onClick = { menu = false; showRename = true })
                            if (nb?.lockPages != true) {
                                DropdownMenuItem(text = { Text("New page before this one") }, onClick = { menu = false; vm.insertPage(before = true) })
                                DropdownMenuItem(text = { Text("New page after this one") }, onClick = { menu = false; vm.insertPage(before = false) })
                            }
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
        val dock = runCatching { Dock.valueOf(settings.toolbarDock) }.getOrDefault(Dock.TOP)
        val deskColor = MaterialTheme.colorScheme.surfaceContainer.toArgb()
        val panelLeft = settings.panelSide == "LEFT"
        var area by remember { mutableStateOf(androidx.compose.ui.geometry.Rect.Zero) }
        var dragAt by remember { mutableStateOf<Offset?>(null) }      // toolbar being dragged
        var panelDragAt by remember { mutableStateOf<Offset?>(null) } // panel being dragged
        fun nearestDock(p: Offset): Dock {
            val d = mapOf(Dock.LEFT to p.x - area.left, Dock.RIGHT to area.right - p.x, Dock.TOP to p.y - area.top, Dock.BOTTOM to area.bottom - p.y)
            return d.minByOrNull { it.value }!!.key
        }
        val grip: @Composable () -> Unit = {
            DragGrip(
                "Move toolbar (drag to an edge)",
                onTap = {},
                onDragStart = { dragAt = it }, onDrag = { dragAt = it },
                onDragEnd = { dragAt?.let { p -> app.settings.update { s -> s.copy(toolbarDock = nearestDock(p).name) } }; dragAt = null },
                rotate = dock == Dock.TOP || dock == Dock.BOTTOM,
            )
        }
        val bar: @Composable () -> Unit = {
            ToolBar(
                tool, onChange = vm::setTool, dock = dock, grip = grip,
                canUndo = ui.canUndo, canRedo = ui.canRedo, onUndo = vm::undo, onRedo = vm::redo,
                onImage = { imageLauncher.launch(androidx.activity.result.PickVisualMediaRequest(androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly)) },
            )
        }
        val panelGrip: @Composable () -> Unit = {
            DragGrip(
                "Move panel (drag left or right, or tap to swap sides)",
                onTap = { app.settings.update { it.copy(panelSide = if (panelLeft) "RIGHT" else "LEFT") } },
                onDragStart = { panelDragAt = it }, onDrag = { panelDragAt = it },
                onDragEnd = {
                    panelDragAt?.let { p -> app.settings.update { it.copy(panelSide = if (p.x < area.center.x) "LEFT" else "RIGHT") } }
                    panelDragAt = null
                },
            )
        }
        Box(Modifier.padding(pad).fillMaxSize().onGloballyPositioned { area = it.boundsInRoot() }) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.weight(1f).fillMaxWidth()) {
            BoxWithConstraints(Modifier.weight(1f).fillMaxHeight()) {
            val outerW = maxWidth
            val wide = outerW >= 720.dp
            // only used when wide (>= 720dp), but keep the range valid on any width
            val panelW = (outerW * panelFraction).coerceIn(minOf(300.dp, outerW * 0.5f), maxOf(outerW - 240.dp, minOf(300.dp, outerW * 0.5f)))
            Row(Modifier.fillMaxSize()) {
            if (showPages && nb != null) {
                PagesSidebar(
                    nb = nb, pageIndex = ui.pageIndex, pressureCurve = settings.pressureCurve,
                    onGo = vm::goTo, onClose = { showPages = false }, vm = vm,
                    modifier = Modifier.width(if (wide) 168.dp else 140.dp).fillMaxHeight(),
                )
            }
            if (panel.visible && wide && panelLeft) {
                ToolPanel(panel, tools, hostFor, vm::openTool, vm::closeTool, vm::showPicker, vm::closePanel,
                    Modifier.width(panelW).fillMaxHeight(), grip = panelGrip)
                ResizeHandle(outerW, panelLeft) { panelFraction = it(panelFraction) }
            }
            BoxWithConstraints(Modifier.weight(1f).fillMaxHeight()) {
                Row(Modifier.fillMaxSize()) {
                for (slot in 0 until step) {
                    val idx = spreadStart + slot
                    val slotPage = nb?.pages?.getOrNull(idx)
                    BoxWithConstraints(Modifier.weight(1f).fillMaxHeight().clipToBounds()) {
                    val boxW = constraints.maxWidth
                    if (slot > 0 && slotPage == null) {
                        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainer), contentAlignment = Alignment.Center) {
                            if (nb?.lockPages != true) OutlinedButton(onClick = vm::appendPage) {
                                Icon(Icons.AutoMirrored.Filled.NoteAdd, null); Spacer(Modifier.width(8.dp)); Text("Add page")
                            }
                        }
                    } else key(slot) {
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { c ->
                            InkCanvasView(c).also { v ->
                                v.listener = object : InkCanvasView.Listener {
                                    override fun onCommit(page: Page) = vm.commit(page)
                                    override fun onSelectionChanged(info: SelectionInfo?) { if (activeSlot == slot) selInfo = info }
                                    override fun onTextRequest(x: Float, y: Float, existing: TextBox?) { textReq = TextRequest(x, y, existing) }
                                    override fun onStylusSeen() {
                                        canvases.forEach { it?.stylusSeen = true }
                                        if (app.settings.state.value.fingerMode == com.advnotes.app.data.FingerMode.AUTO) {
                                            Toast.makeText(c, "Pen detected – fingers now scroll and zoom", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                    override fun onSwipePage(dir: Int) {
                                        lastSwipe = dir
                                        vm.swipe(dir, if (app.settings.state.value.pageNav == "TWO_PAGE") 2 else 1)
                                    }
                                    override fun onTouchStart() {
                                        if (activeSlot != slot) {
                                            canvases.getOrNull(activeSlot)?.clearSelection()
                                            selInfo = null
                                            activeSlot = slot
                                        }
                                        canvas = v
                                        val target = (vm.ui.value.pageIndex.let { if (app.settings.state.value.pageNav == "TWO_PAGE") it - it % 2 else it }) + slot
                                        if (vm.ui.value.pageIndex != target) vm.goTo(target)
                                    }
                                }
                                canvases[slot] = v
                                if (slot == 0) canvas = v
                            }
                        },
                        onRelease = { v -> if (canvases[slot] === v) canvases[slot] = null; if (canvas === v) canvas = canvases[0] },
                        update = { v ->
                            if (nb != null && slotPage != null) {
                                val pageChanged = v.tag != slotPage.id
                                v.notebookId = nb.id
                                v.themeDeskColor = deskColor
                                v.paperStyle = nb.paper
                                v.paperColor = nb.paperColor
                                v.page = slotPage
                                if (pageChanged) {
                                    v.tag = slotPage.id; v.clearSelection()
                                    if (nav == "VERTICAL" && lastSwipe < 0) v.scrollToBottom() else v.scrollToTop()
                                }
                            }
                            v.swipeLabel = { dir ->
                                val st = vm.ui.value; val book = st.notebook
                                val step = if (app.settings.state.value.pageNav == "TWO_PAGE") 2 else 1
                                val target = st.pageIndex - st.pageIndex % step + dir * step
                                when {
                                    book == null -> null
                                    target < 0 -> if (book.lockPages) null else "New page before"
                                    target < book.pages.size -> if (dir > 0) "Next page" else "Previous page"
                                    book.lockPages -> null
                                    else -> "New page"
                                }
                            }
                            v.swipeAxis = when (nav) { "HORIZONTAL", "TWO_PAGE" -> 1; "VERTICAL" -> 2; else -> 0 }
                            v.tool = tool
                            v.prefs = InputPrefs(settings.fingerMode, settings.penButton, settings.pressureCurve, settings.holdToShape)
                        },
                    )
                    }
                    if (slot == activeSlot) selInfo?.let { s ->
                        val density = LocalDensity.current
                        val barH = with(density) { 52.dp.toPx() }
                        val barW = with(density) { (if (s.count > 0) 400 else 220).dp.toPx() }
                        val y = if (s.top - barH - 12 > 0) s.top - barH - 12 else s.bottom + 12
                        val x = (s.left).coerceIn(8f, (boxW - barW - 8).coerceAtLeast(8f))
                        SelectionBar(
                            modifier = Modifier.offset { IntOffset(x.roundToInt(), y.roundToInt()) },
                            hasItems = s.count > 0,
                            canEditText = canvas?.selectedTexts()?.size == 1 && canvas?.selectedStrokes()?.isEmpty() == true,
                            onEditText = {
                                val v = canvas ?: return@SelectionBar
                                val t = v.selectedTexts().singleOrNull() ?: return@SelectionBar
                                v.clearSelection()
                                textReq = TextRequest(t.x, t.y, t)
                            },
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
                }
                }
                // floating pen toolbar
                val align = when (dock) {
                    Dock.TOP -> Alignment.TopCenter; Dock.BOTTOM -> Alignment.BottomCenter
                    Dock.LEFT -> Alignment.CenterStart; Dock.RIGHT -> Alignment.CenterEnd
                }
                val dens = LocalDensity.current
                Box(
                    Modifier.align(align).padding(10.dp).onGloballyPositioned { c ->
                        val w = c.size.width + with(dens) { 20.dp.toPx() }
                        val h = c.size.height + with(dens) { 20.dp.toPx() }
                        val last = step - 1
                        for (i in 0..last) canvases[i]?.setInsets(
                            if (dock == Dock.LEFT && i == 0) w.toFloat() else 0f, if (dock == Dock.TOP) h.toFloat() else 0f,
                            if (dock == Dock.RIGHT && i == last) w.toFloat() else 0f, if (dock == Dock.BOTTOM) h.toFloat() else 0f,
                        )
                    },
                ) { bar() }
            }
            if (panel.visible && wide && !panelLeft) {
                ResizeHandle(outerW, panelLeft) { panelFraction = it(panelFraction) }
                ToolPanel(panel, tools, hostFor, vm::openTool, vm::closeTool, vm::showPicker, vm::closePanel,
                    Modifier.width(panelW).fillMaxHeight(), grip = panelGrip)
            }
            }
            if (panel.visible && !wide) {
                // Phones: the panel covers the lower part of the page; drag its top edge to resize.
                val totalH = with(LocalDensity.current) { maxHeight.toPx() }
                Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().fillMaxHeight(phonePanelFraction)) {
                    Box(
                        Modifier.fillMaxWidth().height(14.dp).background(MaterialTheme.colorScheme.surfaceVariant)
                            .pointerInput(totalH) {
                                detectVerticalDragGestures { _, dy -> phonePanelFraction = (phonePanelFraction - dy / totalH).coerceIn(0.3f, 0.92f) }
                            },
                        contentAlignment = Alignment.Center,
                    ) { Box(Modifier.size(36.dp, 3.dp).background(MaterialTheme.colorScheme.outline, RoundedCornerShape(2.dp))) }
                    ToolPanel(
                        panel, tools, hostFor, vm::openTool, vm::closeTool, vm::showPicker, vm::closePanel,
                        Modifier.fillMaxWidth().weight(1f),
                    )
                }
            }
            }
            }
        }
        // Drop targets while dragging the toolbar / panel
        if (barHidden) {
            androidx.compose.material3.SmallFloatingActionButton(
                onClick = { barHidden = false },
                modifier = Modifier.align(Alignment.TopEnd).padding(6.dp),
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            ) { Icon(Icons.Default.KeyboardArrowDown, "Show top bar") }
        }
        dragAt?.let { p -> DropHint(nearestDock(p), area) }
        panelDragAt?.let { p -> DropHint(if (p.x < area.center.x) Dock.LEFT else Dock.RIGHT, area, thickness = 0.35f) }
        }
    }


    if (showPaper && nb != null) {
        var scopeSel by remember { mutableStateOf(EditorViewModel.PaperScope.ALL) }
        var style by remember { mutableStateOf(page?.paper ?: nb.paper) }
        AlertDialog(
            onDismissRequest = { showPaper = false },
            title = { Text("Paper style") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    PaperPicker(style) { style = it }
                    listOf(
                        EditorViewModel.PaperScope.ALL to "All pages",
                        EditorViewModel.PaperScope.THIS_PAGE to "This page only",
                        EditorViewModel.PaperScope.FROM_HERE to "This page and all after it (new pages too)",
                    ).forEach { (k, label) ->
                        Row(Modifier.fillMaxWidth().clickable { scopeSel = k }, verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(scopeSel == k, { scopeSel = k }); Text(label)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { vm.setPaper(style, scopeSel); showPaper = false }) { Text("Apply") } },
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

    if (showNav) {
        val opts = listOf(
            "ARROWS" to "Arrows and new-page button",
            "HORIZONTAL" to "Horizontal – swipe sideways, pull well past the last page for a new one",
            "TWO_PAGE" to "Two pages side by side – swipe sideways",
            "VERTICAL" to "Vertical – swipe up/down, pull well past the last page for a new one",
        )
        AlertDialog(
            onDismissRequest = { showNav = false },
            title = { Text("Page navigation") },
            text = {
                Column {
                    opts.forEach { (k, label) ->
                        Row(
                            Modifier.fillMaxWidth().clickable { app.settings.update { it.copy(pageNav = k) }; showNav = false },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(nav == k, { app.settings.update { it.copy(pageNav = k) }; showNav = false })
                            Text(label, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    Text(
                        "Swipe with one finger when fingers scroll, or two fingers when they draw.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = { TextButton(onClick = { showNav = false }) { Text("Done") } },
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

enum class Dock { TOP, BOTTOM, LEFT, RIGHT }

/** Drag handle: drag to move the bar/panel, tap for [onTap]. Reports pointer positions in root coordinates. */
@Composable
fun DragGrip(
    description: String,
    onTap: () -> Unit,
    onDragStart: (Offset) -> Unit,
    onDrag: (Offset) -> Unit,
    onDragEnd: () -> Unit,
    rotate: Boolean = false,
) {
    var origin by remember { mutableStateOf(Offset.Zero) }
    var pos by remember { mutableStateOf(Offset.Zero) }
    Box(
        Modifier
            .size(40.dp)
            .onGloballyPositioned { origin = it.positionInRoot() }
            .pointerInput(Unit) { detectTapGestures { onTap() } }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { o -> pos = origin + o; onDragStart(pos) },
                    onDrag = { change, d -> change.consume(); pos += d; onDrag(pos) },
                    onDragEnd = onDragEnd,
                    onDragCancel = onDragEnd,
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Default.DragIndicator, description, Modifier.rotate(if (rotate) 90f else 0f),
            tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** The pen toolbar. Horizontal when docked top/bottom, vertical on the sides; icons are centred. */
@Composable
private fun ToolBar(
    tool: ToolState,
    onChange: (ToolState) -> Unit,
    dock: Dock,
    grip: @Composable () -> Unit,
    canUndo: Boolean,
    canRedo: Boolean,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onImage: () -> Unit,
) {
    val vertical = dock == Dock.LEFT || dock == Dock.RIGHT
    // "tool" = options popped out from the selected tool's icon, "tune" = from the options button
    var optionsAt by remember { mutableStateOf<String?>(null) }
    val items: @Composable () -> Unit = {
        grip()
        IconButton(onClick = onUndo, enabled = canUndo) { Icon(Icons.AutoMirrored.Filled.Undo, "Undo") }
        IconButton(onClick = onRedo, enabled = canRedo) { Icon(Icons.AutoMirrored.Filled.Redo, "Redo") }
        if (vertical) HorizontalDivider(Modifier.width(32.dp).padding(vertical = 6.dp))
        else VerticalDivider(Modifier.height(32.dp).padding(horizontal = 6.dp))
        fun pick(t: Tool) { if (tool.tool == t) { if (t != Tool.LASSO) optionsAt = "tool" } else { optionsAt = null; onChange(tool.copy(tool = t)) } }
        @Composable fun T(icon: ImageVector, label: String, t: Tool) = Box {
            ToolButton(icon, label, tool.tool == t) { pick(t) }
            if (tool.tool == t) ToolOptionsPopup(optionsAt == "tool", tool, onChange) { optionsAt = null }
        }
        T(Icons.Default.Draw, "Pen", Tool.PEN)
        T(Icons.Default.BorderColor, "Highlighter", Tool.HIGHLIGHTER)
        T(Icons.Default.AutoFixNormal, "Eraser", Tool.ERASER)
        T(Icons.Default.Gesture, "Lasso", Tool.LASSO)
        T(Icons.Default.HorizontalRule, "Line", Tool.LINE)
        T(Icons.Default.Category, "Shapes", Tool.SHAPE)
        T(Icons.Default.TextFields, "Text", Tool.TEXT)
        IconButton(onClick = onImage) { Icon(Icons.Default.AddPhotoAlternate, "Insert image") }
        if (vertical) HorizontalDivider(Modifier.width(32.dp).padding(vertical = 6.dp))
        else VerticalDivider(Modifier.height(32.dp).padding(horizontal = 6.dp))
        when (tool.tool) {
            Tool.PEN, Tool.LINE, Tool.SHAPE, Tool.TEXT -> PEN_COLORS.forEach { c ->
                ColorDot(c, c == tool.penColor) { onChange(tool.copy(penColor = c)) }
            }
            Tool.HIGHLIGHTER -> HIGHLIGHTER_COLORS.forEach { c ->
                ColorDot(c, c == tool.hlColor) { onChange(tool.copy(hlColor = c)) }
            }
            Tool.ERASER -> if (!vertical) EraserMode.entries.forEach { m ->
                FilterChip(m == tool.eraserMode, { onChange(tool.copy(eraserMode = m)) },
                    label = { Text(if (m == EraserMode.STROKE) "Whole stroke" else "Partial") })
            }
            Tool.LASSO -> if (!vertical) Text(
                "Circle ink to select · then move, resize, recolour or solve with AI",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (tool.tool != Tool.LASSO) Box {
            IconButton(onClick = { optionsAt = "tune" }) { Icon(Icons.Default.Tune, "Tool options") }
            ToolOptionsPopup(optionsAt == "tune", tool, onChange) { optionsAt = null }
        }
    }
    val pill = RoundedCornerShape(28.dp)
    Surface(shape = pill, color = MaterialTheme.colorScheme.surfaceContainerHigh, shadowElevation = 6.dp, tonalElevation = 2.dp) {
        if (vertical) {
            Column(
                Modifier.width(60.dp).heightIn(max = 720.dp).verticalScroll(rememberScrollState()).padding(vertical = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) { items() }
        } else {
            Row(
                Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) { items() }
        }
    }
}

@Composable
private fun ToolButton(icon: ImageVector, label: String, selected: Boolean, onClick: () -> Unit) {
    androidx.compose.material3.IconToggleButton(
        checked = selected, onCheckedChange = { onClick() },
        colors = androidx.compose.material3.IconButtonDefaults.iconToggleButtonColors(
            checkedContainerColor = MaterialTheme.colorScheme.primary,
            checkedContentColor = MaterialTheme.colorScheme.onPrimary,
        ),
    ) { Icon(icon, label) }
}

/** Small preview of a shape, drawn with the same geometry the Shapes tool uses. */
@Composable
private fun ShapeIcon(kind: ShapeKind) {
    val color = androidx.compose.material3.LocalContentColor.current
    androidx.compose.foundation.Canvas(Modifier.size(18.dp)) {
        val m = 2.dp.toPx(); val e = size.width - m
        val lines = kind == ShapeKind.LINE || kind == ShapeKind.ARROW
        val strokes = com.advnotes.app.ink.ShapeFactory.make(kind, m, if (lines) e else m, e, if (lines) m else e)
        for (xy in strokes) {
            val p = androidx.compose.ui.graphics.Path()
            p.moveTo(xy[0], xy[1])
            var i = 2
            while (i + 1 < xy.size) { p.lineTo(xy[i], xy[i + 1]); i += 2 }
            drawPath(p, color, style = androidx.compose.ui.graphics.drawscope.Stroke(1.6.dp.toPx(),
                cap = androidx.compose.ui.graphics.StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
        }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun ToolOptionsPopup(expanded: Boolean, tool: ToolState, onChange: (ToolState) -> Unit, onDismiss: () -> Unit) {
    // Opens next to the tool's icon in the toolbar instead of as a dialog in the middle of the screen.
    DropdownMenu(expanded, onDismiss, Modifier.widthIn(min = 260.dp, max = 340.dp)) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
            Text(style = MaterialTheme.typography.titleSmall, text =
                when (tool.tool) {
                    Tool.PEN -> "Pen"; Tool.HIGHLIGHTER -> "Highlighter"; Tool.ERASER -> "Eraser"
                    Tool.LASSO -> "Lasso"; Tool.LINE -> "Line"; Tool.SHAPE -> "Shapes"; Tool.TEXT -> "Text"
                }
            )
            Spacer(Modifier.height(8.dp))
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                when (tool.tool) {
                    Tool.PEN, Tool.LINE, Tool.SHAPE, Tool.TEXT -> {
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
                            FilterChip(k == tool.shape, { onChange(tool.copy(shape = k)) }, label = { Text(k.label) }, leadingIcon = { ShapeIcon(k) })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SelectionBar(
    modifier: Modifier,
    hasItems: Boolean,
    canEditText: Boolean,
    onEditText: () -> Unit,
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
            if (canEditText) IconButton(onClick = onEditText) { Icon(Icons.Default.Edit, "Edit text") }
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
                    var edited by remember(s.text) { mutableStateOf(s.text) }
                    OutlinedTextField(
                        edited, { edited = it }, modifier = Modifier.fillMaxWidth(),
                        label = { Text("Answer (edit before writing)") },
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { onInsert(edited) }, enabled = edited.isNotBlank()) { Text("Write on page") }
                        OutlinedButton(onClick = { clipboard.setText(AnnotatedString(edited)) }) { Text("Copy") }
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

/** Divider between page and panel; drag to resize. [update] receives a function old fraction -> new. */
@Composable
private fun ResizeHandle(total: androidx.compose.ui.unit.Dp, panelLeft: Boolean, update: ((Float) -> Float) -> Unit) {
    val totalPx = with(LocalDensity.current) { total.toPx() }
    Box(
        Modifier.fillMaxHeight().width(10.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .pointerInput(totalPx, panelLeft) {
                detectHorizontalDragGestures { _, dx ->
                    val d = (if (panelLeft) dx else -dx) / totalPx
                    update { (it + d).coerceIn(0.25f, 0.7f) }
                }
            },
        contentAlignment = Alignment.Center,
    ) { Box(Modifier.size(3.dp, 36.dp).background(MaterialTheme.colorScheme.outline, RoundedCornerShape(2.dp))) }
}

/** Highlights the edge something will dock to when released. */
@Composable
private fun DropHint(edge: Dock, area: androidx.compose.ui.geometry.Rect, thickness: Float = 0f) {
    val density = LocalDensity.current
    val w = with(density) { area.width.toDp() }
    val h = with(density) { area.height.toDp() }
    val strip = 64.dp
    val (mod, align) = when (edge) {
        Dock.TOP -> Modifier.fillMaxWidth().height(strip) to Alignment.TopCenter
        Dock.BOTTOM -> Modifier.fillMaxWidth().height(strip) to Alignment.BottomCenter
        Dock.LEFT -> Modifier.fillMaxHeight().width(if (thickness > 0) w * thickness else strip) to Alignment.CenterStart
        Dock.RIGHT -> Modifier.fillMaxHeight().width(if (thickness > 0) w * thickness else strip) to Alignment.CenterEnd
    }
    Box(Modifier.size(w, h)) {
        Box(mod.align(align).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.22f), RoundedCornerShape(12.dp)))
    }
}
