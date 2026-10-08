package com.bruh.angel

import com.bruh.angel.ui.chat.MdBlock
import com.bruh.angel.ui.chat.parseMarkdownBlocks
import com.bruh.angel.ui.terminal.TerminalBuffer
import org.junit.Assert.assertEquals
import org.junit.Test

class TerminalBufferTest {
    @Test
    fun stripsAnsiAcrossChunkBoundaries() {
        val buffer = TerminalBuffer()
        buffer.append("\u001B[1;3")      // escape sequence split across reads
        buffer.append("2mgreen\u001B[0m text\n")
        buffer.append("\u001B]0;window title\u0007prompt$ ")
        assertEquals(listOf("green text", "prompt$ "), buffer.snapshot())
    }

    @Test
    fun carriageReturnOverwritesAndCrlfIsNewline() {
        val buffer = TerminalBuffer()
        buffer.append("10%\r55%\r100%\r\ndone\r\n")
        assertEquals(listOf("100%", "done"), buffer.snapshot())
    }

    @Test
    fun backspaceAndScrollbackLimit() {
        val buffer = TerminalBuffer(maxLines = 3)
        buffer.append("abc\b\bX\n")
        (1..5).forEach { buffer.append("line$it\n") }
        assertEquals(listOf("line3", "line4", "line5"), buffer.snapshot())
        buffer.clear()
        assertEquals(emptyList<String>(), buffer.snapshot())
    }
}

class MarkdownBlocksTest {
    @Test
    fun splitsProseAndFencedCode() {
        val blocks = parseMarkdownBlocks("Intro **bold**\n\n```sh\nls -la\necho hi\n```\nAfter")
        assertEquals(
            listOf(
                MdBlock.Prose("Intro **bold**"),
                MdBlock.Code("ls -la\necho hi", "sh"),
                MdBlock.Prose("After")
            ),
            blocks
        )
    }

    @Test
    fun unterminatedFenceRunsToEnd() {
        assertEquals(listOf(MdBlock.Code("x = 1", "")), parseMarkdownBlocks("```\nx = 1"))
    }
}

