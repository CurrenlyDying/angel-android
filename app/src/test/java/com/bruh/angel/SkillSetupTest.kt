package com.bruh.angel

import com.bruh.angel.linuxenv.LinuxDistro
import com.bruh.angel.model.Skill
import com.bruh.angel.model.SkillCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class SkillSetupTest {
    private val distro = LinuxDistro.KALI
    private val recipe = SkillCatalog.connectors(SkillCatalog.REVERSE_ENGINEERING, emptySet(), distro).single()
    private val skill = Skill(SkillCatalog.REVERSE_ENGINEERING, "RE", "d", "body", builtin = true,
        setupDone = true, connectorIds = setOf(recipe.id), setupDistros = setOf(distro.id))

    @Test fun readinessRequiresSuccessfulInstallInThisDistro() {
        assertFalse(SkillCatalog.isSetUp(skill, distro, listOf(recipe)))
        assertTrue(SkillCatalog.isSetUp(skill, distro, listOf(recipe.copy(installedDistro = distro.id))))
        assertFalse(SkillCatalog.isSetUp(skill, LinuxDistro.DEBIAN, listOf(recipe.copy(installedDistro = distro.id))))
        assertFalse(SkillCatalog.isSetUp(skill, distro, emptyList()))
        assertFalse(SkillCatalog.isSetUp(skill.copy(setupDistros = emptySet()), distro, listOf(recipe.copy(installedDistro = distro.id))))
    }

    @Test fun retriesReuseOwnedServersWithoutMovingOtherDistros() {
        assertEquals(recipe, SkillCatalog.reusableConnector(skill, recipe, distro, listOf(recipe)))
        assertNull(SkillCatalog.reusableConnector(skill, recipe, distro, listOf(recipe.copy(installedDistro = "debian"))))
        assertNull(SkillCatalog.reusableConnector(skill.copy(connectorIds = emptySet()), recipe, distro, listOf(recipe)))
        assertEquals(skill, Skill.fromJson(skill.toJson()))
    }

    @Test fun existingRadare2StillInstallsMissingConnectorBuildDependencies() {
        org.junit.Assume.assumeTrue(File("/bin/sh").canExecute())
        val directory = Files.createTempDirectory("angel-r2mcp-deps").toFile()
        val trace = File(directory, "trace")
        try {
            // A minimal distro already has radare2/r2pm and Git, but no compiler.
            // Package-manager stubs record the real setup script without installing anything.
            for (name in listOf("r2pm", "git", "dpkg", "apt-get")) {
                File(directory, name).apply {
                    writeText("#!/bin/sh\nprintf '%s\\n' \"$name \$*\" >> '${trace.path}'\n")
                    check(setExecutable(true))
                }
            }
            val process = ProcessBuilder("/bin/sh", "-c", SkillCatalog.connectorBuildDependencies(distro))
                .redirectErrorStream(true).apply { environment()["PATH"] = directory.path }.start()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            assertEquals(output, 0, process.waitFor())
            val calls = trace.readLines()
            val install = calls.indexOfFirst { it.startsWith("apt-get ") && it.contains("install ") }
            assertTrue(calls.toString(), install >= 0)
            assertTrue(calls.toString(), calls[install].contains("build-essential"))
            assertTrue(calls.toString(), calls[install].contains("libradare2-dev"))
        } finally { directory.deleteRecursively() }
    }
}
