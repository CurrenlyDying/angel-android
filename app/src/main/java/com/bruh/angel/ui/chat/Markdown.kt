package com.bruh.angel.ui.chat

import android.content.ClipData
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.foundation.background
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bruh.angel.R
import com.bruh.angel.ui.theme.AngelTheme
import com.bruh.angel.ui.theme.mono
import kotlinx.coroutines.launch

sealed interface MdBlock {
    data class Prose(val text: String) : MdBlock
    data class Code(val code: String, val language: String) : MdBlock
    data class Table(val header: List<String>, val rows: List<List<String>>) : MdBlock
}

private val TABLE_SEPARATOR = Regex("^\\s*\\|?\\s*:?-{2,}:?\\s*(\\|\\s*:?-{2,}:?\\s*)*\\|?\\s*$")

private fun isTableRow(line: String): Boolean = line.trim().let { it.startsWith("|") && it.count { c -> c == '|' } >= 2 }

private fun splitCells(line: String): List<String> =
    line.trim().removePrefix("|").removeSuffix("|").split('|').map { it.trim() }

/**
 * Splits Markdown into prose, fenced code blocks and pipe tables. Unterminated fences run to the end.
 */
fun parseMarkdownBlocks(text: String): List<MdBlock> {
    val blocks = mutableListOf<MdBlock>()
    val prose = StringBuilder()
    var code: StringBuilder? = null
    var language = ""
    fun flushProse() {
        val value = prose.toString().trim('\n')
        if (value.isNotBlank()) blocks += MdBlock.Prose(value)
        prose.setLength(0)
    }
    val lines = text.lines()
    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        val fence = line.trimStart().startsWith("```")
        when {
            fence && code == null -> {
                flushProse()
                code = StringBuilder()
                language = line.trimStart().removePrefix("```").trim()
            }
            fence -> {
                blocks += MdBlock.Code(code.toString().trimEnd('\n'), language)
                code = null
            }
            code != null -> code.append(line).append('\n')
            isTableRow(line) && i + 1 < lines.size && TABLE_SEPARATOR.matches(lines[i + 1]) && lines[i + 1].contains('|') -> {
                flushProse()
                val header = splitCells(line)
                val rows = mutableListOf<List<String>>()
                i += 2
                while (i < lines.size && isTableRow(lines[i])) {
                    val cells = splitCells(lines[i])
                    rows += List(header.size) { cells.getOrElse(it) { "" } }
                    i++
                }
                blocks += MdBlock.Table(header, rows)
                continue
            }
            else -> prose.append(line).append('\n')
        }
        i++
    }
    code?.let { blocks += MdBlock.Code(it.toString().trimEnd('\n'), language) }
    flushProse()
    return blocks
}

private val INLINE = Regex("(`[^`\\n]+`)|(\\*\\*[^*\\n]+\\*\\*)|(__[^_\\n]+__)|(\\*[^*\\s][^*\\n]*\\*)|(\\[[^\\]\\n]+]\\([^)\\s]+\\))")
private val HEADING = Regex("^(#{1,6})\\s+(.*)$")
private val BULLET = Regex("^(\\s*)[-*+]\\s+(.*)$")
private val NUMBERED = Regex("^(\\s*)(\\d{1,3})[.)]\\s+(.*)$")

fun markdownProse(text: String, codeBackground: Color, linkColor: Color): AnnotatedString = buildAnnotatedString {
    text.lines().forEachIndexed { index, line ->
        if (index > 0) append('\n')
        val heading = HEADING.find(line)
        val bullet = BULLET.find(line)
        val numbered = NUMBERED.find(line)
        when {
            heading != null -> {
                val level = heading.groupValues[1].length
                withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, fontSize = when { level <= 1 -> 21.sp; level == 2 -> 19.sp; else -> 17.sp })) {
                    inline(heading.groupValues[2], codeBackground, linkColor)
                }
            }
            bullet != null -> {
                append("  ".repeat(bullet.groupValues[1].length / 2))
                append("•  ")
                inline(bullet.groupValues[2], codeBackground, linkColor)
            }
            numbered != null -> {
                append("  ".repeat(numbered.groupValues[1].length / 2))
                withStyle(SpanStyle(fontWeight = FontWeight.Medium)) { append(numbered.groupValues[2]); append(".  ") }
                inline(numbered.groupValues[3], codeBackground, linkColor)
            }
            line.trim().matches(Regex("-{3,}|\\*{3,}")) -> append("──────────")
            line.startsWith("> ") -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { inline(line.drop(2), codeBackground, linkColor) }
            else -> inline(line, codeBackground, linkColor)
        }
    }
}

