package com.bruh.angel

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.bruh.angel.agent.LocalModelSession
import com.bruh.angel.local.HuggingFace
import com.bruh.angel.local.LlamaNative
import com.bruh.angel.local.LocalEngine
import com.bruh.angel.local.LocalModel
import com.bruh.angel.local.LocalModelStore
import com.bruh.angel.local.LocalParams
import com.bruh.angel.model.ToolNames
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device llama.cpp tests. They download real GGUF files from Hugging Face over Wi-Fi through the app's
 * own download path (DownloadManager + SHA-256 verification).
 *  - tinyModel…: SmolLM2-135M (~105 MB, removed afterwards).
 *  - qwen…: opt-in (`-e realModel true`), Qwen3-0.6B Q4_0 (~430 MB), kept so it can be used in the app.
 */
@RunWith(AndroidJUnit4::class)
class LocalModelTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun download(repo: String, path: String): LocalModel = runBlocking {
        val store = LocalModelStore.get(context)
        store.models.value.firstOrNull { it.repo == repo && it.location.endsWith(path) && it.ready }?.let { return@runBlocking it }
        val file = HuggingFace.files(repo, null).first { it.path == path }
        assertNotNull("Hugging Face should publish a SHA-256 for LFS files", file.sha256)
        val started = HuggingFace.startDownload(context, repo, file, null, allowMobileData = false)
        withTimeout(20 * 60_000L) {
            var current = store.get(started.id)!!
            while (current.status != LocalModel.Status.READY) {
                assertNotEquals(current.error ?: "download failed", LocalModel.Status.FAILED, current.status)
                delay(2000)
                HuggingFace.reconcile(context)
                current = store.get(started.id)!!
            }
            current
        }
    }

    @Test
    fun parsesHuggingFaceListings() {
        val files = HuggingFace.parseFiles(
            """[{"type":"file","path":"a-Q8_0.gguf","size":200,"lfs":{"oid":"${"b".repeat(64)}","size":200}},
                {"type":"file","path":"mmproj-F16.gguf","size":10},
                {"type":"file","path":"README.md","size":1},
                {"type":"file","path":"sub/a-Q4_K_M.gguf","size":100,"lfs":{"oid":"${"a".repeat(64)}","size":100}},
                {"type":"file","path":"big-Q4_K_M-00001-of-00002.gguf","size":300}]"""
        )
        assertEquals(listOf("sub/a-Q4_K_M.gguf", "a-Q8_0.gguf", "big-Q4_K_M-00001-of-00002.gguf"), files.map { it.path })
        assertEquals("a".repeat(64), files[0].sha256)
        assertEquals("Q4_K_M", files[0].quant)
        assertTrue(files[2].split)
        assertEquals("owner/repo-GGUF", HuggingFace.normalizeRepo("https://huggingface.co/owner/repo-GGUF/tree/main"))
        assertEquals(null, HuggingFace.normalizeRepo("../etc/passwd"))
        assertEquals(listOf("org/model"), HuggingFace.parseRepos("""[{"id":"org/model","downloads":5},{"id":"bad id"}]""").map { it.id })
        assertEquals(LocalParams(contextSize = 8192), LocalParams.fromJson(LocalParams(contextSize = 8192).toJson()))
    }

    @Test
    fun tinyModelLoadsGeneratesReusesCacheAndParses() = runBlocking {
        val model = download("unsloth/SmolLM2-135M-Instruct-GGUF", "SmolLM2-135M-Instruct-Q4_K_M.gguf")
        try {
            LocalEngine.ensureLoaded(context, model, LocalParams(contextSize = 2048, temperature = 0f))
            assertTrue(LocalEngine.isLoaded(model.id))
            val messages = JSONArray()
                .put(JSONObject().put("role", "system").put("content", "You are a helpful assistant."))
                .put(JSONObject().put("role", "user").put("content", "Say hello in one short sentence. 👋"))
            val (first, second) = LocalEngine.withHandle { h ->
                val prompt = LlamaNative.prepare(h, messages.toString().toByteArray(), null, false)
                assertTrue(String(prompt).contains("👋")) // UTF-8 survives the JNI boundary
                var streamed = 0
                val raw = LlamaNative.generate(h, prompt, 24, 0f, 0.95f, 40, 0.05f, 1) { streamed++; true }
                assertTrue("tokens should stream", streamed > 0)
                val parsed = JSONObject(String(LlamaNative.parse(h, raw, false)))
                val stats1 = JSONObject(LlamaNative.stats(h))
                LlamaNative.generate(h, prompt, 4, 0f, 0.95f, 40, 0.05f, 1, null) // same prompt again
                val stats2 = JSONObject(LlamaNative.stats(h))
                Triple(parsed, stats1, stats2).let { (p, s1, s2) -> (p to s1) to s2 }
            }
            val (parsed, stats1) = first
            assertTrue(parsed.toString(), parsed.optString("content").isNotBlank())
            assertTrue(stats1.toString(), stats1.getInt("prompt") > 0 && stats1.getInt("generated") > 0)
            assertEquals(0, stats1.getInt("reused"))
            // Prefix caching: the second run re-evaluates only the last prompt token.
            assertEquals(second.toString(), second.getInt("prompt") - 1, second.getInt("reused"))
        } finally {
            LocalEngine.unload()
            LocalModelStore.get(context).remove(model.id)
        }
    }

    @Test
    fun qwenCallsAndroidShellToolEndToEnd() = runBlocking {
        assumeTrue("opt-in: -e realModel true", InstrumentationRegistry.getArguments().getString("realModel") == "true")
        val model = download("ggml-org/Qwen3-0.6B-GGUF", "Qwen3-0.6B-Q4_0.gguf")
        val params = LocalParams(contextSize = 4096, temperature = 0f, maxTokens = 512, thinking = false)
        val session = LocalModelSession(context, model, params)
        session.user("Use the android_shell tool to run the command `id` on this phone.")
        val reply = session.next()
        val call = reply.tools.firstOrNull()
        assertNotNull("expected a tool call, got: ${reply.text}", call)
        assertEquals(ToolNames.ANDROID, call!!.name)
        assertTrue(call.command, call.command.contains("id"))

        session.results(listOf(call to "exit code 0\nuid=2000(shell) gid=2000(shell) groups=2000(shell)"))
        var partials = 0
        val answer = session.next { partials++ }
        // A 0.6B model's wording varies; check the round trip produced a streamed natural-language answer.
        assertTrue(answer.text, answer.text.isNotBlank() && !answer.text.startsWith("(The model returned an empty reply"))
        assertTrue("reply should stream", partials > 0)
        LocalEngine.unload()
    }
}


