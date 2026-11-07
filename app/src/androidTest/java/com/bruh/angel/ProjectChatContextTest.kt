package com.bruh.angel

import android.app.Application
import android.app.Instrumentation
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import com.bruh.angel.agent.ChatModel
import com.bruh.angel.agent.ModelReply
import com.bruh.angel.agent.RequestedTool
import com.bruh.angel.linuxenv.LinuxDistro
import com.bruh.angel.local.LocalModelStore
import com.bruh.angel.mcp.McpKind
import com.bruh.angel.mcp.McpServerConfig
import com.bruh.angel.model.AgentPrefs
import com.bruh.angel.model.ChatItem
import com.bruh.angel.model.ConversationStore
import com.bruh.angel.model.Project
import com.bruh.angel.model.Provider
import com.bruh.angel.model.ProviderSettings
import com.bruh.angel.model.ProviderSettingsStore
import com.bruh.angel.model.ToolNames
import com.bruh.angel.model.ToolPolicy
import com.bruh.angel.shizuku.ShizukuBridge
import com.bruh.angel.ui.chat.ChatViewModel
import java.io.File
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

/**
 * ViewModel regressions for project/chat context handling with a scripted model and isolated
 * storage, following the ToolApprovalTest pattern:
 * - "Open in a new chat" must start a fresh chat even when its project is already active.
 * - Editing the active project while a tool awaits approval must not redirect that request's
 *   commands into the new working directory or distro.
 */
class ProjectChatContextTest {
    private class FixtureApplication(base: Context, private val directory: File) : Application() {
        init { attachBaseContext(base) }
        override fun getApplicationContext(): Context = this
        override fun getNoBackupFilesDir() = File(directory, "no-backup").apply { mkdirs() }
        override fun getFilesDir() = File(directory, "files").apply { mkdirs() }
        override fun getCacheDir() = File(directory, "cache").apply { mkdirs() }
    }

    /** Replies with plain text only. */
    private class PlainModel(private val reply: String) : ChatModel {
        override var isEmpty = true
        var turns = 0
        override fun user(text: String) { isEmpty = false }
        override fun results(results: List<Pair<RequestedTool, String>>) {}
        override fun exportHistory() = "[]"
        override suspend fun next(onPartial: (String) -> Unit): ModelReply {
            turns++
            return ModelReply(reply, emptyList())
        }
    }

    /** Requests one linux_shell command, then finishes with plain text. */
    private class ToolThenTextModel(private val command: String) : ChatModel {
        override var isEmpty = true
        var turns = 0
        var resultBatches = 0
        override fun user(text: String) { isEmpty = false }
        override fun results(results: List<Pair<RequestedTool, String>>) { resultBatches++ }
        override fun exportHistory() = "[]"
        override suspend fun next(onPartial: (String) -> Unit): ModelReply {
            turns++
            return if (turns == 1) ModelReply("", listOf(RequestedTool("call-1", ToolNames.LINUX, command)))
            else ModelReply("done", emptyList())
        }
    }

    private data class RecordedShell(val command: String, val linux: Boolean, val distro: LinuxDistro, val workdir: String)

    private fun setSession(vm: ChatViewModel, model: ChatModel) {
        val field = ChatViewModel::class.java.getDeclaredField("session").apply { isAccessible = true }
        field.set(vm, model)
    }

    private fun recordShell(vm: ChatViewModel, into: MutableList<RecordedShell>) {
        val field = ChatViewModel::class.java.getDeclaredField("shellExecutor").apply { isAccessible = true }
        val recorder: suspend (String, Boolean, LinuxDistro, String) -> android.os.Bundle = { command, linux, distro, workdir ->
            into += RecordedShell(command, linux, distro, workdir)
            android.os.Bundle().apply {
                putString("output", "fixture output")
                putInt("exitCode", 0)
            }
        }
        field.set(vm, recorder)
    }

