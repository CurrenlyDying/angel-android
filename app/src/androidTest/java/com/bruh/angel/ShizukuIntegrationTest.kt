package com.bruh.angel

import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.bruh.angel.shizuku.ShizukuBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Requires a connected phone with Shizuku running, permission granted to Angel, and Linux installed. */
@RunWith(AndroidJUnit4::class)
class ShizukuIntegrationTest {
    private lateinit var bridge: ShizukuBridge

    @Before
    fun connect() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        // Use the app-wide connection (as the app does); don't tear the service down between tests.
        instrumentation.runOnMainSync {
            bridge = ShizukuBridge.shared(instrumentation.targetContext)
            bridge.connect()
        }
        withTimeout(20_000) { bridge.status.first { it.startsWith("Connected:") } }
        Unit
    }

    @Test
    fun commandsRunAsShellNotRoot() = runBlocking {
        val result = bridge.execute("id", false)
        assertEquals(0, result.getInt("exitCode"))
        assertTrue(result.getString("output")!!.contains("uid=2000(shell)"))
    }

    @Test
    fun linuxExecutesArm64CommandsAndSeesSdcard() = runBlocking {
        val result = bridge.execute("uname -m; cat /etc/alpine-release; printf 'calculation='; expr 6 '*' 7; ls -d /sdcard/Download && echo SDCARD=ok", true)
        val output = result.getString("output")!!
        assertEquals(output, 0, result.getInt("exitCode"))
        assertTrue(output, output.contains("aarch64"))
        assertTrue(output, output.contains("calculation=42"))
        assertTrue(output, output.contains("SDCARD=ok"))
    }

    @Test
    fun cancellationStopsProcessGroupPromptly() = runBlocking {
        val start = System.nanoTime()
        val command = async(Dispatchers.IO) { bridge.execute("sleep 30 & wait", false) }
        delay(800)
        bridge.cancel()
        val result = withTimeout(5_000) { command.await() }
        assertNotEquals(0, result.getInt("exitCode"))
        assertTrue((System.nanoTime() - start) / 1_000_000 < 6000)
    }

    @Test
    fun rejectsCorruptDownloadWithoutReplacingInstalledLinux() = runBlocking {
        val file = File.createTempFile("bad-linux-", ".deb", InstrumentationRegistry.getInstrumentation().targetContext.cacheDir)
        try {
            file.writeText("This is not a verified archive")
            val api = bridge.api()
            val before = withContext(Dispatchers.IO) { api.linuxStatus("alpine") }
            val error = withContext(Dispatchers.IO) {
                ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                    runCatching { api.installArtifact("alpine", "proot", fd) }.exceptionOrNull()
                }
            }
            assertNotNull(error)
            assertTrue(error!!.message.orEmpty().contains("Checksum mismatch"))
            assertEquals(before, withContext(Dispatchers.IO) { api.linuxStatus("alpine") })
        } finally {
            file.delete()
        }
    }

    @Test
    fun androidTerminalKeepsStateAndSurvivesCtrlC(): Unit = runBlocking {
        val term = bridge.openTerminal(linux = false)
        try {
            withContext(Dispatchers.IO) {
                term.send("cd /data/local/tmp; echo \"CWD=\$(pwd)\"")
                readUntil(term, "CWD=/data/local/tmp")
                term.send("echo \"STILL=\$(pwd)\"") // state persists between commands
                readUntil(term, "STILL=/data/local/tmp")
                term.send("sleep 30")
                Thread.sleep(800)
                val start = System.nanoTime()
                term.interrupt()
                term.send("echo \"ALIVE=\$((6*7))\"")
                readUntil(term, "ALIVE=42", timeoutMs = 10_000) // sleep interrupted, shell still alive
                assertTrue((System.nanoTime() - start) / 1_000_000 < 9000)
                term.send("id")
                readUntil(term, "uid=2000(shell)")
            }
        } finally {
            term.close()
        }
    }

    @Test
    fun linuxTerminalKeepsStateAndSurvivesCtrlC(): Unit = runBlocking {
        val term = bridge.openTerminal(linux = true)
        try {
            withContext(Dispatchers.IO) {
                term.send("cd /tmp && echo \"CWD=\$(pwd)\"")
                readUntil(term, "CWD=/tmp")
                term.send("sleep 30")
                Thread.sleep(800)
                term.interrupt()
                term.send("echo \"ALIVE=\$((6*7)) \$(uname -m)\"")
                readUntil(term, "ALIVE=42 aarch64", timeoutMs = 10_000)
            }
        } finally {
            term.close()
        }
    }

    @Test
    fun readScreenListsElements() = runBlocking {
        bridge.execute("input keyevent KEYCODE_WAKEUP", false)
        val screen = bridge.readScreen("")
        assertTrue(screen, screen.startsWith("Screen "))
        assertTrue(screen, screen.contains(" elements"))
    }

    private fun ShizukuBridge.TerminalHandle.send(line: String) {
        input.write((line + "\n").toByteArray())
        input.flush()
    }

    private fun readUntil(term: ShizukuBridge.TerminalHandle, marker: String, timeoutMs: Long = 20_000): String {
        val output = StringBuilder()
        val buffer = ByteArray(4096)
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (term.output.available() > 0) {
                val n = term.output.read(buffer)
                if (n < 0) break
                output.append(String(buffer, 0, n))
                if (output.contains(marker)) return output.toString()
            } else {
                Thread.sleep(50)
            }
        }
        throw AssertionError("Timed out waiting for '$marker'. Output so far:\n$output")
    }
}

