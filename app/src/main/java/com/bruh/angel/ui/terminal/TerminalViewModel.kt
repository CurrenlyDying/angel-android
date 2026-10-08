package com.bruh.angel.ui.terminal

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.bruh.angel.linuxenv.LinuxDistro
import com.bruh.angel.shizuku.ShizukuBridge
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.IOException
import java.io.InputStreamReader
import kotlin.concurrent.thread

/**
 * Persistent shell sessions (Android uid-2000 shell and the selected Linux distro) that survive fold/unfold and
 * navigating away from the terminal page. Commands typed here are the user's own and run without
 * the agent approval flow.
 */
class TerminalViewModel(application: Application) : AndroidViewModel(application) {
    enum class Mode(val title: String) { ANDROID("Android"), LINUX("Linux") }

    private fun banner(mode: Mode) = when (mode) {
        Mode.ANDROID -> "Android shell · uid 2000 via Shizuku (not root)"
        Mode.LINUX -> "${distro.label} · proot (simulated root) · phone storage at /sdcard"
    }

    data class TermState(val lines: List<String> = emptyList(), val running: Boolean = false, val starting: Boolean = false)

    private class Session {
        val buffer = TerminalBuffer()
        val state = MutableStateFlow(TermState())
        @Volatile var handle: ShizukuBridge.TerminalHandle? = null
        var reader: Job? = null
    }

    val bridge = ShizukuBridge.shared(application)
    private val _mode = MutableStateFlow(Mode.ANDROID)
    val mode: StateFlow<Mode> = _mode.asStateFlow()
    private val sessions = Mode.entries.associateWith { Session() }
    private val history = ArrayList<String>()
    private var distro = LinuxDistro.DEFAULT

    /** Switching distro in Settings ends the old Linux shell; the next one starts in the new distro. */
    fun useDistro(d: LinuxDistro) {
        if (d == distro) return
        distro = d
        reset(Mode.LINUX, start = _mode.value == Mode.LINUX)
    }

    fun state(mode: Mode): StateFlow<TermState> = sessions.getValue(mode).state

    fun select(mode: Mode) { _mode.value = mode }

    fun ensureStarted(mode: Mode) {
        val s = sessions.getValue(mode)
        if (s.handle == null && !s.state.value.starting && s.reader?.isActive != true) start(mode)
    }

    private fun start(mode: Mode) {
        val s = sessions.getValue(mode)
        s.state.value = s.state.value.copy(starting = true)
        viewModelScope.launch {
            try {
                val handle = bridge.openTerminal(mode == Mode.LINUX, distro)
                s.handle = handle
                append(s, "[${banner(mode)}]\n[No TTY: full-screen programs (vim, top, less) won't work. Ctrl-C interrupts.]\n")
                s.reader = viewModelScope.launch(Dispatchers.IO) { readLoop(s, handle) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                append(s, "[Could not start ${mode.title} shell: ${e.message ?: e.javaClass.simpleName}]\n")
            } finally {
                s.state.value = s.state.value.copy(starting = false, running = s.handle != null)
            }
        }
    }

    private fun readLoop(s: Session, handle: ShizukuBridge.TerminalHandle) {
        val reader = InputStreamReader(handle.output, Charsets.UTF_8)
        val buffer = CharArray(4096)
        var lastEmit = 0L
        try {
            while (true) {
                val n = reader.read(buffer)
                if (n < 0 || s.handle !== handle) break
                synchronized(s.buffer) { s.buffer.append(String(buffer, 0, n)) }
                val now = SystemClock.uptimeMillis()
                if (now - lastEmit > 40 || !reader.ready()) {
                    emit(s)
                    lastEmit = now
                }
            }
        } catch (_: IOException) {
        } finally {
            if (s.handle === handle) {
                s.handle = null
                append(s, "\n[Session ended. Tap Restart to open a new one.]\n")
            }
            s.state.value = s.state.value.copy(running = s.handle != null)
        }
    }

    private fun emit(s: Session) {
        val lines = synchronized(s.buffer) { s.buffer.snapshot() }
        s.state.value = s.state.value.copy(lines = lines, running = s.handle != null)
    }

    private fun append(s: Session, text: String) {
        synchronized(s.buffer) { s.buffer.append(text) }
        emit(s)
    }

    private fun current() = sessions.getValue(_mode.value)

    fun send(line: String) {
        val s = current()
        val handle = s.handle ?: return
        if (line.isNotBlank()) {
            history.remove(line)
            history.add(line)
            if (history.size > 200) history.removeAt(0)
        }
        append(s, "$PROMPT$line\n") // no TTY, so the shell doesn't echo input; the prompt marks typed lines
        viewModelScope.launch(Dispatchers.IO) {
            try {
                handle.input.write((line + "\n").toByteArray(Charsets.UTF_8))
                handle.input.flush()
            } catch (e: IOException) {
                append(s, "[Shell is not accepting input]\n")
            }
        }
    }

    /** History entry [back] steps from the newest (1 = last command). */
    fun historyEntry(back: Int): String? = history.getOrNull(history.size - back)

    val historySize: Int get() = history.size

    fun interrupt() {
        val s = current()
        val handle = s.handle ?: return
        append(s, "^C\n")
        viewModelScope.launch(Dispatchers.IO) { handle.interrupt() }
    }

    /** Ctrl-D: closes the shell's stdin, which ends the session. */
    fun endOfInput() {
        val handle = current().handle ?: return
        viewModelScope.launch(Dispatchers.IO) { runCatching { handle.input.close() } }
    }

    fun clear() {
        val s = current()
        synchronized(s.buffer) { s.buffer.clear() }
        emit(s)
    }

    fun text(): String = current().let { s -> synchronized(s.buffer) { s.buffer.text() } }

    fun restart() = reset(_mode.value, start = true)

    private fun reset(mode: Mode, start: Boolean) {
        val s = sessions.getValue(mode)
        val old = s.handle
        s.handle = null
        s.reader?.cancel()
        if (old != null) thread(isDaemon = true) { old.close() }
        synchronized(s.buffer) { s.buffer.clear() }
        s.state.value = TermState()
        if (start) start(mode)
    }

    companion object {
        /** Prefix of lines the user typed (the UI colors them). */
        const val PROMPT = "$ "
    }

    override fun onCleared() {
        val open = sessions.values.mapNotNull { it.handle }
        sessions.values.forEach { it.handle = null }
        if (open.isNotEmpty()) thread(isDaemon = true) { open.forEach { it.close() } }
        super.onCleared()
    }
}



