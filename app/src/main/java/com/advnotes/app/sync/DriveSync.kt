package com.advnotes.app.sync

import android.content.Context
import com.advnotes.app.ai.AiException
import com.advnotes.app.ai.Http
import com.advnotes.app.ai.await
import com.advnotes.app.ai.errorMessage
import com.advnotes.app.ai.obj
import com.advnotes.app.ai.str
import com.advnotes.app.data.AppJson
import com.advnotes.app.data.NotebookRepository
import com.advnotes.app.data.SettingsStore
import com.advnotes.app.model.Notebook
import com.advnotes.app.model.newId
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.json.add
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody

sealed interface SyncStatus {
    data object Idle : SyncStatus
    data object Running : SyncStatus
    data class Done(val at: Long, val summary: String) : SyncStatus
    data class Error(val message: String) : SyncStatus
    data object NeedsSignIn : SyncStatus
}

/**
 * Two-way sync of notebooks with a visible "Adv Notes" folder in the user's Google Drive.
 * Uses the narrow `drive.file` scope: the app can only see files it created.
 */
class DriveSync(
    private val context: Context,
    private val repo: NotebookRepository,
    private val settings: SettingsStore,
) {
    companion object {
        const val SCOPE = "https://www.googleapis.com/auth/drive.file"
        private const val FOLDER_NAME = "Adv Notes"
        private const val API = "https://www.googleapis.com/drive/v3"
        private const val UPLOAD = "https://www.googleapis.com/upload/drive/v3"
        private val JSON = "application/json".toMediaType()
    }

    private val _status = MutableStateFlow<SyncStatus>(SyncStatus.Idle)
    val status: StateFlow<SyncStatus> = _status
    private val mutex = Mutex()

    val authRequest: AuthorizationRequest = AuthorizationRequest.builder()
        .setRequestedScopes(listOf(Scope(SCOPE)))
        .build()

    /** If the result has a resolution the UI must launch it (consent screen). */
    suspend fun authorize(): AuthorizationResult =
        Identity.getAuthorizationClient(context).authorize(authRequest).await()

    private suspend fun token(): String? = runCatching {
        val r = authorize()
        if (r.hasResolution()) null else r.accessToken
    }.getOrNull()

    suspend fun disconnect() {
        token()?.let { t ->
            runCatching {
                Http.client.newCall(
                    Request.Builder().url("https://oauth2.googleapis.com/revoke?token=$t")
                        .post("".toRequestBody(null)).build()
                ).await().close()
            }
        }
        settings.update { it.copy(driveAccount = null, autoSync = false) }
        settings.setSecret("drive_folder", null)
        repo.updateSyncState { com.advnotes.app.data.SyncState() }
        SyncWorker.schedule(context, false)
        _status.value = SyncStatus.Idle
    }

    suspend fun sync(): SyncStatus = mutex.withLock {
        if (settings.state.value.driveAccount == null) return SyncStatus.Idle
        _status.value = SyncStatus.Running
        val result = try {
            val t = token()
            if (t == null) SyncStatus.NeedsSignIn else SyncStatus.Done(System.currentTimeMillis(), doSync(t))
        } catch (e: Exception) {
            SyncStatus.Error(e.message ?: e.toString())
        }
        _status.value = result
        result
    }

    private data class Remote(val fileId: String, val notebookId: String, val updatedAt: Long, val asset: String? = null)

    private suspend fun doSync(token: String): String {
        val folder = folderId(token)
        val all = listRemote(token, folder)
        val remote = all.filter { it.asset == null }.associateBy { it.notebookId }
        val remoteAssets = all.filter { it.asset != null }
        repo.refresh()
        var state = repo.syncState()
        val remoteIds = state.remoteIds.toMutableMap()
        val syncedAt = state.syncedAt.toMutableMap()
        var up = 0; var down = 0; var deleted = 0

        // 1. local deletions -> delete on Drive
        for (id in state.tombstones) {
            remote[id]?.let { deleteRemote(token, it.fileId); deleted++ }
            remoteAssets.filter { it.notebookId == id }.forEach { deleteRemote(token, it.fileId) }
            remoteIds.remove(id); syncedAt.remove(id)
        }
        val remoteLive = remote.filterKeys { it !in state.tombstones }

        val locals = repo.summaries.value.associateBy { it.id }

        // 2. remote notebooks
        for ((id, r) in remoteLive) {
            val local = locals[id]
            val lastSynced = syncedAt[id] ?: 0L
            when {
                local == null -> {
                    download(token, r.fileId)?.let { repo.save(it, touch = false); down++ }
                }
                r.updatedAt > local.updatedAt -> {
                    if (local.updatedAt > lastSynced && lastSynced != 0L) {
                        // Edited on both devices: keep the local edits as a separate copy.
                        repo.load(id)?.let { nb ->
                            repo.save(nb.copy(id = newId(), title = nb.title + " (conflict copy)"), touch = false)
                        }
                    }
                    download(token, r.fileId)?.let { repo.save(it, touch = false); down++ }
                }
                local.updatedAt > r.updatedAt -> { upload(token, folder, id, r.fileId); up++ }
            }
            remoteIds[id] = r.fileId
            syncedAt[id] = maxOf(r.updatedAt, repo.summaries.value.firstOrNull { it.id == id }?.updatedAt ?: 0L)
        }

        // 3. local notebooks not on Drive
        for ((id, local) in repo.summaries.value.associateBy { it.id }) {
            if (remoteLive.containsKey(id)) continue
            val wasSynced = remoteIds.containsKey(id)
            if (wasSynced && local.updatedAt <= (syncedAt[id] ?: 0L)) {
                // deleted on another device and not edited here since
                repo.deleteLocalOnly(id); remoteIds.remove(id); syncedAt.remove(id); deleted++
            } else {
                val fileId = upload(token, folder, id, null)
                remoteIds[id] = fileId
                syncedAt[id] = local.updatedAt
                up++
            }
        }

        state = state.copy(remoteIds = remoteIds, syncedAt = syncedAt, tombstones = emptySet(), lastSync = System.currentTimeMillis())
        repo.updateSyncState { state }
        syncAssets(token, folder, remoteAssets.filter { it.notebookId !in state.tombstones })
        return buildList {
            if (up > 0) add("$up uploaded")
            if (down > 0) add("$down downloaded")
            if (deleted > 0) add("$deleted deleted")
        }.joinToString(", ").ifEmpty { "Up to date" }
    }

    private suspend fun call(token: String, b: Request.Builder): String {
        val r = Http.client.newCall(b.header("Authorization", "Bearer $token").build()).await()
        r.use {
            val body = it.body?.string().orEmpty()
            if (!it.isSuccessful) throw AiException("Drive: " + errorMessage(it.code, body))
            return body
        }
    }

    private suspend fun folderId(token: String): String {
        settings.secret("drive_folder")?.let { id ->
            val ok = runCatching {
                val body = call(token, Request.Builder().url("$API/files/$id?fields=id,trashed"))
                Http.json.parseToJsonElement(body).jsonObject["trashed"].str() != "true"
            }.getOrDefault(false)
            if (ok) return id
        }
        val q = "name = '$FOLDER_NAME' and mimeType = 'application/vnd.google-apps.folder' and trashed = false"
        val url = "$API/files".toHttpUrl().newBuilder()
            .addQueryParameter("q", q).addQueryParameter("fields", "files(id)").addQueryParameter("spaces", "drive").build()
        val found = Http.json.parseToJsonElement(call(token, Request.Builder().url(url))).jsonObject["files"]
            ?.jsonArray?.firstOrNull()?.obj()?.get("id").str()
        val id = found ?: run {
            val meta = buildJsonObject { put("name", FOLDER_NAME); put("mimeType", "application/vnd.google-apps.folder") }
            val body = call(token, Request.Builder().url("$API/files?fields=id").post(meta.toString().toRequestBody(JSON)))
            Http.json.parseToJsonElement(body).jsonObject["id"].str()!!
        }
        settings.setSecret("drive_folder", id)
        return id
    }

    private suspend fun listRemote(token: String, folder: String): List<Remote> {
        val out = ArrayList<Remote>()
        var pageToken: String? = null
        do {
            val url = "$API/files".toHttpUrl().newBuilder()
                .addQueryParameter("q", "'$folder' in parents and trashed = false")
                .addQueryParameter("fields", "nextPageToken,files(id,appProperties)")
                .addQueryParameter("pageSize", "200")
                .apply { pageToken?.let { addQueryParameter("pageToken", it) } }
                .build()
            val o = Http.json.parseToJsonElement(call(token, Request.Builder().url(url))).jsonObject
            o["files"]?.jsonArray?.forEach { f ->
                val fo = f.obj() ?: return@forEach
                val props = fo["appProperties"].obj() ?: return@forEach
                val nid = props?.get("notebookId").str() ?: return@forEach
                val asset = if (props["kind"].str() == "asset") props["asset"].str() else null
                out += Remote(fo["id"].str()!!, nid, props["updatedAt"].str()?.toLongOrNull() ?: 0L, asset)
            }
            pageToken = o["nextPageToken"].str()
        } while (pageToken != null)
        return out
    }

    private suspend fun download(token: String, fileId: String): Notebook? {
        val body = call(token, Request.Builder().url("$API/files/$fileId?alt=media"))
        return runCatching { AppJson.decodeFromString(Notebook.serializer(), body) }.getOrNull()
    }

    private suspend fun upload(token: String, folder: String, id: String, existing: String?): String {
        val nb = repo.load(id) ?: throw AiException("Notebook $id missing")
        val json = repo.rawJson(id) ?: throw AiException("Notebook $id missing")
        val meta = buildJsonObject {
            put("name", "${nb.title.replace('/', '-')}.advnote.json")
            put("mimeType", "application/json")
            if (existing == null) putJsonArray("parents") { add(folder) }
            putJsonObject("appProperties") {
                put("notebookId", nb.id)
                put("updatedAt", nb.updatedAt.toString())
            }
        }
        val multipart = MultipartBody.Builder().setType("multipart/related".toMediaType())
            .addPart(meta.toString().toRequestBody(JSON))
            .addPart(json.toRequestBody(JSON))
            .build()
        val b = if (existing == null) Request.Builder().url("$UPLOAD/files?uploadType=multipart&fields=id").post(multipart)
        else Request.Builder().url("$UPLOAD/files/$existing?uploadType=multipart&fields=id").patch(multipart)
        val resp = call(token, b)
        return Http.json.parseToJsonElement(resp).jsonObject["id"].str() ?: existing!!
    }

    /**
     * Imported PDFs/images are immutable files named by UUID, so syncing them is just
     * "upload what Drive lacks, download what this device lacks".
     */
    private suspend fun syncAssets(token: String, folder: String, remoteAssets: List<Remote>) {
        val have = remoteAssets.map { it.notebookId to it.asset }.toSet()
        for (nb in repo.loadAll()) {
            val needed = (nb.pages.mapNotNull { it.background?.asset } + nb.pages.flatMap { p -> p.images.map { it.asset } }).distinct()
            for (name in needed) {
                val local = repo.assetFile(nb.id, name)
                if (local.exists() && (nb.id to name) !in have) uploadAsset(token, folder, nb.id, local)
            }
            for (r in remoteAssets.filter { it.notebookId == nb.id && it.asset in needed }) {
                val local = repo.assetFile(nb.id, r.asset!!)
                if (!local.exists()) downloadTo(token, r.fileId, local)
            }
        }
    }

    private suspend fun uploadAsset(token: String, folder: String, notebookId: String, f: java.io.File) {
        val meta = buildJsonObject {
            put("name", "${f.name}.advnote-asset")
            putJsonArray("parents") { add(folder) }
            putJsonObject("appProperties") {
                put("kind", "asset"); put("notebookId", notebookId); put("asset", f.name)
            }
        }
        val multipart = MultipartBody.Builder().setType("multipart/related".toMediaType())
            .addPart(meta.toString().toRequestBody(JSON))
            .addPart(f.asRequestBody("application/octet-stream".toMediaType()))
            .build()
        call(token, Request.Builder().url("$UPLOAD/files?uploadType=multipart&fields=id").post(multipart))
    }

    private suspend fun downloadTo(token: String, fileId: String, dest: java.io.File) {
        val r = Http.client.newCall(
            Request.Builder().url("$API/files/$fileId?alt=media").header("Authorization", "Bearer $token").build()
        ).await()
        r.use {
            if (!it.isSuccessful) throw AiException("Drive: " + errorMessage(it.code, it.body?.string().orEmpty()))
            dest.parentFile?.mkdirs()
            val tmp = java.io.File(dest.path + ".part")
            it.body!!.byteStream().use { input -> tmp.outputStream().use { o -> input.copyTo(o) } }
            tmp.renameTo(dest)
        }
    }

    private suspend fun deleteRemote(token: String, fileId: String) {
        runCatching { call(token, Request.Builder().url("$API/files/$fileId").delete()) }
    }
}
