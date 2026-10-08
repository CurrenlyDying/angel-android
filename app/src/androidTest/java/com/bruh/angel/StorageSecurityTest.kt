package com.bruh.angel

import android.content.ContextWrapper
import android.system.Os
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.bruh.angel.linuxenv.LinuxInstaller
import com.bruh.angel.model.AgentPrefs
import com.bruh.angel.model.ChatItem
import com.bruh.angel.model.Conversation
import com.bruh.angel.model.ConversationStore
import com.bruh.angel.model.Provider
import com.bruh.angel.model.ProviderSettings
import com.bruh.angel.model.ProviderSettingsStore
import com.bruh.angel.model.ToolNames
import com.bruh.angel.model.ToolPolicy
import com.bruh.angel.shizuku.ScreenReader
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.archivers.tar.TarConstants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File

@RunWith(AndroidJUnit4::class)
class StorageSecurityTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun isolated(dir: File) = object : ContextWrapper(context) {
        override fun getNoBackupFilesDir() = dir
        override fun getFilesDir() = dir
    }

    private fun tempDir(prefix: String) = File(context.cacheDir, "$prefix-${System.nanoTime()}").apply { mkdirs() }

    @Test
    fun profilesAreEncryptedAndSeparated() {
        val dir = tempDir("profile-test")
        try {
            val store = ProviderSettingsStore(isolated(dir))
            for (provider in Provider.entries) store.save(
                ProviderSettings(provider, provider.defaultModel.ifEmpty { "local-model-id" }, "fake-secret-${provider.name}")
            )
            for (provider in Provider.entries) assertEquals("fake-secret-${provider.name}", store.load(provider).apiKey)
            assertEquals(Provider.entries.last(), store.selected())
            assertFalse(File(dir, "providers.enc").readBytes().toString(Charsets.ISO_8859_1).contains("fake-secret"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun selectionPersistsAndRetiredModelsMigrate() {
        val dir = tempDir("select-test")
        try {
            val store = ProviderSettingsStore(isolated(dir))
            store.save(ProviderSettings(Provider.DEEPSEEK, "deepseek-chat", "fake-secret-ds"))
            store.save(ProviderSettings(Provider.OPENAI, "gpt-6.1-sol", "fake-secret-oa"))
            assertEquals(Provider.OPENAI, store.selected())
            store.select(Provider.DEEPSEEK) // switching provider persists without re-saving the key
            val reopened = ProviderSettingsStore(isolated(dir))
            assertEquals(Provider.DEEPSEEK, reopened.selected())
            val deepseek = reopened.load(Provider.DEEPSEEK)
            assertEquals("deepseek-flash", deepseek.model) // deepseek-chat was retired 2026-07-24
            assertEquals("fake-secret-ds", deepseek.apiKey)
            assertEquals("deepseek-flash", ProviderSettingsStore(isolated(dir)).load(Provider.DEEPSEEK).model) // migration persisted
            assertEquals("fake-secret-oa", reopened.load(Provider.OPENAI).apiKey)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun agentPrefsRoundTrip() {
        val dir = tempDir("prefs-test")
        try {
            val store = ProviderSettingsStore(isolated(dir))
            assertEquals(ToolPolicy.ASK, store.loadPrefs().policy(ToolNames.ANDROID))
            store.savePrefs(AgentPrefs("Answer in French", com.bruh.angel.linuxenv.LinuxDistro.KALI, mapOf(ToolNames.ANDROID to ToolPolicy.ALLOW, ToolNames.SCREEN to ToolPolicy.DENY)))
            store.save(ProviderSettings(Provider.GOOGLE, "gemini-3.8-flash", "fake-secret-g")) // must not clobber prefs
            val prefs = ProviderSettingsStore(isolated(dir)).loadPrefs()
            assertEquals("Answer in French", prefs.instructions)
            assertEquals(ToolPolicy.ALLOW, prefs.policy(ToolNames.ANDROID))
            assertEquals(ToolPolicy.DENY, prefs.policy(ToolNames.SCREEN))
            assertEquals(ToolPolicy.ASK, prefs.policy(ToolNames.LINUX))
            assertEquals(com.bruh.angel.linuxenv.LinuxDistro.KALI, prefs.distro)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun conversationsRoundTripAndDelete() {
        val dir = tempDir("conv-test")
        try {
            val store = ConversationStore(isolated(dir))
            val id = ConversationStore.newId()
            val items = listOf(
                ChatItem.UserMsg("Battery?"),
                ChatItem.ToolCall("android_shell: dumpsys battery", "level: 87", 0),
                ChatItem.AgentMsg("**87%**")
            )
            store.save(Conversation(id, "Battery?", 1000L, Provider.ANTHROPIC, "claude-sonnet-5-5", items, "[]"))
            val loaded = ConversationStore(isolated(dir)).load(id)!!
            assertEquals(items, loaded.items)
            assertEquals(Provider.ANTHROPIC, loaded.provider)
            assertEquals("[]", loaded.history)
            assertEquals(listOf(id), store.list().map { it.id })
            assertTrue(ConversationStore.transcript(loaded).contains("$ android_shell: dumpsys battery"))
            store.delete(id)
            assertTrue(store.list().isEmpty())
            assertNull(runCatching { store.load("../../escape") }.getOrNull())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun screenReaderSummarizesActionableElements() {
        val xml = """
            <?xml version='1.0' encoding='UTF-8' standalone='yes' ?>
            <hierarchy rotation="0">
              <node index="0" text="" resource-id="" class="android.widget.FrameLayout" package="com.example" clickable="false" enabled="true" bounds="[0,0][1080,2400]">
                <node index="0" text="Wi-Fi" resource-id="com.example:id/title" class="android.widget.TextView" package="com.example" clickable="false" enabled="true" bounds="[0,100][1080,200]" />
                <node index="1" text="" content-desc="Toggle Wi-Fi" resource-id="com.example:id/toggle" class="android.widget.Switch" package="com.example" checkable="true" checked="true" clickable="true" enabled="true" bounds="[900,100][1060,200]" />
                <node index="2" text="" resource-id="" class="android.view.View" package="com.example" clickable="false" enabled="true" bounds="[0,300][10,310]" />
              </node>
            </hierarchy>
        """.trimIndent()
        val summary = ScreenReader.summarize(xml.byteInputStream(), "")
        assertTrue(summary, summary.startsWith("Screen 1080x2400 | apps: com.example | 2 elements"))
        assertTrue(summary, summary.contains("TextView \"Wi-Fi\" id=title"))
        assertTrue(summary, summary.contains("Switch desc=\"Toggle Wi-Fi\" id=toggle [clickable,checked] tap=(980,150)"))
        val filtered = ScreenReader.summarize(xml.byteInputStream(), "toggle")
        assertTrue(filtered, filtered.contains("1 elements matching \"toggle\""))
        assertFalse(filtered, filtered.contains("\"Wi-Fi\""))
    }

    @Test
    fun cleanupDoesNotFollowSymlinks() {
        val root = tempDir("cleanup-test")
        val outside = tempDir("outside-test")
        val keep = File(outside, "keep.txt").apply { writeText("keep me") }
        try {
            Os.symlink(outside.path, File(root, "external").path)
            Os.symlink(root.path, File(root, "cycle").path)
            LinuxInstaller().deleteTree(root)
            assertFalse(root.exists())
            assertEquals("keep me", keep.readText())
        } finally {
            LinuxInstaller().deleteTree(root)
            outside.deleteRecursively()
        }
    }

    private fun archive(entry: TarArchiveEntry): ByteArray {
        val output = ByteArrayOutputStream()
        TarArchiveOutputStream(output).use { tar ->
            tar.putArchiveEntry(entry)
            tar.closeArchiveEntry()
            tar.finish()
        }
        return output.toByteArray()
    }

    @Test
    fun rejectsTraversalBeforeWriting() {
        val root = tempDir("archive-test")
        try {
            val input = archive(TarArchiveEntry("../escape")).inputStream()
            assertTrue(runCatching { LinuxInstaller().extract(input, root, false) }.isFailure)
            assertTrue(root.listFiles()!!.isEmpty())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun rejectsEscapingSymlinks() {
        val root = tempDir("archive-test")
        try {
            val link = TarArchiveEntry("link", TarConstants.LF_SYMLINK).apply { linkName = "../../escape" }
            assertTrue(runCatching { LinuxInstaller().extract(archive(link).inputStream(), root, false) }.isFailure)
            assertTrue(root.listFiles()!!.isEmpty())
        } finally {
            root.deleteRecursively()
        }
    }
}

