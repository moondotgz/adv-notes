package com.advnotes.app.ai

import com.advnotes.app.i18n.tr

import android.content.Context
import android.net.Uri
import android.util.Base64
import androidx.browser.customtabs.CustomTabsIntent
import com.advnotes.app.data.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.jsonObject
import okhttp3.FormBody
import okhttp3.Request
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketException
import java.net.URLDecoder
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * "Sign in with ChatGPT" using the same public OAuth (PKCE) flow as the open-source Codex CLI.
 * The browser redirects to a loopback server on port 1455 that this class runs while signing in.
 * If the redirect can't reach the app, the user can paste the final URL instead ([completeFromUrl]).
 */
class ChatGptAuth(private val settings: SettingsStore) {
    companion object {
        private const val CLIENT_ID = "app_EMoamEEZ73f0CkXaXp7hrann"
        private const val ISSUER = "https://auth.openai.com"
        private const val PORT = 1455
        private const val REDIRECT = "http://localhost:$PORT/auth/callback"
        private const val K_ACCESS = "chatgpt_access"
        private const val K_REFRESH = "chatgpt_refresh"
        private const val K_ID = "chatgpt_id_token"
        private const val K_ACCOUNT = "chatgpt_account"
        private const val K_EMAIL = "chatgpt_email"
    }

    private var verifier: String? = null
    private var state: String? = null
    private var server: ServerSocket? = null
    private val refreshLock = Mutex()

    fun isSignedIn() = settings.secret(K_REFRESH) != null
    fun accountId(): String? = settings.secret(K_ACCOUNT)
    fun email(): String? = settings.secret(K_EMAIL)

    fun signOut() {
        listOf(K_ACCESS, K_REFRESH, K_ID, K_ACCOUNT, K_EMAIL).forEach { settings.setSecret(it, null) }
    }

