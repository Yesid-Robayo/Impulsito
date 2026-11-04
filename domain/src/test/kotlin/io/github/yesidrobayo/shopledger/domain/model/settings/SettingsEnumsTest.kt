package io.github.yesidrobayo.shopledger.domain.model.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SettingsEnumsTest {
    @Test
    fun `the stored names never change`() {
        assertEquals(listOf("SYSTEM", "LIGHT", "DARK"), ThemeMode.entries.map { it.name })
        // También el orden: es el del selector de colores, con la inicial primero.
        assertEquals(
            listOf(
                "INDIGO_MUSTARD",
                "BERRY_HONEY",
                "RASPBERRY_GOLD",
                "WINE_CHAMPAGNE",
                "PLUM_CORAL",
                "FOREST_HONEY",
                "CARMINE_GOLD",
                "GARNET_HONEY",
                "VIVID_RED_CREAM",
            ),
            ColorPalette.entries.map { it.name },
        )
        assertEquals(
            listOf("EVERY_60_MIN", "EVERY_30_MIN", "WIFI_ONLY"),
            MeteredBackupPolicy.entries.map { it.name },
        )
    }

    @Test
    fun `the lock timeout is stored in seconds and only admits its values`() {
        assertEquals(listOf(0, 60, 300), AppLockTimeout.entries.map { it.seconds })
        for (timeout in AppLockTimeout.entries) {
            assertEquals(timeout, AppLockTimeout.ofSeconds(timeout.seconds))
        }
        // Un valor que llega de una copia ajena no se acepta en silencio.
        assertNull(AppLockTimeout.ofSeconds(30))
    }
}
