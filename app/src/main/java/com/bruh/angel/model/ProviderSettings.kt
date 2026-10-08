package com.bruh.angel.model

import com.bruh.angel.linuxenv.LinuxDistro
import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Defaults are only starting points: model IDs change often. Settings -> "Fetch models" lists
 * what the user's key can actually use. (Checked Oct 2026 against each provider's docs.)
 */
enum class Provider(val label: String, val defaultModel: String, val endpoint: String, val modelsUrl: String) {
    OPENAI("OpenAI", "gpt-6.1-sol", "https://api.openai.com/v1/chat/completions", "https://api.openai.com/v1/models"),
    GOOGLE("Google", "gemini-3.8-flash", "https://generativelanguage.googleapis.com/v1beta/models/", "https://generativelanguage.googleapis.com/v1beta/models?pageSize=1000"),
    ANTHROPIC("Anthropic", "claude-sonnet-5-5", "https://api.anthropic.com/v1/messages", "https://api.anthropic.com/v1/models?limit=1000"),
    DEEPSEEK("DeepSeek", "deepseek-flash", "https://api.deepseek.com/chat/completions", "https://api.deepseek.com/models"),

    /** GGUF models run on the phone with llama.cpp. `model` holds the local model's id; no API key. */
    LOCAL("On-device", "", "", "");

    val isLocal get() = this == LOCAL

    companion object {
        /** Discontinued model IDs -> replacement. deepseek-chat/deepseek-reasoner were retired 2026-07-24. */
        val retiredModels = mapOf("deepseek-chat" to "deepseek-flash", "deepseek-reasoner" to "deepseek-flash")

        /** Best guess of which provider a model ID belongs to (to catch mixed-up settings). */
        fun forModel(model: String): Provider? {
            val id = model.trim().lowercase().substringAfterLast('/')
            return when {
                id.startsWith("deepseek") -> DEEPSEEK
                id.startsWith("gemini") || id.startsWith("gemma") -> GOOGLE
                id.startsWith("claude") -> ANTHROPIC
                id.startsWith("gpt") || id.startsWith("chatgpt") || Regex("^o\\d").containsMatchIn(id) -> OPENAI
                else -> null
            }
        }
    }
}

data class ProviderSettings(val provider: Provider, val model: String, val apiKey: String) {
    /** Non-blocking sanity checks for settings that were obviously entered under the wrong provider. */
    fun problems(): List<String> = if (provider.isLocal) emptyList() else buildList {
        Provider.forModel(model)?.takeIf { it != provider }?.let {
            add("'${model.trim()}' looks like a ${it.label} model, but the selected provider is ${provider.label}.")
        }
        val key = apiKey.trim()
        if (key.isNotEmpty()) {
            val expected = when (provider) {
                Provider.GOOGLE -> "AIza"
                Provider.ANTHROPIC -> "sk-ant-"
                else -> "sk-"
            }
            if (!key.startsWith(expected) || (provider != Provider.ANTHROPIC && key.startsWith("sk-ant-"))) {
                add("This key doesn't look like a ${provider.label} API key (they usually start with \"$expected\").")
            }
        }
    }
}

/** Names of the tools exposed to the model. */
object ToolNames {
    const val ANDROID = "android_shell"
    const val LINUX = "linux_shell"
    const val SCREEN = "read_screen"
    val all = listOf(ANDROID, LINUX, SCREEN)

    fun label(name: String) = when (name) {
        ANDROID -> "Android shell"
        LINUX -> "Linux shell"
        SCREEN -> "Read screen"
        else -> com.bruh.angel.mcp.McpNaming.split(name)?.let { (server, tool) -> "$tool ($server)" } ?: name
    }
}

enum class ToolPolicy(val label: String) { ASK("Ask"), ALLOW("Allow"), DENY("Deny") }

data class AgentPrefs(
    val instructions: String = "",
    val distro: LinuxDistro = LinuxDistro.DEFAULT,
    val policies: Map<String, ToolPolicy> = ToolNames.all.associateWith { ToolPolicy.ASK }
) {
    fun policy(tool: String) = policies[tool] ?: ToolPolicy.ASK
}

