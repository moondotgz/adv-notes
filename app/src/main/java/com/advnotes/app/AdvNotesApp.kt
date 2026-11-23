package com.advnotes.app

import android.app.Application
import androidx.work.Configuration
import com.advnotes.app.ai.ChatGptAuth
import com.advnotes.app.ai.LocalLlama
import com.advnotes.app.ai.ModelDownloads
import com.advnotes.app.data.NotebookRepository
import com.advnotes.app.data.SettingsStore
import com.advnotes.app.sync.DriveSync
import com.advnotes.app.sync.SyncWorker

class AdvNotesApp : Application(), Configuration.Provider {
    lateinit var repo: NotebookRepository
        private set
    lateinit var settings: SettingsStore
        private set
    lateinit var drive: DriveSync
        private set
    val chatGpt by lazy { ChatGptAuth(settings) }
    val local by lazy { LocalLlama(this, settings) }
    val downloads by lazy { ModelDownloads(local) }
    val tools by lazy { com.advnotes.app.tools.ToolRegistry(this, com.advnotes.app.tools.BuiltinTools.all()) }
    val importer by lazy { com.advnotes.app.importer.Importer(this, repo) }

    override fun onCreate() {
        super.onCreate()
        instance = this
        // Lets chrome://inspect (or adb) attach to web tools in debug builds.
        if (BuildConfig.DEBUG) android.webkit.WebView.setWebContentsDebuggingEnabled(true)
        com.advnotes.app.i18n.I18n.init(this)
        repo = NotebookRepository(this)
        settings = SettingsStore(this)
        drive = DriveSync(this, repo, settings)
        SyncWorker.schedule(this, settings.state.value.autoSync)
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().build()

    companion object {
        lateinit var instance: AdvNotesApp
            private set
    }
}
