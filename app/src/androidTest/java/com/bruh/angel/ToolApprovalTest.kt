package com.bruh.angel

import android.app.Application
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import com.bruh.angel.agent.ChatModel
import com.bruh.angel.agent.ModelReply
import com.bruh.angel.agent.RequestedTool
import com.bruh.angel.local.LocalModelStore
import com.bruh.angel.model.AgentPrefs
import com.bruh.angel.model.ChatItem
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

/** Exercises the real ViewModel approval/agent loop with a scripted model and isolated storage. */
class ToolApprovalTest {
    private class FixtureApplication(base: Context, private val directory: File) : Application() {
        init { attachBaseContext(base) }
        override fun getApplicationContext(): Context = this
        override fun getNoBackupFilesDir() = File(directory, "no-backup").apply { mkdirs() }
        override fun getFilesDir() = File(directory, "files").apply { mkdirs() }
        override fun getCacheDir() = File(directory, "cache").apply { mkdirs() }
    }

    private class ScriptedModel : ChatModel {
        override var isEmpty = true
        var turns = 0
        var resultBatches = 0
        override fun user(text: String) { isEmpty = false }
        override fun results(results: List<Pair<RequestedTool, String>>) { resultBatches++ }
        override fun exportHistory() = "[]"
        override suspend fun next(onPartial: (String) -> Unit): ModelReply {
            turns++
            return if (turns == 1) ModelReply("", listOf(
                RequestedTool("denied", ToolNames.ANDROID, "printf SHOULD_NOT_RUN_FIRST"),
                RequestedTool("alternative", ToolNames.LINUX, "printf SHOULD_NOT_RUN_SECOND")
            )) else ModelReply("The model resumed unexpectedly.", emptyList())
        }
    }

    private fun checkRefusal(policy: ToolPolicy) = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val real = instrumentation.targetContext
        val directory = File(real.cacheDir, "approval-test-${UUID.randomUUID()}").apply { mkdirs() }
        val isolated = FixtureApplication(real, directory)
        val viewModels = ViewModelStore()
        lateinit var vm: ChatViewModel
        val model = ScriptedModel()
        instrumentation.runOnMainSync {
            // Keep process-wide singletons attached to the real application, not the fixture.
            LocalModelStore.get(real)
            ShizukuBridge.shared(real)
            vm = ViewModelProvider(viewModels, object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T = ChatViewModel(isolated) as T
            })[ChatViewModel::class.java]
        }
        try {
            withTimeout(10_000) { vm.busy.first { !it } }
            instrumentation.runOnMainSync {
                vm.settings.value = ProviderSettings(Provider.OPENAI, "fixture-model", "not-a-real-key")
                vm.prefs.value = AgentPrefs(policies = mapOf(ToolNames.ANDROID to policy, ToolNames.LINUX to ToolPolicy.ALLOW))
                val field = ChatViewModel::class.java.getDeclaredField("session").apply { isAccessible = true }
                field.set(vm, model)
                assertSame(model, field.get(vm))
                vm.send("Fixture: refuse the first tool and never run the alternative.")
            }
            if (policy == ToolPolicy.ASK) {
                withTimeout(10_000) { vm.pending.first { it != null } }
                instrumentation.runOnMainSync {
                    vm.approve(ChatViewModel.Approval(false, "printf SHOULD_NOT_RUN_FIRST", always = true))
                }
            }
            withTimeout(10_000) { vm.busy.first { !it } }
            assertEquals("No second model turn after refusal", 1, model.turns)
            assertEquals(0, model.resultBatches)
            val calls = vm.items.value.filterIsInstance<ChatItem.ToolCall>()
            assertEquals("No remaining call from the batch may run", 1, calls.size)
            assertEquals(126, calls.single().exitCode)
            assertTrue(vm.items.value.filterIsInstance<ChatItem.AgentMsg>().last().text.contains("request has stopped"))
            assertEquals("Always allow must not be applied by Deny", policy, vm.prefs.value.policy(ToolNames.ANDROID))
            assertEquals(ToolPolicy.ASK, ProviderSettingsStore(isolated).loadPrefs().policy(ToolNames.ANDROID))
        } finally {
            instrumentation.runOnMainSync { viewModels.clear() }
            // onCleared writes its final snapshot asynchronously; keep this small fixture in cache.
        }
    }

    @Test fun denyWithAlwaysAllowDoesNotChangePolicyOrContinueTheBatch() = checkRefusal(ToolPolicy.ASK)
    @Test fun blockedToolStopsTheBatchWithoutPromptingOrResumingTheModel() = checkRefusal(ToolPolicy.DENY)
}
