package io.github.yesidrobayo.shopledger.data.local.backup

import androidx.room3.withWriteTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yesidrobayo.shopledger.data.local.catalog.CatalogDatabaseTest
import io.github.yesidrobayo.shopledger.data.local.catalog.Emissions
import io.github.yesidrobayo.shopledger.data.local.catalog.LATER
import io.github.yesidrobayo.shopledger.data.local.catalog.MUCH_LATER
import io.github.yesidrobayo.shopledger.data.local.catalog.guardFailure
import io.github.yesidrobayo.shopledger.data.local.expense.expense
import io.github.yesidrobayo.shopledger.data.local.expense.expenseCategory
import io.github.yesidrobayo.shopledger.data.local.functionsOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * La marca de cambios sin respaldar: sube de uno en uno, guarda el primer cambio sin copia y no lo
 * mueve después, no toca nada más, y se confirma o se deshace junto con los datos de su
 * transacción.
 */
@RunWith(AndroidJUnit4::class)
internal class BackupStateDaoTest : CatalogDatabaseTest() {
    private val state get() = database.backupStateDao()

    @Test
    fun eachChangeCountsOnceAndKeepsTheOldestUnsyncedInstant() = runBlocking {
        val initial = requireNotNull(state.get())

        assertEquals(1, state.markChanged(LATER))
        assertEquals(initial.copy(changeSeq = 1, firstUnsyncedAt = LATER), state.get())

        // El primer cambio sin copia se conserva: la antigüedad se mide desde el más viejo.
        assertEquals(1, state.markChanged(MUCH_LATER))
        assertEquals(1, state.markChanged(LATER - 1))
        assertEquals(initial.copy(changeSeq = 3, firstUnsyncedAt = LATER), state.get())
    }

    @Test
    fun theMarkIsCommittedOrUndoneWithTheDataOfItsTransaction() = runBlocking {
        val categoryId = database.expenseCategoryDao().insert(expenseCategory("Transporte"))
        val initial = requireNotNull(state.get())

        // Así la usa la escritura de un caso de uso: los datos, y al final la marca.
        database.withWriteTransaction {
            database.expenseDao().insert(expense(categoryId))
            check(state.markChanged(LATER) == 1) { "Falta la fila del estado del respaldo" }
        }
        assertEquals(1L, count("SELECT COUNT(*) FROM expenses"))
        assertEquals(1L, state.get()?.changeSeq)

        // Si algo falla después de marcar, no queda ni el gasto ni la marca.
        var failed = false
        try {
            database.withWriteTransaction {
                database.expenseDao().insert(expense(categoryId))
                state.markChanged(MUCH_LATER)
                throw InjectedFailure()
            }
        } catch (expected: InjectedFailure) {
            failed = true
        }
        assertTrue("La transacción debía fallar", failed)
        assertEquals(1L, count("SELECT COUNT(*) FROM expenses"))
        assertEquals(initial.copy(changeSeq = 1, firstUnsyncedAt = LATER), state.get())
    }

    @Test
    fun theStateEmitsAgainAfterEachChange() = runBlocking {
        val initial = requireNotNull(state.get())
        val emissions = Emissions(this, state.observe())
        try {
            emissions.awaitValue(initial)

            state.markChanged(LATER)
            emissions.awaitValue(initial.copy(changeSeq = 1, firstUnsyncedAt = LATER))
        } finally {
            emissions.stop()
        }
    }

    @Test
    fun withoutTheRowTheMarkSaysSoInsteadOfCreatingOne() = runBlocking {
        // Solo en esta prueba, y a mano: ningún DAO borra la fila. Sin ella la marca devuelve 0,
        // y la transacción que la pidió no debe confirmarse. Su guarda impide borrarla: la prueba
        // la retira antes, como la encontraría la app en una base dañada.
        guardFailure("guard:backup_state_no_delete") { execute("DELETE FROM backup_state") }
        execute("DROP TRIGGER guard_backup_state_no_delete")
        execute("DELETE FROM backup_state")

        assertEquals(0, state.markChanged(LATER))
        assertNull(state.get())
        assertEquals(0L, count("SELECT COUNT(*) FROM backup_state"))
    }