/** AES-GCM encrypted settings; ciphertext lives in noBackupFilesDir, key in Android Keystore. */
class ProviderSettingsStore(context: Context) {
    private val file = AtomicFile(File(context.noBackupFilesDir, "providers.enc"))

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(
                KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build()
            )
        }.generateKey()
    }

    private fun read(): JSONObject {
        if (!file.baseFile.exists()) return JSONObject()
        val bytes = file.readFully()
        require(bytes.size > 12) { "Invalid encrypted settings" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        val plain = try {
            cipher.doFinal(bytes.copyOfRange(12, bytes.size))
        } catch (e: Exception) {
            throw IllegalStateException("Saved settings could not be decrypted")
        }
        // Never include decrypted content in exception messages.
        return try {
            JSONObject(String(plain, Charsets.UTF_8))
        } catch (e: Exception) {
            throw IllegalStateException("Saved settings are corrupt")
        }
    }

    private fun write(data: JSONObject) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val bytes = cipher.iv + cipher.doFinal(data.toString().toByteArray(Charsets.UTF_8))
        val stream = file.startWrite()
        try {
            stream.write(bytes)
            file.finishWrite(stream)
        } catch (e: Exception) {
            file.failWrite(stream)
            throw e
        }
    }

    @Synchronized
    fun selected(): Provider =
        runCatching { Provider.valueOf(read().optString("selected", "OPENAI")) }.getOrDefault(Provider.OPENAI)

    /** Persist which provider is active, independent of saving a key. */
    @Synchronized
    fun select(provider: Provider) {
        write(read().put("selected", provider.name))
    }

    /** Loads a profile, migrating discontinued model IDs (and persisting the migration). */
    @Synchronized
    fun load(provider: Provider): ProviderSettings {
        val all = read()
        val data = all.optJSONObject(provider.name)
        val stored = data?.optString("model").orEmpty().ifBlank { provider.defaultModel }
        val model = if (provider == Provider.DEEPSEEK) Provider.retiredModels[stored] ?: stored else stored
        if (model != stored && data != null) write(all.put(provider.name, data.put("model", model)))
        return ProviderSettings(provider, model, data?.optString("key").orEmpty())
    }

    @Synchronized
    fun save(settings: ProviderSettings) {
        require(isValidModel(settings.model)) { "Enter a valid model ID" }
        require(settings.provider.isLocal || settings.apiKey.isNotBlank()) { "Enter an API key" }
        write(
            read().put("selected", settings.provider.name).put(
                settings.provider.name,
                JSONObject().put("model", settings.model.trim()).put("key", settings.apiKey.trim())
            )
        )
    }

    @Synchronized
    fun loadPrefs(): AgentPrefs {
        val data = read()
        val policies = data.optJSONObject("policy")
        return AgentPrefs(
            instructions = data.optString("instructions", ""),
            distro = LinuxDistro.fromId(data.optString("distro", "")),
            policies = ToolNames.all.associateWith { tool ->
                runCatching { ToolPolicy.valueOf(policies?.optString(tool).orEmpty()) }.getOrDefault(ToolPolicy.ASK)
            }
        )
    }

    @Synchronized
    fun savePrefs(prefs: AgentPrefs) {
        require(prefs.instructions.length <= 4000) { "Custom instructions are limited to 4000 characters" }
        val policy = JSONObject()
        prefs.policies.forEach { (tool, value) -> if (tool in ToolNames.all) policy.put(tool, value.name) }
        write(read().put("instructions", prefs.instructions).put("distro", prefs.distro.id).put("policy", policy))
    }

    @Synchronized
    fun loadLocalParams(): com.bruh.angel.local.LocalParams =
        com.bruh.angel.local.LocalParams.fromJson(read().optJSONObject("local"))

    @Synchronized
    fun saveLocalParams(params: com.bruh.angel.local.LocalParams) {
        write(read().put("local", params.toJson()))
    }

    @Synchronized
    fun loadMcp(): List<com.bruh.angel.mcp.McpServerConfig> {
        val list = read().optJSONArray("mcp") ?: return emptyList()
        return (0 until list.length()).mapNotNull { list.optJSONObject(it)?.let(com.bruh.angel.mcp.McpServerConfig::fromJson) }
            .distinctBy { it.id }
    }

    @Synchronized
    fun saveMcp(servers: List<com.bruh.angel.mcp.McpServerConfig>) {
        write(read().put("mcp", org.json.JSONArray(servers.map { it.toJson() })))
    }

    /** Optional Hugging Face access token (gated models), kept encrypted like API keys. */
    @Synchronized
    fun hfToken(): String = read().optString("hfToken", "")

    @Synchronized
    fun setHfToken(token: String) {
        write(read().put("hfToken", token.trim()))
    }

    companion object {
        private const val ALIAS = "angel.providers"
        fun isValidModel(model: String) = model.trim().matches(Regex("[A-Za-z0-9._:/-]{1,160}"))
    }
}

