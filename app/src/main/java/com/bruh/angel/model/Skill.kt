package com.bruh.angel.model

import org.json.JSONArray
import org.json.JSONObject

/**
 * A reusable capability pack — an Agent "skill" in the [SKILL.md](https://agentskills.io) sense:
 * [instructions] appended to the system prompt when the skill is *active* (attached to the active
 * project), plus — for built-ins — an optional setup script and a bundle of MCP connectors defined
 * in [SkillCatalog]. Built-ins ship with the app (bodies in `assets/skills/<id>/SKILL.md`); others
 * are imported from a pasted or fetched SKILL.md.
 *
 * Persisted (encrypted) in [ProviderSettingsStore]; built-in bodies are re-read from assets on load,
 * so only [enabled]/[setupDone]/[connectorIds] matter for them, while imported skills are stored whole.
 */
data class Skill(
    val id: String,
    val name: String,
    val description: String,
    /** The SKILL.md body (markdown). Appended to the system prompt while the skill is attached. */
    val instructions: String,
    val builtin: Boolean = false,
    val enabled: Boolean = true,
    /** "builtin", "import", or a source URL. */
    val source: String = "",
    /** True once this skill's tools/connectors were installed into a distro. */
    val setupDone: Boolean = false,
    /** [McpServerConfig.id]s this skill installed, so attaching it turns them on for a project. */
    val connectorIds: Set<String> = emptySet(),
    /** Environments where setup completed; legacy setupDone alone cannot identify a distro. */
    val setupDistros: Set<String> = emptySet()
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("name", name).put("description", description)
        .put("instructions", instructions).put("builtin", builtin).put("enabled", enabled)
        .put("source", source).put("setupDone", setupDone)
        .put("connectorIds", JSONArray(connectorIds.toList()))
        .put("setupDistros", JSONArray(setupDistros.toList()))

    companion object {
        val ID = Regex("[a-z0-9][a-z0-9-]{0,63}")
        const val MAX = 200
        const val MAX_BODY = 8000

        fun fromJson(o: JSONObject): Skill? {
            val id = o.optString("id")
            if (!ID.matches(id)) return null
            val connectors = o.optJSONArray("connectorIds")
            val distros = o.optJSONArray("setupDistros")
            return Skill(
                id = id,
                name = o.optString("name").ifBlank { id }.take(60),
                description = o.optString("description").take(400),
                instructions = o.optString("instructions").take(MAX_BODY),
                builtin = o.optBoolean("builtin", false),
                enabled = o.optBoolean("enabled", true),
                source = o.optString("source"),
                setupDone = o.optBoolean("setupDone", false),
                connectorIds = if (connectors == null) emptySet()
                else (0 until connectors.length()).map { connectors.optString(it) }.filter { it.isNotBlank() }.toSet(),
                setupDistros = if (distros == null) emptySet()
                else (0 until distros.length()).map { distros.optString(it) }.filter { it.isNotBlank() }.toSet()
            )
        }

        /** Lowercase slug safe in ids and paths; collisions in [taken] get a numeric suffix. */
        fun slug(name: String, taken: Set<String>): String {
            val base = name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(40).trim('-').ifEmpty { "skill" }
            var candidate = base
            var n = 2
            while (candidate in taken) candidate = "$base-${n++}"
            return candidate
        }

        /**
         * Parses a SKILL.md: a leading `---` front-matter block with `name:` and `description:` (both
         * required), the remainder is the [instructions] body. Returns null when the required metadata
         * is missing or malformed. The resulting skill is marked imported ([builtin] = false).
         */
        fun parseSkillMd(text: String, taken: Set<String> = emptySet()): Skill? {
            val lines = text.replace("\r\n", "\n").replace("\r", "\n").split("\n")
            if (lines.firstOrNull()?.trim() != "---") return null
            val end = (1 until lines.size).firstOrNull { lines[it].trim() == "---" } ?: return null
            val meta = HashMap<String, String>()
            for (i in 1 until end) {
                val colon = lines[i].indexOf(':')
                if (colon <= 0) continue
                val key = lines[i].substring(0, colon).trim().lowercase()
                var value = lines[i].substring(colon + 1).trim()
                if (value.length >= 2 && ((value.first() == '"' && value.last() == '"') || (value.first() == '\'' && value.last() == '\''))) {
                    value = value.substring(1, value.length - 1)
                }
                if (key.isNotEmpty()) meta[key] = value
            }
            val name = meta["name"]?.trim().orEmpty()
            val description = meta["description"]?.trim().orEmpty()
            if (name.isBlank() || description.isBlank()) return null
            val body = lines.subList(end + 1, lines.size).joinToString("\n").trim()
            return Skill(
                id = slug(name, taken),
                name = name.take(60),
                description = description.take(400),
                instructions = body.take(MAX_BODY),
                builtin = false,
                source = "import"
            )
        }
    }
}
