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
    fun theDaoOnlyReadsAndMarksChanges() {
        // La fila nace con la base. Las escrituras del respaldo llegan con él: una función nueva
        // rompe esta prueba a propósito, y quien la agregue revisa que cambie solo sus columnas.
        assertEquals(
            setOf("observe", "get", "markChanged"),
            functionsOf(BackupStateDao::class.java),
        )
    }

    /** Una falla inventada a propósito, sin mensaje. */
    private class InjectedFailure : RuntimeException()
}
