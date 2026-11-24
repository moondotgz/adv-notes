package com.advnotes.app.ai

import com.advnotes.app.i18n.tr

import android.content.Context
import android.net.Uri
import android.util.Base64
import androidx.browser.customtabs.CustomTabsIntent
import com.advnotes.app.data.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.URLDecoder
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * "Sign in with Claude" using the same public OAuth (PKCE) flow as the Claude Code CLI.
 * The browser redirects to a loopback server that this class runs while signing in.
 * If the redirect can't reach the app, the user can paste the final URL instead.
 *
 * Unofficial: Anthropic may change this endpoint or stop serving third-party clients at
 * any time. It uses the user's Claude subscription, not an Anthropic API key.
 */
class ClaudeCodeAuth(private val settings: SettingsStore) {
    companion object {
        private const val CLIENT_ID = "9d1c250a-e61b-44d9-88ed-5944d1962f5e"
        private const val AUTHORIZE = "https://claude.com/cai/oauth/authorize"
        private const val TOKEN = "https://platform.claude.com/v1/oauth/token"
        private const val PROFILE = "https://api.anthropic.com/api/oauth/profile"
        private const val MANUAL_REDIRECT = "https://platform.claude.com/oauth/code/callback"
        private const val SCOPE =
            "org:create_api_key user:profile user:inference " +
                "user:sessions:claude_code user:mcp_servers user:file_upload"
        private const val K_ACCESS = "claude_code_access"
        private const val K_REFRESH = "claude_code_refresh"
        private const val K_EXPIRES = "claude_code_expires"
        private const val K_EMAIL = "claude_code_email"
        private const val K_PLAN = "claude_code_plan"

        /** The headers Anthropic expects from Claude Code when an OAuth token is used. */
        const val USER_AGENT = "claude-cli/2.1.325 (external, cli)"
        const val OAUTH_BETA = "oauth-2025-04-20"
        private val JSON = "application/json".toMediaType()
    }

    private var verifier: String? = null
    private var state: String? = null
    private var redirectUri: String? = null
    private var server: ServerSocket? = null
    private val refreshLock = Mutex()

    /** Set when a pasted URL completes the sign-in, so the waiting loop ends quietly. */
    @Volatile private var completedFromPaste = false

    fun isSignedIn() = settings.secret(K_REFRESH) != null
    fun email(): String? = settings.secret(K_EMAIL)
    fun plan(): String? = settings.secret(K_PLAN)

    /** "ada@example.com (Max)", or just the email / plan when one is missing. */
    fun accountLabel(): String? {
        val e = email()
        val p = plan()
        return when {
            e != null && p != null -> "$e ($p)"
            e != null -> e
            p != null -> p
            else -> null
        }
    }

    fun signOut() {
        listOf(K_ACCESS, K_REFRESH, K_EXPIRES, K_EMAIL, K_PLAN).forEach { settings.setSecret(it, null) }
    }

