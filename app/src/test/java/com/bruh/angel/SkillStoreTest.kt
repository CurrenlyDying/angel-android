package com.bruh.angel

import com.bruh.angel.model.Project
import com.bruh.angel.model.Skill
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SkillStoreTest {

    private val sample = """
        ---
        name: Reverse Engineering
        description: Analyze and reverse-engineer apps and binaries on the device.
        ---

        # Reverse Engineering

        Body goes here.
    """.trimIndent()

    @Test fun parse_readsFrontMatterAndBody() {
        val s = Skill.parseSkillMd(sample)!!
        assertEquals("Reverse Engineering", s.name)
        assertEquals("reverse-engineering", s.id)
        assertEquals("Analyze and reverse-engineer apps and binaries on the device.", s.description)
        assertTrue(s.instructions.startsWith("# Reverse Engineering"))
        assertFalse(s.builtin)
        assertEquals("import", s.source)
    }

    @Test fun parse_stripsQuotesFromValues() {
        val s = Skill.parseSkillMd("---\nname: \"Quoted Name\"\ndescription: 'single quoted'\n---\nbody")!!
        assertEquals("Quoted Name", s.name)
        assertEquals("single quoted", s.description)
    }

    @Test fun parse_rejectsMissingFrontMatter() {
        assertNull(Skill.parseSkillMd("# Just a heading\n\nno front matter"))
        assertNull(Skill.parseSkillMd(""))
    }

    @Test fun parse_rejectsMissingRequiredMetadata() {
        assertNull(Skill.parseSkillMd("---\nname: Only a name\n---\nbody"))   // no description
        assertNull(Skill.parseSkillMd("---\ndescription: only a desc\n---\nbody")) // no name
    }

    @Test fun parse_handlesCrlfAndAvoidsIdCollisions() {
        val s = Skill.parseSkillMd("---\r\nname: My Skill\r\ndescription: d\r\n---\r\nbody\r\n", taken = setOf("my-skill"))!!
        assertEquals("my-skill-2", s.id)
        assertEquals("body", s.instructions)
    }

    @Test fun skill_roundTrips() {
        val s = Skill(
            id = "reverse-engineering", name = "Reverse Engineering", description = "d",
            instructions = "body", builtin = true, enabled = false, source = "builtin",
            setupDone = true, connectorIds = setOf("radare2")
        )
        assertEquals(s, Skill.fromJson(s.toJson()))
    }

    @Test fun skill_rejectsInvalidId() {
        assertNull(Skill.fromJson(JSONObject().put("id", "Bad Id")))
        assertNull(Skill.fromJson(JSONObject()))
    }

    @Test fun project_skillsRoundTripAndLegacyDecode() {
        val p = Project(id = "proj-1234", name = "RE", workdir = "/root/re", distroId = "kali",
            connectors = setOf("radare2"), skills = setOf("reverse-engineering"))
        assertEquals(setOf("reverse-engineering"), Project.fromJson(p.toJson())!!.skills)
        // A project saved before the Skills feature has no "skills" key.
        val legacy = JSONObject().put("id", "proj-5678").put("name", "Old")
        assertTrue(Project.fromJson(legacy)!!.skills.isEmpty())
    }
}
