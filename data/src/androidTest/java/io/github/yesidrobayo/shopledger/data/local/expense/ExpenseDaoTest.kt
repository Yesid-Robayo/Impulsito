package io.github.yesidrobayo.shopledger.data.local.expense

import androidx.room3.useReaderConnection
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yesidrobayo.shopledger.data.local.catalog.CREATED_AT
import io.github.yesidrobayo.shopledger.data.local.catalog.CatalogDatabaseTest
import io.github.yesidrobayo.shopledger.data.local.catalog.Emissions
import io.github.yesidrobayo.shopledger.data.local.catalog.FOREIGN_KEY
import io.github.yesidrobayo.shopledger.data.local.catalog.LATER
import io.github.yesidrobayo.shopledger.data.local.catalog.MUCH_LATER
import io.github.yesidrobayo.shopledger.data.local.catalog.constraintFailure
import io.github.yesidrobayo.shopledger.data.local.catalog.guardFailure
import io.github.yesidrobayo.shopledger.data.local.functionsOf
import io.github.yesidrobayo.shopledger.data.local.ids
import io.github.yesidrobayo.shopledger.data.local.money.moneyAccount
import io.github.yesidrobayo.shopledger.domain.model.Limit
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Los gastos: cada columna en su lugar y entera, la lista de un período del más nuevo al más
 * antiguo y por su índice, y una anulación que toca solo sus columnas y una sola vez. Un gasto no
 * se edita ni se borra.
 */
@RunWith(AndroidJUnit4::class)
internal class ExpenseDaoTest : CatalogDatabaseTest() {
    private val expenses get() = database.expenseDao()
    private var categoryId = 0L

    @Before
    fun createCategory() = runBlocking {
        categoryId = database.expenseCategoryDao().insert(expenseCategory("Servicios"))
    }

    @Test
    fun everyColumnReadsBackFromItsOwnPlace() = runBlocking {
        // El gasto recurrente todavía no tiene tabla ni llave: solo su columna. La cuenta ya tiene
        // las dos, así que el gasto apunta a una que existe.
        val accountId = database.moneyAccountDao().insert(moneyAccount("Banco").copy(id = 7))
        val full =
            expense(categoryId, businessDate = "2026-03-15", occurredAt = LATER).copy(
                amount = 123_456,
                description = "Factura de agua de marzo",
                moneyAccountId = accountId,
                recurringExpenseId = 3,
                createdAt = MUCH_LATER,
            )
        val bare = expense(categoryId)

        val fullId = expenses.insert(full)
        val bareId = expenses.insert(bare)

        assertEquals(full.copy(id = fullId), expenses.findById(fullId))
        assertEquals(bare.copy(id = bareId), expenses.findById(bareId))
        assertNull(expenses.findById(MISSING_ID))
    }

    @Test
    fun periodListIsNewestFirstWithBothEndsIncludedAndUsesItsIndex() = runBlocking {
        val before = expenses.insert(expense(categoryId, businessDate = "2025-12-31"))
        val firstDay = expenses.insert(expense(categoryId, businessDate = "2026-01-01"))
        val lastDay = expenses.insert(expense(categoryId, businessDate = "2026-01-31"))
        // Con un reloj que dice antes, pero registrado después en la misma fecha de negocio.
        val sameLastDay =
            expenses.insert(
                expense(categoryId, businessDate = "2026-01-31", occurredAt = CREATED_AT - 1),
            )
        // Registrado después, pero con una fecha anterior: el orden es por fecha de negocio.
        val middle = expenses.insert(expense(categoryId, businessDate = "2026-01-10"))
        expenses.insert(expense(categoryId, businessDate = "2026-02-01"))

        val emissions =
            Emissions(this, expenses.observeIn("2026-01-01", "2026-01-31").ids { it.id })
        try {
            emissions.awaitValue(listOf(sameLastDay, lastDay, middle, firstDay))

            // Uno anulado sigue en la lista, y la lista vuelve a emitir con un gasto nuevo.
            expenses.markVoided(middle, voidedAt = LATER, voidReason = "UNDO", voidNote = null)
            val newest = expenses.insert(expense(categoryId, businessDate = "2026-01-20"))
            emissions.awaitValue(listOf(sameLastDay, lastDay, newest, middle, firstDay))
        } finally {
            emissions.stop()
        }
        assertEquals(
            listOf(before),
            expenses.observeIn("2025-12-31", "2025-12-31").first().map { it.id },
        )
        // Con años de gastos, la lista del período no recorre la tabla: busca por el índice. Es la
        // misma sentencia de observeIn.
        val plan =
            database.useReaderConnection { connection ->
                connection.usePrepared("EXPLAIN QUERY PLAN $EXPENSES_IN_PERIOD_SQL") { statement ->
                    statement.bindText(1, "2026-01-01")
                    statement.bindText(2, "2026-01-31")
                    // La cuarta columna del plan es el detalle de cada paso.
                    buildList { while (statement.step()) add(statement.getText(3)) }
                }
            }
        assertTrue(
            "Plan sin el índice: $plan",
            plan.any { it.contains("USING INDEX index_expenses_status_business_date") },
        )
    }

    @Test
    fun voidingChangesOnlyItsColumnsAndOnlyOnce() = runBlocking {
        val id = expenses.insert(expense(categoryId).copy(description = "Bolsas"))
        val active = requireNotNull(expenses.findById(id))

        assertEquals(1, expenses.markVoided(id, LATER, "ENTRY_ERROR", "Lo anoté dos veces"))
        val voided =
            active.copy(
                status = "VOIDED",
                voidedAt = LATER,
                voidReason = "ENTRY_ERROR",
                voidNote = "Lo anoté dos veces",
            )
        assertEquals(voided, expenses.findById(id))

        // Un gasto se anula una sola vez: la segunda anulación no cambia nada.
        assertEquals(0, expenses.markVoided(id, MUCH_LATER, "OTHER", null))
        assertEquals(voided, expenses.findById(id))
        assertEquals(0, expenses.markVoided(MISSING_ID, LATER, "UNDO", null))
    }

    @Test
    fun amountIsExactAtItsLimitAndTheCategoryMustExist() = runBlocking {
        val largest = expense(categoryId).copy(amount = Limit.DOCUMENT_TOTAL.max)
        val id = expenses.insert(largest)

        assertEquals(largest.copy(id = id), expenses.findById(id))
        assertEquals(
            1L,
            count("SELECT COUNT(*) FROM expenses WHERE typeof(amount) = 'integer'"),
        )
        constraintFailure(FOREIGN_KEY) { expenses.insert(expense(MISSING_ID)) }
    }

    @Test
    fun insertingWithAnExistingIdFailsAndKeepsTheOriginal() = runBlocking {
        val original = expense(categoryId)
        val id = expenses.insert(original)

        // Un INSERT OR REPLACE borraría el gasto y escribiría otro en su lugar. La guarda rechaza
        // el id repetido antes que la llave primaria, también con REPLACE.
        guardFailure("guard:expenses") { expenses.insert(original.copy(id = id, amount = 1)) }

        assertEquals(original.copy(id = id), expenses.findById(id))
    }

    @Test
    fun theDaoOnlyAddsReadsAndVoids() {
        // Una función nueva que edite o borre un gasto rompe esta prueba a propósito: un gasto
        // registrado solo se anula.
        assertEquals(
            setOf("observeIn", "findById", "insert", "markVoided"),
            functionsOf(ExpenseDao::class.java),
        )
    }

    private companion object {
        const val MISSING_ID = 999L
    }
}
