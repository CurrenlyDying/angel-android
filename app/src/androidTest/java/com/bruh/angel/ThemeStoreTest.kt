package com.bruh.angel

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.platform.app.InstrumentationRegistry
import com.bruh.angel.ui.theme.SurfaceStyle
import com.bruh.angel.ui.theme.ThemeMode
import com.bruh.angel.ui.theme.ThemePreferences
import com.bruh.angel.ui.theme.ThemeStore
import org.junit.Assert.assertEquals
import org.junit.Test

class ThemeStoreTest {
    @Test
    fun persistsNormalizesAndRestoresAppearanceWithoutTouchingUserSettings() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val isolated = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                base.getSharedPreferences("appearance-test", mode)
        }
        isolated.getSharedPreferences("appearance", Context.MODE_PRIVATE).edit().clear().commit()
        val first = ThemeStore(isolated)
        try {
            val desired = ThemePreferences(mode = ThemeMode.DARK, accentHex = "#b8a1ff", surfaceStyle = SurfaceStyle.GLASS,
                glassOpacity = 0.7f, cornerRadius = 18f, textScale = 1.2f, ambientBackground = false,
                highContrast = true, reduceMotion = true).normalized()
            first.update(desired)
            assertEquals(desired, first.settings.value)
            val reopened = ThemeStore(isolated)
            try {
                assertEquals(desired, reopened.settings.value)
                reopened.update(ThemePreferences())
                assertEquals(ThemePreferences(), reopened.settings.value)
            } finally { reopened.close() }
        } finally {
            first.close()
            isolated.getSharedPreferences("appearance", Context.MODE_PRIVATE).edit().clear().commit()
        }
    }
}
