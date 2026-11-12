package io.github.yesidrobayo.shopledger.domain.usecase.backup

import io.github.yesidrobayo.shopledger.domain.model.backup.PinRelease
import io.github.yesidrobayo.shopledger.domain.model.settings.MeteredBackupPolicy
import io.github.yesidrobayo.shopledger.domain.model.settings.PreferenceChange
import io.github.yesidrobayo.shopledger.domain.port.BackupSettingsPort
import io.github.yesidrobayo.shopledger.domain.port.Clock
import io.github.yesidrobayo.shopledger.domain.testing.FakeAppSettingsRepository
import io.github.yesidrobayo.shopledger.domain.testing.appSettings
import io.github.yesidrobayo.shopledger.domain.testing.regionalSettings
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.DayOfWeek
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** `Copias con datos móviles` guarda la política y vuelve a programar la subida solo si cambió. */
class ChangeMeteredBackupPolicyUseCaseTest {
    private val now = Instant.parse("2026-11-12T09:00:00Z")
    private val settings =
        FakeAppSettingsRepository(appSettings(regionalSettings("Etc/GMT+5", DayOfWeek.MONDAY)))
    private var rescheduled = 0

    private val port =
        object : BackupSettingsPort {
            override suspend fun uploadedThisMonth(): Long = 0

            override suspend fun releasePinnedCopy(key: String): PinRelease = PinRelease.FAILED

            override suspend fun onMeteredPolicyChanged() {
                rescheduled += 1
            }

            override suspend fun copiesEncrypted(): Boolean = false
        }

    private val change = ChangeMeteredBackupPolicyUseCase(settings, port, Clock { now })

    @Test
    fun `a new policy is saved and the upload is scheduled again`() = runTest {
        assertTrue(change(MeteredBackupPolicy.EVERY_30_MIN))

        assertEquals(
            listOf(PreferenceChange.MeteredBackup(MeteredBackupPolicy.EVERY_30_MIN)),
            settings.preferenceWrites,
        )
        assertEquals(
            MeteredBackupPolicy.EVERY_30_MIN,
            settings.settings.value.preferences.meteredBackupPolicy,
        )
        assertEquals(now, settings.settings.value.updatedAt)
        assertEquals(1, rescheduled)
    }

    @Test
    fun `choosing the current policy writes nothing`() = runTest {
        // Los ajustes de prueba empiezan en solo con Wi-Fi.
        assertFalse(change(MeteredBackupPolicy.WIFI_ONLY))

        assertEquals(emptyList<PreferenceChange>(), settings.preferenceWrites)
        assertEquals(0, rescheduled)
    }
}