    @Test
    fun theDaoOnlyReadsMarksChangesAndWritesTheEngineColumns() {
        // La fila nace con la base. Una función nueva rompe esta prueba a propósito, y quien la
        // agregue revisa que cambie solo sus columnas, como la prueba de abajo.
        assertEquals(
            setOf(
                "observe",
                "get",
                "markChanged",
                "isEmpty",
                "recordCount",
                "catalogCount",
                "forceUpload",
                "markAttempt",
                "restoreAttempt",
                "saveIntent",
                "clearIntent",
                "markUploadFinished",
                "recordFailure",
                "recordTransportFailure",
                "leaveConnected",
                "markConflict",
                "keepThisPhone",
                "reopenAfterConflict",
                "confirm",
                "confirmUnrestorable",
                "recordInvariantFailures",
                "markFullVerify",
                "recordActiveInvariantFailures",
                "markStaleNotice",
            ),
            functionsOf(BackupStateDao::class.java),
        )
    }

    @Test
    fun eachEngineWriteChangesOnlyItsColumns() = runBlocking {
        execute(
            "UPDATE backup_state SET link_state = 'CONNECTED', destination_kind = 'WEBDAV', " +
                "destination_id = 'huella', change_seq = 3, first_unsynced_at = $LATER WHERE id = 1",
        )
        var before = requireNotNull(state.get())
        suspend fun changed(expected: BackupStateEntity) {
            val after = requireNotNull(state.get())
            assertEquals(expected, after)
            before = after
        }

        assertEquals(1, state.markAttempt(LATER))
        changed(before.copy(lastAttemptAt = LATER))
        assertEquals(1, state.restoreAttempt(null))
        changed(before.copy(lastAttemptAt = null))
        assertEquals(1, state.restoreAttempt(LATER))
        changed(before.copy(lastAttemptAt = LATER))
        assertEquals(1, state.saveIntent(4, 3, "ab".repeat(32), 1_234))
        changed(
            before.copy(
                pendingGeneration = 4,
                pendingSeq = 3,
                pendingSha256 = "ab".repeat(32),
                pendingSizeBytes = 1_234,
            ),
        )
        assertEquals(1, state.markUploadFinished(MUCH_LATER))
        changed(before.copy(lastUploadFinishedAt = MUCH_LATER))
        assertEquals(1, state.recordFailure("QUOTA_EXCEEDED"))
        changed(before.copy(failureCode = "QUOTA_EXCEEDED"))
        assertEquals(1, state.clearIntent())
        changed(
            before.copy(
                pendingGeneration = null,
                pendingSeq = null,
                pendingSha256 = null,
                pendingSizeBytes = null,
            ),
        )

        // Confirmar sin cambios nuevos: nada queda sin copia, y la intención se borra.
        state.saveIntent(4, 3, "ab".repeat(32), 1_234)
        before = requireNotNull(state.get())
        assertEquals(0, state.confirm(4, "teléfono", "copia", 3, MUCH_LATER, LATER, null, "otra"))
        changed(before)
        assertEquals(1, state.confirm(4, "teléfono", "copia", 3, MUCH_LATER, LATER, null, "huella"))
        changed(
            before.copy(
                headGeneration = 4,
                headDeviceId = "teléfono",
                headFileRef = "copia",
                verifiedSeq = 3,
                lastSuccessAt = MUCH_LATER,
                firstUnsyncedAt = null,
                failureCode = null,
                pendingGeneration = null,
                pendingSeq = null,
                pendingSha256 = null,
                pendingSizeBytes = null,
            ),
        )
        // Con un cambio llegado durante la copia, queda pendiente desde el instante de la copia.
        state.markChanged(MUCH_LATER)
        before = requireNotNull(state.get())
        assertEquals(
            1,
            state.confirm(5, "teléfono", "copia-5", 3, MUCH_LATER, LATER, null, "huella"),
        )
        changed(
            before.copy(
                headGeneration = 5,
                headFileRef = "copia-5",
                firstUnsyncedAt = LATER,
            ),
        )

        // La lectura completa solo se anota si el destino sigue siendo el mismo.
        assertEquals(0, state.markFullVerify(MUCH_LATER, "otra"))
        changed(before.copy(headGeneration = 5, headFileRef = "copia-5", firstUnsyncedAt = LATER))
        assertEquals(1, state.markFullVerify(MUCH_LATER, "huella"))
        changed(before.copy(lastFullVerifyAt = MUCH_LATER))

        // La verificación diaria deja el número y su fallo, sin tapar otro fallo que ya estaba.
        assertEquals(1, state.recordActiveInvariantFailures(2))
        changed(before.copy(invariantFailures = 2, failureCode = "LOCAL_INVARIANT"))
        state.recordFailure("QUOTA_EXCEEDED")
        before = requireNotNull(state.get())
        assertEquals(1, state.recordActiveInvariantFailures(3))
        changed(before.copy(invariantFailures = 3))
        // La notificación de un día sin copia solo anota su hora.
        assertEquals(1, state.markStaleNotice(MUCH_LATER))
        changed(before.copy(lastStaleNoticeAt = MUCH_LATER))

        // El enlace solo deja de estar conectado si sigue en el mismo destino.
        assertEquals(0, state.markConflict("OTHER_DEVICE", null, null, null, "otra"))
        assertEquals(0, state.leaveConnected("PERMISSION_LOST", "otra"))
        changed(before)
        assertEquals(1, state.markConflict("OTHER_DEVICE", 7, "otro", "C", "huella"))
        changed(
            before.copy(
                linkState = "CONFLICT",
                conflictKind = "OTHER_DEVICE",
                conflictGeneration = 7,
                conflictDeviceId = "otro",
                conflictFileRef = "C",
            ),
        )
        assertEquals(0, state.leaveConnected("PERMISSION_LOST", "huella"))
        execute("UPDATE backup_state SET link_state = 'CONNECTED' WHERE id = 1")
        before = requireNotNull(state.get())
        assertEquals(1, state.leaveConnected("PERMISSION_LOST", "huella"))
        changed(before.copy(linkState = "PERMISSION_LOST"))
    }

