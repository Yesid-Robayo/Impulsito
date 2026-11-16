package io.github.yesidrobayo.shopledger.domain.model.backup

import io.github.yesidrobayo.shopledger.domain.model.settings.MeteredBackupPolicy
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Los plazos del estado del respaldo, con los valores de diseño del documento de respaldo. */
class BackupTimingsTest {
    @Test
    fun `the status deadlines are the design values`() {
        assertEquals(24.hours, BackupTimings.STALE_NOTICE)
        assertEquals(15.minutes, BackupTimings.SAVING_SOON)
        assertEquals(7.days, BackupTimings.HUMAN_CHECK)
        assertEquals(6L, BackupTimings.TRANSPORT_FAILURES_BEFORE_PERSISTENT)
    }

    @Test
    fun `the scheduling deadlines are the design values`() {
        // Entre 1 y 2 minutos tras el último cambio, y una racha nunca pospone más de 10 minutos.
        assertEquals(90.seconds, BackupTimings.BACKUP_DEBOUNCE)
        assertEquals(10.minutes, BackupTimings.BACKUP_MAX_WAIT)
        assertEquals(24.hours, BackupTimings.QUOTA_RETRY)
    }

    @Test
    fun `each policy has its interval on mobile data, and Wi-Fi only has none`() {
        assertEquals(60.minutes, BackupTimings.meteredInterval(MeteredBackupPolicy.EVERY_60_MIN))
        assertEquals(30.minutes, BackupTimings.meteredInterval(MeteredBackupPolicy.EVERY_30_MIN))
        assertNull(BackupTimings.meteredInterval(MeteredBackupPolicy.WIFI_ONLY))
    }

    @Test
    fun `the status values keep their priority order`() {
        // El orden es la prioridad: gana el primero. Cambiarlo cambia lo que ve la persona.
        assertEquals(
            listOf(
                "RESTORING", "NOT_CONNECTED", "POLICY_PENDING", "PERMISSION_LOST", "CONFLICT",
                "NOTHING_TO_BACK_UP", "UPLOADING", "STALE", "FAILED", "CHECK_DUE", "HANDED_OFF",
                "UP_TO_DATE", "BLOCKED_BY_DATA_SAVER", "WAITING_FOR_WIFI", "WAITING_FOR_NETWORK",
                "PENDING",
            ),
            BackupStatus.entries.map { it.name },
        )
    }

    @Test
    fun `the stored failure kinds are the ones the table guard accepts`() {
        assertEquals(
            listOf("NETWORK", "TLS", "HTTP_5XX", "RATE_LIMIT"),
            BackupFailureKind.entries.map { it.name },
        )
    }

    @Test
    fun `no network means not validated and counted as mobile data`() {
        assertEquals(
            BackupNetwork(validated = false, metered = true, dataSaverRestricted = false),
            BackupNetwork.NONE,
        )
    }
}
