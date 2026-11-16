package com.advnotes.app.ui

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import com.advnotes.app.ai.LocalLlama
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
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
    ) { pad ->
        Column(
            Modifier
                .padding(pad)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Section("Pen & touch") {
                Text("Finger input", style = MaterialTheme.typography.labelLarge)
                FingerMode.entries.forEach { m ->
                    RadioRow(m.label, s.fingerMode == m) { app.settings.update { it.copy(fingerMode = m) } }
                }
                Spacer(Modifier.height(8.dp))
                Text("While holding the S Pen side button", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PenButtonAction.entries.forEach { a ->
                        FilterChip(s.penButton == a, { app.settings.update { it.copy(penButton = a) } }, label = { Text(a.label) })
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text("Pressure response", style = MaterialTheme.typography.labelLarge)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Light", style = MaterialTheme.typography.bodySmall)
                    Slider(
                        s.pressureCurve, { v -> app.settings.update { it.copy(pressureCurve = v) } },
                        valueRange = 0.4f..2.5f, modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                    )
                    Text("Firm", style = MaterialTheme.typography.bodySmall)
                }
                Text(
                    "Light: thick lines with little pressure. Firm: you need to press harder for thick lines.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                SwitchRow("Hold to snap shapes", "Keep the pen still at the end of a stroke to turn it into a straight line or clean shape.", s.holdToShape) { v ->
                    app.settings.update { it.copy(holdToShape = v) }
                }
            }

            Section("AI math solver") {
                Text(
                    "Select handwriting with the lasso and tap Solve. The selected area is sent as an image to the provider below.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                ProviderType.entries.forEach { p ->
                    val unavailable = p == ProviderType.LOCAL && !BuildConfig.HAS_LLAMA
                    RadioRow(p.label + if (unavailable) " — not in this build" else "", s.provider == p, enabled = !unavailable) {
                        app.settings.update { it.copy(provider = p) }
                    }
                }
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                key(s.provider) { ProviderConfig(s.provider) }
                TestButton(s.provider)
            }

            DriveSection()

            Section("About") {
                Text("Adv Notes ${BuildConfig.VERSION_NAME}")
                Text(
                    "Notebooks are stored on this device. API keys and sign-in tokens are encrypted with the Android Keystore " +
                        "and only sent to the provider you choose.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(4.dp))
            content()
        }
    }
}

@Composable
private fun RadioRow(label: String, selected: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().selectable(selected, enabled = enabled, onClick = onClick).padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected, onClick, enabled = enabled)
        Text(label, color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline)
    }
}

