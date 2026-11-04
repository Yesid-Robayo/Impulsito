package io.github.yesidrobayo.shopledger.domain.model.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SettingsEnumsTest {
    @Test
    fun `the stored names never change`() {
        assertEquals(listOf("SYSTEM", "LIGHT", "DARK"), ThemeMode.entries.map { it.name })
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
