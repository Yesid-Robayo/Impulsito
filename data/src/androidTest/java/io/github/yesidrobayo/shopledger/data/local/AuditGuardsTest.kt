package io.github.yesidrobayo.shopledger.data.local

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yesidrobayo.shopledger.data.local.catalog.CREATED_AT
import io.github.yesidrobayo.shopledger.data.local.catalog.LATER
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Las sondas de las guardas del registro de auditoría: un evento entra solo con un tipo y un
 * resultado conocidos, nunca cambia, y solo se borra lo que pasa de las 5.000 filas. Cada guarda
 * rechaza lo que debe, con su mensaje, y deja pasar lo válido.
 */
@RunWith(AndroidJUnit4::class)
internal class AuditGuardsTest : GuardProbeTest() {
    @Test
    fun anEventEntersOnlyWithAKnownTypeAndResult() = runBlocking {
        val valid = auditEventRow()
        for (broken in listOf(
            valid.with("event_type" to "LOGIN"),
            valid.with("event_type" to "export_csv"),
            valid.with("event_type" to null),
            valid.with("result" to "PARTIAL"),
            valid.with("result" to null),
            valid.with("occurred_at" to 1.5),
            valid.with("occurred_at" to "hoy"),
            valid.with("occurred_at" to null),
        )) {
            rejected(AUDIT, broken.insertInto("audit_events"))
        }

        for (type in EVENT_TYPES) insert("audit_events", valid.with("event_type" to type))
        val id = insert("audit_events", valid.with("result" to "FAILURE"))
        // INSERT OR REPLACE borraría el evento sin pasar por la guarda de borrado.
        rejected(AUDIT, valid.withId(id).insertOrReplaceInto("audit_events"))
        assertEquals(EVENT_TYPES.size + 1L, count("SELECT COUNT(*) FROM audit_events"))
        assertEquals(1L, count("SELECT COUNT(*) FROM audit_events WHERE result = 'FAILURE'"))
    }

    @Test
    fun anEventNeverChanges() = runBlocking {
        val id = insert("audit_events", auditEventRow())

        rejected(APPEND_ONLY, update("audit_events", id, "result" to "FAILURE"))
        rejected(APPEND_ONLY, update("audit_events", id, "occurred_at" to LATER))
        rejected(APPEND_ONLY, update("audit_events", id, "id" to id + 1))
        assertEquals(CREATED_AT, count("SELECT occurred_at FROM audit_events WHERE id = $id"))
    }

    @Test
    fun onlyWhatPassesTheCapIsDeleted() = runBlocking {
        execute(insertAuditEventsSql(5_000))
        rejected(APPEND_ONLY, DELETE_OLDEST)
        rejected(APPEND_ONLY, "DELETE FROM audit_events")
        assertEquals(5_000L, count("SELECT COUNT(*) FROM audit_events"))

        // Con una fila de más, se borra esa sola. Borrar todo se detiene en el tope y se deshace
        // entero.
        insert("audit_events", auditEventRow())
        rejected(APPEND_ONLY, "DELETE FROM audit_events")
        assertEquals(5_001L, count("SELECT COUNT(*) FROM audit_events"))
        execute(DELETE_OLDEST)
        assertEquals(5_000L, count("SELECT COUNT(*) FROM audit_events"))
        assertEquals(2L, count("SELECT min(id) FROM audit_events"))
    }

    private companion object {
        const val AUDIT = "guard:audit_events"
        const val APPEND_ONLY = "guard:audit_events_append_only"
        const val DELETE_OLDEST =
            "DELETE FROM audit_events WHERE id = (SELECT min(id) FROM audit_events)"

        /** Todos los valores de `AuditEventType`, con el nombre que guarda la base. */
        val EVENT_TYPES =
            listOf(
                "EXPORT_CSV",
                "EXPORT_FULL_COPY",
                "RESTORE",
                "RESTORE_REJECTED",
                "DESTINATION_DISCONNECTED",
                "DESTINATION_CHANGED",
                "APP_LOCK_CHANGED",
                "ENCRYPTION_CHANGED",
                "RECOVERY_CODE_VIEWED",
                "DELETE_MY_DATA",
                "AUTH_FAILED",
            )
    }
}

/** Un evento válido cualquiera: una exportación que salió bien. */
internal fun auditEventRow(): Row = row(
    "occurred_at" to CREATED_AT,
    "event_type" to "EXPORT_CSV",
    "result" to "SUCCESS",
)

/**
 * Agrega [rows] exportaciones que salieron bien, en una sola sentencia, cada una un milisegundo
 * después de la anterior. Cada fila pasa por la guarda de inserción.
 */
internal fun insertAuditEventsSql(rows: Int): String =
    "WITH RECURSIVE n(i) AS (SELECT 1 UNION ALL SELECT i + 1 FROM n WHERE i < $rows) " +
        "INSERT INTO audit_events (occurred_at, event_type, result) " +
        "SELECT $CREATED_AT + i, 'EXPORT_CSV', 'SUCCESS' FROM n"
