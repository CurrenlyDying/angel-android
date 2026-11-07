package com.bruh.angel

import com.bruh.angel.linuxenv.LinuxDistro
import com.bruh.angel.mcp.McpKind
import com.bruh.angel.mcp.McpRecipes
import com.bruh.angel.mcp.McpRuntime
import com.bruh.angel.mcp.McpSetup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class GithubRecipeTest {

    private fun pythonFixture(test: (File, File) -> Unit) {
        org.junit.Assume.assumeTrue(File("/usr/bin/python3").canExecute())
        val root = Files.createTempDirectory("angel-python-entry").toFile()
        try {
            val project = File(root, "project's folder").apply { mkdirs() }
            val site = File(root, "site").apply { mkdirs() }
            test(project, site)
        } finally { root.deleteRecursively() }
    }

    private fun distribution(site: File, name: String, source: File, entry: String) {
        File(site, "$name-1.0.dist-info").apply {
            mkdirs()
            File(this, "METADATA").writeText("Metadata-Version: 2.1\nName: $name\nVersion: 1.0\n")
            File(this, "entry_points.txt").writeText("[console_scripts]\n$entry = fixture:main\n")
            File(this, "direct_url.json").writeText(org.json.JSONObject()
                .put("url", source.toURI().toString()).put("dir_info", org.json.JSONObject()).toString())
        }
    }

    private fun selectPythonStart(project: File, site: File): Pair<Int, String> {
        val process = ProcessBuilder("/usr/bin/python3", "-c", McpRecipes.githubPythonStartSelector, project.path)
            .redirectErrorStream(true).apply { environment()["PYTHONPATH"] = site.path }.start()
        val output = process.inputStream.bufferedReader().use { it.readText().trim() }
        return process.waitFor() to output
    }

    @Test fun pythonStartUsesTheProjectsEntryNotADependencyExecutable() = pythonFixture { project, site ->
        distribution(site, "aaa-dependency", File(project.parentFile, "dependency"), "cffi-gen-src")
        distribution(site, "fixture-server", project, "mcp-server-fixture")
        val (exit, command) = selectPythonStart(project, site)
        assertEquals(command, 0, exit)
        assertTrue(command, command.endsWith("/mcp-server-fixture"))
        assertTrue(command, !command.contains("cffi-gen-src"))
    }

    @Test fun requirementsProjectStartsItsOwnScriptWithQuotedPaths() = pythonFixture { project, site ->
        File(project, "server.py").writeText("print('fixture-script-ran')\n")
        distribution(site, "aaa-dependency", File(project.parentFile, "dependency"), "cffi-gen-src")
        val (exit, command) = selectPythonStart(project, site)
        assertEquals(command, 0, exit)
        val process = ProcessBuilder("/bin/sh", "-c", command).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().use { it.readText().trim() }
        assertEquals(output, 0, process.waitFor())
        assertEquals("fixture-script-ran", output)
    }

    @Test fun missingPythonEntryDoesNotFallBackToADependencyCommand() = pythonFixture { project, site ->
        distribution(site, "aaa-dependency", File(project.parentFile, "dependency"), "cffi-gen-src")
        val (exit, message) = selectPythonStart(project, site)
        assertTrue(message, exit != 0)
        assertTrue(message, message.contains("Could not find a console entry point"))
    }

    @Test fun validateGithub_rules() {
        assertNull(McpRecipes.validateGithub("https://github.com/owner/repo", "", ""))
        assertNull(McpRecipes.validateGithub("https://github.com/owner/repo", "main", "packages/server"))
        assertNotNull(McpRecipes.validateGithub("https://github.com/owner/repo", "", "../etc")) // traversal
        assertNotNull(McpRecipes.validateGithub("https://evil.example/owner/repo", "", ""))
        assertNotNull(McpRecipes.validateGithub("https://github.com/owner/repo", "bad branch!", ""))
    }

    @Test fun repoName_derivation() {
        assertEquals("repo", McpRecipes.githubRepoName("https://github.com/owner/repo"))
        assertEquals("My-Server", McpRecipes.githubRepoName("https://github.com/owner/My-Server.git"))
        assertEquals("repo", McpRecipes.githubRepoName("https://github.com/owner/repo/"))
    }

    @Test fun installScript_clonesAndDetects() {
        val script = McpRecipes.githubInstallScript("gh", "https://github.com/owner/repo", "", "", LinuxDistro.ALPINE)
        assertTrue(script.contains("git clone --depth 1"))
        assertTrue(script.contains("'/root/.angel/mcp/gh'"))   // DIR is single-quoted
        assertTrue(script.contains("package.json"))
        assertTrue(script.contains("pyproject.toml"))
        assertTrue(script.contains("run.sh"))
        assertTrue(script.contains("apk add"))                 // Alpine path
    }

    @Test fun installScript_aptDistroFetchesNode() {
        val script = McpRecipes.githubInstallScript("gh", "https://github.com/owner/repo", "dev", "", LinuxDistro.DEBIAN)
        assertTrue(script.contains("nodejs.org/dist"))
        assertTrue(script.contains("apt-get"))
        assertTrue(script.contains("--branch 'dev'"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun installScript_rejectsTraversal() {
        McpRecipes.githubInstallScript("gh", "https://github.com/owner/repo", "", "../../root", LinuxDistro.ALPINE)
    }

    @Test fun setupGithub_buildsCustomServer() {
        val config = McpSetup.github("", "https://github.com/owner/cool-mcp", "", "", LinuxDistro.ALPINE, emptySet())
        assertEquals(McpKind.CUSTOM, config.kind)
        assertEquals(McpRuntime.LINUX, config.runtime)
        assertEquals("cool-mcp", config.name)
        assertTrue(config.installScript.isNotBlank())
        assertTrue(config.command.contains("run.sh"))
    }
}
