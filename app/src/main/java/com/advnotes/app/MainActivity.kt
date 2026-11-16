package com.advnotes.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxSize
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.advnotes.app.editor.EditorScreen
import com.advnotes.app.ui.AdvNotesTheme
import com.advnotes.app.ui.LibraryScreen
import com.advnotes.app.ui.SettingsScreen

/** Files another app shared with us: one (SEND) or several (SEND_MULTIPLE), from EXTRA_STREAM or the clip data. */
internal fun sharedUris(i: android.content.Intent): List<android.net.Uri> {
    if (i.action != android.content.Intent.ACTION_SEND && i.action != android.content.Intent.ACTION_SEND_MULTIPLE) return emptyList()
    val fromExtras: List<android.net.Uri> = if (i.action == android.content.Intent.ACTION_SEND_MULTIPLE)
        androidx.core.content.IntentCompat.getParcelableArrayListExtra(i, android.content.Intent.EXTRA_STREAM, android.net.Uri::class.java).orEmpty()
    else listOfNotNull(androidx.core.content.IntentCompat.getParcelableExtra(i, android.content.Intent.EXTRA_STREAM, android.net.Uri::class.java))
    if (fromExtras.isNotEmpty()) return fromExtras
    val clip = i.clipData ?: return emptyList()
    return (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AdvNotesTheme {
                Surface(Modifier.fillMaxSize()) {
                    val nav = rememberNavController()
                    NavHost(nav, startDestination = "library") {
                        composable("library") {
                            LibraryScreen(
                                onOpen = { id -> nav.navigate("editor/$id") },
                                onSettings = { nav.navigate("settings") },
                                onTrash = { nav.navigate("trash") },
                                sharedFiles = sharedUris(intent),
                                onShareConsumed = { intent = android.content.Intent() },
                            )
                        }
                        composable("editor/{id}") { entry ->
                            EditorScreen(
                                notebookId = entry.arguments?.getString("id").orEmpty(),
                                onBack = { nav.popBackStack() },
                                onSettings = { nav.navigate("settings") },
                            )
                        }
                        composable("trash") { com.advnotes.app.ui.TrashScreen(onBack = { nav.popBackStack() }) }
                        composable("settings") { SettingsScreen(onBack = { nav.popBackStack() }) }
                    }
                }
            }
        }
    }
}
