package io.github.yesidrobayo.shopledger.domain.rule.backup

import io.github.yesidrobayo.shopledger.domain.model.backup.BackupDestinationKind.DOCUMENT_SLOTS
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupDestinationKind.WEBDAV
import io.github.yesidrobayo.shopledger.domain.model.backup.UploadGateVerdict.PROCEED
import io.github.yesidrobayo.shopledger.domain.model.backup.UploadGateVerdict.WAIT_FOR_INTERVAL
import io.github.yesidrobayo.shopledger.domain.model.backup.UploadGateVerdict.WAIT_FOR_UNMETERED
import io.github.yesidrobayo.shopledger.domain.model.backup.UploadSchedule
import io.github.yesidrobayo.shopledger.domain.model.settings.MeteredBackupPolicy.EVERY_30_MIN
import io.github.yesidrobayo.shopledger.domain.model.settings.MeteredBackupPolicy.EVERY_60_MIN
import io.github.yesidrobayo.shopledger.domain.model.settings.MeteredBackupPolicy.WIFI_ONLY
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * El ritmo de las copias automáticas: qué se encola con cada destino y política, qué deja pasar la
 * compuerta al empezar, y cuándo una racha de cambios ya esperó demasiado. Un reloj atrasado nunca
 * detiene una copia.
 */
class UploadCadenceTest {
    private val now = Instant.parse("2026-11-07T15:00:00Z")

    @Test
    fun `with WebDAV both uploads are queued, the one for any network with what is left`() {
        // Hace 20 minutos terminó la última subida: con 60, faltan 40; con 30, faltan 10.
        val last = now - 20.minutes
        assertEquals(
            UploadSchedule(true, 40.minutes, true),
            UploadCadence.schedule(WEBDAV, EVERY_60_MIN, last, now, pastInterval = false),
        )
        assertEquals(
            UploadSchedule(true, 10.minutes, true),
            UploadCadence.schedule(WEBDAV, EVERY_30_MIN, last, now, pastInterval = false),
        )
    }

    @Test
    fun `with Wi-Fi only there is no upload over mobile data, not even a day late`() {
        for (pastInterval in listOf(false, true)) {
            assertEquals(
                UploadSchedule(true, null, true),
                UploadCadence.schedule(WEBDAV, WIFI_ONLY, now - 5.minutes, now, pastInterval),
            )
        }
    }

    @Test
    fun `no earlier upload, an expired interval or a clock behind means no delay`() {
        val cases =
            listOf(
                null,
                now - 60.minutes,
                now - 3.minutes - 60.minutes,
                // El reloj retrocedió: la última subida parece del futuro.
                now + 5.minutes,
            )
        for (last in cases) {
            assertEquals(
                "$last",
                UploadSchedule(true, Duration.ZERO, true),
                UploadCadence.schedule(WEBDAV, EVERY_60_MIN, last, now, pastInterval = false),
            )
        }
    }

    @Test
    fun `a day without a copy queues the upload for any network without the interval`() {
        assertEquals(
            UploadSchedule(true, Duration.ZERO, true),
            UploadCadence.schedule(WEBDAV, EVERY_60_MIN, now - 1.minutes, now, pastInterval = true),
        )
    }

    @Test
    fun `with the phone cloud only one upload, with no network required and its own interval`() {
        assertEquals(
            UploadSchedule(false, 45.minutes, false),
            UploadCadence.schedule(
                DOCUMENT_SLOTS,
                WIFI_ONLY,
                now - 15.minutes,
                now,
                pastInterval = false,
            ),
        )
        assertEquals(
            UploadSchedule(false, Duration.ZERO, false),
            UploadCadence.schedule(DOCUMENT_SLOTS, EVERY_30_MIN, null, now, pastInterval = false),
        )
        assertEquals(
            UploadSchedule(false, Duration.ZERO, false),
            UploadCadence.schedule(
                DOCUMENT_SLOTS,
                EVERY_60_MIN,
                now - 1.minutes,
                now,
                pastInterval = true,
            ),
        )
    }

    @Test
    fun `the gate lets any upload through on a network without a data charge`() {
        for (policy in listOf(EVERY_60_MIN, EVERY_30_MIN, WIFI_ONLY)) {
            assertEquals(
                PROCEED,
                UploadCadence.gate(WEBDAV, policy, metered = false, now - 1.minutes, now),
            )
        }
    }

    @Test
    fun `on mobile data the gate holds the upload while the interval runs`() {
        assertEquals(
            WAIT_FOR_INTERVAL,
            UploadCadence.gate(WEBDAV, EVERY_60_MIN, metered = true, now - 59.minutes, now),
        )
        assertEquals(
            PROCEED,
            UploadCadence.gate(WEBDAV, EVERY_60_MIN, metered = true, now - 60.minutes, now),
        )
        assertEquals(
            WAIT_FOR_INTERVAL,
            UploadCadence.gate(WEBDAV, EVERY_30_MIN, metered = true, now - 29.minutes, now),
        )
        assertEquals(
            PROCEED,
            UploadCadence.gate(WEBDAV, EVERY_30_MIN, metered = true, now - 30.minutes, now),
        )
        assertEquals(PROCEED, UploadCadence.gate(WEBDAV, EVERY_60_MIN, metered = true, null, now))
    }

    @Test
    fun `a clock behind on mobile data counts as an expired interval`() {
        assertEquals(
            PROCEED,
            UploadCadence.gate(WEBDAV, EVERY_60_MIN, metered = true, now + 10.minutes, now),
        )
    }

    @Test
    fun `with Wi-Fi only and mobile data the gate waits for a network without a charge`() {
        assertEquals(
            WAIT_FOR_UNMETERED,
            UploadCadence.gate(WEBDAV, WIFI_ONLY, metered = true, null, now),
        )
    }

    @Test
    fun `with the phone cloud the gate looks at its interval with any network or none`() {
        for (metered in listOf(false, true)) {
            assertEquals(
                WAIT_FOR_INTERVAL,
                UploadCadence.gate(DOCUMENT_SLOTS, WIFI_ONLY, metered, now - 30.minutes, now),
            )
            assertEquals(
                PROCEED,
                UploadCadence.gate(DOCUMENT_SLOTS, WIFI_ONLY, metered, now - 60.minutes, now),
            )
        }
    }

    @Test
    fun `a streak waits ten minutes at most from its oldest change`() {
        assertFalse(UploadCadence.maxWaitReached(null, now))
        assertFalse(UploadCadence.maxWaitReached(now - 9.minutes - 59.seconds, now))
        assertTrue(UploadCadence.maxWaitReached(now - 10.minutes, now))
        // El reloj retrocedió: el cambio parece del futuro, y la espera cuenta como vencida.
        assertTrue(UploadCadence.maxWaitReached(now + 1.seconds, now))
    }
}
