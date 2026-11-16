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
                            )
                        }
                        composable("editor/{id}") { entry ->
                            EditorScreen(
                                notebookId = entry.arguments?.getString("id").orEmpty(),
                                onBack = { nav.popBackStack() },
                                onSettings = { nav.navigate("settings") },
                            )
                        }
                        composable("settings") { SettingsScreen(onBack = { nav.popBackStack() }) }
                    }
                }
            }
        }
    }
}
