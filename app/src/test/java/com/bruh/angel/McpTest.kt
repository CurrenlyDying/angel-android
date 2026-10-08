package com.bruh.angel

import com.bruh.angel.agent.ProviderException
import com.bruh.angel.agent.ToolSchemas
import com.bruh.angel.agent.validatedToolCall
import com.bruh.angel.linuxenv.LinuxDistro
import com.bruh.angel.mcp.McpClient
import com.bruh.angel.mcp.McpInput
import com.bruh.angel.mcp.McpKind
import com.bruh.angel.mcp.McpNaming
import com.bruh.angel.mcp.McpNet
import com.bruh.angel.mcp.McpPackage
import com.bruh.angel.mcp.McpPackageType
import com.bruh.angel.mcp.McpRecipes
import com.bruh.angel.mcp.McpRegistry
import com.bruh.angel.mcp.McpRuntime
import com.bruh.angel.mcp.McpServerConfig
import com.bruh.angel.mcp.McpSetup
import com.bruh.angel.mcp.McpTool
import com.bruh.angel.mcp.readSse
import com.bruh.angel.model.ToolPolicy
import com.bruh.angel.ui.mcp.headerValue
import com.bruh.angel.ui.mcp.parseEnv
import com.bruh.angel.ui.mcp.parseHeaders
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.BufferedReader
import java.io.StringReader

class McpTest {
    private fun tool(name: String) = McpTool(name, "", "", JSONObject(), false)

    // ---- config ----

    @Test
    fun configSurvivesJsonRoundTrip() {
        val c = McpServerConfig(
            id = "fetch", name = "Fetch", kind = McpKind.CATALOG, runtime = McpRuntime.LINUX, command = "run it",
            installScript = "echo hi", url = "https://x/mcp", env = mapOf("K" to "v"), headers = mapOf("Authorization" to "Bearer t"),
            enabled = false, policy = ToolPolicy.ALLOW, installedDistro = "alpine", disabledTools = setOf("a", "b"),
            description = "d", source = "pypi|mcp-server-fetch|1.0"
        )
        assertEquals(c, McpServerConfig.fromJson(JSONObject(c.toJson().toString())))
    }

    @Test
    fun configWithBadIdIsRejected() {
        assertNull(McpServerConfig.fromJson(JSONObject().put("id", "../etc").put("name", "x")))
        assertNull(McpServerConfig.fromJson(JSONObject().put("id", "Upper")))
    }

    @Test
    fun slugIsSafeAndUnique() {
        assertEquals("brave-search", McpServerConfig.slug("Brave Search!", emptySet()))
        assertEquals("brave-search-2", McpServerConfig.slug("Brave Search!", setOf("brave-search")))
        assertEquals("server", McpServerConfig.slug("!!!", emptySet()))
        assertEquals("abcdefghijklmnop", McpServerConfig.slug("abcdefghijklmnopqrstuvwxyz", emptySet()))
        assertTrue(McpServerConfig.ID.matches(McpServerConfig.slug("Ünïcode ✨ name", emptySet())))
    }

    // ---- naming ----

    @Test
    fun namingSanitizesCollidesAndSplits() {
        assertEquals("get_weather", McpNaming.sanitize("get weather"))
        assertEquals("tool", McpNaming.sanitize("???"))
        assertEquals(40, McpNaming.sanitize("x".repeat(100)).length)
        val refs = McpNaming.qualify("srv", listOf(tool("a b"), tool("a_b"), tool("c")))
        assertEquals(listOf("mcp__srv__a_b", "mcp__srv__a_b_2", "mcp__srv__c"), refs.map { it.qualified })
        assertEquals("fetch" to "fetch", McpNaming.split("mcp__fetch__fetch"))
        assertEquals("a" to "b__c", McpNaming.split("mcp__a__b__c"))
        assertNull(McpNaming.split("mcp__x__"))
        assertNull(McpNaming.split("run_shell"))
    }

    // ---- schemas ----

    private fun js(text: String) = JSONObject(text.replace('@', '$'))
    private fun strings(a: JSONArray) = (0 until a.length()).map { a.getString(it) }

