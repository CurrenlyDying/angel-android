package com.bruh.angel.ui.theme

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class ThemeStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("appearance", Context.MODE_PRIVATE)
    private val mutableSettings = MutableStateFlow(read())
    val settings = mutableSettings.asStateFlow()
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> mutableSettings.value = read() }

    init { preferences.registerOnSharedPreferenceChangeListener(listener) }

    fun close() { preferences.unregisterOnSharedPreferenceChangeListener(listener) }

    fun update(value: ThemePreferences) {
        val safe = value.normalized()
        mutableSettings.value = safe
        preferences.edit()
            .putString("mode", safe.mode.name)
            .putString("accent", safe.accentHex)
            .putString("material", safe.surfaceStyle.name)
            .putFloat("opacity", safe.glassOpacity)
            .putFloat("corners", safe.cornerRadius)
            .putFloat("textScale", safe.textScale)
            .putBoolean("ambient", safe.ambientBackground)
            .putBoolean("contrast", safe.highContrast)
            .putBoolean("motion", safe.reduceMotion)
            .apply()
    }

    private fun read() = ThemePreferences(
        mode = ThemeMode.entries.firstOrNull { it.name == preferences.getString("mode", null) } ?: ThemeMode.SYSTEM,
        accentHex = preferences.getString("accent", null) ?: "6EE7D8",
        surfaceStyle = SurfaceStyle.entries.firstOrNull { it.name == preferences.getString("material", null) } ?: SurfaceStyle.TONAL,
        glassOpacity = preferences.getFloat("opacity", 0.78f),
        cornerRadius = preferences.getFloat("corners", 24f),
        textScale = preferences.getFloat("textScale", 1f),
        ambientBackground = preferences.getBoolean("ambient", true),
        highContrast = preferences.getBoolean("contrast", false),
        reduceMotion = preferences.getBoolean("motion", false)
    ).normalized()
}