    @Test
    fun keepingThisPhoneLeavesTheConflictOnlyForItsDestination() = runBlocking {
        execute(
            "UPDATE backup_state SET link_state = 'CONFLICT', destination_kind = 'WEBDAV', " +
                "destination_id = 'huella', change_seq = 3, verified_seq = 1, " +
                "head_generation = 2, head_device_id = 'este', head_file_ref = 'copia-2', " +
                "conflict_kind = 'OTHER_DEVICE', conflict_generation = 7, " +
                "conflict_device_id = 'otro', conflict_file_ref = 'copia-7' WHERE id = 1",
        )
        var before = requireNotNull(state.get())
        val cleared =
            { entity: BackupStateEntity ->
                entity.copy(
                    linkState = "CONNECTED",
                    conflictKind = null,
                    conflictGeneration = null,
                    conflictDeviceId = null,
                    conflictFileRef = null,
                    changeSeq = entity.changeSeq + 1,
                    firstUnsyncedAt = LATER,
                )
            }

        assertEquals(0, state.keepThisPhone(7, "otro", "copia-7", LATER, "otra"))
        assertEquals(0, state.reopenAfterConflict(LATER, "otra"))
        assertEquals(before, state.get())
        assertEquals(1, state.keepThisPhone(7, "otro", "copia-7", LATER, "huella"))
        assertEquals(
            cleared(before).copy(
                headGeneration = 7,
                headDeviceId = "otro",
                headFileRef = "copia-7",
                shrinkAcceptedGeneration = 7,
            ),
            state.get(),
        )
        // Ya no está en conflicto: no se sale dos veces.
        assertEquals(0, state.keepThisPhone(7, "otro", "copia-7", MUCH_LATER, "huella"))

        execute(
            "UPDATE backup_state SET link_state = 'CONFLICT', conflict_kind = 'CLOUD_AHEAD' WHERE id = 1",
        )
        before = requireNotNull(state.get())
        assertEquals(1, state.reopenAfterConflict(MUCH_LATER, "huella"))
        // El primer cambio sin copia ya tenía instante: lo conserva.
        assertEquals(cleared(before), state.get())
    }

    @Test
    fun forcingAnUploadIsOneMoreChangeThatKeepsTheOldestInstant() = runBlocking {
        var before = requireNotNull(state.get())
        assertEquals(1, state.forceUpload(LATER))
        assertEquals(
            before.copy(changeSeq = before.changeSeq + 1, firstUnsyncedAt = LATER),
            state.get(),
        )
        before = requireNotNull(state.get())
        assertEquals(1, state.forceUpload(MUCH_LATER))
        assertEquals(before.copy(changeSeq = before.changeSeq + 1), state.get())
    }

    @Test
    fun theGuardCountsTheActiveDatabaseLikeACopy() = runBlocking {
        assertEquals(0L, state.recordCount())
        assertEquals(0L, state.catalogCount())
        val category = database.expenseCategoryDao().insert(expenseCategory("Luz"))
        database.expenseDao().insert(expense(category))
        assertEquals(1L, state.recordCount())
        assertEquals(0L, state.catalogCount())
    }

