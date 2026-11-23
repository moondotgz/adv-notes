package com.advnotes.app.ui

import com.advnotes.app.i18n.tr

import android.app.Activity
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import com.advnotes.app.AdvNotesApp
import com.advnotes.app.sync.SyncWorker
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import kotlinx.coroutines.launch

/**
 * Returns a function that asks the user for Google Drive access (showing Google's consent screen
 * when needed) and reports whether access was granted.
 */
@Composable
fun rememberDriveAuthLauncher(onResult: (Boolean) -> Unit): () -> Unit {
    val app = AdvNotesApp.instance
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    fun granted(r: AuthorizationResult) {
        val email = runCatching { r.toGoogleSignInAccount()?.email }.getOrNull()
        app.settings.update { it.copy(driveAccount = email ?: "Google Drive") }
        SyncWorker.schedule(ctx, app.settings.state.value.autoSync)
        onResult(true)
    }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { res ->
        val r = if (res.resultCode == Activity.RESULT_OK) runCatching {
            Identity.getAuthorizationClient(ctx).getAuthorizationResultFromIntent(res.data)
        }.getOrNull() else null
        if (r?.accessToken != null) granted(r) else onResult(false)
    }

    return {
        scope.launch {
            try {
                val r = app.drive.authorize()
                val pi = r.pendingIntent
                if (r.hasResolution() && pi != null) launcher.launch(IntentSenderRequest.Builder(pi.intentSender).build())
                else granted(r)
            } catch (e: Exception) {
                Toast.makeText(ctx, tr("Google sign-in failed: {0}", e.message), Toast.LENGTH_LONG).show()
                onResult(false)
            }
        }
    }
}
