package com.advnotes.app.ui

import com.advnotes.app.i18n.tr

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Draw
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Widgets
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.advnotes.app.AdvNotesApp
import com.advnotes.app.BuildConfig
import com.advnotes.app.ai.AiClients
import com.advnotes.app.ai.DownloadState
import com.advnotes.app.ai.LlamaBridge
import com.advnotes.app.ai.MathSolver
import com.advnotes.app.ai.SolveMode
import com.advnotes.app.data.FingerMode
import com.advnotes.app.data.PenButtonAction
import com.advnotes.app.data.ProviderType
import com.advnotes.app.sync.SyncStatus
import com.advnotes.app.sync.SyncWorker
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val app = AdvNotesApp.instance
    val s by app.settings.state.collectAsState()
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            LargeTopAppBar(
                title = { Text(tr("Settings")) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, tr("Back")) } },
                scrollBehavior = scroll,
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            )
        },
    ) { pad ->
        Column(
            Modifier
                .padding(pad)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .wrapContentWidth(Alignment.CenterHorizontally)
                .widthIn(max = 760.dp)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            SettingsGroup(tr("Language"), Icons.Default.Translate) {
                val I = com.advnotes.app.i18n.I18n
                val sys = java.util.Locale.getDefault().getDisplayName(java.util.Locale.getDefault())
                RadioRow(tr("System default ({0})", sys), I.choice == "") { I.set("") }
                I.available.forEach { lang ->
                    RadioRow(if (lang.machineTranslated) lang.name + " · " + tr("machine-translated") else lang.name, I.choice == lang.tag) { I.set(lang.tag) }
                }
                if (I.active()?.machineTranslated == true) Text(
                    tr("This translation was made by machine and may have mistakes. Corrections are welcome — see docs/TRANSLATING.md in the app's repository."),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            SettingsGroup(tr("Pen & touch"), Icons.Default.Draw) {
                FieldLabel(tr("Finger input"), s.fingerMode.label)
                Segmented(
                    listOf(FingerMode.AUTO to tr("Auto"), FingerMode.NAVIGATE to tr("Scroll only"), FingerMode.DRAW to tr("Draw")),
                    s.fingerMode, { m -> app.settings.update { it.copy(fingerMode = m) } },
                )
                FieldLabel(tr("S Pen side button"), tr("What the pen does while you hold its button"))
                Segmented(PenButtonAction.entries.map { it to it.label }, s.penButton, { a -> app.settings.update { it.copy(penButton = a) } })
                FieldLabel(tr("Pressure response"), tr("Light: thick lines with little pressure. Firm: press harder for thick lines."))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(tr("Light"), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Slider(
                        s.pressureCurve, { v -> app.settings.update { it.copy(pressureCurve = v) } },
                        valueRange = 0.4f..2.5f, modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
                    )
                    Text(tr("Firm"), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                SwitchRow(tr("Hold to snap shapes"), tr("Keep the pen still at the end of a stroke to turn it into a straight line or clean shape."), s.holdToShape) { v ->
                    app.settings.update { it.copy(holdToShape = v) }
                }
            }

            SettingsGroup(tr("Layout"), Icons.Default.Dashboard) {
                FieldLabel(tr("Toolbar position"), tr("You can also drag the ⠿ handle on the toolbar to an edge."))
                Segmented(listOf("TOP" to tr("Top"), "BOTTOM" to tr("Bottom"), "LEFT" to tr("Left"), "RIGHT" to tr("Right")), s.toolbarDock,
                    { k -> app.settings.update { it.copy(toolbarDock = k) } })
                FieldLabel(tr("Tools panel on tablets"))
                Segmented(listOf("LEFT" to tr("Left side"), "RIGHT" to tr("Right side")), s.panelSide, { k -> app.settings.update { it.copy(panelSide = k) } })
            }

            SettingsGroup("AI", Icons.Default.AutoAwesome) {
                SwitchRow(tr("AI features"), tr("Turn off to hide Solve, the AI chat and AI buttons in tools. Nothing is sent to an AI provider while it's off; handwriting to text still works on the device."), s.aiEnabled) { v ->
                    app.settings.update { it.copy(aiEnabled = v) }
                }
                if (s.aiEnabled) {
                Text(
                    tr("Used by Solve, To text and the AI chat. Lassoed handwriting is sent as an image to the provider you pick."),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                ProviderType.entries.forEach { p ->
                    val unavailable = p == ProviderType.LOCAL && !BuildConfig.HAS_LLAMA
                    ChoiceRow(
                        providerIcon(p), p.label, providerBlurb(p) + if (unavailable) tr(" Not included in this build.") else "",
                        selected = s.provider == p, enabled = !unavailable,
                    ) { app.settings.update { it.copy(provider = p) } }
                }
                HorizontalDivider(Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)
                key(s.provider) { ProviderConfig(s.provider) }
                TestButton(s.provider)
                }
            }

            DriveSection()

            ToolsSection()

            SettingsGroup(tr("About"), Icons.Default.Info) {
                Text(tr("Adv Notes {0}", BuildConfig.VERSION_NAME), style = MaterialTheme.typography.bodyLarge)
                Text(
                    tr("Notebooks are stored on this device. API keys and sign-in tokens are encrypted with the Android Keystore ") +
                        tr("and only sent to the provider you choose."),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                UpdateCheck()
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

private fun providerIcon(p: ProviderType) = when (p) {
    ProviderType.OPENAI, ProviderType.CHATGPT -> Icons.Default.Bolt
    ProviderType.CLAUDE_CODE, ProviderType.ANTHROPIC -> Icons.Default.AutoAwesome
    ProviderType.OPENROUTER -> Icons.Default.Hub
    ProviderType.CUSTOM_OPENAI, ProviderType.CUSTOM_ANTHROPIC -> Icons.Default.Dns
    ProviderType.LOCAL -> Icons.Default.PhoneAndroid
}

private fun providerBlurb(p: ProviderType) = when (p) {
    ProviderType.OPENAI -> "GPT models with your OpenAI API key."
    ProviderType.CHATGPT -> "Your ChatGPT Plus/Pro plan, via the Codex sign-in. Unofficial."
    ProviderType.CLAUDE_CODE -> "Your Claude Pro/Max/Team plan, via the Claude Code sign-in. Unofficial."
    ProviderType.ANTHROPIC -> "Claude models with your Anthropic API key."
    ProviderType.OPENROUTER -> "Hundreds of models with one key, with prices shown."
    ProviderType.CUSTOM_OPENAI -> "Your own server: LM Studio, Ollama, vLLM, LiteLLM…"
    ProviderType.CUSTOM_ANTHROPIC -> "Any server speaking the Anthropic Messages API."
    ProviderType.LOCAL -> "Runs on this device with llama.cpp. Offline, slower."
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    val icon = when (title) {
        "Tools" -> Icons.Default.Widgets
        else -> Icons.Default.Cloud
    }
    SettingsGroup(title, icon) { content() }
}

@Composable
private fun RadioRow(label: String, selected: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).selectable(selected, enabled = enabled, onClick = onClick).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected, onClick, enabled = enabled)
        Text(label, color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline)
    }
}

@Composable
private fun SwitchRow(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked, onChange)
    }
}

@Composable
private fun SecretField(label: String, initial: String, onChange: (String) -> Unit) {
    var v by remember { mutableStateOf(initial) }
    var show by remember { mutableStateOf(false) }
    OutlinedTextField(
        v, { v = it; onChange(it) },
        label = { Text(label) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
        visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        trailingIcon = {
            IconButton(onClick = { show = !show }) {
                Icon(if (show) Icons.Default.VisibilityOff else Icons.Default.Visibility, if (show) tr("Hide") else tr("Show"))
            }
        },
    )
}

@Composable
private fun ProviderConfig(p: ProviderType) {
    val app = AdvNotesApp.instance
    val st = app.settings
    val scope = rememberCoroutineScope()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when (p) {
            ProviderType.CHATGPT -> ChatGptConfig()
            ProviderType.CLAUDE_CODE -> ClaudeCodeConfig()
            ProviderType.LOCAL -> LocalModelsConfig()
            else -> Unit
        }
        if (p.needsBaseUrl) {
            var base by remember { mutableStateOf(st.baseUrl(p)) }
            OutlinedTextField(
                base, { base = it; st.setBaseUrl(p, it) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                label = { Text(tr("Base URL")) },
                placeholder = {
                    Text(if (p == ProviderType.CUSTOM_OPENAI) "https://host/v1  (e.g. http://192.168.1.5:8080/v1)" else "https://host  (…/v1/messages is added)")
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            )
        }
        if (p.needsKey || p.needsBaseUrl) {
            SecretField(if (p.needsBaseUrl) tr("API key (optional)") else tr("API key"), st.apiKey(p)) { st.setApiKey(p, it) }
        }
        if (p != ProviderType.LOCAL) ModelPicker(p, st)
        val note = when (p) {
            ProviderType.OPENAI -> "Uses the Chat Completions API with your platform.openai.com key."
            ProviderType.ANTHROPIC -> "Uses the Claude Messages API with your console.anthropic.com key."
            ProviderType.OPENROUTER -> "Pick any model that reads images. Prices are per million tokens; a solve is usually a few thousand tokens."
            ProviderType.CUSTOM_OPENAI -> "Any server with an OpenAI-style /chat/completions endpoint: LM Studio, Ollama, llama.cpp server, vLLM, LiteLLM…"
            ProviderType.CUSTOM_ANTHROPIC -> "Any server that implements the Anthropic /v1/messages API."
            ProviderType.CHATGPT -> "Unofficial workaround: signs in as the Codex CLI and uses its backend to bill your ChatGPT Plus/Pro plan. It is not OpenAI's approved \"Sign in with ChatGPT\" route for third-party apps, so it may stop working at any time, and any risk to your account is yours."
            ProviderType.CLAUDE_CODE -> "Unofficial workaround: signs in as the Claude Code CLI and uses its backend to bill your Claude Pro/Max/Team plan. It is not Anthropic's approved route for third-party apps, so it may stop working at any time, and any risk to your account is yours."
            ProviderType.LOCAL -> "Runs a small vision model fully on this device with llama.cpp. No internet needed after download; slower and less accurate than cloud models."
        }
        Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ChatGptConfig() {
    val app = AdvNotesApp.instance
    val auth = app.chatGpt
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var signedIn by remember { mutableStateOf(auth.isSignedIn()) }
    var waiting by remember { mutableStateOf<Job?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var pasted by remember { mutableStateOf("") }

    if (signedIn) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(tr("Signed in") + (auth.email()?.let { " as $it" } ?: ""), Modifier.weight(1f))
            TextButton(onClick = { auth.signOut(); signedIn = false }) { Text(tr("Sign out")) }
        }
    } else if (waiting != null) {
        Text(tr("Finish signing in in your browser…"))
        LinearProgressIndicator(Modifier.fillMaxWidth())
        Text(
            tr("If the browser shows “can't reach this page” after signing in, copy the address from the address bar and paste it here:"),
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedTextField(pasted, { pasted = it }, label = { Text("http://localhost:1455/auth/callback?code=…") }, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = pasted.isNotBlank(), onClick = {
                scope.launch {
                    try { auth.completeFromUrl(pasted); signedIn = true; waiting?.cancel(); waiting = null }
                    catch (e: Exception) { error = e.message }
                }
            }) { Text(tr("Use pasted URL")) }
            OutlinedButton(onClick = { auth.cancel(); waiting?.cancel(); waiting = null }) { Text(tr("Cancel")) }
        }
    } else {
        Button(onClick = {
            error = null
            waiting = scope.launch {
                try { auth.signIn(ctx); signedIn = true } catch (e: Exception) { if (e !is kotlinx.coroutines.CancellationException) error = e.message }
                waiting = null
            }
        }) { Text(tr("Sign in with ChatGPT")) }
    }
    error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
}

@Composable
private fun ClaudeCodeConfig() {
    val app = AdvNotesApp.instance
    val auth = app.claudeCode
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var signedIn by remember { mutableStateOf(auth.isSignedIn()) }
    var account by remember { mutableStateOf(auth.accountLabel()) }
    var waiting by remember { mutableStateOf<Job?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var pasted by remember { mutableStateOf("") }

    if (signedIn) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(tr("Signed in") + (account?.let { " as $it" } ?: ""), Modifier.weight(1f))
            TextButton(onClick = { auth.signOut(); signedIn = false; account = null }) { Text(tr("Sign out")) }
        }
    } else if (waiting != null) {
        Text(tr("Finish signing in in your browser…"))
        LinearProgressIndicator(Modifier.fillMaxWidth())
        Text(
            tr("If the browser shows “can't reach this page” after signing in, copy the address from the address bar and paste it here:"),
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedTextField(pasted, { pasted = it }, label = { Text("http://localhost:…/callback?code=…") }, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = pasted.isNotBlank(), onClick = {
                scope.launch {
                    try {
                        auth.completeFromUrl(pasted)
                        signedIn = true; account = auth.accountLabel(); waiting?.cancel(); waiting = null
                    } catch (e: Exception) {
                        if (e !is kotlinx.coroutines.CancellationException) error = e.message
                    }
                }
            }) { Text(tr("Use pasted URL")) }
            OutlinedButton(onClick = { auth.cancel(); waiting?.cancel(); waiting = null }) { Text(tr("Cancel")) }
        }
    } else {
        Button(onClick = {
            error = null
            waiting = scope.launch {
                try {
                    auth.signIn(ctx)
                    signedIn = true
                    account = auth.accountLabel()
                } catch (e: Exception) {
                    if (e !is kotlinx.coroutines.CancellationException) error = e.message
                }
                waiting = null
            }
        }) { Text(tr("Sign in with Claude")) }
    }
    error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
}

@Composable
private fun LocalModelsConfig() {
    val app = AdvNotesApp.instance
    val s by app.settings.state.collectAsState()
    val downloads by app.downloads.state.collectAsState()
    val version by app.downloads.version.collectAsState()
    val scope = rememberCoroutineScope()
    var refresh by remember { mutableStateOf(0) }
    val models = remember(version, refresh) { app.local.models() }
    var confirmDelete by remember { mutableStateOf<com.advnotes.app.ai.LocalModel?>(null) }

    if (!LlamaBridge.available) {
        Text(tr("The on-device runner isn't available in this build."), color = MaterialTheme.colorScheme.error)
        return
    }
    Text(tr("Installed models"), style = MaterialTheme.typography.labelLarge)
    if (models.isEmpty()) Text(tr("None yet — download one below or import GGUF files."), style = MaterialTheme.typography.bodySmall)
    models.forEach { m ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            RadioButton(s.localModel == m.name, { app.settings.update { it.copy(localModel = m.name) } }, enabled = m.ready)
            Column(Modifier.weight(1f)) {
                Text(m.name)
                Text(
                    if (m.ready) "%.1f GB".format(m.sizeBytes / 1e9) else "incomplete",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = { confirmDelete = m }) { Icon(Icons.Default.Delete, tr("Delete")) }
        }
    }
    // downloads in progress (or failed) for models that aren't installed yet
    downloads.filterKeys { k -> models.none { it.name == k && it.ready } }.forEach { (name, d) ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(name)
                when (d) {
                    is DownloadState.Running -> {
                        LinearProgressIndicator({ d.progress }, Modifier.fillMaxWidth().padding(vertical = 4.dp))
                        Text("${d.label} ${(d.progress * 100).toInt()}%", style = MaterialTheme.typography.bodySmall)
                    }
                    is DownloadState.Failed -> Text(d.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
            TextButton(onClick = { app.downloads.cancel(name) }) { Text(if (d is DownloadState.Running) tr("Cancel") else tr("Dismiss")) }
        }
    }
    Spacer(Modifier.height(8.dp))
    HuggingFaceSearch()
    Spacer(Modifier.height(8.dp))
    ImportModel()
    Spacer(Modifier.height(8.dp))
    LocalLogs()
    Text(tr("CPU threads: {0}", s.localThreads))
    Slider(
        s.localThreads.toFloat(), { v -> app.settings.update { it.copy(localThreads = v.toInt()) } },
        valueRange = 1f..8f, steps = 6,
    )
    confirmDelete?.let { m ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text(tr("Delete {0}?", m.name)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = null
                    scope.launch {
                        app.local.delete(m)
                        if (s.localModel == m.name) app.settings.update { it.copy(localModel = null) }
                        refresh++
                    }
                }) { Text(tr("Delete")) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text(tr("Cancel")) } },
        )
    }
}

@Composable
private fun ImportModel() {
    val app = AdvNotesApp.instance
    var open by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var model by remember { mutableStateOf<Uri?>(null) }
    var mmproj by remember { mutableStateOf<Uri?>(null) }
    val pickModel = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { model = it }
    val pickProj = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { mmproj = it }
    OutlinedButton(onClick = { open = true }) { Text(tr("Import GGUF files…")) }
    if (open) {
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text(tr("Import a vision model")) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(tr("You need two files: the model GGUF and its matching mmproj (vision projector) GGUF."), style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(name, { name = it }, label = { Text(tr("Name")) }, singleLine = true)
                    OutlinedButton(onClick = { pickModel.launch(arrayOf("*/*")) }) { Text(if (model == null) tr("Choose model .gguf") else tr("Model ✓")) }
                    OutlinedButton(onClick = { pickProj.launch(arrayOf("*/*")) }) { Text(if (mmproj == null) tr("Choose mmproj .gguf") else tr("Projector ✓")) }
                }
            },
            confirmButton = {
                TextButton(enabled = name.isNotBlank() && model != null && mmproj != null, onClick = {
                    app.downloads.import(name.trim(), model!!, mmproj!!)
                    open = false; name = ""; model = null; mmproj = null
                }) { Text(tr("Import")) }
            },
            dismissButton = { TextButton(onClick = { open = false }) { Text(tr("Cancel")) } },
        )
    }
}

/** Sends a tiny rendered "12 × 7 =" image to the configured provider to check the setup. */
@Composable
private fun TestButton(p: ProviderType) {
    val app = AdvNotesApp.instance
    val scope = rememberCoroutineScope()
    var result by remember { mutableStateOf<String?>(null) }
    var running by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(enabled = !running, onClick = {
            running = true; result = null
            scope.launch {
                result = try {
                    val bmp = Bitmap.createBitmap(360, 120, Bitmap.Config.ARGB_8888)
                    Canvas(bmp).apply {
                        drawColor(android.graphics.Color.WHITE)
                        drawText("12 × 7 =", 30f, 80f, Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 56f })
                    }
                    val client = AiClients.forProvider(p, app.settings, app.chatGpt, app.claudeCode, app.local)
                    "✓ " + client.complete(MathSolver.request(bmp, SolveMode.ANSWER, "")) { }.trim()
                } catch (e: Exception) {
                    "✗ " + (e.message ?: e.toString())
                }
                running = false
            }
        }) { Text(tr("Test connection")) }
        if (running) { Spacer(Modifier.width(12.dp)); CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) }
    }
    result?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
}

