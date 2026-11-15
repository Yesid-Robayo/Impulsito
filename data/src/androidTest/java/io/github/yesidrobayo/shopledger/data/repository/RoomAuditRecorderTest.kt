package io.github.yesidrobayo.shopledger.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yesidrobayo.shopledger.data.local.audit.AUDIT_EVENTS_MAX_ROWS
import io.github.yesidrobayo.shopledger.data.local.catalog.CatalogDatabaseTest
import io.github.yesidrobayo.shopledger.data.local.catalog.FIXED_CLOCK
import io.github.yesidrobayo.shopledger.data.local.insertAuditEventsSql
import io.github.yesidrobayo.shopledger.domain.model.audit.AuditEventType
import io.github.yesidrobayo.shopledger.domain.model.audit.AuditResult
import io.github.yesidrobayo.shopledger.domain.port.EventLog
import io.github.yesidrobayo.shopledger.domain.port.LogLevel
import io.github.yesidrobayo.shopledger.domain.port.LogLine
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * El registro de auditoría sobre una base real con sus guardas: cada evento de `AuditEventType`
 * entra con sus dos resultados, ninguno suma a `change_seq`, el tope de 5.000 filas se respeta
 * podando el más viejo, y una falla al anotar nunca sale de quien anota.
 */
@RunWith(AndroidJUnit4::class)
internal class RoomAuditRecorderTest : CatalogDatabaseTest() {
    private val lines = mutableListOf<String>()
    private val log =
        object : EventLog {
            override fun write(level: LogLevel, line: LogLine) {
                lines += line.text
            }
        }

    private fun recorder() = RoomAuditRecorder({ database.auditEventDao() }, FIXED_CLOCK, log)

    private suspend fun changeSeq(): Long = count("SELECT change_seq FROM backup_state")

    @Test
    fun everyEventTypeIsAcceptedWithBothResultsAndNoneCountsAsAChange() = runBlocking {
        val seq = changeSeq()
        val recorder = recorder()

        AuditEventType.entries.forEach { type ->
            AuditResult.entries.forEach { result -> recorder.record(type, result) }
        }

        val stored = AuditEventType.entries.size * AuditResult.entries.size
        assertEquals(stored.toLong(), count("SELECT COUNT(*) FROM audit_events"))
        assertEquals(
            AuditEventType.entries.size.toLong(),
            count("SELECT COUNT(DISTINCT event_type) FROM audit_events"),
        )
        // Ningún evento es un cambio del negocio: no deja nada sin respaldar.
        assertEquals(seq, changeSeq())
        assertTrue(lines.isEmpty())
    }

    @Test
    fun atTheCapTheOldestEventMakesRoomForTheNewOne() = runBlocking {
        execute(insertAuditEventsSql(AUDIT_EVENTS_MAX_ROWS))
        val oldest = count("SELECT min(id) FROM audit_events")

        recorder().record(AuditEventType.DELETE_MY_DATA, AuditResult.SUCCESS)

        assertEquals(AUDIT_EVENTS_MAX_ROWS.toLong(), count("SELECT COUNT(*) FROM audit_events"))
        assertEquals(oldest + 1, count("SELECT min(id) FROM audit_events"))
        assertEquals(
            1L,
            count(
                "SELECT COUNT(*) FROM audit_events " +
                    "WHERE id = (SELECT max(id) FROM audit_events) " +
                    "AND event_type = 'DELETE_MY_DATA' AND result = 'SUCCESS'",
            ),
        )
    }

    @Test
    fun aFailureToRecordNeverReachesTheCallerAndLogsOnlyItsClass() = runBlocking {
        val failing =
            RoomAuditRecorder({ throw IOException("ruta /secreta") }, FIXED_CLOCK, log)

        failing.record(AuditEventType.EXPORT_CSV, AuditResult.SUCCESS)

        assertEquals(0L, count("SELECT COUNT(*) FROM audit_events"))
        assertEquals(1, lines.size)
        assertFalse(lines.single().contains("secreta"))
    }

    @Test
    fun withoutAnOpenDatabaseNothingIsRecorded() = runBlocking {
        RoomAuditRecorder({ null }, FIXED_CLOCK, log)
            .record(AuditEventType.AUTH_FAILED, AuditResult.FAILURE)

        assertEquals(0L, count("SELECT COUNT(*) FROM audit_events"))
        assertTrue(lines.isEmpty())
    }
}
