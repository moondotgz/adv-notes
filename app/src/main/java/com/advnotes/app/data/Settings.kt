@file:Suppress("DEPRECATION")

package com.advnotes.app.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** What the S Pen side button does while held. */
enum class PenButtonAction(val label: String) { ERASER("Eraser"), LASSO("Lasso"), NONE("Nothing") }

/** How finger touches are treated. */
enum class FingerMode(val label: String) {
    AUTO("Auto (draw until a pen is detected)"),
    NAVIGATE("Fingers only scroll & zoom"),
    DRAW("Fingers draw"),
}

enum class ProviderType(val label: String, val needsKey: Boolean, val needsBaseUrl: Boolean) {
    OPENAI("OpenAI (API key)", true, false),
    CHATGPT("ChatGPT subscription (unofficial)", false, false),
    ANTHROPIC("Anthropic (API key)", true, false),
    OPENROUTER("OpenRouter", true, false),
    CUSTOM_OPENAI("Custom OpenAI-compatible", false, true),
    CUSTOM_ANTHROPIC("Custom Anthropic-compatible", false, true),
    LOCAL("On-device (llama.cpp)", false, false);

    val defaultModel: String
        get() = when (this) {
            OPENAI -> "gpt-5"
            CHATGPT -> "gpt-5"
            ANTHROPIC -> "claude-opus-5-5"
            OPENROUTER -> "openai/gpt-5"
            CUSTOM_OPENAI -> ""
            CUSTOM_ANTHROPIC -> ""
            LOCAL -> ""
        }
}

data class AppSettings(
    val fingerMode: FingerMode = FingerMode.AUTO,
    val penButton: PenButtonAction = PenButtonAction.ERASER,
    /** 0.3 (soft, wide range) .. 2.0 (hard) — exponent applied to raw pressure. */
    val pressureCurve: Float = 1f,
    val holdToShape: Boolean = true,
    val provider: ProviderType = ProviderType.OPENAI,
    val autoSync: Boolean = false,
    val driveAccount: String? = null,
    val localModel: String? = null,
    val localThreads: Int = 4,
    /** Handwriting → text: language name (see Handwriting.LANGUAGES) and engine. */
    val hwLanguage: String = "English",
    val hwEngine: String = "ON_DEVICE",
    /** Where the pen toolbar is docked: TOP, BOTTOM, LEFT or RIGHT. */
    val toolbarDock: String = "TOP",
    /** Side of the page the tools panel sits on (tablets): LEFT or RIGHT. */
    val panelSide: String = "RIGHT",
)

/**
 * Non-secret preferences live in normal SharedPreferences; API keys and OAuth tokens live in
 * [EncryptedSharedPreferences] backed by the Android Keystore.
 */
@Suppress("DEPRECATION") // security-crypto is deprecated but still the simplest Keystore-backed store
class SettingsStore(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val secrets: SharedPreferences by lazy { secretsOverride?.invoke(context) ?: openSecrets(context) }

    companion object {
        /** Tests (Robolectric has no AndroidKeyStore) can substitute plain preferences here. */
        @androidx.annotation.VisibleForTesting
        var secretsOverride: ((Context) -> SharedPreferences)? = null
    }

    private fun openSecrets(context: Context): SharedPreferences = runCatching {
        val key = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        EncryptedSharedPreferences.create(
            context, "secrets", key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }.getOrElse {
        // Keystore problems (rare, e.g. after a backup restore) – reset rather than crash.
        context.deleteSharedPreferences("secrets")
        val key = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        EncryptedSharedPreferences.create(
            context, "secrets", key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    private val _state = MutableStateFlow(read())
    val state: StateFlow<AppSettings> = _state

    private fun read() = AppSettings(
        fingerMode = enumOr(prefs.getString("fingerMode", null), FingerMode.AUTO),
        penButton = enumOr(prefs.getString("penButton", null), PenButtonAction.ERASER),
        pressureCurve = prefs.getFloat("pressureCurve", 1f),
        holdToShape = prefs.getBoolean("holdToShape", true),
        provider = enumOr(prefs.getString("provider", null), ProviderType.OPENAI),
        autoSync = prefs.getBoolean("autoSync", false),
        driveAccount = prefs.getString("driveAccount", null),
        localModel = prefs.getString("localModel", null),
        localThreads = prefs.getInt("localThreads", 4),
        hwLanguage = prefs.getString("hwLanguage", null) ?: "English",
        hwEngine = prefs.getString("hwEngine", null) ?: "ON_DEVICE",
        toolbarDock = prefs.getString("toolbarDock", null) ?: "TOP",
        panelSide = prefs.getString("panelSide", null) ?: "RIGHT",
    )

    fun update(block: (AppSettings) -> AppSettings) {
        val s = block(_state.value)
        prefs.edit()
            .putString("fingerMode", s.fingerMode.name)
            .putString("penButton", s.penButton.name)
            .putFloat("pressureCurve", s.pressureCurve)
            .putBoolean("holdToShape", s.holdToShape)
            .putString("provider", s.provider.name)
            .putBoolean("autoSync", s.autoSync)
            .putString("driveAccount", s.driveAccount)
            .putString("localModel", s.localModel)
            .putInt("localThreads", s.localThreads)
            .putString("hwLanguage", s.hwLanguage)
            .putString("hwEngine", s.hwEngine)
            .putString("toolbarDock", s.toolbarDock)
            .putString("panelSide", s.panelSide)
            .apply()
        _state.value = s
    }

    // Per-provider configuration -------------------------------------------------------------

    fun model(p: ProviderType): String = prefs.getString("model_${p.name}", null) ?: p.defaultModel
    fun setModel(p: ProviderType, v: String) = prefs.edit().putString("model_${p.name}", v.trim()).apply()

    fun baseUrl(p: ProviderType): String = prefs.getString("base_${p.name}", null) ?: ""
    fun setBaseUrl(p: ProviderType, v: String) = prefs.edit().putString("base_${p.name}", v.trim()).apply()

    fun apiKey(p: ProviderType): String = secrets.getString("key_${p.name}", null) ?: ""
    fun setApiKey(p: ProviderType, v: String) = secrets.edit().putString("key_${p.name}", v.trim()).apply()

    fun secret(name: String): String? = secrets.getString(name, null)
    fun setSecret(name: String, v: String?) = secrets.edit().apply {
        if (v == null) remove(name) else putString(name, v)
    }.apply()

    private inline fun <reified T : Enum<T>> enumOr(v: String?, d: T): T =
        v?.let { runCatching { enumValueOf<T>(it) }.getOrNull() } ?: d
}