    @Test
    fun geminiSchemaResolvesRefsAndDropsUnsupportedKeys() {
        val schema = js("""{"@schema":"x","type":"object","additionalProperties":false,
            "properties":{"a":{"@ref":"#/@defs/A"},"b":{"type":["string","null"]},
              "c":{"allOf":[{"type":"object","properties":{"d":{"type":"integer"}},"required":["d","zz"]}]}},
            "required":["a","missing"],
            "@defs":{"A":{"type":"string","description":"an A"}}}""")
        val out = ToolSchemas.parameters(schema, gemini = true)!!
        val props = out.getJSONObject("properties")
        assertEquals("string", props.getJSONObject("a").getString("type"))
        assertEquals("an A", props.getJSONObject("a").getString("description"))
        assertEquals("string", props.getJSONObject("b").getString("type"))
        assertTrue(props.getJSONObject("b").getBoolean("nullable"))
        val c = props.getJSONObject("c")
        assertEquals("integer", c.getJSONObject("properties").getJSONObject("d").getString("type"))
        assertEquals(listOf("d"), strings(c.getJSONArray("required")))
        assertEquals(listOf("a"), strings(out.getJSONArray("required")))
        assertFalse(out.has("additionalProperties"))
        assertFalse(out.has("\$schema"))
        assertFalse(out.has("\$defs"))
    }

    @Test
    fun otherProvidersKeepStandardKeywordsButLoseSchemaMetadata() {
        val schema = js("""{"@schema":"x","type":"object","additionalProperties":false,"properties":{"a":{"type":["string","null"]}}}""")
        val out = ToolSchemas.parameters(schema, gemini = false)!!
        assertTrue(out.has("additionalProperties"))
        assertFalse(out.has("\$schema"))
        assertFalse(out.getJSONObject("properties").getJSONObject("a").has("nullable"))
    }

    @Test
    fun noArgumentToolsAreOmittedForGeminiOnly() {
        val none = JSONObject("""{"type":"object"}""")
        assertNull(ToolSchemas.parameters(none, gemini = true))
        val other = ToolSchemas.parameters(none, gemini = false)!!
        assertEquals(0, other.getJSONObject("properties").length())
    }

    @Test
    fun recursiveRefsTerminate() {
        val schema = js("""{"type":"object","properties":{"n":{"@ref":"#/@defs/N"}},
            "@defs":{"N":{"type":"object","properties":{"child":{"@ref":"#/@defs/N"}}}}}""")
        assertNotNull(ToolSchemas.parameters(schema, gemini = false))
        ToolSchemas.parameters(schema, gemini = true)
    }

    // ---- recipes ----

    private val npm = McpPackage(McpPackageType.NPM, "@modelcontextprotocol/server-memory", "2026.8.31")
    private val pypi = McpPackage(McpPackageType.PYPI, "mcp-server-time", "2026.8.18",
        inputs = listOf(McpInput("tz", "Timezone", McpInput.Kind.ARG, flag = "--local-timezone", default = "UTC")))

    @Test
    fun quoteEscapesSingleQuotes() {
        assertEquals("'it'\\''s'", McpRecipes.quote("it's"))
        assertEquals("''", McpRecipes.quote(""))
    }

    @Test
    fun validationRejectsShellMetacharactersInPackageCoordinates() {
        assertNull(McpRecipes.validate(npm))
        assertNull(McpRecipes.validate(pypi))
        assertNotNull(McpRecipes.validate(npm.copy(version = "1.0.0; rm -rf /")))
        assertNotNull(McpRecipes.validate(npm.copy(identifier = "x\$(id)")))
        assertNotNull(McpRecipes.validate(pypi.copy(identifier = "a b")))
        assertNotNull(McpRecipes.validate(pypi.copy(version = "")))
    }

