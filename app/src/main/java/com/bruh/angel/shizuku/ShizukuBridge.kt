package com.bruh.angel.shizuku

import com.bruh.angel.linuxenv.LinuxDistro
import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import rikka.shizuku.Shizuku
import java.io.InputStream
import java.io.OutputStream

class ShizukuBridge(context: Context) {
    private val _status = MutableStateFlow("Shizuku not connected")
    val status = _status.asStateFlow()
    private var ready = CompletableDeferred<IShellService>()
    @Volatile private var service: IShellService? = null
    private var binding = false
    private var closed = false
    private val handler = Handler(Looper.getMainLooper())
    private val retry = Runnable { if (!closed && Shizuku.pingBinder()) refresh() }
    // The version changes with every APK install, so Shizuku restarts the service with matching code.
    private val serviceVersion = runCatching {
        (context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime / 1000L).toInt()
    }.getOrDefault(3)
    private val args = Shizuku.UserServiceArgs(ComponentName(context, ShellService::class.java))
        .daemon(false).processNameSuffix("shell").debuggable(false).version(serviceVersion)
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            if (closed) return
            if (!binder.pingBinder()) { disconnected(); return }
            val api = IShellService.Stub.asInterface(binder)
            try {
                check(api.uid() == 2000) { "Root-mode Shizuku is not supported. Start Shizuku via ADB." }
                service = api; _status.value = "Connected: uid 2000 (ADB shell)"; ready.complete(api)
            } catch (e: Exception) {
                _status.value = e.message ?: "Service connection failed"; ready.completeExceptionally(e); binding = false
                if (!binder.isBinderAlive) scheduleRetry()
            }
        }
        override fun onServiceDisconnected(name: ComponentName) { disconnected() }
    }
    private fun disconnected() {
        if (closed) return
        service = null; binding = false
        ready.cancel(); ready = CompletableDeferred(); _status.value = "Shizuku disconnected; reconnect in Settings"
        scheduleRetry()
    }
    private fun scheduleRetry() { handler.removeCallbacks(retry); handler.postDelayed(retry, 1000) }
    private val received = Shizuku.OnBinderReceivedListener { handler.post { refresh() } }
    private val died = Shizuku.OnBinderDeadListener { handler.post { disconnected() } }
    private val permission = Shizuku.OnRequestPermissionResultListener { _, result ->
        handler.post {
            if (!closed) {
                if (result == PackageManager.PERMISSION_GRANTED) refresh() else _status.value = "Shizuku permission denied"
            }
        }
    }
    init {
        Shizuku.addBinderReceivedListenerSticky(received)
        Shizuku.addBinderDeadListener(died)
        Shizuku.addRequestPermissionResultListener(permission)
    }
    fun connect() {
        try {
            if (!Shizuku.pingBinder()) { _status.value = "Start Shizuku in the Shizuku app, then reconnect"; return }
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) Shizuku.requestPermission(41)
            else refresh()
        } catch (e: Exception) { _status.value = e.message ?: "Shizuku connection failed" }
    }
    private fun refresh() {
        if (closed) return
        try {
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) { _status.value = "Shizuku available; tap Connect to grant permission"; return }
            check(Shizuku.getUid() == 2000) { "Shizuku must run as uid 2000, not root" }
            if (service == null && !binding) {
                if (ready.isCompleted) ready = CompletableDeferred()
                binding = true; _status.value = "Connecting to Shizuku shell service…"
                Shizuku.bindUserService(args, connection)
                handler.postDelayed({
                    if (!closed && service == null && binding) {
                        binding = false
                        runCatching { Shizuku.unbindUserService(args, connection, false) }
                        scheduleRetry()
                    }
                }, 5000)
            }
        } catch (e: Exception) { binding = false; _status.value = e.message ?: "Shizuku unavailable" }
    }
    suspend fun api(): IShellService = withContext(Dispatchers.Main.immediate) {
        withTimeout(15_000) {
            check(!closed) { "Shizuku connection is closed" }
            service ?: if (binding) ready.await() else error("Connect Shizuku in Settings first")
        }
    }
    suspend fun execute(command: String, linux: Boolean, distro: LinuxDistro = LinuxDistro.DEFAULT) =
        withContext(Dispatchers.IO) { api().execute(command, linux, distro.id) }
    suspend fun readScreen(filter: String): String = withContext(Dispatchers.IO) { api().readScreen(filter) }
    suspend fun cancel() = withContext(Dispatchers.IO) { runCatching { service?.cancel() }; Unit }

    /** Client side of a persistent shell session running in the Shizuku process. */
    class TerminalHandle internal constructor(
        val id: Int,
        val input: OutputStream,
        val output: InputStream,
        private val api: IShellService
    ) {
        fun interrupt() { runCatching { api.signalTerminal(id, 2) } }
        fun close() {
            runCatching { input.close() }
            runCatching { output.close() }
            runCatching { api.closeTerminal(id) }
        }
    }

    suspend fun openTerminal(linux: Boolean, distro: LinuxDistro = LinuxDistro.DEFAULT): TerminalHandle {
        val api = api()
        return withContext(Dispatchers.IO) {
            val toShell = ParcelFileDescriptor.createPipe()   // [0] read end -> service stdin, [1] we write
            val fromShell = ParcelFileDescriptor.createPipe() // [0] we read, [1] write end -> service output
            try {
                val id = api.openTerminal(linux, distro.id, toShell[0], fromShell[1])
                TerminalHandle(
                    id,
                    ParcelFileDescriptor.AutoCloseOutputStream(toShell[1]),
                    ParcelFileDescriptor.AutoCloseInputStream(fromShell[0]),
                    api
                )
            } catch (e: Exception) {
                toShell[1].close(); fromShell[0].close()
                throw e
            } finally {
                // The service received its own duplicates of these ends.
                toShell[0].close(); fromShell[1].close()
            }
        }
    }

    /** A background process in the Shizuku service: stdin to write, stdout/stderr to read. */
    class SpawnHandle internal constructor(
        val id: Int,
        val stdin: OutputStream,
        val stdout: InputStream,
        val stderr: InputStream,
        private val api: IShellService
    ) {
        /** Exit code once the process has ended, otherwise null. Returns the code only once. */
        fun exitCode(): Int? = runCatching { api.spawnExit(id) }.getOrNull()?.takeIf { it >= 0 }
        fun kill() {
            runCatching { api.killSpawn(id) }
            runCatching { stdin.close() }
            runCatching { stdout.close() }
            runCatching { stderr.close() }
        }
    }

    /** Starts a long-running process. With [mergeStderr], stderr is delivered through [SpawnHandle.stdout]. */
    suspend fun spawn(
        command: String, linux: Boolean, distro: LinuxDistro = LinuxDistro.DEFAULT,
        env: List<String> = emptyList(), mergeStderr: Boolean = false
    ): SpawnHandle {
        val api = api()
        return withContext(Dispatchers.IO) {
            val toProcess = ParcelFileDescriptor.createPipe()
            val out = ParcelFileDescriptor.createPipe()
            val err = if (mergeStderr) null else ParcelFileDescriptor.createPipe()
            val errWrite = err?.get(1) ?: out[1].dup()
            try {
                val id = api.spawn(linux, distro.id, command, env.toTypedArray(), toProcess[0], out[1], errWrite)
                SpawnHandle(
                    id,
                    ParcelFileDescriptor.AutoCloseOutputStream(toProcess[1]),
                    ParcelFileDescriptor.AutoCloseInputStream(out[0]),
                    if (err != null) ParcelFileDescriptor.AutoCloseInputStream(err[0]) else java.io.ByteArrayInputStream(ByteArray(0)),
                    api
                )
            } catch (e: Exception) {
                toProcess[1].close(); out[0].close(); err?.get(0)?.close()
                throw e
            } finally {
                toProcess[0].close(); out[1].close(); errWrite.close()
            }
        }
    }

    fun close() {
        closed = true; handler.removeCallbacksAndMessages(null)
        Shizuku.removeBinderReceivedListener(received); Shizuku.removeBinderDeadListener(died)
        Shizuku.removeRequestPermissionResultListener(permission)
        runCatching { Shizuku.unbindUserService(args, connection, true) }
    }

    companion object {
        @Volatile private var instance: ShizukuBridge? = null

        /** One connection for the whole app (chat agent, terminal, Linux installer). */
        fun shared(context: Context): ShizukuBridge = instance ?: synchronized(this) {
            instance ?: ShizukuBridge(context.applicationContext).also { instance = it }
        }
    }
}



