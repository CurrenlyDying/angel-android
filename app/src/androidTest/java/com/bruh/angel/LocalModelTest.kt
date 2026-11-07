package com.bruh.angel

import android.os.ParcelFileDescriptor
import android.os.Bundle
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
import kotlinx.coroutines.async
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
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

    private fun report(message: String) {
        println(message)
        InstrumentationRegistry.getInstrumentation().sendStatus(2, Bundle().apply {
            putString("stream", "\n$message\n")
        })
    }

    private fun download(repo: String, path: String): LocalModel = runBlocking {
        val store = LocalModelStore.get(context)
        HuggingFace.reconcile(context)
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
    fun missingDownloadedFileIsMarkedFailed() = runBlocking {
        val store = LocalModelStore.get(context)
        val id = LocalModelStore.newId()
        val file = java.io.File(context.cacheDir, "$id.gguf")
        try {
            store.upsert(LocalModel(id, "Missing test model", LocalModel.Source.FILE, file.path, 8))
            HuggingFace.reconcile(context)
            assertEquals(LocalModel.Status.FAILED, store.get(id)!!.status)
            assertTrue(store.get(id)!!.error.orEmpty().contains("missing"))
        } finally {
            store.remove(id)
        }
    }

    @Test
    fun openDescriptorLoadsWithoutReopeningAPath() = runBlocking {
        assumeTrue("opt-in: -e realModel true", InstrumentationRegistry.getArguments().getString("realModel") == "true")
        val model = download("ggml-org/Qwen3-0.6B-GGUF", "Qwen3-0.6B-Q4_0.gguf")
        LocalEngine.ensureLoaded(context, model, LocalParams(contextSize = 1024))
        LocalEngine.unload() // initialize the backend, then test the descriptor API directly
        val handle = ParcelFileDescriptor.open(java.io.File(model.location), ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
            // An invalid path proves native code uses the descriptor. The borrowed fd closes here.
            LlamaNative.load("/unreadable/model.gguf", 1024, 2, fd.fd)
        }
        try {
            val messages = JSONArray().put(JSONObject().put("role", "user").put("content", "Say hello."))
            val prompt = LlamaNative.prepare(handle, messages.toString().toByteArray(), null, false)
            assertTrue(LlamaNative.generate(handle, prompt, 8, 0f, .95f, 40, .05f, 1, null).isNotEmpty())
        } finally {
            LlamaNative.free(handle)
        }
    }

    /** Opt-in probe for a model imported through the system picker; does not change selection. */
    @Test
    fun importedModelLoadsAndGenerates() = runBlocking {
        val id = InstrumentationRegistry.getArguments().getString("modelId")
        assumeTrue("opt-in: -e modelId <local model id>", !id.isNullOrBlank())
        val model = LocalModelStore.get(context).get(id!!)!!
        try {
            LocalEngine.ensureLoaded(context, model, LocalParams(contextSize = 2048, temperature = 0f))
            LocalEngine.withHandle { handle ->
                val messages = JSONArray().put(JSONObject().put("role", "user").put("content", "Reply with a short greeting."))
                val prompt = LlamaNative.prepare(handle, messages.toString().toByteArray(), null, false)
                val result = LlamaNative.generate(handle, prompt, 32, 0f, .95f, 40, .05f, 1, null)
                val parsed = JSONObject(String(LlamaNative.parse(handle, result, false)))
                report("Model probe: ${model.name}; ${LlamaNative.info(handle)}; reply=${parsed.optString("content")}")
                assertTrue(parsed.toString(), parsed.optString("content").isNotBlank())
            }
        } finally {
            LocalEngine.unload()
        }
    }

    /** Opt-in comparison of cold load/prefill/generation with CPU weight repacking enabled/disabled. */
    @Test
    fun benchmarkImportedModelWeightBuffers() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val id = args.getString("benchmarkModelId")
        assumeTrue("opt-in: benchmarkModelId", !id.isNullOrBlank())
        val model = LocalModelStore.get(context).get(id!!)!!
        val repack = args.getString("repack") != "false"
        LocalEngine.unload()
        LocalEngine.systemInfo(context)
        val start = System.nanoTime()
        val handle = if (model.source == LocalModel.Source.LINKED) {
            context.contentResolver.openFileDescriptor(android.net.Uri.parse(model.location), "r")!!.use {
                LlamaNative.load("", 2048, 6, fd = it.fd, repack = repack)
            }
        } else LlamaNative.load(model.location, 2048, 6, repack = repack)
        val loadMs = (System.nanoTime() - start) / 1_000_000
        report("Weight buffer load: model=${model.name} repack=$repack loadMs=$loadMs")
        try {
            val messages = JSONArray().put(JSONObject().put("role", "user").put("content",
                "Context: " + "hello ".repeat(500) + "\nReply with a short greeting."))
            val preparing = System.nanoTime()
            val prompt = LlamaNative.prepare(handle, messages.toString().toByteArray(), null, false)
            report("Weight buffer prepareMs=${(System.nanoTime() - preparing) / 1_000_000}")
            val output = LlamaNative.generate(handle, prompt, 32, 0f, .95f, 40, .05f, 1, null)
            val parsing = System.nanoTime()
            val parsed = JSONObject(String(LlamaNative.parse(handle, output, false)))
            report("Weight buffer parseMs=${(System.nanoTime() - parsing) / 1_000_000}")
            report("Weight buffers: model=${model.name} repack=$repack loadMs=$loadMs stats=${LlamaNative.stats(handle)} reply=${parsed.optString("content")}")
            assertTrue(parsed.toString(), parsed.optString("content").isNotBlank())
        } finally {
            val freeing = System.nanoTime()
            LlamaNative.free(handle)
            report("Weight buffer freeMs=${(System.nanoTime() - freeing) / 1_000_000}")
        }
    }

    @Test
    fun cancellingModelLoadReturnsIdleAndTheModelCanBeLoadedAgain() = runBlocking {
        val id = InstrumentationRegistry.getArguments().getString("loadCancellationModelId")
        assumeTrue("opt-in: loadCancellationModelId", !id.isNullOrBlank())
        val model = LocalModelStore.get(context).get(id!!)!!
        val progress = CompletableDeferred<Unit>()
        try {
            LocalEngine.unload()
            val task = async {
                LocalEngine.ensureLoaded(context, model, LocalParams(contextSize = 2048)) { percent ->
                    if (percent in 1..95) progress.complete(Unit)
                }
            }
            withTimeout(120_000) { progress.await() }
            val stopped = System.nanoTime()
            withTimeout(15_000) { task.cancelAndJoin() }
            val stopMs = (System.nanoTime() - stopped) / 1_000_000
            report("Stop during model loading: ${stopMs}ms; state=${LocalEngine.state.value}")
            assertEquals(LocalEngine.State.Idle, LocalEngine.state.value)
            withTimeout(180_000) { LocalEngine.ensureLoaded(context, model, LocalParams(contextSize = 2048)) }
            assertTrue(LocalEngine.isLoaded(model.id))
        } finally { LocalEngine.unload() }
    }

    @Test
    fun importedAgentAnswersGreeting() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val id = args.getString("modelId")
        assumeTrue("opt-in: -e modelId <local model id>", !id.isNullOrBlank())
        val model = LocalModelStore.get(context).get(id!!)!!
        val saved = com.bruh.angel.model.ProviderSettingsStore(context).loadLocalParams()
        val useSaved = args.getString("useSavedLocalParams").toBoolean()
        val params = if (useSaved) saved else
            LocalParams(contextSize = 4096, temperature = 0f, maxTokens = 64, thinking = false)
        report("Greeting parameters: ${params.toJson()}")
        val progress = mutableListOf<String>()
        try {
            LocalEngine.ensureLoaded(context, model, params)
            val start = System.nanoTime()
            var firstText: Long? = null
            val session = LocalModelSession(context, model, params, onProgress = { progress += it })
            session.user("How are you? Answer in one short sentence.")
            val reply = withTimeout(if (useSaved) 300_000L else 90_000L) {
                session.next { if (it.isNotBlank() && firstText == null) firstText = (System.nanoTime() - start) / 1_000_000 }
            }
            report("Greeting: firstTextMs=$firstText totalMs=${(System.nanoTime() - start) / 1_000_000}; reply=${reply.text}")
            assertTrue(reply.text, reply.text.isNotBlank() && !reply.text.startsWith("(The model returned an empty reply"))
            assertTrue(progress.toString(), progress.any { it.startsWith("Reading prompt") })
        } finally { LocalEngine.unload() }
    }

    @Test
    fun cancellingPrefillStopsQuicklyAndNextReplyWorks() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val id = args.getString("modelId")
        assumeTrue("opt-in: modelId or realModel", !id.isNullOrBlank() || args.getString("realModel") == "true")
        val model = if (id != null) LocalModelStore.get(context).get(id)!!
            else download("ggml-org/Qwen3-0.6B-GGUF", "Qwen3-0.6B-Q4_0.gguf")
        val params = LocalParams(contextSize = 4096, temperature = 0f, maxTokens = 32, thinking = false)
        try {
            LocalEngine.ensureLoaded(context, model, params)
            val started = CompletableDeferred<Unit>()
            var watching = false
            val warm = LocalModelSession(context, model, params, onProgress = {
                if (watching && it.startsWith("Reading prompt")) started.complete(Unit)
            })
            warm.user("Say hello in one short sentence.")
            withTimeout(90_000) { warm.next() }
            watching = true
            warm.user("Hello ".repeat(1600))
            val task = async {
                warm.next()
            }
            withTimeout(20_000) { started.await() }
            delay(150) // cancel inside a decode, rather than before it begins
            val stopped = System.nanoTime()
            withTimeout(5_000) { task.cancelAndJoin() }
            val stopMs = (System.nanoTime() - stopped) / 1_000_000
            report("Stop during prompt evaluation: ${stopMs}ms")
            assertTrue("Stop took ${stopMs}ms", stopMs < 3000)
            val session = LocalModelSession(context, model, params)
            session.user("Say hello in one short sentence.")
            val reply = withTimeout(90_000) { session.next() }
            assertTrue(reply.text, reply.text.isNotBlank() && !reply.text.startsWith("(The model returned an empty reply"))
            val reused = LocalEngine.withHandle { JSONObject(LlamaNative.stats(it)).getInt("reused") }
            // Recurrent models may need the documented full-cache fallback after an abort.
            if (id == null || args.getString("requirePrefixReuse") == "true") {
                assertTrue("Completed prompt prefix should survive Stop; reused=$reused", reused >= 128)
            }
        } finally { LocalEngine.unload() }
    }

    @Test
    fun cancellingGeneratedReplyAllowsTwoFollowingTurns() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val id = args.getString("modelId")
        assumeTrue("opt-in: modelId or realModel", !id.isNullOrBlank() || args.getString("realModel") == "true")
        val model = if (id != null) LocalModelStore.get(context).get(id)!!
            else download("ggml-org/Qwen3-0.6B-GGUF", "Qwen3-0.6B-Q4_0.gguf")
        val params = LocalParams(contextSize = 4096, temperature = 0f, maxTokens = 512, thinking = false)
        try {
            LocalEngine.ensureLoaded(context, model, params)
            val started = CompletableDeferred<Unit>()
            val session = LocalModelSession(context, model, params)
            session.user("Write a long fairy tale of at least 600 words about a dragon learning to cook. Begin the story immediately.")
            val task = async { session.next { if (it.isNotBlank()) started.complete(Unit) } }
            withTimeout(120_000) { started.await() }
            assertTrue("The story must still be generating when Stop is pressed", !task.isCompleted)
            val stopped = System.nanoTime()
            withTimeout(5_000) { task.cancelAndJoin() }
            val stopMs = (System.nanoTime() - stopped) / 1_000_000
            report("Stop during generated reply: ${stopMs}ms")
            assertTrue("Stop took ${stopMs}ms", stopMs < 3000)

            val recovered = LocalModelSession(context, model, params.copy(maxTokens = 64))
            recovered.user("Say hello in one short sentence.")
            val hello = withTimeout(90_000) { recovered.next() }
            assertTrue(hello.text, hello.text.isNotBlank() && !hello.text.startsWith("(The model returned an empty reply"))
            recovered.user("What is 2 + 2? Answer only the number.")
            val followup = withTimeout(90_000) { recovered.next() }
            report("After Stop: ${hello.text}; follow-up=${followup.text}")
            assertTrue(followup.text, followup.text.isNotBlank() && !followup.text.startsWith("(The model returned an empty reply"))
            if (id == null || args.getString("fullPrefillControl") == "true") {
                LocalEngine.unload()
                val baseline = LocalModelSession(context, model, params.copy(maxTokens = 64))
                baseline.user("Say hello in one short sentence.")
                val baselineHello = withTimeout(90_000) { baseline.next() }
                baseline.user("What is 2 + 2? Answer only the number.")
                // A full-prefill control checks cache correctness independently of model accuracy.
                LocalEngine.withHandle { LlamaNative.clearCache(it) }
                val baselineFollowup = withTimeout(90_000) { baseline.next() }
                report("Fresh baseline: ${baselineHello.text}; follow-up=${baselineFollowup.text}")
                assertEquals("The cached follow-up should match a full prompt evaluation", baselineFollowup.text, followup.text)
            }
        } finally { LocalEngine.unload() }
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
                val raw = LlamaNative.generate(h, prompt, 24, 0f, 0.95f, 40, 0.05f, 1, listener = { streamed++; true })
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