    @Test
    fun installScriptsUseTheDistrosPackageManager() {
        val apt = LinuxDistro.entries.first { it.aptBased }
        val apk = LinuxDistro.entries.first { !it.aptBased }
        val aptScript = McpRecipes.installScript("memory", npm, apt)
        assertTrue(aptScript.startsWith("set -e"))
        assertTrue(aptScript.contains("apt-get"))
        assertTrue(aptScript.contains("npm install"))
        assertTrue(aptScript.contains("nodejs.org/dist/v24.21.0/node-v24.21.0-linux-arm64.tar.gz"))
        assertTrue(aptScript.contains("sha256sum -c"))
        assertTrue(aptScript.contains("need=\"\$need wget\""))
        assertFalse(McpRecipes.installScript("time", pypi, apt).contains("nodejs.org"))
        assertTrue(aptScript.contains("dpkg --configure -a"))
        assertTrue(aptScript.indexOf("dpkg --configure -a") < aptScript.indexOf("apt-get -o"))
        assertTrue(aptScript.indexOf("flock 9") < aptScript.indexOf("dpkg --configure -a"))
        assertTrue(aptScript.contains("'@modelcontextprotocol/server-memory@2026.8.31'"))
        assertTrue(aptScript.contains("rm -rf '/root/.angel/mcp/memory'"))
        val apkScript = McpRecipes.installScript("time", pypi, apk)
        assertTrue(apkScript.contains("apk add"))
        assertTrue(apkScript.contains("venv"))
        assertTrue(apkScript.contains("'mcp-server-time==2026.8.18'"))
        assertFalse(apkScript.contains("apt-get"))
        val apkNode = McpRecipes.installScript("memory", npm, apk)
        assertTrue(apkNode.contains("nodejs npm"))
        assertFalse(apkNode.contains("nodejs.org"))
    }

    @Test
    fun runCommandQuotesEveryArgumentAndUsesDefaults() {
        assertEquals("cd '/root/.angel/mcp/memory' && exec ./node_modules/.bin/\"\$(cat entry.txt)\"", McpRecipes.runCommand("memory", npm, emptyMap()))
        val cmd = McpRecipes.runCommand("time", pypi, mapOf("tz" to "Europe/Paris; reboot"))
        assertTrue(cmd.contains("./venv/bin/"))
        assertTrue(cmd.endsWith("'--local-timezone' 'Europe/Paris; reboot'"))
        assertTrue(McpRecipes.runCommand("time", pypi, emptyMap()).endsWith("'--local-timezone' 'UTC'"))
    }

    @Test
    fun envUsesTypedValuesThenDefaultsAndSkipsEmpty() {
        val p = npm.copy(inputs = listOf(
            McpInput("A", "A", McpInput.Kind.ENV, default = "def"), McpInput("B", "B", McpInput.Kind.ENV), McpInput("C", "C", McpInput.Kind.ENV)
        ))
        assertEquals(mapOf("A" to "def", "C" to "x"), McpRecipes.env(p, mapOf("C" to " x ")))
    }

    @Test
    fun sourceRoundTripsAndRejectsGarbage() {
        assertEquals(npm, McpRecipes.packageFromSource(McpRecipes.source(npm)))
        assertEquals("pypi|mcp-server-time|2026.8.18", McpRecipes.source(pypi))
        assertNull(McpRecipes.packageFromSource("npm|x y|1"))
        assertNull(McpRecipes.packageFromSource("other|a|1"))
        assertNull(McpRecipes.packageFromSource(""))
    }

    @Test
    fun setupBuildsConfigsWithUniqueSafeIds() {
        val remote = McpSetup.remote("", "192.168.1.5:8000/mcp", mapOf("Authorization" to "Bearer t", "X" to ""), setOf("192-168-1-5-8000"))
        assertEquals("http://192.168.1.5:8000/mcp", remote.url)
        assertEquals("192-168-1-5-8000-2", remote.id)
        assertEquals(mapOf("Authorization" to "Bearer t"), remote.headers)
        assertEquals(McpKind.REMOTE, remote.kind)
        val custom = McpSetup.custom("My Server", "  node x.js ", "", McpRuntime.ANDROID, emptyMap(), emptySet())
        assertEquals("node x.js", custom.command)
        assertEquals(McpKind.CUSTOM, custom.kind)
    }

    // ---- registry ----