@Composable
private fun SwitchRow(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title)
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
                Icon(if (show) Icons.Default.VisibilityOff else Icons.Default.Visibility, if (show) "Hide" else "Show")
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
            ProviderType.LOCAL -> LocalModelsConfig()
            else -> Unit
        }
        if (p.needsBaseUrl) {
            var base by remember { mutableStateOf(st.baseUrl(p)) }
            OutlinedTextField(
                base, { base = it; st.setBaseUrl(p, it) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                label = { Text("Base URL") },
                placeholder = {
                    Text(if (p == ProviderType.CUSTOM_OPENAI) "https://host/v1  (e.g. http://192.168.1.5:8080/v1)" else "https://host  (…/v1/messages is added)")
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            )
        }
        if (p.needsKey || p.needsBaseUrl) {
            SecretField(if (p.needsBaseUrl) "API key (optional)" else "API key", st.apiKey(p)) { st.setApiKey(p, it) }
        }
        if (p != ProviderType.LOCAL) {
            var model by remember { mutableStateOf(st.model(p)) }
            var options by remember { mutableStateOf<List<String>>(emptyList()) }
            var loading by remember { mutableStateOf(false) }
            var error by remember { mutableStateOf<String?>(null) }
            Box {
                OutlinedTextField(
                    model, { model = it; st.setModel(p, it) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    label = { Text("Model (must accept images)") },
                    trailingIcon = {
                        if (loading) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        else IconButton(onClick = {
                            loading = true; error = null
                            scope.launch {
                                try { options = AiClients.listModels(p, st) } catch (e: Exception) { error = e.message }
                                loading = false
                            }
                        }) { Icon(Icons.Default.Refresh, "Load model list") }
                    },
                )
                DropdownMenu(options.isNotEmpty(), { options = emptyList() }, Modifier.widthIn(max = 420.dp).height(360.dp)) {
                    options.forEach { m ->
                        DropdownMenuItem(text = { Text(m) }, onClick = { model = m; st.setModel(p, m); options = emptyList() })
                    }
                }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        }
        val note = when (p) {
            ProviderType.OPENAI -> "Uses the Chat Completions API with your platform.openai.com key."
            ProviderType.ANTHROPIC -> "Uses the Claude Messages API with your console.anthropic.com key."
            ProviderType.OPENROUTER -> "Any OpenRouter model that supports image input works."
            ProviderType.CUSTOM_OPENAI -> "Any server with an OpenAI-style /chat/completions endpoint: LM Studio, Ollama, llama.cpp server, vLLM, LiteLLM…"
            ProviderType.CUSTOM_ANTHROPIC -> "Any server that implements the Anthropic /v1/messages API."
            ProviderType.CHATGPT -> "Uses your ChatGPT Plus/Pro plan through the same sign-in as the Codex CLI. This is not an official API and may stop working if OpenAI changes it."
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
            Text("Signed in" + (auth.email()?.let { " as $it" } ?: ""), Modifier.weight(1f))
            TextButton(onClick = { auth.signOut(); signedIn = false }) { Text("Sign out") }
        }
    } else if (waiting != null) {
        Text("Finish signing in in your browser…")
        LinearProgressIndicator(Modifier.fillMaxWidth())
        Text(
            "If the browser shows “can't reach this page” after signing in, copy the address from the address bar and paste it here:",
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedTextField(pasted, { pasted = it }, label = { Text("http://localhost:1455/auth/callback?code=…") }, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = pasted.isNotBlank(), onClick = {
                scope.launch {
                    try { auth.completeFromUrl(pasted); signedIn = true; waiting?.cancel(); waiting = null }
                    catch (e: Exception) { error = e.message }
                }
            }) { Text("Use pasted URL") }
            OutlinedButton(onClick = { auth.cancel(); waiting?.cancel(); waiting = null }) { Text("Cancel") }
        }
    } else {
        Button(onClick = {
            error = null
            waiting = scope.launch {
                try { auth.signIn(ctx); signedIn = true } catch (e: Exception) { if (e !is kotlinx.coroutines.CancellationException) error = e.message }
                waiting = null
            }
        }) { Text("Sign in with ChatGPT") }
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
        Text("The on-device runner isn't available in this build.", color = MaterialTheme.colorScheme.error)
        return
    }
    Text("Installed models", style = MaterialTheme.typography.labelLarge)
    if (models.isEmpty()) Text("None yet — download one below or import GGUF files.", style = MaterialTheme.typography.bodySmall)
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
            IconButton(onClick = { confirmDelete = m }) { Icon(Icons.Default.Delete, "Delete") }
        }
    }
    Spacer(Modifier.height(8.dp))
    Text("Download", style = MaterialTheme.typography.labelLarge)
    LocalLlama.PRESETS.forEach { preset ->
        val d = downloads[preset.name]
        val installed = models.any { it.name == preset.name && it.ready }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(preset.name)
                when (d) {
                    is DownloadState.Running -> {
                        LinearProgressIndicator({ d.progress }, Modifier.fillMaxWidth().padding(vertical = 4.dp))
                        Text("${d.label} ${(d.progress * 100).toInt()}%", style = MaterialTheme.typography.bodySmall)
                    }
                    is DownloadState.Failed -> Text(d.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    null -> Text(if (installed) "Installed" else preset.approxSize, style = MaterialTheme.typography.bodySmall)
                }
            }
            when {
                d is DownloadState.Running -> TextButton(onClick = { app.downloads.cancel(preset.name) }) { Text("Cancel") }
                !installed -> IconButton(onClick = { app.downloads.download(preset) }) { Icon(Icons.Default.Download, "Download") }
            }
        }
    }
    Spacer(Modifier.height(8.dp))
    ImportModel()
    Spacer(Modifier.height(8.dp))
    Text("CPU threads: ${s.localThreads}")
    Slider(
        s.localThreads.toFloat(), { v -> app.settings.update { it.copy(localThreads = v.toInt()) } },
        valueRange = 1f..8f, steps = 6,
    )
    confirmDelete?.let { m ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete ${m.name}?") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = null
                    scope.launch {
                        app.local.delete(m)
                        if (s.localModel == m.name) app.settings.update { it.copy(localModel = null) }
                        refresh++
                    }
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancel") } },
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
    OutlinedButton(onClick = { open = true }) { Text("Import GGUF files…") }
    if (open) {
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text("Import a vision model") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("You need two files: the model GGUF and its matching mmproj (vision projector) GGUF.", style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true)
                    OutlinedButton(onClick = { pickModel.launch(arrayOf("*/*")) }) { Text(if (model == null) "Choose model .gguf" else "Model ✓") }
                    OutlinedButton(onClick = { pickProj.launch(arrayOf("*/*")) }) { Text(if (mmproj == null) "Choose mmproj .gguf" else "Projector ✓") }
                }
            },
            confirmButton = {
                TextButton(enabled = name.isNotBlank() && model != null && mmproj != null, onClick = {
                    app.downloads.import(name.trim(), model!!, mmproj!!)
                    open = false; name = ""; model = null; mmproj = null
                }) { Text("Import") }
            },
            dismissButton = { TextButton(onClick = { open = false }) { Text("Cancel") } },
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
                    val client = AiClients.forProvider(p, app.settings, app.chatGpt, app.local)
                    "✓ " + client.complete(MathSolver.request(bmp, SolveMode.ANSWER, "")) { }.trim()
                } catch (e: Exception) {
                    "✗ " + (e.message ?: e.toString())
                }
                running = false
            }
        }) { Text("Test connection") }
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

    Section("Google Drive backup & sync") {
        if (s.driveAccount == null) {
            Text(
                "Back up notebooks to an “Adv Notes” folder in your Google Drive and keep them in sync across devices. " +
                    "The app can only see files it creates.",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(4.dp))
            Button(onClick = connect) { Text("Connect Google Drive") }
        } else {
            Text("Connected: ${s.driveAccount}")
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
            SwitchRow("Sync automatically", "Hourly in the background and after you close a notebook.", s.autoSync) { v ->
                app.settings.update { it.copy(autoSync = v) }
                SyncWorker.schedule(ctx, v)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = status != SyncStatus.Running, onClick = { scope.launch { app.drive.sync() } }) { Text("Sync now") }
                if (status == SyncStatus.NeedsSignIn) OutlinedButton(onClick = connect) { Text("Reconnect") }
                TextButton(onClick = { scope.launch { app.drive.disconnect() } }) { Text("Disconnect") }
            }
        }
    }
}
