package io.github.yesidrobayo.shopledger.data.local.expense

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yesidrobayo.shopledger.data.local.catalog.CREATED_AT
import io.github.yesidrobayo.shopledger.data.local.catalog.CatalogDatabaseTest
import io.github.yesidrobayo.shopledger.data.local.catalog.Emissions
import io.github.yesidrobayo.shopledger.data.local.catalog.FOREIGN_KEY
import io.github.yesidrobayo.shopledger.data.local.catalog.LATER
import io.github.yesidrobayo.shopledger.data.local.catalog.MUCH_LATER
import io.github.yesidrobayo.shopledger.data.local.catalog.UNIQUE
import io.github.yesidrobayo.shopledger.data.local.catalog.constraintFailure
import io.github.yesidrobayo.shopledger.data.local.catalog.guardFailure
import io.github.yesidrobayo.shopledger.data.local.functionsOf
import io.github.yesidrobayo.shopledger.data.local.ids
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Las categorías de gasto: iniciales sin nombre junto a las de la persona, clave única, nombre
 * único entre las vigentes, orden por creación, archivo y reactivación, y la de otros gastos, que
 * nunca se archiva ni se borra. Con gastos, una categoría no se borra.
 *
 * La base nace con sus categorías iniciales, así que las pruebas de esas filas usan las que ya
 * trae, y las listas se comparan con lo que había al nacer.
 */
@RunWith(AndroidJUnit4::class)
internal class ExpenseCategoryDaoTest : CatalogDatabaseTest() {
    private val categories get() = database.expenseCategoryDao()
    private val expenses get() = database.expenseDao()

    @Test
    fun seededAndPersonCategoriesReadBackWithEveryColumn() = runBlocking {
        val seeded = seededExpenseCategory("OTHER", isSystem = 1)
        val own = expenseCategory("Arriendo del local", isFixed = 1)

        val seededId = seededId("OTHER")
        val ownId = categories.insert(own)

        assertEquals(seeded.copy(id = seededId), categories.findById(seededId))
        assertEquals(own.copy(id = ownId), categories.findById(ownId))
        assertEquals("arriendo del local", categories.findById(ownId)?.normalizedName)
        assertNull(categories.findById(MISSING_ID))
    }

    @Test
    fun keysAndActiveNamesCannotRepeatButUnnamedSeedsDoNotCollide() = runBlocking {
        // Las nueve iniciales nacen sin nombre: SQLite no compara nulos, así que no chocan, y una
        // más sin nombre tampoco. Su clave, en cambio, no se repite.
        categories.insert(seededExpenseCategory("TEST_SEED"))
        constraintFailure(UNIQUE) { categories.insert(seededExpenseCategory("RENT")) }
        constraintFailure(UNIQUE) { categories.insert(seededExpenseCategory("TEST_SEED")) }

        // Tildes, mayúsculas y espacios de más no hacen otro nombre: la guarda lo rechaza.
        val cleaningId = categories.insert(expenseCategory("Limpieza Baño"))
        guardFailure(GUARD) { categories.insert(expenseCategory("  LIMPIÉZA   BAÑO ")) }
        // En español la ñ es otra letra: Baño y Bano son dos nombres.
        val otherId = categories.insert(expenseCategory("Limpieza Bano"))
        assertEquals(otherId, categories.findActiveByNormalizedName("limpieza bano")?.id)

        // Una archivada no reserva su nombre: la unicidad es solo entre las vigentes.
        categories.setArchivedAt(cleaningId, archivedAt = LATER, updatedAt = LATER)
        assertNull(categories.findActiveByNormalizedName("limpieza baño"))
        val againId = categories.insert(expenseCategory("Limpieza baño"))
        assertEquals(againId, categories.findActiveByNormalizedName("limpieza baño")?.id)
        assertEquals(LATER, categories.findById(cleaningId)?.archivedAt)
        assertNull(categories.findActiveByNormalizedName("limpieza"))

        // Con una vigente de ese nombre, la archivada no se reactiva, y otra no se renombra a él.
        guardFailure(GUARD) { categories.setArchivedAt(cleaningId, null, MUCH_LATER) }
        guardFailure(GUARD) {
            categories.update(otherId, "Limpieza baño", "limpieza baño", isFixed = 0, LATER)
        }
        assertEquals(LATER, categories.findById(cleaningId)?.archivedAt)
        assertEquals("limpieza bano", categories.findById(otherId)?.normalizedName)
    }

