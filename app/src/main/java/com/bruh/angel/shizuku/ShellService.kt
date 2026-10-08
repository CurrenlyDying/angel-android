package com.bruh.angel.shizuku

import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import androidx.annotation.Keep
import com.bruh.angel.linuxenv.LinuxDistro
import com.bruh.angel.linuxenv.LinuxInstaller
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.system.exitProcess

/**
 * Runs inside the Shizuku user-service process as the ADB shell user (uid 2000).
 * Shizuku constructs this Binder; it is NOT an Android Service. No root APIs or su.
 */
@Keep
class ShellService : IShellService.Stub() {
    private val processLock = Any()
    private var process: Process? = null
    private var processGroup = 0
    private val installer = LinuxInstaller()
    private val reader = Executors.newCachedThreadPool()
    private val terminals = ConcurrentHashMap<Int, Terminal>()
    private val nextTerminal = AtomicInteger(1)
    private val screenLock = Any()
    private val spawns = ConcurrentHashMap<Int, Spawn>()
    private val nextSpawn = AtomicInteger(1)

    private class Spawn(val process: Process, val pgid: Int) {
        @Volatile var exit = -1
    }

    private class Terminal(
        val process: Process,
        val pgid: Int,
        val distro: LinuxDistro?,
        val input: InputStream,
        val output: OutputStream
    )

    override fun uid(): Int = Os.getuid()

    override fun destroy() {
        cancel()
        terminals.keys.toList().forEach { closeTerminal(it) }
        spawns.keys.toList().forEach { killSpawn(it) }
        exitProcess(0)
    }

    override fun cancel() = synchronized(processLock) {
        if (processGroup > 0) runCatching { Os.kill(-processGroup, OsConstants.SIGKILL) }
        processGroup = 0
        process?.destroyForcibly()
        process = null
    }

    /** Binder only carries a few exception types across processes; anything else would reach the app as a vague error. */
    private inline fun <T> guarded(block: () -> T): T = try {
        block()
    } catch (e: Exception) {
        when (e) {
            is IllegalStateException, is IllegalArgumentException, is SecurityException,
            is NullPointerException, is UnsupportedOperationException -> throw e
            else -> throw IllegalStateException("${e.javaClass.simpleName}: ${e.message}")
        }
    }

    override fun linuxStatus(distro: String): String = guarded {
        requireShell()
        installer.status(LinuxDistro.parse(distro))
    }

    override fun linuxInstalled(): String = guarded {
        requireShell()
        installer.installed().joinToString(",") { it.id }
    }

    @Synchronized
    override fun installArtifact(distro: String, name: String, archive: ParcelFileDescriptor) = guarded {
        requireShell()
        installer.install(LinuxDistro.parse(distro), name, archive)
    }

    @Synchronized
    override fun finishInstall(distro: String): String = guarded {
        requireShell()
        val d = LinuxDistro.parse(distro)
        installer.commit(d)
        try {
            val result = runCommand("/bin/uname -m && /bin/cat /etc/os-release", d)
            check(result.getInt("exitCode") == 0) { "${d.label} smoke test failed: ${result.getString("output")}" }
            installer.markReady(d)
            "${installer.status(d)}\n${result.getString("output")}"
        } catch (e: Exception) {
            installer.rollback(d)
            throw e
        }
    }

    @Synchronized
    override fun removeLinux(distro: String) = guarded {
        requireShell()
        val d = LinuxDistro.parse(distro)
        terminals.filterValues { it.distro == d }.keys.forEach { closeTerminal(it) }
        installer.remove(d)
    }

    @Synchronized
    override fun execute(command: String, linux: Boolean, distro: String): Bundle {
        requireShell()
        require(command.isNotBlank() && command.length <= 8000)
        val d = if (linux) LinuxDistro.fromId(distro).also { check(linuxReady(it)) { "Install ${it.label} in Settings first" } } else null
        return runCommand(command, d)
    }

    private fun requireShell() {
        check(Os.getuid() == 2000) { "Only Shizuku ADB shell uid 2000 is supported; root mode is refused" }
    }

    private fun linuxReady(distro: LinuxDistro) = installer.status(distro).startsWith("Ready:")

    /** Fixed wrapper: prints its PID (= new process-group id) then execs the real command (passed as argv). */
    private val wrapper = listOf("/system/bin/setsid", "/system/bin/sh", "-c", "echo $$; exec \"\$@\"", "angel")

