package com.openstation.launcher

import android.graphics.Color
import java.util.Locale

object ScreensaverScenes {
    const val PREF_KEY_SCENE = "screensaver_scene"
    const val SCENE_AUTO = "auto"
    const val SCENE_AURORA = "aurora"
    const val SCENE_BIOLUMINESCENT = "bioluminescent"
    const val SCENE_DOLOMITES = "dolomites"
    const val SCENE_MIST_FOREST = "mist_forest"
    const val SCENE_PLANET_EARTH = "earth_orbit"

    data class SceneSpec(
        val id: String,
        val title: String,
        val subtitle: String,
        val accentHex: String,
        val secondaryHex: String
    )

    val SCENES: List<SceneSpec> = listOf(
        SceneSpec(
            id = SCENE_AURORA,
            title = "Северное сияние",
            subtitle = "Лофотенские фьорды · Полярная ночь",
            accentHex = "#34D399",
            secondaryHex = "#8B5CF6"
        ),
        SceneSpec(
            id = SCENE_BIOLUMINESCENT,
            title = "Биолюминесценция",
            subtitle = "Тихий прибой · Млечный Путь",
            accentHex = "#22D3EE",
            secondaryHex = "#3B82F6"
        ),
        SceneSpec(
            id = SCENE_DOLOMITES,
            title = "Золотые Доломиты",
            subtitle = "Вершины над морем облаков",
            accentHex = "#FBBF24",
            secondaryHex = "#F97316"
        ),
        SceneSpec(
            id = SCENE_MIST_FOREST,
            title = "Тёмный лес",
            subtitle = "Вековые ели · Ночной туман",
            accentHex = "#34D399",
            secondaryHex = "#0EA5E9"
        ),
        SceneSpec(
            id = SCENE_PLANET_EARTH,
            title = "Планета Земля",
            subtitle = "Вид с орбиты · Живое 60fps видео",
            accentHex = "#38BDF8",
            secondaryHex = "#818CF8"
        )
    )

    fun isValidPreference(id: String?): Boolean {
        if (id == SCENE_AUTO) return true
        return SCENES.any { it.id == id }
    }

    fun normalizePreference(id: String?): String =
        if (isValidPreference(id)) id!! else SCENE_AUTO

    fun findScene(id: String?): SceneSpec =
        SCENES.firstOrNull { it.id == id } ?: SCENES.first()

    fun preferenceLabel(prefId: String?): String {
        val norm = normalizePreference(prefId)
        if (norm == SCENE_AUTO) return "Автосмена миров"
        return findScene(norm).title
    }

    fun nextPreference(currentPref: String?): String {
        val order = listOf(SCENE_AUTO) + SCENES.map { it.id }
        val idx = order.indexOf(normalizePreference(currentPref))
        return if (idx < 0) SCENE_AUTO else order[(idx + 1) % order.size]
    }

    fun stepVisualScene(currentSceneId: String?, delta: Int): SceneSpec {
        val idx = SCENES.indexOfFirst { it.id == currentSceneId }.let { if (it < 0) 0 else it }
        val n = SCENES.size
        val next = ((idx + delta) % n + n) % n
        return SCENES[next]
    }

    fun weatherIconForCondition(condition: String?): String {
        val c = condition.orEmpty().lowercase(Locale.ROOT)
        return when {
            c.contains("thunder") || c.contains("storm") || c.contains("гроз") -> "⛈"
            c.contains("snow") || c.contains("blizzard") || c.contains("sleet") || c.contains("снег") -> "❄️"
            c.contains("rain") || c.contains("drizzle") || c.contains("shower") || c.contains("дожд") || c.contains("лив") -> "🌧"
            c.contains("fog") || c.contains("mist") || c.contains("haze") || c.contains("туман") -> "🌫"
            c.contains("partly") || c.contains("broken") || c.contains("few") || c.contains("перемен") || c.contains("малообл") -> "⛅"
            c.contains("overcast") || c.contains("cloud") || c.contains("пасмур") || c.contains("облач") -> "☁️"
            c.contains("night") || c.contains("ноч") -> "🌙"
            else -> "☀️"
        }
    }

    fun formatTemperature(tempC: Int): String = when {
        tempC > 0 -> "+$tempC°"
        else -> "$tempC°"
    }

    fun formatWeatherPill(
        valid: Boolean,
        tempC: Int,
        condition: String?,
        conditionText: String?,
        city: String?
    ): String? {
        if (!valid) return null
        val icon = weatherIconForCondition(condition)
        val temp = formatTemperature(tempC)
        val desc = conditionText?.trim().orEmpty()
        val loc = city?.trim().orEmpty()
        val tail = listOf(desc, loc).filter { it.isNotEmpty() }.joinToString(" · ")
        return if (tail.isNotEmpty()) "$icon  $temp  ·  $tail" else "$icon  $temp"
    }

    fun boostSaturationForGlow(rgb: Int): Int {
        val hsv = FloatArray(3)
        Color.colorToHSV(rgb, hsv)
        if (hsv[1] < 0.35f) hsv[1] = 0.55f
        if (hsv[2] < 0.55f) hsv[2] = 0.78f
        return Color.HSVToColor(255, hsv)
    }
}