@Composable
private fun DriveSection() {
    val app = AdvNotesApp.instance
    val s by app.settings.state.collectAsState()
    val status by app.drive.status.collectAsState()
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val connect = rememberDriveAuthLauncher { ok -> if (ok) scope.launch { app.drive.sync() } }

    Section(tr("Google Drive backup & sync")) {
        if (s.driveAccount == null) {
            Text(
                tr("Back up notebooks to an “Adv Notes” folder in your Google Drive and keep them in sync across devices. ") +
                    tr("The app can only see files it creates."),
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(4.dp))
            Button(onClick = connect) { Text(tr("Connect Google Drive")) }
        } else {
            Text(tr("Connected: {0}", s.driveAccount))
            Text(
                when (val st = status) {
                    SyncStatus.Idle -> "Not synced yet in this session"
                    SyncStatus.Running -> "Syncing…"
                    is SyncStatus.Done -> "Last sync ${DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(st.at))} — ${st.summary}"
                    is SyncStatus.Error -> "Error: ${st.message}"
                    SyncStatus.NeedsSignIn -> "Permission needed — tap Reconnect"
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (status is SyncStatus.Error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SwitchRow(tr("Sync automatically"), tr("Hourly in the background and after you close a notebook."), s.autoSync) { v ->
                app.settings.update { it.copy(autoSync = v) }
                SyncWorker.schedule(ctx, v)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = status != SyncStatus.Running, onClick = { scope.launch { app.drive.sync() } }) { Text(tr("Sync now")) }
                if (status == SyncStatus.NeedsSignIn) OutlinedButton(onClick = connect) { Text(tr("Reconnect")) }
                TextButton(onClick = { scope.launch { app.drive.disconnect() } }) { Text(tr("Disconnect")) }
            }
        }
    }
}

@Composable
private fun ToolsSection() {
    val app = AdvNotesApp.instance
    val tools by app.tools.tools.collectAsState()
    val scope = rememberCoroutineScope()
    var message by remember { mutableStateOf<String?>(null) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            message = try { "Installed " + app.tools.install(uri).name } catch (e: Exception) { "Couldn't install: ${e.message}" }
        }
    }
    var market by remember { mutableStateOf(false) }
    if (market) com.advnotes.app.tools.market.MarketplaceDialog(onDismiss = { market = false })
    Section(tr("Tools")) {
        Text(
            tr("Tools open in the side panel next to a notebook (the ▦ button in the editor). Get more from the marketplace, ") +
                tr("or install your own .zip: see docs/TOOLS.md to build or port one."),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        tools.forEach { t ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
                Icon(t.icon, null)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(t.name)
                    Text(
                        when {
                            t is com.advnotes.app.tools.WebTool && t.installed -> "Installed · v${t.manifest.version}" + if (t.manifest.author.isNotBlank()) " · ${t.manifest.author}" else ""
                            t is com.advnotes.app.tools.WebTool -> "Built in (web)"
                            else -> "Built in"
                        },
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (t is com.advnotes.app.tools.WebTool && t.installed) {
                    IconButton(onClick = { app.tools.uninstall(t.id) }) { Icon(Icons.Default.Delete, tr("Uninstall")) }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { market = true }) { Text(tr("Browse marketplace")) }
            OutlinedButton(onClick = { pick.launch(arrayOf("application/zip", "application/octet-stream", "*/*")) }) { Text(tr("Install from .zip…")) }
        }
        message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}

/** Search Hugging Face for GGUF vision models and download one (model + mmproj projector). */
@Composable
private fun HuggingFaceSearch() {
    val app = AdvNotesApp.instance
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<com.advnotes.app.ai.HuggingFace.Repo>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var picked by remember { mutableStateOf<String?>(null) }
    var showAll by remember { mutableStateOf(false) }
    val token = remember { app.settings.secret("hf_token") }

    fun search() {
        loading = true; error = null; showAll = false
        scope.launch {
            try { results = com.advnotes.app.ai.HuggingFace.search(query, app.settings.secret("hf_token")) }
            catch (e: Exception) { error = e.message; results = emptyList() }
            loading = false
        }
    }
    LaunchedEffect(Unit) { search() }
    LaunchedEffect(query) {
        if (query.isNotBlank()) { kotlinx.coroutines.delay(500); search() }
    }

    Text(tr("Find a model on Hugging Face"), style = MaterialTheme.typography.labelLarge)
    Text(
        tr("The solver needs a vision model: a GGUF repo that also has an mmproj (vision projector) file. ") +
            tr("Small models (1–4B parameters, Q4) run best on phones."),
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    OutlinedTextField(
        query, { query = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
        placeholder = { Text(tr("e.g. qwen vl, gemma, smolvlm, minicpm")) },
        trailingIcon = {
            if (loading) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            else IconButton(onClick = { search() }) { Icon(Icons.Default.Refresh, tr("Search")) }
        },
    )
    Text(if (query.isBlank()) tr("Trending vision models") else tr("Most downloaded GGUF models for “{0}”", query.trim()),
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    if (!loading && error == null && results.isEmpty()) Text(tr("No GGUF models found."), style = MaterialTheme.typography.bodySmall)
    (if (showAll) results else results.take(8)).forEach { r ->
        Row(
            Modifier.fillMaxWidth().selectable(false, onClick = { picked = r.id }).padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(r.id, style = MaterialTheme.typography.bodyMedium)
                Text(
                    "↓ ${compact(r.downloads)} · ♥ ${compact(r.likes)}" + (if (r.vision) " · vision" else "") +
                        (if (r.updated.isNotEmpty()) " · ${r.updated}" else ""),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(Icons.Default.Download, tr("Choose files"))
        }
    }
    if (!showAll && results.size > 8) TextButton(onClick = { showAll = true }) { Text(tr("Show {0} more", results.size - 8)) }
    SecretField(tr("Hugging Face token (only for gated models, e.g. Gemma)"), token ?: "") { app.settings.setSecret("hf_token", it.ifBlank { null }) }

    picked?.let { repo -> ModelFilesDialog(repo, onDismiss = { picked = null }) }
}

private fun compact(n: Long): String = when {
    n >= 1_000_000 -> "%.1fM".format(n / 1e6)
    n >= 1_000 -> "%.1fk".format(n / 1e3)
    else -> n.toString()
}

private fun gb(bytes: Long) = "%.2f GB".format(bytes / 1e9)

@Composable
private fun ModelFilesDialog(repo: String, onDismiss: () -> Unit) {
    val app = AdvNotesApp.instance
    val ctx = LocalContext.current
    var files by remember { mutableStateOf<com.advnotes.app.ai.HuggingFace.RepoFiles?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var model by remember { mutableStateOf<com.advnotes.app.ai.HuggingFace.GgufFile?>(null) }
    var proj by remember { mutableStateOf<com.advnotes.app.ai.HuggingFace.GgufFile?>(null) }
    var name by remember { mutableStateOf("") }
    val ramBytes = remember {
        val am = ctx.getSystemService(android.content.Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        android.app.ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }.totalMem
    }
    LaunchedEffect(repo) {
        try {
            val f = com.advnotes.app.ai.HuggingFace.files(repo, app.settings.secret("hf_token"))
            files = f
            model = com.advnotes.app.ai.HuggingFace.defaultModel(f.models)
            proj = com.advnotes.app.ai.HuggingFace.defaultProjector(f.projectors)
        } catch (e: Exception) { error = e.message }
    }
    LaunchedEffect(model) {
        model?.let { m -> name = repo.substringAfterLast('/').removeSuffix("-GGUF").removeSuffix("-gguf") + (if (m.quant.isNotEmpty()) " (${m.quant})" else "") }
    }
    val f = files
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(repo.substringAfterLast('/'), maxLines = 2) },
        text = {
            Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(repo, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                when {
                    error != null -> Text(error!!, color = MaterialTheme.colorScheme.error)
                    f == null -> LinearProgressIndicator(Modifier.fillMaxWidth())
                    f.models.isEmpty() -> Text(tr("No GGUF model files in this repository."))
                    else -> {
                        if (f.projectors.isEmpty()) Text(
                            tr("This repository has no mmproj (vision projector) file, so the model can't read your handwriting. ") +
                                tr("Look for another upload of the same model (ggml-org, unsloth and bartowski usually include one)."),
                            color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall,
                        )
                        Text(tr("Model file"), style = MaterialTheme.typography.labelLarge)
                        f.models.forEach { m ->
                            RadioRow("${m.quant.ifEmpty { m.name }} · ${gb(m.size)}" + if (m.isSplit) tr(" · split, not supported") else "",
                                model == m, enabled = !m.isSplit) { model = m }
                        }
                        if (f.projectors.isNotEmpty()) {
                            Text(tr("Vision projector"), style = MaterialTheme.typography.labelLarge)
                            f.projectors.forEach { p -> RadioRow("${p.quant.ifEmpty { p.name }} · ${gb(p.size)}", proj == p) { proj = p } }
                        }
                        val total = (model?.size ?: 0) + (proj?.size ?: 0)
                        Text(tr("Download: {0}", gb(total)) + if (ramBytes > 0) tr(" · this device has {0} RAM", gb(ramBytes)) else "", style = MaterialTheme.typography.bodySmall)
                        if (ramBytes > 0 && total > ramBytes * 0.6) Text(
                            tr("Probably too big for this device: the model must fit in memory. Pick a smaller quantisation or model."),
                            color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall,
                        )
                        OutlinedTextField(name, { name = it }, label = { Text(tr("Name")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = model != null && proj != null && name.isNotBlank(),
                onClick = {
                    app.downloads.download(
                        name.trim(),
                        com.advnotes.app.ai.HuggingFace.downloadUrl(repo, model!!.path),
                        com.advnotes.app.ai.HuggingFace.downloadUrl(repo, proj!!.path),
                        app.settings.secret("hf_token"),
                    )
                    onDismiss()
                },
            ) { Text(tr("Download")) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Cancel")) } },
    )
}

@Composable
private fun UpdateCheck() {
    val app = AdvNotesApp.instance
    val s by app.settings.state.collectAsState()
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<String?>(null) }
    var found by remember { mutableStateOf<com.advnotes.app.update.Update?>(null) }
    SwitchRow(tr("Check for updates"), tr("Looks for a new release on GitHub when the library opens."), s.checkUpdates) { v ->
        app.settings.update { it.copy(checkUpdates = v) }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(enabled = !busy, onClick = {
            busy = true; msg = null
            scope.launch {
                try {
                    val u = com.advnotes.app.update.Updater.check()
                    if (u == null) msg = tr("You're up to date.") else found = u
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    msg = tr("Couldn't check: {0}", e.message)
                }
                busy = false
            }
        }) { Text(tr("Check now")) }
        msg?.let { Spacer(Modifier.width(12.dp)); Text(it, style = MaterialTheme.typography.bodySmall) }
    }
    found?.let { UpdateDialog(it) { found = null } }
}

/** Test-load button plus a viewer for llama.cpp's output, to check that a model really loads. */
@Composable
private fun LocalLogs() {
    val app = AdvNotesApp.instance
    val scope = rememberCoroutineScope()
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    val version by com.advnotes.app.ai.LlamaLog.version.collectAsState()
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    var show by remember { mutableStateOf(false) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(enabled = !busy, onClick = {
            busy = true; result = null
            scope.launch { result = app.local.testLoad(); busy = false }
        }) { Text(tr("Test load")) }
        OutlinedButton(onClick = { show = true }) { Text(tr("View logs")) }
        if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
    }
    result?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    if (show) {
        val text = remember(version) { com.advnotes.app.ai.LlamaLog.text() }
        val scroll = rememberScrollState()
        LaunchedEffect(version) { scroll.scrollTo(scroll.maxValue) }
        AlertDialog(
            onDismissRequest = { show = false },
            title = { Text(tr("On-device model log")) },
            text = {
                Box(Modifier.fillMaxWidth().heightIn(max = 420.dp).verticalScroll(scroll)) {
                    androidx.compose.foundation.text.selection.SelectionContainer {
                        Text(
                            text.ifBlank { "Nothing logged yet. Tap Test load, or run the solver with a local model." },
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, fontSize = 11.sp),
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = { show = false }) { Text(tr("Close")) } },
            dismissButton = {
                Row {
                    TextButton(onClick = { clipboard.setText(androidx.compose.ui.text.AnnotatedString(text)) }) { Text(tr("Copy")) }
                    TextButton(onClick = { com.advnotes.app.ai.LlamaLog.clear() }) { Text("Clear") }
                }
            },
        )
    }
}
