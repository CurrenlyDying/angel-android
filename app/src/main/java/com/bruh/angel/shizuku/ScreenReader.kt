package com.bruh.angel.shizuku

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream

/** Turns a uiautomator XML dump into a compact element list a model can act on. */
object ScreenReader {
    private val BOUNDS = Regex("\\[(-?\\d+),(-?\\d+)]\\[(-?\\d+),(-?\\d+)]")

    fun summarize(input: InputStream, filter: String, maxElements: Int = 250): String {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(input, "UTF-8")
        val lines = StringBuilder()
        val packages = linkedSetOf<String>()
        var screen = ""
        var shown = 0
        var matched = 0
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType != XmlPullParser.START_TAG || parser.name != "node") continue
            fun attr(name: String) = parser.getAttributeValue(null, name).orEmpty()
            attr("package").takeIf { it.isNotEmpty() }?.let { packages += it }
            val bounds = BOUNDS.find(attr("bounds"))?.destructured?.toList()?.map { it.toInt() }
            if (screen.isEmpty() && bounds != null) screen = "${bounds[2] - bounds[0]}x${bounds[3] - bounds[1]}"
            val text = attr("text").replace(Regex("\\s+"), " ").trim()
            val desc = attr("content-desc").replace(Regex("\\s+"), " ").trim()
            val id = attr("resource-id").substringAfter(":id/")
            val cls = attr("class").substringAfterLast('.')
            val flags = buildList {
                if (attr("clickable") == "true") add("clickable")
                if (attr("long-clickable") == "true") add("long-clickable")
                if (attr("scrollable") == "true") add("scrollable")
                if (cls.contains("EditText")) add("editable")
                if (attr("checkable") == "true") add(if (attr("checked") == "true") "checked" else "unchecked")
                if (attr("selected") == "true") add("selected")
                if (attr("focused") == "true") add("focused")
                if (attr("enabled") == "false") add("disabled")
            }
            val actionable = flags.any { it in setOf("clickable", "long-clickable", "scrollable", "editable") }
            if (text.isEmpty() && desc.isEmpty() && !actionable) continue
            if (filter.isNotBlank() && listOf(text, desc, id).none { it.contains(filter, ignoreCase = true) }) continue
            matched++
            if (shown >= maxElements) continue
            shown++
            lines.append("- ").append(cls.ifEmpty { "View" })
            if (text.isNotEmpty()) lines.append(" \"").append(text.take(150)).append('"')
            if (desc.isNotEmpty() && desc != text) lines.append(" desc=\"").append(desc.take(150)).append('"')
            if (id.isNotEmpty()) lines.append(" id=").append(id)
            if (flags.isNotEmpty()) lines.append(" [").append(flags.joinToString(",")).append(']')
            if (bounds != null) lines.append(" tap=(").append((bounds[0] + bounds[2]) / 2).append(',')
                .append((bounds[1] + bounds[3]) / 2).append(')')
            lines.append('\n')
        }
        return buildString {
            append("Screen ").append(screen.ifEmpty { "?" })
            append(" | apps: ").append(packages.joinToString().ifEmpty { "unknown" })
            append(" | ").append(matched).append(" elements")
            if (filter.isNotBlank()) append(" matching \"").append(filter).append('"')
            if (matched > shown) append(" (first ").append(shown).append(" shown)")
            append('\n')
            append(lines.ifEmpty { "(no readable elements; the app may draw its own UI or the screen may be locked)\n" })
        }
    }
}