private fun AnnotatedString.Builder.inline(text: String, codeBackground: Color, linkColor: Color) {
    var last = 0
    for (match in INLINE.findAll(text)) {
        append(text.substring(last, match.range.first))
        val token = match.value
        when {
            token.startsWith("`") -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = codeBackground, fontSize = 14.sp)) {
                append(token.trim('`'))
            }
            token.startsWith("**") || token.startsWith("__") -> withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) {
                append(token.substring(2, token.length - 2))
            }
            token.startsWith("*") -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                append(token.substring(1, token.length - 1))
            }
            else -> {
                val label = token.substringAfter('[').substringBefore("](")
                val url = token.substringAfter("](").dropLast(1)
                // Only open web links; anything else is shown as plain text.
                if (url.startsWith("https://") || url.startsWith("http://")) {
                    withLink(LinkAnnotation.Url(url, TextLinkStyles(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)))) {
                        append(label)
                    }
                } else append(label)
            }
        }
        last = match.range.last + 1
    }
    append(text.substring(last))
}

@Composable
fun MarkdownText(text: String, modifier: Modifier = Modifier) {
    val blocks = remember(text) { parseMarkdownBlocks(text) }
    val codeBackground = MaterialTheme.colorScheme.surfaceContainerHighest
    val linkColor = MaterialTheme.colorScheme.primary
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        blocks.forEach { block ->
            when (block) {
                is MdBlock.Prose -> Text(
                    text = remember(block.text, codeBackground, linkColor) { markdownProse(block.text, codeBackground, linkColor) },
                    style = MaterialTheme.typography.bodyLarge
                )
                is MdBlock.Code -> CodeBlock(block.code, block.language)
                is MdBlock.Table -> TableBlock(block)
            }
        }
    }
}

@Composable
private fun CodeBlock(code: String, language: String) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val colors = AngelTheme.colors
    Surface(
        shape = MaterialTheme.shapes.small,
        color = colors.terminalBackground,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth().padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(language.ifEmpty { "code" }, style = MaterialTheme.typography.labelSmall, color = colors.terminalDim)
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("Code", code))) } }, modifier = Modifier.size(36.dp)) {
                    Icon(painterResource(R.drawable.ic_copy), contentDescription = "Copy code", modifier = Modifier.size(16.dp), tint = colors.terminalDim)
                }
            }
            Text(
                text = code,
                modifier = Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
                style = MaterialTheme.typography.bodySmall.mono().copy(fontSize = 13.sp, lineHeight = 19.sp),
                color = colors.terminalForeground,
                softWrap = false
            )
        }
    }
}

/**
 * Pipe table. A custom layout measures every cell, sizes each column to its widest cell (capped) and
 * each row to its tallest cell, so rows stay aligned even when a cell wraps. Scrolls sideways when wide.
 */
@Composable
private fun TableBlock(table: MdBlock.Table) {
    val codeBackground = MaterialTheme.colorScheme.surfaceContainerHighest
    val linkColor = MaterialTheme.colorScheme.primary
    val zebra = MaterialTheme.colorScheme.surfaceContainer
    val divider = MaterialTheme.colorScheme.outlineVariant
    val columns = table.header.size
    val allRows = listOf(table.header) + table.rows
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Layout(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            content = {
                allRows.forEachIndexed { r, row ->
                    repeat(columns) { c ->
                        Cell(
                            row.getOrElse(c) { "" }, bold = r == 0, codeBackground = codeBackground, linkColor = linkColor,
                            background = if (r > 0 && r % 2 == 0) zebra else Color.Transparent,
                            rule = if (r == 0) divider else null
                        )
                    }
                }
            }
        ) { measurables, _ ->
            val maxCell = 300.dp.roundToPx()
            val widths = IntArray(columns)
            measurables.forEachIndexed { i, m -> val c = i % columns; widths[c] = maxOf(widths[c], minOf(m.maxIntrinsicWidth(Constraints.Infinity), maxCell)) }
            val heights = IntArray(allRows.size)
            measurables.forEachIndexed { i, m -> val r = i / columns; heights[r] = maxOf(heights[r], m.minIntrinsicHeight(widths[i % columns])) }
            val placeables = measurables.mapIndexed { i, m -> m.measure(Constraints.fixed(widths[i % columns], heights[i / columns])) }
            val totalWidth = widths.sum()
            layout(totalWidth, heights.sum()) {
                var y = 0
                allRows.indices.forEach { r ->
                    var x = 0
                    repeat(columns) { c ->
                        placeables[r * columns + c].place(x, y)
                        x += widths[c]
                    }
                    y += heights[r]
                }
            }
        }
    }
}

/** Zebra rows and the header rule are drawn by the cells themselves (they know their row). */
@Composable
private fun Cell(text: String, bold: Boolean, codeBackground: Color, linkColor: Color, background: Color, rule: Color?) {
    Text(
        text = remember(text, codeBackground, linkColor) { markdownProse(text, codeBackground, linkColor) },
        modifier = Modifier
            .background(background)
            .then(if (rule != null) Modifier.drawBehind { drawLine(rule, Offset(0f, size.height), Offset(size.width, size.height), 1.dp.toPx()) } else Modifier)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        style = if (bold) MaterialTheme.typography.labelLarge else MaterialTheme.typography.bodyMedium,
        color = if (bold) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
    )
}
