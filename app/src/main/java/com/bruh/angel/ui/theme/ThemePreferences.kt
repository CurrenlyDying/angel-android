package com.bruh.angel.ui.theme

enum class ThemeMode(val label: String) { SYSTEM("System"), LIGHT("Light"), DARK("Dark") }
enum class SurfaceStyle(val label: String) { TONAL("Tonal"), SOLID("Solid"), GLASS("Glass") }

data class ThemePreferences(
    val mode: ThemeMode = ThemeMode.SYSTEM,
    val accentHex: String = "6EE7D8",
    val surfaceStyle: SurfaceStyle = SurfaceStyle.TONAL,
    val glassOpacity: Float = 0.78f,
    val cornerRadius: Float = 24f,
    val textScale: Float = 1f,
    val ambientBackground: Boolean = true,
    val highContrast: Boolean = false,
    val reduceMotion: Boolean = false
) {
    fun normalized() = copy(
        accentHex = normalizeAccent(accentHex) ?: "6EE7D8",
        glassOpacity = glassOpacity.finiteOr(0.78f).coerceIn(0.6f, 0.96f),
        cornerRadius = cornerRadius.finiteOr(24f).coerceIn(8f, 32f),
        textScale = textScale.finiteOr(1f).coerceIn(0.9f, 1.3f)
    )

    companion object {
        fun normalizeAccent(value: String): String? = value.trim().removePrefix("#")
            .takeIf { it.matches(Regex("[0-9a-fA-F]{6}")) }?.uppercase()
    }
}

private fun Float.finiteOr(fallback: Float) = if (isFinite()) this else fallback