    @Test
    fun registryKeepsOnlySafeInstallableEntries() {
        val json = """{"servers":[
          {"server":{"name":"io.github.acme/cool-server","description":"Cool","repository":{"url":"https://github.com/acme/cool"},
            "packages":[{"registryType":"npm","identifier":"@acme/cool","version":"1.2.3","transport":{"type":"stdio"},
              "environmentVariables":[{"name":"API_KEY","description":"key","isRequired":true,"isSecret":true}],
              "packageArguments":[{"type":"positional","value":"serve"},{"type":"named","name":"--root","isRequired":true,"description":"dir"}]}],
            "remotes":[{"type":"streamable-http","url":"https://cool.example.com/mcp","headers":[{"name":"Authorization","value":"Bearer {token}","isSecret":true}]}]}},
          {"server":{"name":"io.github.acme/image-only","packages":[{"registryType":"oci","identifier":"x","version":"1"}]}},
          {"server":{"name":"io.github.acme/plain-http","remotes":[{"type":"streamable-http","url":"http://public.example.com/mcp"}]}},
          {"server":{"name":"io.github.acme/evil","packages":[{"registryType":"npm","identifier":"evil","version":"1.0; rm -rf /"}]}},
          {"server":{"name":"io.github.acme/templated","remotes":[{"type":"sse","url":"https://{tenant}.example.com/sse"}]}}
        ],"metadata":{"nextCursor":"abc"}}"""
        val page = McpRegistry.parse(json)
        assertEquals("abc", page.nextCursor)
        assertEquals(1, page.entries.size)
        val e = page.entries[0]
        assertEquals("io.github.acme/cool-server", e.id)
        assertEquals("cool-server", e.name)
        assertEquals("acme.github.io", e.publisher)
        assertFalse(e.curated)
        val pkg = e.pkg!!
        assertEquals(McpPackageType.NPM, pkg.type)
        assertEquals(listOf("serve"), pkg.fixedArgs)
        assertEquals(listOf("API_KEY", "arg1"), pkg.inputs.map { it.key })
        assertTrue(pkg.inputs[0].secret && pkg.inputs[0].required)
        assertEquals("--root", pkg.inputs[1].flag)
        val remote = e.remote!!
        assertEquals("https://cool.example.com/mcp", remote.url)
        assertEquals(McpInput.Kind.HEADER, remote.inputs[0].kind)
        assertEquals("Bearer ", remote.inputs[0].default)
    }

    @Test
    fun registryHandlesEmptyAnswers() {
        assertTrue(McpRegistry.parse("""{"servers":[]}""").entries.isEmpty())
        assertNull(McpRegistry.parse("""{"servers":[],"metadata":{"nextCursor":""}}""").nextCursor)
    }

    // ---- network policy ----

    @Test
    fun httpIsOnlyAcceptedForPrivateHosts() {
        listOf(
            "http://192.168.1.5:8000/mcp", "http://10.0.0.2/x", "http://172.16.0.1", "http://169.254.1.1", "http://127.0.0.1:3000",
            "http://localhost:3000/mcp", "http://nas.local/mcp", "http://myhost/mcp", "http://100.64.1.2/mcp", "http://[fd00::1]/mcp",
            "http://box.tail1234.ts.net/mcp", "https://example.com/mcp"
        ).forEach { assertNull(it, McpNet.check(it)) }
        listOf(
            "http://example.com/mcp", "http://8.8.8.8", "http://172.32.0.1", "http://100.128.0.1", "ftp://192.168.1.1",
            "http://user:pw@192.168.1.1/", "https:///nohost", "not a url"
        ).forEach { assertNotNull(it, McpNet.check(it)) }
    }

    @Test
    fun normalizeAddsTheRightScheme() {
        assertEquals("http://192.168.1.5:8000/mcp", McpNet.normalize(" 192.168.1.5:8000/mcp "))
        assertEquals("https://example.com/mcp", McpNet.normalize("example.com/mcp"))
        assertEquals("http://localhost:3000", McpNet.normalize("localhost:3000"))
        assertEquals("https://a.b/c", McpNet.normalize("https://a.b/c"))
        assertEquals("", McpNet.normalize("   "))
    }

    // ---- protocol helpers ----