    private fun randomUrlSafe(bytes: Int): String {
        val b = ByteArray(bytes).also { SecureRandom().nextBytes(it) }
        return Base64.encodeToString(b, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    /** Opens the browser and waits (up to 5 minutes) for the loopback redirect. */
    suspend fun signIn(context: Context) {
        val v = randomUrlSafe(32)
        val st = randomUrlSafe(32)
        verifier = v
        state = st
        completedFromPaste = false
        val challenge = Base64.encodeToString(
            MessageDigest.getInstance("SHA-256").digest(v.toByteArray()),
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
        )

        val socket = withContext(Dispatchers.IO) {
            runCatching { server?.close() }
            ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).also { server = it }
        }
        val redirect = "http://localhost:${socket.localPort}/callback"
        redirectUri = redirect
        val url = Uri.parse(AUTHORIZE).buildUpon()
            .appendQueryParameter("code", "true")
            .appendQueryParameter("client_id", CLIENT_ID)
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("redirect_uri", redirect)
            .appendQueryParameter("scope", SCOPE)
            .appendQueryParameter("code_challenge", challenge)
            .appendQueryParameter("code_challenge_method", "S256")
            .appendQueryParameter("state", st)
            .build()
        try {
            withContext(Dispatchers.Main) {
                CustomTabsIntent.Builder().build().launchUrl(context, url)
            }
            val code = withTimeout(5 * 60_000) { waitForCode(socket, st) }
            exchange(code, v, st, redirect)
            fetchProfile()
        } catch (e: TimeoutCancellationException) {
            throw AiException(tr("Sign-in timed out. Try again."))
        } finally {
            runCatching { socket.close() }
            server = null
        }
    }

    /** Cancels a pending loopback wait. */
    fun cancel() { runCatching { server?.close() } }

    private suspend fun waitForCode(socket: ServerSocket, expectedState: String): String = withContext(Dispatchers.IO) {
        while (true) {
            val client = try { socket.accept() } catch (e: SocketException) {
                if (completedFromPaste) throw CancellationException("Sign-in completed")
                throw AiException(tr("Sign-in cancelled"))
            }
            client.use { c ->
                c.soTimeout = 10_000
                val reqLine = try { c.getInputStream().bufferedReader().readLine().orEmpty() }
                    catch (e: SocketTimeoutException) { return@use }
                val path = reqLine.split(" ").getOrNull(1).orEmpty()
                val query = parseQuery(path.substringAfter('?', ""))
                val isCallback = path.startsWith("/callback")
                val code = query["code"]
                val failure = when {
                    !isCallback -> "Unexpected request"
                    query["error"] != null -> query["error_description"] ?: query["error"]
                    code == null -> "No authorization code received"
                    query["state"] != expectedState -> "State mismatch — start the sign-in again."
                    else -> null
                }
                val ok = isCallback && failure == null
                val html = if (ok) "<h2>Signed in to Adv Notes.</h2><p>You can return to the app.</p>"
                else "<h2>Sign-in failed</h2><p>$failure</p>"
                val bytes = "<html><body style='font-family:sans-serif;padding:2em'>$html</body></html>".toByteArray()
                c.getOutputStream().apply {
                    write("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                    write(bytes); flush()
                }
                if (ok) return@withContext code!!
                if (isCallback) throw AiException(failure!!)
            }
        }
        @Suppress("UNREACHABLE_CODE") ""
    }

    /** Fallback: the user pastes the localhost URL their browser ended up on, or the code it shows. */
    suspend fun completeFromUrl(pasted: String) {
        val v = verifier ?: throw AiException(tr("Start the sign-in first."))
        val expected = state
        val redirect = redirectUri ?: MANUAL_REDIRECT
        val (code, returnedState) = parsePasted(pasted)
        if (returnedState != null && expected != null && returnedState != expected) {
            throw AiException(tr("State mismatch — start the sign-in again."))
        }
        completedFromPaste = true
        cancel()
        exchange(code, v, expected.orEmpty(), redirect)
        fetchProfile()
    }

    private fun parsePasted(pasted: String): Pair<String, String?> {
        val s = pasted.trim()
        if (s.startsWith("http", ignoreCase = true) || s.contains("code=")) {
            val q = parseQuery(s.substringAfter('?', s))
            q["error"]?.let { throw AiException(q["error_description"] ?: it) }
            val code = q["code"] ?: throw AiException(tr("That URL has no ?code= parameter."))
            return code to q["state"]
        }
        val parts = s.split('#', limit = 2)
        val code = parts[0].trim()
        if (code.isEmpty()) throw AiException(tr("Paste the code shown after signing in."))
        return code to parts.getOrNull(1)?.trim()
    }

    private fun parseQuery(q: String): Map<String, String> = q.split('&').filter { it.contains('=') }.associate {
        val (k, v) = it.split('=', limit = 2)
        URLDecoder.decode(k, "UTF-8") to URLDecoder.decode(v, "UTF-8")
    }

    private suspend fun exchange(code: String, verifier: String, state: String, redirect: String) {
        tokenRequest(buildJsonObject {
            put("grant_type", "authorization_code")
            put("code", code)
            put("redirect_uri", redirect)
            put("client_id", CLIENT_ID)
            put("code_verifier", verifier)
            put("state", state)
        })
    }

    private suspend fun tokenRequest(body: kotlinx.serialization.json.JsonObject) {
        val req = Request.Builder().url(TOKEN)
            .post(body.toString().toRequestBody(JSON))
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .header("User-Agent", USER_AGENT)
            .header("anthropic-beta", OAUTH_BETA)
            .build()
        val resp = Http.client.newCall(req).await()
        resp.use { r ->
            val text = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw AiException(errorMessage(r.code, text))
            val o = Http.json.parseToJsonElement(text).jsonObject
            val access = o["access_token"].str() ?: throw AiException(tr("The sign-in response had no access token."))
            val refresh = o["refresh_token"].str()
            val expiresIn = o["expires_in"].str()?.toLongOrNull() ?: 3600L
            settings.setSecret(K_ACCESS, access)
            if (refresh != null) settings.setSecret(K_REFRESH, refresh)
            settings.setSecret(K_EXPIRES, (System.currentTimeMillis() + expiresIn * 1000).toString())
        }
    }

    /** Reads the account email and plan for display; failure here never blocks sign-in. */
    private suspend fun fetchProfile() {
        val access = settings.secret(K_ACCESS) ?: return
        val req = Request.Builder().url(PROFILE)
            .header("Authorization", "Bearer $access")
            .header("Content-Type", "application/json")
            .header("Cache-Control", "no-cache")
            .header("User-Agent", USER_AGENT)
            .build()
        val o = try {
            withTimeoutOrNull(10_000) {
                Http.client.newCall(req).await().use { r ->
                    if (!r.isSuccessful) null else Http.json.parseToJsonElement(r.body?.string().orEmpty()).jsonObject
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        } ?: return
        val account = o["account"].obj()
        (account?.get("email").str() ?: account?.get("email_address").str())
            ?.let { settings.setSecret(K_EMAIL, it) }
        o["organization"].obj()?.get("organization_type").str()?.let { type ->
            settings.setSecret(K_PLAN, when (type) {
                "claude_max" -> "Max"
                "claude_pro" -> "Pro"
                "claude_team" -> "Team"
                "claude_enterprise" -> "Enterprise"
                else -> type
            })
        }
    }

    /** Returns an access token, refreshing it when it expires within 5 minutes. */
    suspend fun validAccessToken(): String? = refreshLock.withLock {
        val access = settings.secret(K_ACCESS)
        val refresh = settings.secret(K_REFRESH) ?: return@withLock null
        val exp = settings.secret(K_EXPIRES)?.toLongOrNull() ?: 0L
        if (access != null && exp - System.currentTimeMillis() > 5 * 60_000) return@withLock access
        try {
            tokenRequest(buildJsonObject {
                put("grant_type", "refresh_token")
                put("refresh_token", refresh)
                put("client_id", CLIENT_ID)
            })
        } catch (e: AiException) {
            // A rejected refresh token can't be recovered without signing in again.
            val m = e.message.orEmpty()
            if (m.contains("HTTP 400") || m.contains("HTTP 401") || m.contains("HTTP 403")) {
                signOut()
                throw AiException(tr("Your Claude sign-in expired. Sign in again in Settings."))
            }
            throw e
        }
        settings.secret(K_ACCESS)
    }
}
