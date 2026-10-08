package com.bruh.angel

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import com.bruh.angel.ui.AppearanceScreen
import com.bruh.angel.ui.chat.ChatScreen
import androidx.test.platform.app.InstrumentationRegistry
import com.bruh.angel.ui.theme.AngelTheme
import com.bruh.angel.ui.theme.SurfaceStyle
import com.bruh.angel.ui.theme.ThemeMode
import com.bruh.angel.ui.theme.ThemePreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import androidx.compose.ui.graphics.Color

class AppearanceScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun transparentScreensInheritReadableTextInDarkMode() {
        val actual = AtomicReference<Color>()
        val expected = AtomicReference<Color>()
        compose.setContent {
            AngelTheme(preferences = ThemePreferences(mode = ThemeMode.DARK)) {
                actual.set(LocalContentColor.current)
                expected.set(MaterialTheme.colorScheme.onSurface)
                Text("Readable by default")
            }
        }
        compose.runOnIdle { assertEquals(expected.get(), actual.get()) }
    }

    @Test
    fun rendersThemeVariantsForVisualReview() {
        val preferences = mutableStateOf(ThemePreferences(mode = ThemeMode.DARK, surfaceStyle = SurfaceStyle.GLASS))
        val appearance = mutableStateOf(false)
        val narrow = mutableStateOf(false)
        compose.setContent {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                Box((if (narrow.value) Modifier.width(360.dp).fillMaxHeight() else Modifier.fillMaxSize()).testTag("viewport")) {
                    AngelTheme(preferences = preferences.value) {
                        if (appearance.value) AppearanceScreen(onBack = {}) else ChatScreen(items = emptyList(), onSend = {})
                    }
                }
            }
        }
        saveCapture("home-dark-glass.png")
        compose.runOnIdle { appearance.value = true }
        saveCapture("appearance-dark-glass.png")
        compose.runOnIdle { preferences.value = ThemePreferences(mode = ThemeMode.LIGHT) }
        saveCapture("appearance-light-tonal.png")
        compose.runOnIdle {
            appearance.value = false
            narrow.value = true
            preferences.value = ThemePreferences(mode = ThemeMode.DARK, textScale = 1.3f)
        }
        saveCapture("home-narrow-large-text.png")
    }

    private fun saveCapture(name: String) {
        compose.waitForIdle()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        File(context.cacheDir, name).outputStream().use { output ->
            compose.onNodeWithTag("viewport").captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, output)
        }
    }

    @Test
    fun customizesAndResetsAppearance() {
        val preferences = mutableStateOf(ThemePreferences())
        compose.setContent {
            AngelTheme(preferences = preferences.value, onPreferencesChange = { preferences.value = it }) {
                AppearanceScreen(onBack = {})
            }
        }
        compose.onNodeWithText("Dark").performScrollTo().performClick()
        compose.onNodeWithText("Glass").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(ThemeMode.DARK, preferences.value.mode)
            assertEquals(SurfaceStyle.GLASS, preferences.value.surfaceStyle)
        }
        compose.onNode(hasSetTextAction()).performScrollTo().performTextReplacement("00ccaa")
        compose.onNodeWithText("Apply").performClick()
        compose.runOnIdle { assertEquals("00CCAA", preferences.value.accentHex) }
        compose.onNodeWithText("High contrast").performScrollTo()
        compose.onNode(androidx.compose.ui.test.hasContentDescription("High contrast")).performClick()
        compose.runOnIdle { assertTrue(preferences.value.highContrast) }
        compose.onNodeWithText("Reset").performClick()
        compose.onNodeWithText("Reset appearance").performClick()
        compose.runOnIdle { assertEquals(ThemePreferences(), preferences.value) }
    }
}
