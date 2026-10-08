package com.bruh.angel

import com.bruh.angel.model.Provider
import com.bruh.angel.model.ProviderSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderSanityTest {
    @Test
    fun guessesProviderFromModelId() {
        assertEquals(Provider.DEEPSEEK, Provider.forModel("deepseek-flash"))
        assertEquals(Provider.GOOGLE, Provider.forModel("models/gemini-3.8-flash"))
        assertEquals(Provider.ANTHROPIC, Provider.forModel("claude-sonnet-5-5"))
        assertEquals(Provider.OPENAI, Provider.forModel("gpt-6.1-sol"))
        assertEquals(Provider.OPENAI, Provider.forModel("o4-mini"))
        assertEquals(null, Provider.forModel("my-custom-model"))
    }

    @Test
    fun flagsDeepSeekSettingsSavedUnderGoogle() {
        val problems = ProviderSettings(Provider.GOOGLE, "deepseek-flash", "sk-0123456789abcdef").problems()
        assertEquals(2, problems.size)
        assertTrue(problems[0], problems[0].contains("looks like a DeepSeek model"))
        assertTrue(problems[1], problems[1].contains("doesn't look like a Google API key"))
    }

    @Test
    fun acceptsConsistentSettings() {
        assertTrue(ProviderSettings(Provider.DEEPSEEK, "deepseek-flash", "sk-abc").problems().isEmpty())
        assertTrue(ProviderSettings(Provider.GOOGLE, "gemini-3.8-flash", "AIzaSyExample").problems().isEmpty())
        assertTrue(ProviderSettings(Provider.ANTHROPIC, "claude-opus-5-5", "sk-ant-abc").problems().isEmpty())
        assertTrue(ProviderSettings(Provider.OPENAI, "gpt-6.1-sol", "sk-proj-abc").problems().isEmpty())
        assertEquals(1, ProviderSettings(Provider.OPENAI, "gpt-6.1-sol", "sk-ant-abc").problems().size)
    }
}

