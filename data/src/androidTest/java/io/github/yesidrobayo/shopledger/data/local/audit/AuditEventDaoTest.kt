package io.github.yesidrobayo.shopledger.data.local.audit

import androidx.room3.useReaderConnection
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yesidrobayo.shopledger.data.local.catalog.CREATED_AT
import io.github.yesidrobayo.shopledger.data.local.catalog.CatalogDatabaseTest
import io.github.yesidrobayo.shopledger.data.local.catalog.Emissions
import io.github.yesidrobayo.shopledger.data.local.catalog.LATER
import io.github.yesidrobayo.shopledger.data.local.catalog.category
import io.github.yesidrobayo.shopledger.data.local.foreignKeysOf
import io.github.yesidrobayo.shopledger.data.local.functionsOf
import io.github.yesidrobayo.shopledger.data.local.ids
import io.github.yesidrobayo.shopledger.data.local.indexesOf
import io.github.yesidrobayo.shopledger.data.local.insertAuditEventsSql
import io.github.yesidrobayo.shopledger.data.local.texts
import io.github.yesidrobayo.shopledger.data.transaction.BackupStateChangeCounter
import io.github.yesidrobayo.shopledger.data.transaction.runnerFor
import io.github.yesidrobayo.shopledger.domain.port.TxResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * El registro de auditoría: guarda solo qué pasó y cuándo, se lee desde el más nuevo, se poda
 * desde el más viejo solo sobre el tope, y escribirlo no cuenta como un cambio sin respaldar.
 */
@RunWith(AndroidJUnit4::class)
internal class AuditEventDaoTest : CatalogDatabaseTest() {
    private val events get() = database.auditEventDao()

    @Test
    fun theTableHasNoColumnForAmountsOrNames() = runBlocking {
        val columns =
            database.useReaderConnection {
                it.texts("SELECT name FROM pragma_table_info('audit_events') ORDER BY cid")
            }

        assertEquals(listOf("id", "occurred_at", "event_type", "result"), columns)
        // Se lee por la llave primaria, y no apunta a nada del negocio.
        assertEquals(emptyList<String>(), database.indexesOf("audit_events"))
        assertEquals(emptyList<String>(), database.foreignKeysOf("audit_events"))
    }

    @Test
    fun theLatestEventsComeNewestFirstAndAgainOnEachChange() = runBlocking {
        val emissions = Emissions(this, events.observeLatest(limit = 2).ids { it.id })
        try {
            emissions.awaitValue(emptyList())

            val first = events.insert(event("EXPORT_CSV"))
            emissions.awaitValue(listOf(first))

            val second = events.insert(event("RESTORE_REJECTED", result = "FAILURE"))
            val third = events.insert(event("AUTH_FAILED", result = "FAILURE", at = CREATED_AT))
            // Manda el orden del registro, no el reloj: el tercero va primero aunque diga antes.
            emissions.awaitValue(listOf(third, second))
        } finally {
            emissions.stop()
        }
    }

    @Test
    fun pruningKeepsTheNewestEventsUpToTheCap() = runBlocking {
        execute(insertAuditEventsSql(AUDIT_EVENTS_MAX_ROWS - 1))
        assertEquals(0, events.pruneOldest())

        events.insert(event("EXPORT_CSV"))
        assertEquals(0, events.pruneOldest())
        assertEquals(AUDIT_EVENTS_MAX_ROWS.toLong(), count("SELECT COUNT(*) FROM audit_events"))

        val newest = (1..3).map { events.insert(event("RESTORE")) }
        assertEquals(3, events.pruneOldest())
        assertEquals(AUDIT_EVENTS_MAX_ROWS.toLong(), count("SELECT COUNT(*) FROM audit_events"))
        // Se fueron los tres más viejos, y los nuevos siguen.
        assertEquals(4L, count("SELECT min(id) FROM audit_events"))
        assertEquals(newest.last(), count("SELECT max(id) FROM audit_events"))
        assertEquals(0, events.pruneOldest())
    }

    @Test
    fun writingAndPruningEventsCountNoChange() = runBlocking {
        execute(insertAuditEventsSql(AUDIT_EVENTS_MAX_ROWS))
        val before = requireNotNull(database.backupStateDao().get())

        events.insert(event("EXPORT_FULL_COPY"))
        assertEquals(1, events.pruneOldest())

        assertEquals(before, database.backupStateDao().get())
    }

    @Test
    fun anOperationAndItsEventCountAsOneChange() = runBlocking {
        val runner = runnerFor(database, BackupStateChangeCounter(database.backupStateDao()))
        val before = requireNotNull(database.backupStateDao().get())

        // Así lo usa quien registra: la operación en su escritura, y el evento después, fuera.
        runner.write {
            database.categoryDao().insert(category("Bebidas"))
            TxResult.Commit(Unit)
        }
        val afterOperation = requireNotNull(database.backupStateDao().get())
        events.insert(event("EXPORT_CSV"))
        events.pruneOldest()

        assertEquals(before.changeSeq + 1, afterOperation.changeSeq)
        assertEquals(afterOperation, database.backupStateDao().get())
        assertEquals(1L, count("SELECT COUNT(*) FROM audit_events"))
    }

    @Test
    fun noFunctionEditsAnEvent() {
        assertEquals(
            // `observeLatestFailures` solo lee, como `observeLatest`.
            setOf("observeLatest", "observeLatestFailures", "insert", "pruneOldest"),
            functionsOf(AuditEventDao::class.java),
        )
    }

    private fun event(
        type: String,
        result: String = "SUCCESS",
        at: Long = LATER,
    ): AuditEventEntity = AuditEventEntity(occurredAt = at, eventType = type, result = result)
}
