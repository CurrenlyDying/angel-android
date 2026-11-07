package com.bruh.angel.model

import com.bruh.angel.linuxenv.LinuxDistro
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * A workspace that groups chats and gives the agent a fixed context: a working directory inside a
 * Linux distro, per-project instructions, a chosen subset of installed MCP servers ([connectors])
 * and, optionally, a cloned Git repository. Persisted (encrypted) in [ProviderSettingsStore];
 * conversations reference it by [id].
 */
data class Project(
    val id: String,
    val name: String,
    /** Absolute path inside the proot Linux userland that `linux_shell` and the terminal run in. */
    val workdir: String,
    /** [LinuxDistro.id] the project uses, overriding the global selection while it is active. */
    val distroId: String,
    /** Appended to the system prompt for chats in this project. */
    val instructions: String = "",
    /** [McpServerConfig.id]s that are switched on for this project. */
    val connectors: Set<String> = emptySet(),
    /** [Skill.id]s attached to this project: their instructions are injected and connectors turned on. */
    val skills: Set<String> = emptySet(),
    /** Optional GitHub repository cloned into [workdir] when the project was created. */
    val repoUrl: String = "",
    val created: Long = System.currentTimeMillis(),
    val updated: Long = System.currentTimeMillis()
) {
    val distro: LinuxDistro get() = LinuxDistro.fromId(distroId)

    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("name", name).put("workdir", workdir).put("distroId", distroId)
        .put("instructions", instructions)
        .put("connectors", JSONArray(connectors.toList()))
        .put("skills", JSONArray(skills.toList()))
        .put("repoUrl", repoUrl).put("created", created).put("updated", updated)

    companion object {
        val ID = Regex("[A-Za-z0-9-]{8,64}")
        const val MAX = 100

        fun newId(): String = UUID.randomUUID().toString()

        fun fromJson(o: JSONObject): Project? {
            val id = o.optString("id")
            if (!ID.matches(id)) return null
            val connectors = o.optJSONArray("connectors")
            val skills = o.optJSONArray("skills")
            return Project(
                id = id,
                name = o.optString("name").ifBlank { "Project" },
                workdir = o.optString("workdir").ifBlank { "/root" },
                distroId = o.optString("distroId").ifBlank { LinuxDistro.DEFAULT.id },
                instructions = o.optString("instructions"),
                connectors = if (connectors == null) emptySet()
                    else (0 until connectors.length()).map { connectors.optString(it) }.filter { it.isNotBlank() }.toSet(),
                skills = if (skills == null) emptySet()
                    else (0 until skills.length()).map { skills.optString(it) }.filter { it.isNotBlank() }.toSet(),
                repoUrl = o.optString("repoUrl"),
                created = o.optLong("created", System.currentTimeMillis()),
                updated = o.optLong("updated", System.currentTimeMillis())
            )
        }
    }
}