    @Test
    fun listsFollowCreationOrderAndEmitAgainOnArchiveAndReactivation() = runBlocking {
        val before = categories.observeActive().first().map { it.id }
        // El orden es el de creación, no el alfabético ni el del texto traducido.
        val zeta = categories.insert(expenseCategory("Zapatos de trabajo"))
        val alfa = categories.insert(expenseCategory("Agua"))
        val seeded = seededId("TRANSPORT")

        val active = Emissions(this, categories.observeActive().ids { it.id })
        val archived = Emissions(this, categories.observeArchived().ids { it.id })
        try {
            active.awaitValue(before + listOf(zeta, alfa))
            archived.awaitValue(emptyList())

            categories.setArchivedAt(zeta, archivedAt = LATER, updatedAt = LATER)
            categories.setArchivedAt(seeded, archivedAt = LATER, updatedAt = LATER)
            active.awaitValue(before - seeded + listOf(alfa))
            // La inicial nació antes que las de la persona.
            archived.awaitValue(listOf(seeded, zeta))

            // Reactivada, vuelve a su lugar de creación.
            categories.setArchivedAt(zeta, archivedAt = null, updatedAt = MUCH_LATER)
            active.awaitValue(before - seeded + listOf(zeta, alfa))
            archived.awaitValue(listOf(seeded))
        } finally {
            active.stop()
            archived.stop()
        }
        assertEquals(MUCH_LATER, categories.findById(zeta)?.updatedAt)
    }

    @Test
    fun updateChangesWhatThePersonWritesAndKeepsTheRest() = runBlocking {
        val seeded = seededExpenseCategory("WAGES")
        val id = seededId("WAGES")
        categories.setArchivedAt(id, archivedAt = LATER, updatedAt = LATER)

        // La persona renombra una inicial: desde ahí se muestra su nombre, y la clave se queda.
        val changed = categories.update(id, "Sueldos", "sueldos", isFixed = 1, MUCH_LATER)

        assertEquals(1, changed)
        val expected =
            seeded.copy(
                id = id,
                name = "Sueldos",
                normalizedName = "sueldos",
                isFixed = 1,
                archivedAt = LATER,
                updatedAt = MUCH_LATER,
            )
        assertEquals(expected, categories.findById(id))
        assertEquals(0, categories.update(MISSING_ID, "Nada", "nada", isFixed = 0, LATER))
    }

    @Test
    fun theOtherExpensesCategoryIsNeverArchivedNorDeleted() = runBlocking {
        val other = seededExpenseCategory("OTHER", isSystem = 1)
        val id = seededId("OTHER")

        assertEquals(0, categories.setArchivedAt(id, archivedAt = LATER, updatedAt = LATER))
        assertEquals(0, categories.delete(id))

        assertEquals(other.copy(id = id), categories.findById(id))
        assertEquals(0, categories.setArchivedAt(MISSING_ID, archivedAt = LATER, updatedAt = LATER))
    }

    @Test
    fun aCategoryWithExpensesIsNotDeletedEvenIfTheyAreVoided() = runBlocking {
        val unused = categories.insert(expenseCategory("Publicidad"))
        val used = categories.insert(expenseCategory("Empaques"))
        val expenseId = expenses.insert(expense(used))
        expenses.markVoided(expenseId, LATER, voidReason = "ENTRY_ERROR", voidNote = null)

        assertFalse(categories.hasExpenses(unused))
        assertTrue(categories.hasExpenses(used))

        // Con gastos, la llave RESTRICT hace fallar el borrado y la categoría se queda.
        constraintFailure(FOREIGN_KEY) { categories.delete(used) }
        assertEquals(used, categories.findById(used)?.id)

        assertEquals(1, categories.delete(unused))
        assertNull(categories.findById(unused))
        assertEquals(0, categories.delete(unused))
    }

    @Test
    fun insertingWithAnExistingIdFailsAndKeepsTheOriginal() = runBlocking {
        val original = expenseCategory("Internet")
        val id = categories.insert(original)

        // Un INSERT OR REPLACE borraría la categoría y escribiría otra en su lugar.
        constraintFailure(UNIQUE) {
            categories.insert(expenseCategory("Teléfono").copy(id = id))
        }

        assertEquals(original.copy(id = id), categories.findById(id))
        assertEquals(CREATED_AT, categories.findById(id)?.createdAt)
    }

    @Test
    fun theDaoHasOnlyTheseFunctions() {
        // Una función nueva rompe esta prueba a propósito: quien la agregue revisa que no archive
        // ni borre la de otros gastos, y que no use REPLACE.
        assertEquals(
            setOf(
                "observeActive",
                "observeArchived",
                "findById",
                "findActiveByNormalizedName",
                "hasExpenses",
                "insert",
                "update",
                "setArchivedAt",
                "delete",
            ),
            functionsOf(ExpenseCategoryDao::class.java),
        )
    }

    /** El id de la categoría inicial de clave [systemKey], que la base trae desde que nace. */
    private suspend fun seededId(systemKey: String): Long =
        count("SELECT id FROM expense_categories WHERE system_key = '$systemKey'")

    private companion object {
        const val MISSING_ID = 999L
        const val GUARD = "guard:expense_categories"
    }
}
