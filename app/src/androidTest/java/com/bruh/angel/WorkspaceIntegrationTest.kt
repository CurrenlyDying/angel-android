package com.bruh.angel

import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import com.bruh.angel.linuxenv.LinuxDistro
import com.bruh.angel.linuxenv.ProjectWorkspace
import com.bruh.angel.mcp.McpKind
import com.bruh.angel.mcp.McpManager
import com.bruh.angel.mcp.McpServerConfig
import com.bruh.angel.mcp.McpState
import com.bruh.angel.model.ProviderSettingsStore
import com.bruh.angel.shizuku.ShizukuBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.UUID
import org.json.JSONObject

/** Isolated fixtures only: never creates or edits a user's project, repository or MCP configuration. */
class WorkspaceIntegrationTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var bridge: ShizukuBridge
    private val distro = LinuxDistro.KALI

    @Before fun connect() = runBlocking {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            bridge = ShizukuBridge.shared(context)
            bridge.connect()
        }
        withTimeout(20_000) { bridge.status.first { it.startsWith("Connected:") } }
        Unit
    }

    private suspend fun command(text: String, cwd: String = ""): String {
        val result = bridge.execute(text, true, distro, cwd)
        val output = result.getString("output").orEmpty()
        assertEquals(output, 0, result.getInt("exitCode"))
        return output
    }

    private suspend fun workspace(block: suspend (String) -> Unit) {
        val path = "/tmp/angel-workspace-test-${UUID.randomUUID()}"
        command("mkdir -p -- ${ProjectWorkspace.quote(path)}")
        try { block(path) } finally { command("rm -rf -- ${ProjectWorkspace.quote(path)}") }
    }

    @Test fun projectWorkdirAppliesToCommandsAndTerminal() = runBlocking {
        workspace { path ->
            assertEquals(path, command("pwd", path).trim())
            val terminal = bridge.openTerminal(true, distro, path)
            try {
                withContext(Dispatchers.IO) {
                    terminal.input.write("printf '__CWD__%s\\n' \"\$PWD\"\n".toByteArray())
                    terminal.input.flush()
                    val text = StringBuilder()
                    val buffer = ByteArray(4096)
                    withTimeout(10_000) {
                        while (!text.contains("__CWD__$path")) {
                            if (terminal.output.available() > 0) {
                                val count = terminal.output.read(buffer)
                                if (count < 0) break
                                text.append(String(buffer, 0, count))
                            } else delay(25)
                        }
                    }
                    assertTrue(text.toString(), text.contains("__CWD__$path"))
                }
            } finally { terminal.close() }
        }
    }

    @Test fun missingProjectDirectoryDoesNotRunInAnotherFolder(): Unit = runBlocking {
        val result = runCatching {
            bridge.execute("printf '__UNEXPECTED_COMMAND__'", true, distro, "/tmp/angel-missing-${UUID.randomUUID()}")
        }
        result.getOrNull()?.let {
            val output = it.getString("output").orEmpty()
            assertTrue(output, it.getInt("exitCode") != 0)
            assertTrue(output, !output.contains("__UNEXPECTED_COMMAND__"))
        }
    }

    @Test fun scriptTimeoutDoesNotWaitForBlockedOutputReader() = runBlocking {
        val start = System.nanoTime()
        val result = runCatching { bridge.runLinuxScript("sleep 30", distro, timeoutMs = 300) }
        assertTrue(result.toString(), result.exceptionOrNull()?.message.orEmpty().contains("timed out"))
        assertTrue("Timeout should stop the script promptly", (System.nanoTime() - start) / 1_000_000 < 5000)
        assertEquals("still works", command("printf 'still works'"))
    }

    @Test fun gitPreservesUnusualNamesAndUnstagesBeforeFirstCommit() = runBlocking {
        workspace { path ->
            command("git init -q", path)
            val names = listOf(" space.txt", "trailing.txt ", "café.txt", "quote\"file.txt", "line\nbreak.txt", "old -> name.txt")
            command(names.joinToString("\n") { "printf 'fixture\\n' > ${ProjectWorkspace.quote(it)}" }, path)
            val entries = ProjectWorkspace.parseStatus(command(ProjectWorkspace.STATUS, path))
            assertEquals(names.toSet(), entries.map { it.path }.toSet())
            for (name in names) command(ProjectWorkspace.stage(name), path)
            assertTrue(ProjectWorkspace.parseStatus(command(ProjectWorkspace.STATUS, path)).all { it.staged })
            // Unstage must retain working files, including edits made after staging.
            command("printf 'new content\\n' > ${ProjectWorkspace.quote(names.first())}", path)
            for (name in names) command(ProjectWorkspace.unstage(name), path)
            assertTrue(ProjectWorkspace.parseStatus(command(ProjectWorkspace.STATUS, path)).all { it.untracked })
            assertEquals("new content", command("cat -- ${ProjectWorkspace.quote(names.first())}", path).trim())
        }
    }

    @Test fun unstagingRenameRestoresBothIndexPaths() = runBlocking {
        workspace { path ->
            command("git init -q; printf 'fixture\\n' > old.txt; git add old.txt", path)
            command(ProjectWorkspace.commit("Fixture"), path)
            command("git mv old.txt 'new -> name.txt'", path)
            val entry = ProjectWorkspace.parseStatus(command(ProjectWorkspace.STATUS, path)).single()
            assertEquals("old.txt", entry.originalPath)
            assertTrue(command(ProjectWorkspace.diff(entry.path, true, false, entry.originalPath), path).contains("rename from old.txt"))
            command(ProjectWorkspace.unstage(entry.path, entry.originalPath), path)
            assertTrue(ProjectWorkspace.parseStatus(command(ProjectWorkspace.STATUS, path)).none { it.staged })
            assertEquals("fixture", command("cat 'new -> name.txt'", path).trim())
        }
    }

    @Test fun mcpSetupFailureIsReportedAndUsesRequestedDistro() = runBlocking {
        val directory = File(context.cacheDir, "mcp-setup-test-${UUID.randomUUID()}").apply { mkdirs() }
        val isolated = object : ContextWrapper(context) {
            override fun getNoBackupFilesDir() = directory
        }
        val job = SupervisorJob()
        val manager = McpManager(bridge, ProviderSettingsStore(isolated), CoroutineScope(job + Dispatchers.Main), { LinuxDistro.ALPINE })
        try {
            val config = McpServerConfig("setup-test", "Fixture", McpKind.CUSTOM,
                installScript = "cat /etc/os-release; exit 17")
            manager.add(config)
            val failure = runCatching { withTimeout(15_000) { manager.installAndAwait(config.id, distro) } }.exceptionOrNull()
            assertNotNull(failure)
            val result = manager.servers.value.single()
            assertEquals(McpState.ERROR, result.state)
            assertTrue(result.message, result.message.contains("17"))
            assertTrue(result.log.toString(), result.log.any { it.contains("Kali") })
        } finally {
            manager.shutdown()
            job.cancelAndJoin()
            directory.deleteRecursively()
        }
    }

    @Test fun localMcpToolsAreDiscoveredOnDemand() = runBlocking {
        val directory = File(context.cacheDir, "mcp-discovery-test-${UUID.randomUUID()}").apply { mkdirs() }
        val isolated = object : ContextWrapper(context) {
            override fun getNoBackupFilesDir() = directory
        }
        val job = SupervisorJob()
        val manager = McpManager(bridge, ProviderSettingsStore(isolated), CoroutineScope(job + Dispatchers.Main), { distro })
        val script = """
            import sys,json
            for line in sys.stdin:
                msg=json.loads(line)
                if 'id' not in msg: continue
                method=msg.get('method')
                if method=='initialize': result={'protocolVersion':msg['params']['protocolVersion'],'capabilities':{'tools':{}},'serverInfo':{'name':'fixture','version':'1'}}
                elif method=='tools/list': result={'tools':[{'name':'greet','description':'Return a greeting','inputSchema':{'type':'object','properties':{}}}]}
                elif method=='tools/call': result={'content':[{'type':'text','text':'fixture hello'}]}
                else: result={}
                print(json.dumps({'jsonrpc':'2.0','id':msg['id'],'result':result}),flush=True)
        """.trimIndent()
        try {
            manager.load()
            manager.prepare() // await the empty fixture store before adding its server
            manager.add(McpServerConfig("discovery-test", "Greeting fixture", McpKind.CUSTOM,
                command = "python3 -u -c ${ProjectWorkspace.quote(script)}", installedDistro = distro.id))
            assertEquals(McpState.OFF, manager.servers.value.single().state)
            assertEquals(listOf(McpManager.LOCAL_DISCOVERY_TOOL), manager.localExternalTools.list().map { it.name })
            val found = withTimeout(20_000) { manager.call(McpManager.LOCAL_DISCOVERY_TOOL, JSONObject().put("query", "greet")) }
            assertTrue(found.text, !found.isError && found.text.contains("mcp__discovery-test__greet"))
            assertEquals(McpState.READY, manager.servers.value.single().state)
            assertTrue(manager.localExternalTools.list().any { it.name == "mcp__discovery-test__greet" })
            assertEquals("fixture hello", manager.call("mcp__discovery-test__greet", JSONObject()).text)
            manager.resetLocalTools()
            assertEquals(listOf(McpManager.LOCAL_DISCOVERY_TOOL), manager.localExternalTools.list().map { it.name })
        } finally {
            manager.shutdown()
            job.cancelAndJoin()
            directory.deleteRecursively()
        }
    }
}
