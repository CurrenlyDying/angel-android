package com.bruh.angel.agent

import org.json.JSONArray
import org.json.JSONObject

/** Turns the JSON Schemas MCP servers publish into something every model provider accepts. */
object ToolSchemas {
    private const val MAX_DEPTH = 8
    private const val MAX_DESCRIPTION = 1000

    /** Keys Gemini's OpenAPI-subset Schema understands; anything else makes the whole request fail with HTTP 400. */
    private val GEMINI_KEYS = setOf(
        "type", "format", "description", "nullable", "enum", "properties", "required", "items",
        "minItems", "maxItems", "minimum", "maximum", "anyOf", "title", "default", "minLength", "maxLength", "pattern"
    )

    fun describe(description: String, fallback: String) =
        description.trim().ifEmpty { fallback }.let { if (it.length > MAX_DESCRIPTION) it.take(MAX_DESCRIPTION) + "…" else it }

    /** The `parameters` object for a declaration, or null when the tool takes no arguments and the provider needs it omitted. */
    fun parameters(schema: JSONObject, gemini: Boolean): JSONObject? {
        val defs = (schema.optJSONObject("\$defs") ?: schema.optJSONObject("definitions"))
        val clean = clean(schema, defs, gemini, 0) ?: JSONObject()
        clean.put("type", "object")
        if (!clean.has("properties")) clean.put("properties", JSONObject())
        if (gemini && clean.getJSONObject("properties").length() == 0) return null
        return clean
    }

    private fun clean(node: JSONObject, defs: JSONObject?, gemini: Boolean, depth: Int): JSONObject? {
        if (depth > MAX_DEPTH) return JSONObject().put("type", "string")
        var source = node
        source.optString("\$ref").takeIf { it.isNotEmpty() }?.let { ref ->
            val target = defs?.optJSONObject(ref.substringAfterLast('/'))
            source = target ?: return JSONObject().put("type", "string")
        }
        val out = JSONObject()
        source.optJSONArray("allOf")?.let { all ->
            for (i in 0 until all.length()) {
                val part = clean(all.optJSONObject(i) ?: continue, defs, gemini, depth + 1) ?: continue
                merge(out, part)
            }
        }
        val keys = source.keys().asSequence().toList()
        for (key in keys) {
            val value = source.get(key)
            when (key) {
                "\$schema", "\$id", "\$ref", "\$defs", "definitions", "allOf", "examples", "\$comment" -> {}
                "oneOf", "anyOf" -> {
                    val variants = JSONArray()
                    val array = value as? JSONArray ?: continue
                    var nullable = false
                    for (i in 0 until array.length()) {
                        val variant = array.optJSONObject(i) ?: continue
                        if (variant.optString("type") == "null") { nullable = true; continue }
                        clean(variant, defs, gemini, depth + 1)?.let { variants.put(it) }
                    }
                    if (variants.length() == 1) merge(out, variants.getJSONObject(0))
                    else if (variants.length() > 1) out.put("anyOf", variants)
                    if (nullable && gemini) out.put("nullable", true)
                }
                "type" -> {
                    if (value is JSONArray) {
                        val types = (0 until value.length()).map { value.optString(it) }
                        types.firstOrNull { it != "null" }?.let { out.put("type", it) }
                        if ("null" in types && gemini) out.put("nullable", true)
                    } else out.put("type", value)
                }
                "properties" -> {
                    val props = value as? JSONObject ?: continue
                    val cleaned = JSONObject()
                    props.keys().forEach { name ->
                        val child = props.optJSONObject(name) ?: return@forEach
                        clean(child, defs, gemini, depth + 1)?.let { cleaned.put(name, it) }
                    }
                    out.put("properties", cleaned)
                }
                "items" -> (value as? JSONObject)?.let { clean(it, defs, gemini, depth + 1) }?.let { out.put("items", it) }
                "description" -> out.put("description", describe(value.toString(), ""))
                else -> if (!gemini || key in GEMINI_KEYS) out.put(key, value)
            }
        }
        if (gemini) {
            if (out.optString("type") == "array" && !out.has("items")) out.put("items", JSONObject().put("type", "string"))
            if (!out.has("type") && !out.has("anyOf")) out.put("type", if (out.has("properties")) "object" else "string")
            out.optJSONArray("required")?.let { req ->
                val props = out.optJSONObject("properties")
                val kept = JSONArray()
                for (i in 0 until req.length()) if (props?.has(req.optString(i)) == true) kept.put(req.optString(i))
                if (kept.length() == 0) out.remove("required") else out.put("required", kept)
            }
            if (out.optString("type") == "object" && !out.has("properties")) return JSONObject().put("type", "string")
            if (out.has("enum") && out.optString("type") != "string") {
                // Gemini only accepts string enums.
                out.remove("enum")
            }
        }
        return out
    }

    private fun merge(into: JSONObject, from: JSONObject) {
        from.keys().forEach { key ->
            when (key) {
                "properties" -> {
                    val target = into.optJSONObject("properties") ?: JSONObject().also { into.put("properties", it) }
                    val source = from.getJSONObject("properties")
                    source.keys().forEach { target.put(it, source.get(it)) }
                }
                "required" -> {
                    val target = into.optJSONArray("required") ?: JSONArray().also { into.put("required", it) }
                    val source = from.getJSONArray("required")
                    for (i in 0 until source.length()) target.put(source.get(i))
                }
                else -> if (!into.has(key)) into.put(key, from.get(key))
            }
        }
    }
}