    @Test
    fun transportFailuresCountUpAndTurnPersistentAtTheLimitUntilAConfirmedCopy() = runBlocking {
        execute(
            "UPDATE backup_state SET link_state = 'CONNECTED', destination_kind = 'WEBDAV', " +
                "destination_id = 'huella', change_seq = 3, first_unsynced_at = $LATER, " +
                "failure_code = 'VERIFY_MISMATCH' WHERE id = 1",
        )
        val initial = requireNotNull(state.get())

        // Antes del tope, el fallo que había sigue a la vista y solo cambian la cuenta y la clase.
        for (count in 1L..5L) {
            val kind = if (count % 2 == 0L) "HTTP_5XX" else "TLS"
            assertEquals(1, state.recordTransportFailure(kind, 6, "TRANSPORT_PERSISTENT"))
            assertEquals(
                initial.copy(consecutiveFailures = count, lastFailureKind = kind),
                state.get(),
            )
        }
        assertEquals(1, state.recordTransportFailure("TLS", 6, "TRANSPORT_PERSISTENT"))
        assertEquals(
            initial.copy(
                consecutiveFailures = 6,
                lastFailureKind = "TLS",
                failureCode = "TRANSPORT_PERSISTENT",
            ),
            state.get(),
        )
        // Después del tope sigue contando, y sigue persistente.
        state.recordFailure("VERIFY_MISMATCH")
        state.recordTransportFailure("RATE_LIMIT", 6, "TRANSPORT_PERSISTENT")
        assertEquals(7L, state.get()?.consecutiveFailures)
        assertEquals("TRANSPORT_PERSISTENT", state.get()?.failureCode)

        // Solo una copia confirmada vuelve la cuenta a cero.
        state.confirm(1, "teléfono", "copia", 3, MUCH_LATER, LATER, null, "huella")
        val confirmed = requireNotNull(state.get())
        assertEquals(0L, confirmed.consecutiveFailures)
        assertNull(confirmed.lastFailureKind)
        assertNull(confirmed.failureCode)
    }

    @Test
    fun anUnrestorableCopyBecomesTheHeadButLeavesTheChangesPending() = runBlocking {
        execute(
            "UPDATE backup_state SET link_state = 'CONNECTED', destination_kind = 'WEBDAV', " +
                "destination_id = 'huella', change_seq = 3, first_unsynced_at = $LATER, " +
                "consecutive_failures = 2, last_failure_kind = 'TLS' WHERE id = 1",
        )
        state.saveIntent(4, 3, "ab".repeat(32), 1_234)
        val before = requireNotNull(state.get())

        assertEquals(
            0,
            state.confirmUnrestorable(4, "teléfono", "copia", "LOCAL_INVARIANT_FATAL", "otra"),
        )
        assertEquals(before, state.get())
        assertEquals(
            1,
            state.confirmUnrestorable(4, "teléfono", "copia", "LOCAL_INVARIANT_FATAL", "huella"),
        )
        assertEquals(
            before.copy(
                headGeneration = 4,
                headDeviceId = "teléfono",
                headFileRef = "copia",
                consecutiveFailures = 0,
                lastFailureKind = null,
                failureCode = "LOCAL_INVARIANT_FATAL",
                pendingGeneration = null,
                pendingSeq = null,
                pendingSha256 = null,
                pendingSizeBytes = null,
            ),
            state.get(),
        )
    }

    @Test
    fun theCountOfRepairsStaysOnlyWithACopyThatStillNeedsThem() = runBlocking {
        execute(
            "UPDATE backup_state SET link_state = 'CONNECTED', destination_kind = 'WEBDAV', " +
                "destination_id = 'huella', change_seq = 3 WHERE id = 1",
        )
        assertEquals(1, state.recordInvariantFailures(2))
        assertEquals(2L, state.get()?.invariantFailures)

        state.confirm(1, "teléfono", "copia-1", 3, MUCH_LATER, LATER, "LOCAL_INVARIANT", "huella")
        assertEquals(2L, state.get()?.invariantFailures)
        assertEquals("LOCAL_INVARIANT", state.get()?.failureCode)

        state.confirm(2, "teléfono", "copia-2", 3, MUCH_LATER, LATER, null, "huella")
        assertEquals(0L, state.get()?.invariantFailures)
        assertNull(state.get()?.failureCode)
    }

    @Test
    fun aDatabaseWithoutBusinessIsEmpty() = runBlocking {
        assertTrue(state.isEmpty())
        database.expenseCategoryDao().insert(expenseCategory("Transporte"))
        // Una categoría de gasto sola no es negocio que respaldar.
        assertTrue(state.isEmpty())
        database.expenseDao().insert(
            expense(database.expenseCategoryDao().insert(expenseCategory("Luz"))),
        )
        assertFalse(state.isEmpty())
    }

    /** Una falla inventada a propósito, sin mensaje. */
    private class InjectedFailure : RuntimeException()
}
