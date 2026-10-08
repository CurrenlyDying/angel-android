package com.bruh.angel

import androidx.compose.ui.graphics.Color
import com.bruh.angel.ui.theme.AngelDarkScheme
import com.bruh.angel.ui.theme.AngelLightScheme
import com.bruh.angel.ui.theme.ThemePreferences
import com.bruh.angel.ui.theme.contrastRatio
import com.bruh.angel.ui.theme.contrastingInk
import com.bruh.angel.ui.theme.readableAccent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemePreferencesTest {
    @Test
    fun normalizesHexAndRejectsInvalidColors() {
        assertEquals("B8A1FF", ThemePreferences.normalizeAccent(" #b8a1ff "))
        listOf("", "fff", "FFFFFFFF", "orange", "12345g", "##abcdef").forEach {
            assertNull(ThemePreferences.normalizeAccent(it))
        }
    }

    @Test
    fun clampsStoredAppearanceValues() {
        val preferences = ThemePreferences(accentHex = "invalid", glassOpacity = -1f, cornerRadius = 500f, textScale = 3f).normalized()
        assertEquals("6EE7D8", preferences.accentHex)
        assertEquals(0.6f, preferences.glassOpacity, 0f)
        assertEquals(32f, preferences.cornerRadius, 0f)
        assertEquals(1.3f, preferences.textScale, 0f)
    }

    @Test
    fun handlesNonFiniteSettingsAndKeepsNormalizationIdempotent() {
        val preferences = ThemePreferences(glassOpacity = Float.NaN, cornerRadius = Float.POSITIVE_INFINITY, textScale = Float.NEGATIVE_INFINITY).normalized()
        assertEquals(ThemePreferences(), preferences)
        assertEquals(preferences, preferences.normalized())
    }

    @Test
    fun customAccentsMaintainTextContrastAcrossBothThemes() {
        val accents = listOf(Color.Black, Color.White, Color.Red, Color.Green, Color.Blue,
            Color(0xFF6EE7D8), Color(0xFFB8A1FF), Color(0xFF74BFFF), Color(0xFFFFB477), Color(0xFFF5A0BD))
        listOf(AngelDarkScheme, AngelLightScheme).forEach { scheme ->
            listOf(false, true).forEach { highContrast ->
                val minimum = if (highContrast) 7f else 4.5f
                accents.forEach { raw ->
                    val accent = readableAccent(raw, scheme.surfaceContainerHighest, highContrast)
                    assertTrue(contrastRatio(accent, scheme.surfaceContainerHighest) >= minimum)
                    assertTrue(contrastRatio(accent, scheme.background) >= minimum)
                    assertTrue(contrastRatio(contrastingInk(accent), accent) >= 4.5f)
                }
            }
        }
    }
}
