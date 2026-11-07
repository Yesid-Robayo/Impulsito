package io.github.yesidrobayo.shopledger.domain.rule.backup

import io.github.yesidrobayo.shopledger.domain.model.backup.AppOpenUpload
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupStatus
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupStatusReport
import org.junit.Assert.assertEquals
import org.junit.Test

/** Abrir la app con cambios sin copia intenta subir en ese momento, salvo que algo lo impida. */
class AppOpenUploadRuleTest {
    private fun decide(status: BackupStatus, pending: Long) =
        AppOpenUploadRule.decide(BackupStatusReport(status, pending, savingSoon = false))

    @Test
    fun `nothing uploads when the status itself stops the copy`() {
        val blocking =
            listOf(
                BackupStatus.RESTORING,
                BackupStatus.NOT_CONNECTED,
                BackupStatus.POLICY_PENDING,
                BackupStatus.PERMISSION_LOST,
                BackupStatus.CONFLICT,
                BackupStatus.NOTHING_TO_BACK_UP,
                BackupStatus.UPLOADING,
            )

        for (status in blocking) {
            assertEquals(status.name, AppOpenUpload.NONE, decide(status, pending = 5))
        }
    }

    @Test
    fun `a day without a copy uploads without waiting for the interval`() {
        assertEquals(AppOpenUpload.PAST_INTERVAL, decide(BackupStatus.STALE, pending = 5))
    }

    @Test
    fun `any other status uploads only with pending changes`() {
        val waiting =
            listOf(
                BackupStatus.FAILED,
                BackupStatus.CHECK_DUE,
                BackupStatus.HANDED_OFF,
                BackupStatus.UP_TO_DATE,
                BackupStatus.BLOCKED_BY_DATA_SAVER,
                BackupStatus.WAITING_FOR_WIFI,
                BackupStatus.WAITING_FOR_NETWORK,
                BackupStatus.PENDING,
            )

        for (status in waiting) {
            assertEquals(
                status.name,
                AppOpenUpload.RESPECTING_INTERVAL,
                decide(status, pending = 1),
            )
            assertEquals(status.name, AppOpenUpload.NONE, decide(status, pending = 0))
        }
    }

    @Test
    fun `every status has a decision`() {
        // Si se agrega un estado, esta prueba obliga a decidir qué hace la apertura con él.
        assertEquals(16, BackupStatus.entries.size)
    }
}
