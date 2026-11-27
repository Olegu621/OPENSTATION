package com.openstation.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ScreensaverScenesTest {
    @Test fun stepsAcrossVisualScenesWithWrapAround() {
        val s0 = ScreensaverScenes.SCENES.first()
        val sLast = ScreensaverScenes.SCENES.last()
        assertEquals(ScreensaverScenes.SCENES[1].id, ScreensaverScenes.stepVisualScene(s0.id, +1).id)
        assertEquals(sLast.id, ScreensaverScenes.stepVisualScene(s0.id, -1).id)
        assertEquals(s0.id, ScreensaverScenes.stepVisualScene(sLast.id, +1).id)
    }

    @Test fun preferenceCyclesThroughAutoAndAllScenes() {
        assertEquals(ScreensaverScenes.SCENE_AUTO, ScreensaverScenes.normalizePreference("unknown"))
        assertEquals(ScreensaverScenes.SCENE_AURORA, ScreensaverScenes.nextPreference(ScreensaverScenes.SCENE_AUTO))
        assertEquals(ScreensaverScenes.SCENE_AUTO, ScreensaverScenes.nextPreference(ScreensaverScenes.SCENE_COSMOS_PULSE))
    }

    @Test fun weatherFormattingHandlesSignIconsAndNullWhenInvalid() {
        assertNull(ScreensaverScenes.formatWeatherPill(false, 12, "clear", "Ясно", "Москва"))
        assertEquals("☀️  +7°  ·  Ясно · Казань", ScreensaverScenes.formatWeatherPill(true, 7, "clear", "Ясно", "Казань"))
        assertEquals("❄️  -14°  ·  Снег", ScreensaverScenes.formatWeatherPill(true, -14, "snow", "Снег", ""))
        assertEquals("🌧  0°", ScreensaverScenes.formatWeatherPill(true, 0, "light-rain", "", ""))
    }
}
