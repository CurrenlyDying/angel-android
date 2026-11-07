package com.bruh.angel

import com.bruh.angel.linuxenv.LinuxDistro
import com.bruh.angel.linuxenv.ProjectWorkspace
import com.bruh.angel.model.ChatItem
import com.bruh.angel.model.Conversation
import com.bruh.angel.model.ConversationStore
import com.bruh.angel.model.Project
import com.bruh.angel.model.Provider
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectStoreTest {

    @Test fun project_roundTrips() {
        val p = Project(
            id = "proj-1234", name = "My App", workdir = "/root/projects/app", distroId = "debian",
            instructions = "be terse", connectors = setOf("fetch", "memory"), repoUrl = "https://github.com/owner/repo",
            created = 100L, updated = 200L
        )
        val back = Project.fromJson(p.toJson())!!
        assertEquals(p, back)
        assertEquals(LinuxDistro.DEBIAN, back.distro)
        assertEquals(setOf("fetch", "memory"), back.connectors)
    }

    @Test fun project_defaultsForMissingFields() {
        val p = Project.fromJson(JSONObject().put("id", "proj-5678"))!!
        assertEquals("Project", p.name)
        assertEquals("/root", p.workdir)
        assertEquals(LinuxDistro.DEFAULT.id, p.distroId)
        assertTrue(p.connectors.isEmpty())
        assertEquals("", p.repoUrl)
    }

    @Test fun project_rejectsInvalidId() {
        assertNull(Project.fromJson(JSONObject().put("id", "short")))
        assertNull(Project.fromJson(JSONObject()))
    }

    @Test fun conversation_preservesProjectId() {
        val c = Conversation("abcd1234", "t", 123L, Provider.OPENAI, "m", listOf(ChatItem.UserMsg("hi")), null, "proj-xyz")
        val encoded = ConversationStore.encode(c)
        assertEquals("proj-xyz", encoded.getString("projectId"))
        assertEquals("proj-xyz", ConversationStore.decode(encoded).projectId)
    }

    @Test fun conversation_nullProjectIdRoundTrips() {
        val c = Conversation("abcd1234", "t", 1L, Provider.OPENAI, "m", emptyList(), null, null)
        val decoded = ConversationStore.decode(ConversationStore.encode(c))
        assertNull(decoded.projectId)
    }

    @Test fun conversation_legacyWithoutProjectIdDecodesAsNull() {
        // A conversation saved before the Projects feature has no "projectId" key.
        val legacy = JSONObject().put("id", "abcd1234").put("title", "t").put("updated", 1L)
            .put("provider", "OPENAI").put("model", "m")
        assertNull(ConversationStore.decode(legacy).projectId)
    }

    @Test fun parseStatus_splitsStagedUnstagedUntracked() {
        val out = buildString {
            append(" M src/a.kt\u0000")   // unstaged modify
            append("M  src/b.kt\u0000")   // staged modify
            append("A  src/c.kt\u0000")   // staged add
            append("?? new.txt\u0000")    // untracked
            append("MM both.kt\u0000")    // staged + unstaged
            append("R  renamed.kt\u0000old.kt\u0000") // rename, staged
        }
        val entries = ProjectWorkspace.parseStatus(out)
        val staged = entries.filter { it.staged }.map { it.path }.toSet()
        val unstaged = entries.filter { !it.staged && !it.untracked }.map { it.path }.toSet()
        val untracked = entries.filter { it.untracked }.map { it.path }.toSet()
        assertEquals(setOf("src/b.kt", "src/c.kt", "both.kt", "renamed.kt"), staged)
        assertEquals(setOf("src/a.kt", "both.kt"), unstaged)
        assertEquals(setOf("new.txt"), untracked)
    }

    @Test fun parseStatus_preservesExactFileNames() {
        val names = listOf(" space.txt", "trailing.txt ", "café.txt", "quote\"file.txt", "line\nbreak.txt", "old -> name.txt")
        val output = names.joinToString("") { "?? $it\u0000" }
        assertEquals(names, ProjectWorkspace.parseStatus(output).map { it.path })
    }

    @Test fun parseStatus_doesNotTreatRenameSourceAsAnotherEntry() {
        val output = "R  new -> name.txt\u0000 M old.txt\u0000 M next.txt\u0000"
        assertEquals(listOf("new -> name.txt", "next.txt"), ProjectWorkspace.parseStatus(output).map { it.path })
    }

    @Test(expected = IllegalArgumentException::class)
    fun parseStatus_rejectsTruncatedOutput() {
        ProjectWorkspace.parseStatus("?? complete.txt\u0000?? partial\n[Output truncated at 48 KB]")
    }

    @Test fun validateRepoUrl_acceptsGithubRejectsOthers() {
        assertNull(ProjectWorkspace.validateRepoUrl("https://github.com/owner/repo"))
        assertNull(ProjectWorkspace.validateRepoUrl("https://github.com/owner/repo.git"))
        assertFalse(ProjectWorkspace.validateRepoUrl("http://github.com/owner/repo") == null) // not https
        assertFalse(ProjectWorkspace.validateRepoUrl("https://gitlab.com/o/r") == null)
        assertFalse(ProjectWorkspace.validateRepoUrl("") == null)
    }
}
