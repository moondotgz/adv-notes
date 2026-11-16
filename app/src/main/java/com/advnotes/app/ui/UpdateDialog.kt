package com.advnotes.app.ui

import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.advnotes.app.BuildConfig
import com.advnotes.app.update.Update
import com.advnotes.app.update.Updater
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** "Version X is available": downloads the APK with progress, then opens the system installer. */
@Composable
fun UpdateDialog(update: Update, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var progress by remember { mutableStateOf<Float?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }

    fun start() {
        error = null; progress = 0f
        job = scope.launch {
            try {
                val apk = Updater.download(ctx, update) { progress = it }
                progress = null
                if (Updater.install(ctx, apk)) onDismiss()
                else Toast.makeText(ctx, "Allow installs from Adv Notes, then tap Update again", Toast.LENGTH_LONG).show()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                progress = null; error = e.message ?: "Download failed"
            }
        }
    }

    AlertDialog(
        onDismissRequest = { if (progress == null) onDismiss() },
        title = { Text("Update available") },
        text = {
            Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                Text("Adv Notes ${update.version} is out (you have ${BuildConfig.VERSION_NAME}).")
                if (update.size > 0) Text("%.1f MB".format(update.size / 1_048_576.0), style = MaterialTheme.typography.bodySmall)
                if (update.notes.isNotBlank()) Text(update.notes.trim().take(800), style = MaterialTheme.typography.bodySmall, modifier = Modifier.fillMaxWidth())
                progress?.let { LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth()) }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = { TextButton(onClick = ::start, enabled = progress == null) { Text(if (error != null) "Retry" else "Update") } },
        dismissButton = {
            TextButton(onClick = { job?.cancel(); progress = null; onDismiss() }) { Text(if (progress != null) "Cancel" else "Later") }
        },
    )
}
