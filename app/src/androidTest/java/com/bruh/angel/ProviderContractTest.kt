package com.bruh.angel

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bruh.angel.agent.ModelSession
import com.bruh.angel.agent.ProviderApi
import com.bruh.angel.model.Provider
import com.bruh.angel.model.ProviderSettings
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Native request/response contracts, using an in-memory interceptor: no network or paid calls. */
@RunWith(AndroidJUnit4::class)
class ProviderContractTest {
    private fun session(
        provider: Provider,
        replies: List<String>,
        requests: MutableList<Request>,
        bodies: MutableList<JSONObject>,
        code: Int = 200
    ): ModelSession {
        var count = 0
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            requests += request
            val buffer = Buffer()
            request.body!!.writeTo(buffer)
            bodies += JSONObject(buffer.readUtf8())
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("Test")
                .body(replies[count++].toResponseBody("application/json".toMediaType())).build()
        }.build()
        return ModelSession(ProviderSettings(provider, provider.defaultModel, "test-only-key"), http)
    }

    private fun client(reply: String, requests: MutableList<Request>, code: Int = 200) = OkHttpClient.Builder().addInterceptor { chain ->
        requests += chain.request()
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code).message("Test")
            .body(reply.toResponseBody("application/json".toMediaType())).build()
    }.build()

    @Test
    fun openAiAndDeepSeekToolRoundTrips() = runBlocking {
        for (provider in listOf(Provider.OPENAI, Provider.DEEPSEEK)) {
            val requests = mutableListOf<Request>()
            val bodies = mutableListOf<JSONObject>()
            val model = session(provider, listOf(
                // refusal/annotations are response-only fields: echoing them back makes OpenAI return HTTP 400.
                """{"choices":[{"message":{"role":"assistant","content":null,"refusal":null,"annotations":[],"reasoning_content":"retained","tool_calls":[{"id":"call_1","type":"function","function":{"name":"android_shell","arguments":"{\"command\":\"id\"}"}}]}}]}""",
                """{"choices":[{"message":{"role":"assistant","content":"Shell confirmed"}}]}"""
            ), requests, bodies)
            model.user("Check shell UID")
            val reply = model.next()
            assertEquals("id", reply.tools.single().command)
            model.results(listOf(reply.tools.single() to "uid=2000"))
            assertEquals("Shell confirmed", model.next().text)
            assertEquals(provider.endpoint, requests[0].url.toString())
            assertEquals("Bearer test-only-key", requests[0].header("Authorization"))
            val messages = bodies[1].getJSONArray("messages")
            val assistant = messages.getJSONObject(2)
            assertFalse(assistant.has("refusal"))
            assertFalse(assistant.has("annotations"))
            // DeepSeek thinking mode with tools requires reasoning_content to be passed back; others don't accept it.
            assertEquals(provider == Provider.DEEPSEEK, assistant.has("reasoning_content"))
            assertEquals("call_1", assistant.getJSONArray("tool_calls").getJSONObject(0).getString("id"))
            assertEquals("call_1", messages.getJSONObject(3).getString("tool_call_id"))
            assertEquals("uid=2000", messages.getJSONObject(3).getString("content"))
            assertTrue(messages.getJSONObject(0).getString("content").contains("never prefix them with `adb`"))
        }
    }

    @Test
    fun deepSeekDefaultIsCurrentModel() {
        assertEquals("deepseek-flash", Provider.DEEPSEEK.defaultModel)
        assertEquals("deepseek-flash", Provider.retiredModels["deepseek-chat"])
    }

    @Test
    fun anthropicToolRoundTrip() = runBlocking {
        val requests = mutableListOf<Request>()
        val bodies = mutableListOf<JSONObject>()
        val model = session(Provider.ANTHROPIC, listOf(
            """{"content":[{"type":"text","text":"Checking"},{"type":"tool_use","id":"tool_1","name":"linux_shell","input":{"command":"uname -m"}}]}""",
            """{"content":[{"type":"text","text":"aarch64"}]}"""
        ), requests, bodies)
        model.user("Check Linux")
        val reply = model.next()
        assertEquals("Checking", reply.text)
        model.results(listOf(reply.tools.single() to "aarch64"))
        assertEquals("aarch64", model.next().text)
        assertEquals("2023-06-01", requests[0].header("anthropic-version"))
        val result = bodies[1].getJSONArray("messages").getJSONObject(2).getJSONArray("content").getJSONObject(0)
        assertEquals("tool_result", result.getString("type"))
        assertEquals("tool_1", result.getString("tool_use_id"))
    }

    @Test
    fun geminiRetainsThoughtSignatureAndNativeResponse() = runBlocking {
        val requests = mutableListOf<Request>()
        val bodies = mutableListOf<JSONObject>()
        val model = session(Provider.GOOGLE, listOf(
            """{"candidates":[{"content":{"role":"model","parts":[{"thoughtSignature":"opaque-signature","functionCall":{"name":"android_shell","args":{"command":"id"}}}]}}]}""",
            """{"candidates":[{"content":{"role":"model","parts":[{"text":"Done"}]}}]}"""
        ), requests, bodies)
        model.user("Check UID")
        val reply = model.next()
        model.results(listOf(reply.tools.single() to "Denied by user"))
        model.next()
        assertEquals("test-only-key", requests[0].header("x-goog-api-key"))
        assertFalse(requests[0].url.toString().contains("test-only-key"))
        val contents = bodies[1].getJSONArray("contents")
        assertEquals("opaque-signature", contents.getJSONObject(1).getJSONArray("parts").getJSONObject(0).getString("thoughtSignature"))
        assertEquals("Denied by user", contents.getJSONObject(2).getJSONArray("parts").getJSONObject(0)
            .getJSONObject("functionResponse").getJSONObject("response").getString("result"))
    }

    @Test
    fun readScreenToolHasOptionalFilter() = runBlocking {
        val bodies = mutableListOf<JSONObject>()
        val model = session(Provider.ANTHROPIC, listOf(
            """{"content":[{"type":"tool_use","id":"s1","name":"read_screen","input":{}}]}"""
        ), mutableListOf(), bodies)
        model.user("What's on screen?")
        val tool = model.next().tools.single()
        assertEquals("read_screen", tool.name)
        assertEquals("", tool.command)
        val tools = bodies[0].getJSONArray("tools")
        assertEquals(listOf("android_shell", "linux_shell", "read_screen"), (0 until tools.length()).map { tools.getJSONObject(it).getString("name") })
    }

    @Test
    fun httpErrorsShowProviderReasonButRedactKeys() = runBlocking {
        val model = session(Provider.DEEPSEEK,
            listOf("""{"error":{"message":"Model Not Exist (key test-only-key)","type":"invalid_request_error"}}"""),
            mutableListOf(), mutableListOf(), 400)
        model.user("hello")
        val message = runCatching { model.next() }.exceptionOrNull()!!.message!!
        assertTrue(message, message.contains("DeepSeek HTTP 400"))
        assertTrue(message, message.contains("Model Not Exist"))
        assertTrue(message, message.contains("fetch models", ignoreCase = true))
        assertFalse(message, message.contains("test-only-key"))

        val auth = session(Provider.OPENAI, listOf("""{"error":"bad sk-abcdefghijklmnop"}"""), mutableListOf(), mutableListOf(), 401)
        auth.user("hello")
        val authMessage = runCatching { auth.next() }.exceptionOrNull()!!.message!!
        assertTrue(authMessage, authMessage.contains("401"))
        assertFalse(authMessage, authMessage.contains("sk-abcdefghijklmnop"))
    }

    @Test
    fun listModelsFiltersAndAuthenticates() = runBlocking {
        val requests = mutableListOf<Request>()
        val openAi = ProviderApi.listModels(ProviderSettings(Provider.OPENAI, "x", "test-only-key"), client(
            """{"data":[{"id":"gpt-6.1-sol"},{"id":"text-embedding-3-large"},{"id":"gpt-4o-mini-tts"},{"id":"gpt-6-luna"}]}""", requests))
        assertEquals(listOf("gpt-6-luna", "gpt-6.1-sol"), openAi)
        assertEquals(Provider.OPENAI.modelsUrl, requests[0].url.toString())
        assertEquals("Bearer test-only-key", requests[0].header("Authorization"))
        val gemini = ProviderApi.listModels(ProviderSettings(Provider.GOOGLE, "x", "test-only-key"), client(
            """{"models":[{"name":"models/gemini-3.8-flash","supportedGenerationMethods":["generateContent"]},{"name":"models/embedding-1","supportedGenerationMethods":["embedContent"]}]}""",
            requests))
        assertEquals(listOf("gemini-3.8-flash"), gemini)
        assertEquals("test-only-key", requests[1].header("x-goog-api-key"))
        val anthropic = ProviderApi.listModels(ProviderSettings(Provider.ANTHROPIC, "x", "test-only-key"), client(
            """{"data":[{"id":"claude-sonnet-5-5"},{"id":"claude-opus-5-5"}]}""", requests))
        assertEquals(listOf("claude-opus-5-5", "claude-sonnet-5-5"), anthropic)
        assertEquals("2023-06-01", requests[2].header("anthropic-version"))
    }

    @Test
    fun unknownToolsAreRejected() = runBlocking {
        val model = session(Provider.ANTHROPIC,
            listOf("""{"content":[{"type":"tool_use","id":"bad","name":"unknown","input":{"command":"id"}}]}"""),
            mutableListOf(), mutableListOf())
        model.user("hello")
        assertTrue(runCatching { model.next() }.isFailure)
    }
}