    /** Creates a ViewModel with isolated storage and [project] pre-seeded, avoiding init races. */
    private fun seededViewModel(
        instrumentation: Instrumentation, real: Context, directory: File,
        project: Project, viewModels: ViewModelStore
    ): Pair<ChatViewModel, FixtureApplication> {
        val isolated = FixtureApplication(real, directory)
        ProviderSettingsStore(isolated).saveProjects(listOf(project))
        lateinit var vm: ChatViewModel
        instrumentation.runOnMainSync {
            // Keep process-wide singletons attached to the real application, not the fixture.
            LocalModelStore.get(real)
            ShizukuBridge.shared(real)
            vm = ViewModelProvider(viewModels, object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T = ChatViewModel(isolated) as T
            })[ChatViewModel::class.java]
        }
        return vm to isolated
    }

    @Test fun openInNewChatWithActiveProjectStartsFreshChatAndKeepsOldOne() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val real = instrumentation.targetContext
        val directory = File(real.cacheDir, "new-chat-test-${UUID.randomUUID()}").apply { mkdirs() }
        val viewModels = ViewModelStore()
        val project = Project(
            id = Project.newId(), name = "Release QA new chat",
            workdir = "/root/qa-new-chat", distroId = LinuxDistro.DEBIAN.id
        )
        val (vm, isolated) = seededViewModel(instrumentation, real, directory, project, viewModels)
        val projectId = project.id
        try {
            withTimeout(10_000) { vm.busy.first { !it } }
            instrumentation.runOnMainSync {
                vm.settings.value = ProviderSettings(Provider.OPENAI, "fixture-model", "not-a-real-key")
                vm.setActiveProject(project)
            }
            withTimeout(10_000) { vm.activeProject.first { it?.id == projectId } }

            val first = PlainModel("pong from the project chat")
            instrumentation.runOnMainSync {
                setSession(vm, first)
                vm.send("first message in the project chat")
            }
            withTimeout(10_000) { vm.busy.first { !it } }
            assertEquals(1, first.turns)
            assertTrue(vm.items.value.any { it is ChatItem.UserMsg && it.text == "first message in the project chat" })
            assertTrue(vm.items.value.any { it is ChatItem.AgentMsg && it.text == "pong from the project chat" })

            // The explicit action with the SAME project already active must still start a fresh chat.
            instrumentation.runOnMainSync { vm.openProjectInNewChat(project) }
            assertTrue("A new chat starts empty", vm.items.value.isEmpty())

            // The old conversation is preserved, with its project assignment. Persist is
            // asynchronous; poll until the saved copy also holds the agent's reply.
            val oldMeta = withTimeout(10_000) {
                vm.history.first { list -> list.any { it.title.startsWith("first message") } }
                    .first { it.title.startsWith("first message") }
            }
            assertEquals(projectId, oldMeta.projectId)
            val store = ConversationStore(isolated)
            val stored = withTimeout(10_000) {
                var loaded = store.load(oldMeta.id)
                while (loaded == null || loaded.items.none { it is ChatItem.AgentMsg }) {
                    kotlinx.coroutines.delay(50)
                    loaded = store.load(oldMeta.id)
                }
                loaded!!
            }
            assertTrue(stored.items.any { it is ChatItem.UserMsg && it.text == "first message in the project chat" })
            assertTrue(stored.items.any { it is ChatItem.AgentMsg && it.text == "pong from the project chat" })

            // The fresh chat still belongs to the same project.
            val second = PlainModel("pong again")
            instrumentation.runOnMainSync {
                setSession(vm, second)
                vm.send("second message in the fresh chat")
            }
            withTimeout(10_000) { vm.busy.first { !it } }
            val newMeta = withTimeout(10_000) {
                vm.history.first { list -> list.any { it.title.startsWith("second message") } }
                    .first { it.title.startsWith("second message") }
            }
            assertEquals(projectId, newMeta.projectId)
            assertNotEquals(oldMeta.id, newMeta.id)
            val fresh = ConversationStore(isolated).load(newMeta.id)!!
            assertFalse(fresh.items.any { it is ChatItem.UserMsg && it.text.contains("first message") })
        } finally {
            instrumentation.runOnMainSync { viewModels.clear() }
        }
    }

    @Test fun editingActiveProjectDuringPendingApprovalKeepsOriginalExecutionContext() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val real = instrumentation.targetContext
        val directory = File(real.cacheDir, "context-test-${UUID.randomUUID()}").apply { mkdirs() }
        val viewModels = ViewModelStore()
        val project = Project(
            id = Project.newId(), name = "Release QA context",
            workdir = "/original", distroId = LinuxDistro.DEBIAN.id,
            connectors = setOf("original")
        )
        val (vm, _) = seededViewModel(instrumentation, real, directory, project, viewModels)
        try {
            withTimeout(10_000) { vm.busy.first { !it } }
            instrumentation.runOnMainSync { vm.setActiveProject(project) }
            withTimeout(10_000) { vm.activeProject.first { it?.id == project.id } }

            val model = ToolThenTextModel("printf MARKER")
            val recorded = java.util.concurrent.CopyOnWriteArrayList<RecordedShell>()
            instrumentation.runOnMainSync {
                vm.settings.value = ProviderSettings(Provider.OPENAI, "fixture-model", "not-a-real-key")
                vm.prefs.value = AgentPrefs(policies = mapOf(ToolNames.LINUX to ToolPolicy.ASK))
                setSession(vm, model)
                recordShell(vm, recorded)
                vm.send("run the marker command")
            }
            withTimeout(10_000) { vm.pending.first { it != null } }

            // While the approval is pending, save a different workdir and distro for the same project.
            instrumentation.runOnMainSync {
                // Do not start real servers: inspect the actual discovery surface while this
                // scripted request is paused. Local profile suppresses background preparation.
                vm.settings.value = ProviderSettings(Provider.LOCAL, "fixture-model", "")
                vm.mcp.add(McpServerConfig("original", "Original connector", McpKind.REMOTE, url = "http://127.0.0.1:1/mcp"))
                vm.mcp.add(McpServerConfig("redirected", "Redirected connector", McpKind.REMOTE, url = "http://127.0.0.1:1/mcp"))
                vm.updateProject(project.copy(workdir = "/redirected", distroId = LinuxDistro.KALI.id,
                    connectors = setOf("redirected")))
            }
            withTimeout(10_000) { vm.activeProject.first { it?.workdir == "/redirected" } }
            val discovery = vm.mcp.localExternalTools.list().single().description
            assertTrue(discovery, discovery.contains("Original connector"))
            assertFalse(discovery, discovery.contains("Redirected connector"))
            val selectedDistro = vm.mcp.javaClass.getDeclaredField("selectedDistro").apply { isAccessible = true }
            @Suppress("UNCHECKED_CAST")
            val distroForServer = selectedDistro.get(vm.mcp) as () -> LinuxDistro
            assertEquals(LinuxDistro.DEBIAN, distroForServer())

            // Deletion must not orphan a request or leave its active project stale.
            instrumentation.runOnMainSync { vm.deleteProject(project.id) }
            assertTrue(vm.projects.value.any { it.id == project.id })
            assertTrue(vm.notice.value.contains("Stop the current request"))

            instrumentation.runOnMainSync {
                vm.approve(ChatViewModel.Approval(true, "printf MARKER", always = false))
            }
            withTimeout(10_000) { vm.busy.first { !it } }

            assertEquals("The approved command ran exactly once", 1, recorded.size)
            assertEquals("The pending request keeps its original working directory",
                "/original", recorded.single().workdir)
            assertEquals("The pending request keeps its original distro",
                LinuxDistro.DEBIAN, recorded.single().distro)
            assertTrue(recorded.single().linux)
            assertEquals("The request completed after the tool result", 2, model.turns)
            assertEquals(1, model.resultBatches)
            assertTrue(vm.items.value.filterIsInstance<ChatItem.AgentMsg>().any { it.text == "done" })
            assertEquals(LinuxDistro.KALI, distroForServer())
            assertTrue(vm.mcp.localExternalTools.list().single().description.contains("Redirected connector"))
        } finally {
            instrumentation.runOnMainSync { viewModels.clear() }
        }
    }
}
