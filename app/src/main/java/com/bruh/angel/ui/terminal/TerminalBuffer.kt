package com.bruh.angel.ui.terminal

/**
 * Scrollback for a pipe-based (non-TTY) shell. Strips ANSI/OSC escape sequences (state survives
 * chunk boundaries), handles CR overwrite and backspace, and keeps at most [maxLines] lines.
 * Not thread-safe; callers synchronize.
 */
class TerminalBuffer(private val maxLines: Int = 4000) {
    private enum class Esc { NONE, ESC, CSI, OSC, OSC_ESC }

    private val lines = ArrayDeque<String>()
    private val current = StringBuilder()
    private var esc = Esc.NONE
    private var carriageReturn = false

    fun append(text: String) {
        for (c in text) {
            when (esc) {
                Esc.ESC -> { esc = when (c) { '[' -> Esc.CSI; ']' -> Esc.OSC; else -> Esc.NONE }; continue }
                Esc.CSI -> { if (c in '@'..'~') esc = Esc.NONE; continue }
                Esc.OSC -> { esc = when (c) { '\u0007' -> Esc.NONE; '\u001B' -> Esc.OSC_ESC; else -> Esc.OSC }; continue }
                Esc.OSC_ESC -> { esc = Esc.NONE; continue }
                Esc.NONE -> Unit
            }
            when (c) {
                '\u001B' -> esc = Esc.ESC
                '\n' -> { newline(); carriageReturn = false }
                '\r' -> carriageReturn = true
                '\b' -> if (current.isNotEmpty()) current.setLength(current.length - 1)
                '\u0007', '\u0000' -> Unit
                else -> {
                    if (carriageReturn) { current.setLength(0); carriageReturn = false }
                    current.append(c)
                }
            }
        }
    }

    private fun newline() {
        lines.addLast(current.toString())
        current.setLength(0)
        while (lines.size > maxLines) lines.removeFirst()
    }

    fun snapshot(): List<String> = if (current.isEmpty()) lines.toList() else lines.toList() + current.toString()

    fun text(): String = snapshot().joinToString("\n")

    fun clear() {
        lines.clear()
        current.setLength(0)
        carriageReturn = false
    }
}