    private fun prootArgs(home: File, command: List<String>, env: List<String> = emptyList()): List<String> {
        val runtime = File(home, "runtime")
        return listOf(
            File(runtime, "bin/proot").path, "--kill-on-exit", "--link2symlink", "-0",
            "-r", File(home, "rootfs").path,
            "-b", "/dev", "-b", "/proc", "-b", "/sys",
            "-b", "/storage/emulated/0:/sdcard",
            "-w", "/root",
            "/usr/bin/env", "-i", "HOME=/root", "USER=root", "TERM=dumb", "LANG=C.UTF-8",
            "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
        ) + env + command
    }

    private fun prootEnv(home: File): Map<String, String> {
        val runtime = File(home, "runtime")
        return mapOf(
            "PROOT_TMP_DIR" to File(home, "tmp").path,
            "PROOT_LOADER" to File(runtime, "libexec/proot/loader").path,
            "PROOT_LOADER_32" to File(runtime, "libexec/proot/loader32").path,
            "PROOT_NO_SECCOMP" to "1",
            "LD_LIBRARY_PATH" to File(runtime, "lib").path
        )
    }

    private fun runCommand(command: String, distro: LinuxDistro?): Bundle {
        val home = distro?.let { installer.dir(it) }
        val args = if (home != null) prootArgs(home, listOf("/bin/sh", "-lc", command))
        else listOf("/system/bin/sh", "-c", command)
        val builder = ProcessBuilder(wrapper + args).redirectErrorStream(true)
            .directory(home ?: File("/data/local/tmp"))
        if (home != null) builder.environment().putAll(prootEnv(home))
        val p = synchronized(processLock) { builder.start().also { process = it } }
        p.outputStream.close()
        val output = ByteArrayOutputStream()
        val future = reader.submit {
            p.inputStream.bufferedReader().use { input ->
                val group = input.readLine()?.toIntOrNull() ?: error("Could not create command process group")
                synchronized(processLock) {
                    if (process === p) processGroup = group
                    else runCatching { Os.kill(-group, OsConstants.SIGKILL) }
                }
                val buffer = CharArray(2048)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    val bytes = String(buffer, 0, n).toByteArray(Charsets.UTF_8)
                    synchronized(output) {
                        if (output.size() < MAX_OUTPUT) output.write(bytes, 0, minOf(bytes.size, MAX_OUTPUT - output.size()))
                    }
                }
            }
        }
        var timedOut = false
        try {
            if (!p.waitFor(60, TimeUnit.SECONDS)) {
                timedOut = true
                cancel()
                p.waitFor(2, TimeUnit.SECONDS)
            }
            runCatching { future.get(2, TimeUnit.SECONDS) }
            val text = synchronized(output) { output.toString("UTF-8") }
            return Bundle().apply {
                putString("output", text +
                    (if (output.size() >= MAX_OUTPUT) "\n[Output truncated at 48 KB]" else "") +
                    (if (timedOut) "\n[60-second timeout]" else ""))
                putInt("exitCode", if (timedOut) 124 else runCatching { p.exitValue() }.getOrDefault(130))
            }
        } finally {
            cancel()
            runCatching { p.inputStream.close() }
            future.cancel(true)
        }
    }

    // ---- Interactive terminal sessions -------------------------------------------------------

    override fun openTerminal(linux: Boolean, distro: String, input: ParcelFileDescriptor, output: ParcelFileDescriptor): Int {
        requireShell()
        val d = if (linux) LinuxDistro.fromId(distro) else null
        try {
            if (d != null) check(linuxReady(d)) { "Install ${d.label} in Settings first" }
            check(terminals.size < MAX_TERMINALS) { "Too many open terminal sessions" }
        } catch (e: Exception) {
            input.close(); output.close()
            throw e
        }
        val home = d?.let { installer.dir(it) }
        // -i: interactive shell (prompts, survives SIGINT) even though stdin is a pipe, not a TTY.
        val args = if (home != null) prootArgs(home, listOf("/bin/sh", "-l", "-i")) else listOf("/system/bin/sh", "-i")
        val builder = ProcessBuilder(wrapper + args).redirectErrorStream(true)
            .directory(home ?: File("/data/local/tmp"))
        if (home != null) builder.environment().putAll(prootEnv(home))
        else builder.environment().apply { put("HOME", "/data/local/tmp"); put("TERM", "dumb") }
        val p = try {
            builder.start()
        } catch (e: Exception) {
            input.close(); output.close()
            throw e
        }
        val stdout = p.inputStream
        val pgid = readFirstLine(stdout)?.toIntOrNull()
        if (pgid == null) {
            p.destroyForcibly()
            input.close(); output.close()
            error("Could not start terminal")
        }
        val id = nextTerminal.getAndIncrement()
        val toClient = ParcelFileDescriptor.AutoCloseOutputStream(output)
        val fromClient = ParcelFileDescriptor.AutoCloseInputStream(input)
        terminals[id] = Terminal(p, pgid, d, fromClient, toClient)
        thread(isDaemon = true, name = "angel-term-out-$id") {
            try { pump(stdout, toClient) } catch (_: IOException) { } finally { closeTerminal(id) }
        }
        thread(isDaemon = true, name = "angel-term-in-$id") {
            try { pump(fromClient, p.outputStream) } catch (_: IOException) { } finally { runCatching { p.outputStream.close() } }
        }
        return id
    }

    override fun signalTerminal(id: Int, signal: Int) {
        requireShell()
        require(signal == OsConstants.SIGINT) { "Only SIGINT is supported" }
        val terminal = terminals[id] ?: return
        // Even without a TTY, mksh enables partial job control and starts each foreground job in its
        // own process group, so signal by session (setsid made the leader's pid the session id).
        // The leader (mksh itself, or proot for Linux) is skipped; interactive shells handle SIGINT.
        sessionMembers(terminal.pgid).filter { it != terminal.pgid }.forEach { runCatching { Os.kill(it, signal) } }
    }

    override fun closeTerminal(id: Int) {
        val terminal = terminals.remove(id) ?: return
        sessionMembers(terminal.pgid).forEach { runCatching { Os.kill(it, OsConstants.SIGKILL) } }
        runCatching { Os.kill(-terminal.pgid, OsConstants.SIGKILL) }
        terminal.process.destroyForcibly()
        runCatching { terminal.output.close() }
        runCatching { terminal.input.close() }
    }

    private fun readFirstLine(input: InputStream): String? {
        val bytes = ByteArrayOutputStream()
        while (bytes.size() < 32) {
            val b = input.read()
            if (b < 0) return null
            if (b == '\n'.code) return bytes.toString("UTF-8").trim()
            bytes.write(b)
        }
        return null
    }

    private fun pump(input: InputStream, output: OutputStream) {
        val buffer = ByteArray(8192)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            output.write(buffer, 0, n)
            output.flush()
        }
    }

    /** PIDs whose session id (field 6 of /proc/<pid>/stat) equals [sid]. */
    private fun sessionMembers(sid: Int): List<Int> = File("/proc").listFiles().orEmpty().mapNotNull { dir ->
        val pid = dir.name.toIntOrNull() ?: return@mapNotNull null
        runCatching {
            val stat = File(dir, "stat").readText()
            // Fields after the parenthesised command: state, ppid, pgrp, session, ...
            val fields = stat.substring(stat.lastIndexOf(')') + 2).split(' ')
            if (fields[3].toInt() == sid) pid else null
        }.getOrNull()
    }

    // ---- Long-running processes (MCP servers, installers) -----------------------------------

    override fun spawn(
        linux: Boolean, distro: String, command: String, env: Array<String>,
        stdin: ParcelFileDescriptor, stdout: ParcelFileDescriptor, stderr: ParcelFileDescriptor
    ): Int {
        fun closeAll() { runCatching { stdin.close() }; runCatching { stdout.close() }; runCatching { stderr.close() } }
        val d: LinuxDistro?
        try {
            requireShell()
            require(command.isNotBlank() && command.length <= 20_000) { "Invalid command" }
            require(env.size <= 32 && env.all { it.length <= 4096 && ENV_PAIR.matches(it) }) { "Invalid environment" }
            d = if (linux) LinuxDistro.fromId(distro).also { check(linuxReady(it)) { "Install ${it.label} in Settings first" } } else null
            check(spawns.values.count { it.exit < 0 } < MAX_SPAWNS) { "Too many background processes" }
        } catch (e: Exception) {
            closeAll()
            throw e
        }
        val home = d?.let { installer.dir(it) }
        val args = if (home != null) prootArgs(home, listOf("/bin/sh", "-c", command), env.toList())
        else listOf("/system/bin/sh", "-c", command)
        val builder = ProcessBuilder(wrapper + args).directory(home ?: File("/data/local/tmp"))
        if (home != null) builder.environment().putAll(prootEnv(home))
        else builder.environment().apply {
            put("HOME", "/data/local/tmp"); put("TERM", "dumb")
            env.forEach { put(it.substringBefore('='), it.substringAfter('=')) }
        }
        val p = try {
            builder.start()
        } catch (e: Exception) {
            closeAll()
            throw e
        }
        val pgid = readFirstLine(p.inputStream)?.toIntOrNull()
        if (pgid == null) {
            p.destroyForcibly()
            closeAll()
            error("Could not start the process")
        }
        val id = nextSpawn.getAndIncrement()
        val spawn = Spawn(p, pgid)
        spawns[id] = spawn
        val toClient = ParcelFileDescriptor.AutoCloseOutputStream(stdout)
        val errClient = ParcelFileDescriptor.AutoCloseOutputStream(stderr)
        val fromClient = ParcelFileDescriptor.AutoCloseInputStream(stdin)
        val outPump = thread(isDaemon = true, name = "angel-spawn-out-$id") {
            try { pump(p.inputStream, toClient) } catch (_: IOException) { }
        }
        val errPump = thread(isDaemon = true, name = "angel-spawn-err-$id") {
            try { pump(p.errorStream, errClient) } catch (_: IOException) { }
        }
        thread(isDaemon = true, name = "angel-spawn-in-$id") {
            try { pump(fromClient, p.outputStream) } catch (_: IOException) { } finally { runCatching { p.outputStream.close() } }
        }
        thread(isDaemon = true, name = "angel-spawn-wait-$id") {
            spawn.exit = p.waitFor()
            // Nothing the process started may outlive it.
            runCatching { Os.kill(-pgid, OsConstants.SIGKILL) }
            runCatching { outPump.join(3000) }
            runCatching { errPump.join(3000) }
            runCatching { toClient.close() }
            runCatching { errClient.close() }
            runCatching { fromClient.close() }
        }
        return id
    }

    override fun spawnExit(id: Int): Int {
        requireShell()
        val spawn = spawns[id] ?: return -2
        if (spawn.exit >= 0) spawns.remove(id)
        return spawn.exit
    }

    override fun killSpawn(id: Int) {
        requireShell()
        val spawn = spawns[id] ?: return
        sessionMembers(spawn.pgid).forEach { runCatching { Os.kill(it, OsConstants.SIGKILL) } }
        runCatching { Os.kill(-spawn.pgid, OsConstants.SIGKILL) }
        spawn.process.destroyForcibly()
    }

    // ---- Screen reading ----------------------------------------------------------------------

    override fun readScreen(filter: String): String {
        requireShell()
        require(filter.length <= 200) { "Filter too long" }
        synchronized(screenLock) {
            val dump = File("/data/local/tmp/angel-screen.xml")
            val log = File("/data/local/tmp/angel-screen.log")
            try {
                dump.delete()
                val p = ProcessBuilder("/system/bin/uiautomator", "dump", dump.path)
                    .redirectErrorStream(true).redirectOutput(log).start()
                p.outputStream.close()
                if (!p.waitFor(25, TimeUnit.SECONDS)) {
                    p.destroyForcibly()
                    error("Reading the screen timed out")
                }
                if (!dump.isFile || dump.length() == 0L) {
                    val reason = if (log.isFile) log.readText().trim().take(300) else ""
                    error("Could not read the screen. $reason".trim())
                }
                return dump.inputStream().use { ScreenReader.summarize(it, filter) }
            } finally {
                // The dump contains on-screen text; never leave it behind.
                dump.delete()
                log.delete()
            }
        }
    }

    private companion object {
        const val MAX_OUTPUT = 48_000
        const val MAX_TERMINALS = 4
        const val MAX_SPAWNS = 16
        val ENV_PAIR = Regex("[A-Za-z_][A-Za-z0-9_]*=.*", RegexOption.DOT_MATCHES_ALL)
    }
}