    @Test
    fun sseParserHandlesEventsCommentsAndMultilineData() {
        val text = "event: endpoint\ndata: /messages?x=1\n\n: keep-alive\ndata: {\"a\":1}\ndata: more\n\nevent: last\ndata: tail"
        val events = ArrayList<Pair<String, String>>()
        readSse(BufferedReader(StringReader(text))) { e, d -> events += e to d }
        assertEquals(listOf("endpoint" to "/messages?x=1", "message" to "{\"a\":1}\nmore", "last" to "tail"), events)
    }

    @Test
    fun toolResultsBecomeText() {
        val result = JSONObject().put("content", JSONArray()
            .put(JSONObject().put("type", "text").put("text", "hello"))
            .put(JSONObject().put("type", "image").put("mimeType", "image/png").put("data", "AAAA"))
            .put(JSONObject().put("type", "resource").put("resource", JSONObject().put("uri", "file:///x").put("text", "body"))))
        assertEquals("hello\n[image image/png omitted]\nbody", McpClient.contentToText(result))
        assertEquals("(no output)", McpClient.contentToText(JSONObject()))
        assertEquals("{\"k\":1}", McpClient.contentToText(JSONObject().put("structuredContent", JSONObject().put("k", 1))))
        val big = McpClient.contentToText(JSONObject().put("content", JSONArray().put(JSONObject().put("type", "text").put("text", "x".repeat(40_000)))))
        assertTrue(big.endsWith("[output truncated: 10000 more characters]"))
    }

    // ---- agent integration ----

    @Test
    fun mcpToolCallsKeepTheirArgumentsAsJson() {
        val call = validatedToolCall("id1", "mcp__fetch__fetch", JSONObject().put("url", "https://example.com"))
        assertEquals("mcp__fetch__fetch", call.name)
        assertEquals("https://example.com", JSONObject(call.command).getString("url"))
    }

    @Test
    fun hostedServersOnOneMachineGetDistinctNames() {
        val a = McpSetup.remote("", "192.168.1.5:8765/mcp", emptyMap(), emptySet())
        val b = McpSetup.remote("", "192.168.1.5:8766/sse", emptyMap(), setOf(a.id))
        assertEquals("192.168.1.5:8765", a.name)
        assertEquals("192.168.1.5:8766", b.name)
        assertEquals("example.com", McpSetup.remote("", "https://example.com/mcp", emptyMap(), emptySet()).name)
    }

    @Test
    fun mcpArgumentsDoNotShowEscapedSlashes() {
        val call = validatedToolCall("id1", "mcp__fetch__fetch", JSONObject().put("url", "https://example.com/a").put("p", "c:\\dir/x"))
        assertTrue(call.command, call.command.contains("https://example.com/a"))
        assertEquals("c:\\dir/x", JSONObject(call.command).getString("p"))
    }

    @Test
    fun unknownAndOversizedToolCallsAreRejected() {
        try { validatedToolCall("1", "mcp__fetch__fetch", JSONObject().put("x", "y".repeat(100_001))); fail() } catch (_: ProviderException) {}
        try { validatedToolCall("1", "format_disk", JSONObject()); fail() } catch (_: ProviderException) {}
        try { validatedToolCall("1", "mcp__UPPER__x", JSONObject()); fail() } catch (_: ProviderException) {}
    }

    // ---- UI parsing ----

    @Test
    fun headerAndEnvTextParseStrictly() {
        assertEquals(mapOf("Authorization" to "Bearer t", "X-Key" to "v"), parseHeaders("X-Key: v\nbad line\nbad name: v", "t"))
        assertEquals(mapOf("A" to "1=2", "B_C" to ""), parseEnv("A=1=2\nB_C=\n1BAD=x\n=x\nnoequals"))
    }

    @Test
    fun registryHeaderTemplatesKeepTheirPrefix() {
        val bearer = McpInput("Authorization", "Authorization", McpInput.Kind.HEADER, default = "Bearer ")
        assertEquals("Bearer abc", headerValue(bearer, "abc"))
        assertEquals("Bearer abc", headerValue(bearer, "Bearer abc"))
        assertEquals("", headerValue(bearer, "Bearer "))
        assertEquals("plain", headerValue(McpInput("X", "X", McpInput.Kind.HEADER), "plain"))
    }
}