    private fun randomUrlSafe(bytes: Int): String {
        val b = ByteArray(bytes).also { SecureRandom().nextBytes(it) }
        return Base64.encodeToString(b, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    /** Opens the browser and waits (up to 5 minutes) for the loopback redirect. */
    suspend fun signIn(context: Context) {
        val v = randomUrlSafe(64)
        val st = randomUrlSafe(24)
        verifier = v; state = st
        val challenge = Base64.encodeToString(
            MessageDigest.getInstance("SHA-256").digest(v.toByteArray()),
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
        )
        val url = Uri.parse("$ISSUER/oauth/authorize").buildUpon()
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("client_id", CLIENT_ID)
            .appendQueryParameter("redirect_uri", REDIRECT)
            .appendQueryParameter("scope", "openid profile email offline_access")
            .appendQueryParameter("code_challenge", challenge)
            .appendQueryParameter("code_challenge_method", "S256")
            .appendQueryParameter("id_token_add_organizations", "true")
            .appendQueryParameter("codex_cli_simplified_flow", "true")
            .appendQueryParameter("state", st)
            .appendQueryParameter("originator", "codex_cli_rs")
            .build()

        val socket = withContext(Dispatchers.IO) {
            runCatching { server?.close() }
            ServerSocket(PORT, 1, InetAddress.getByName("127.0.0.1")).also { server = it }
        }
        withContext(Dispatchers.Main) {
            CustomTabsIntent.Builder().build().launchUrl(context, url)
        }
        try {
            val code = withTimeout(5 * 60_000) { waitForCode(socket, st) }
            exchange(code, v)
        } finally {
            runCatching { socket.close() }
            server = null
        }
    }

    /** Cancels a pending loopback wait. */
    fun cancel() { runCatching { server?.close() } }

    private suspend fun waitForCode(socket: ServerSocket, expectedState: String): String = withContext(Dispatchers.IO) {
        while (true) {
            val client = try { socket.accept() } catch (e: SocketException) { throw AiException(tr("Sign-in cancelled")) }
            client.use { c ->
                val reqLine = c.getInputStream().bufferedReader().readLine().orEmpty()
                val path = reqLine.split(" ").getOrNull(1).orEmpty()
                val query = parseQuery(path.substringAfter('?', ""))
                val ok = path.startsWith("/auth/callback") && query["code"] != null && query["state"] == expectedState
                val html = if (ok) "<h2>Signed in to Adv Notes.</h2><p>You can return to the app.</p>"
                else "<h2>Sign-in failed</h2><p>${query["error_description"] ?: query["error"] ?: "Unexpected request"}</p>"
                val bytes = "<html><body style='font-family:sans-serif;padding:2em'>$html</body></html>".toByteArray()
                c.getOutputStream().apply {
                    write("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                    write(bytes); flush()
                }
                if (ok) return@withContext query["code"]!!
                if (path.startsWith("/auth/callback") && query["error"] != null) {
                    throw AiException(query["error_description"] ?: query["error"]!!)
                }
            }
        }
        @Suppress("UNREACHABLE_CODE") ""
    }

    /** Fallback: the user pastes the localhost URL their browser ended up on. */
    suspend fun completeFromUrl(pasted: String) {
        val v = verifier ?: throw AiException(tr("Start the sign-in first."))
        val q = parseQuery(pasted.substringAfter('?', ""))
        val code = q["code"] ?: throw AiException(tr("That URL has no ?code= parameter."))
        if (q["state"] != null && q["state"] != state) throw AiException(tr("State mismatch — start the sign-in again."))
        cancel()
        exchange(code, v)
    }

    private fun parseQuery(q: String): Map<String, String> = q.split('&').filter { it.contains('=') }.associate {
        val (k, v) = it.split('=', limit = 2)
        URLDecoder.decode(k, "UTF-8") to URLDecoder.decode(v, "UTF-8")
    }

    private suspend fun exchange(code: String, verifier: String) {
        val form = FormBody.Builder()
            .add("grant_type", "authorization_code")
            .add("code", code)
            .add("redirect_uri", REDIRECT)
            .add("client_id", CLIENT_ID)
            .add("code_verifier", verifier)
            .build()
        tokenRequest(form)
    }

    private suspend fun tokenRequest(form: FormBody) {
        val req = Request.Builder().url("$ISSUER/oauth/token").post(form).build()
        val resp = Http.client.newCall(req).await()
        resp.use { r ->
            val body = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw AiException(errorMessage(r.code, body))
            val o = Http.json.parseToJsonElement(body).jsonObject
            o["access_token"].str()?.let { settings.setSecret(K_ACCESS, it) }
            o["refresh_token"].str()?.let { settings.setSecret(K_REFRESH, it) }
            o["id_token"].str()?.let { id ->
                settings.setSecret(K_ID, id)
                val claims = jwtClaims(id)
                claims?.get("email").str()?.let { settings.setSecret(K_EMAIL, it) }
                claims?.get("https://api.openai.com/auth").obj()?.get("chatgpt_account_id").str()
                    ?.let { settings.setSecret(K_ACCOUNT, it) }
            }
        }
    }

    private fun jwtClaims(jwt: String) = runCatching {
        val part = jwt.split('.')[1]
        val json = String(Base64.decode(part, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING))
        Http.json.parseToJsonElement(json).jsonObject
    }.getOrNull()

    /** Returns an access token, refreshing it when it expires within 5 minutes. */
    suspend fun validAccessToken(): String? = refreshLock.withLock {
        val access = settings.secret(K_ACCESS)
        val refresh = settings.secret(K_REFRESH) ?: return null
        val exp = access?.let { jwtClaims(it)?.get("exp").str()?.toLongOrNull() } ?: 0L
        if (access != null && exp * 1000 - System.currentTimeMillis() > 5 * 60_000) return access
        tokenRequest(
            FormBody.Builder()
                .add("grant_type", "refresh_token")
                .add("refresh_token", refresh)
                .add("client_id", CLIENT_ID)
                .add("scope", "openid profile email")
                .build()
        )
        settings.secret(K_ACCESS)
    }
}
