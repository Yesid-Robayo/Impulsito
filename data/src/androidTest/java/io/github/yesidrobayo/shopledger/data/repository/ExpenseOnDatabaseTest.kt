package io.github.yesidrobayo.shopledger.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yesidrobayo.shopledger.data.backup.InvariantQueries
import io.github.yesidrobayo.shopledger.data.transaction.RestoreLock
import io.github.yesidrobayo.shopledger.domain.model.ArchiveFilter
import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.DocumentStatus
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.ReportPeriod
import io.github.yesidrobayo.shopledger.domain.model.VoidReason
import io.github.yesidrobayo.shopledger.domain.model.expense.ExpenseCategoryDraft
import io.github.yesidrobayo.shopledger.domain.model.expense.ExpenseRequest
import io.github.yesidrobayo.shopledger.domain.port.Clock
import io.github.yesidrobayo.shopledger.domain.usecase.expense.ArchiveExpenseCategoryUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.expense.DeleteExpenseCategoryUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.expense.ExpenseCategoryLifecycleResult
import io.github.yesidrobayo.shopledger.domain.usecase.expense.ReactivateExpenseCategoryUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.expense.RegisterExpenseResult
import io.github.yesidrobayo.shopledger.domain.usecase.expense.RegisterExpenseUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.expense.SaveExpenseCategoryResult
import io.github.yesidrobayo.shopledger.domain.usecase.expense.SaveExpenseCategoryUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.expense.VoidExpenseResult
import io.github.yesidrobayo.shopledger.domain.usecase.expense.VoidExpenseUseCase
import kotlin.time.Instant
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.YearMonth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Registrar y anular gastos, y llevar sus categorías, sobre la base real con sus guardas: cada caso
 * de uso es un solo cambio para el respaldo, anular deja la fila con su historia y nunca la borra,
 * y lo que la base impide sale como un resultado con su motivo. La zona de las pruebas está tres
 * horas al oeste de UTC, así que el instante de las filas cae el 31 de diciembre de 2025.
 *
 * Los montos, fechas y nombres son inventados.
 */
@RunWith(AndroidJUnit4::class)
internal class ExpenseOnDatabaseTest : RepositoryTest() {
    private val expenses by lazy { RoomExpenseRepository(database) }
    private val categories by lazy { RoomExpenseCategoryRepository(database) }
    private val settings by lazy { RoomAppSettingsRepository(database, RestoreLock { false }) }

    private val register by lazy {
        RegisterExpenseUseCase(runner, settings, expenses, categories, Clock { AT })
    }
    private val void by lazy { VoidExpenseUseCase(runner, expenses, Clock { LATER_AT }) }
    private val save by lazy { SaveExpenseCategoryUseCase(runner, categories, Clock { AT }) }
    private val archive by lazy { ArchiveExpenseCategoryUseCase(runner, categories, Clock { AT }) }
    private val reactivate by lazy {
        ReactivateExpenseCategoryUseCase(runner, categories, Clock { LATER_AT })
    }
    private val delete by lazy { DeleteExpenseCategoryUseCase(runner, categories) }

    private suspend fun seeded(key: String): Long =
        count("SELECT id FROM expense_categories WHERE system_key = '$key'")

    private suspend fun spend(amount: Long, categoryId: Long, date: String? = null): Long {
        val chosen = date?.let { requireNotNull(BusinessDate.parse(it)) }
        val result = register(ExpenseRequest(Money(amount), categoryId, date = chosen))
        return (result as RegisterExpenseResult.Registered).expenseId
    }

    private suspend fun activeTotal(year: Int, month: Int): Long =
        expenses.observeIn(ReportPeriod.Month(YearMonth(year, month))).first()
            .filter { it.status == DocumentStatus.ACTIVE }
            .sumOf { it.content.amount.minor }

    @Test
    fun aNewBaseHasTheNineInitialCategories() = runBlocking {
        val active = categories.observe(ArchiveFilter.ACTIVE).first()

        assertEquals(
            listOf(
                "RENT",
                "UTILITIES",
                "INTERNET_PHONE",
                "TRANSPORT",
                "PACKAGING_SUPPLIES",
                "ADVERTISING",
                "TAXES_FEES",
                "WAGES",
                "OTHER",
            ),
            active.map { it.systemKey },
        )
        assertEquals(listOf("OTHER"), active.filter { it.isSystem }.map { it.systemKey })
    }

    @Test
    fun anExpensePassesItsGuardAsOneChange() = runBlocking {
        val before = changeSeq()

        val id = spend(600_000, seeded("RENT"))

        assertEquals(before + 1, changeSeq())
        val stored = requireNotNull(expenses.findById(id))
        assertEquals(DocumentStatus.ACTIVE, stored.status)
        assertEquals(Money(600_000), stored.content.amount)
        assertEquals(BusinessDate.parse("2025-12-31"), stored.content.businessDate)
        assertEquals(AT, stored.content.occurredAt)
        assertInvariantsHold()
    }

    @Test
    fun aPastExpenseKeepsItsDayAtNoon() = runBlocking {
        val id = spend(45_000, seeded("UTILITIES"), date = "2025-11-20")

        val stored = requireNotNull(expenses.findById(id))
        assertEquals(BusinessDate.parse("2025-11-20"), stored.content.businessDate)
        // Las 12:00 tres horas al oeste de UTC son las 15:00 en UTC.
        assertEquals(Instant.parse("2025-11-20T15:00:00Z"), stored.content.occurredAt)
        assertEquals(AT, stored.content.createdAt)
    }

    @Test
    fun aZeroAmountIsRejectedBeforeTheGuard() = runBlocking {
        val before = changeSeq()

        val result = register(ExpenseRequest(Money.ZERO, seeded("OTHER")))

        assertEquals(true, result is RegisterExpenseResult.Invalid)
        assertEquals(before, changeSeq())
        assertEquals(0L, count("SELECT COUNT(*) FROM expenses"))
    }

    @Test
    fun voidingLastMonthLowersOnlyLastMonthAndKeepsTheRow() = runBlocking {
        val november = spend(120_000, seeded("RENT"), date = "2025-11-20")
        spend(50_000, seeded("OTHER"))
        assertEquals(120_000L, activeTotal(2025, 11))
        assertEquals(50_000L, activeTotal(2025, 12))
        val before = changeSeq()

        assertEquals(
            VoidExpenseResult.Voided,
            void(november, VoidReason.ENTRY_ERROR, " Se anotó dos veces "),
        )
        assertEquals(VoidExpenseResult.AlreadyVoided, void(november, VoidReason.OTHER, null))

        assertEquals(before + 1, changeSeq())
        assertEquals(0L, activeTotal(2025, 11))
        assertEquals(50_000L, activeTotal(2025, 12))
        assertEquals(2L, count("SELECT COUNT(*) FROM expenses"))
        val voided = requireNotNull(expenses.findById(november))
        assertEquals(LATER_AT, voided.voiding?.voidedAt)
        assertEquals("Se anotó dos veces", voided.voiding?.note)
        assertEquals(Money(120_000), voided.content.amount)
        assertInvariantsHold()
    }

    @Test
    fun aCategoryWithVoidedExpensesIsArchivedAndNotDeleted() = runBlocking {
        val created = save(null, ExpenseCategoryDraft("Fumigación"))
        val id = (created as SaveExpenseCategoryResult.Saved).categoryId
        void(spend(9_000, id), VoidReason.ENTRY_ERROR, null)

        assertEquals(ExpenseCategoryLifecycleResult.HasExpenses, delete(id))
        assertEquals(ExpenseCategoryLifecycleResult.Done, archive(id))
        assertEquals(AT, requireNotNull(categories.findById(id)).archivedAt)
        assertEquals(
            RegisterExpenseResult.CategoryArchived,
            register(ExpenseRequest(Money(10), id)),
        )
        assertInvariantsHold()
    }

    @Test
    fun otherIsNeverArchivedNorDeleted() = runBlocking {
        val other = seeded("OTHER")
        val before = changeSeq()

        assertEquals(ExpenseCategoryLifecycleResult.Protected, archive(other))
        assertEquals(ExpenseCategoryLifecycleResult.Protected, delete(other))

        assertEquals(before, changeSeq())
        assertNull(requireNotNull(categories.findById(other)).archivedAt)
    }

    @Test
    fun aCategoryWithoutExpensesIsDeleted() = runBlocking {
        val created = save(null, ExpenseCategoryDraft("Fumigación"))
        val id = (created as SaveExpenseCategoryResult.Saved).categoryId

        assertEquals(ExpenseCategoryLifecycleResult.Done, delete(id))

        assertNull(categories.findById(id))
    }

    @Test
    fun reactivatingIsRejectedWhileAnotherActiveCategoryHasTheName() = runBlocking {
        val first = (
            save(
                null,
                ExpenseCategoryDraft("Fumigación"),
            ) as SaveExpenseCategoryResult.Saved
            )
            .categoryId
        archive(first)
        val second = (
            save(
                null,
                ExpenseCategoryDraft("FUMIGACION"),
            ) as SaveExpenseCategoryResult.Saved
            )
            .categoryId

        assertEquals(ExpenseCategoryLifecycleResult.NameTaken, reactivate(first))
        assertNotNull(requireNotNull(categories.findById(first)).archivedAt)

        assertEquals(
            SaveExpenseCategoryResult.Saved(second),
            save(second, ExpenseCategoryDraft("Fumigación del local")),
        )
        assertEquals(ExpenseCategoryLifecycleResult.Done, reactivate(first))
        assertNull(requireNotNull(categories.findById(first)).archivedAt)
    }

    @Test
    fun renamingAnInitialCategoryAndBlankingItPassTheGuard() = runBlocking {
        val rent = seeded("RENT")

        save(rent, ExpenseCategoryDraft("Arriendo", isFixed = true))
        assertEquals("Arriendo", requireNotNull(categories.findById(rent)).details.name)
        assertEquals(true, requireNotNull(categories.findById(rent)).details.isFixed)

        save(rent, ExpenseCategoryDraft("  ", isFixed = true))
        val back = requireNotNull(categories.findById(rent))
        assertNull(back.details.name)
        assertEquals("RENT", back.systemKey)
    }

    private suspend fun assertInvariantsHold() {
        for (query in InvariantQueries.MODEL) {
            val rows = query.sql.trimEnd().removeSuffix(";")
            assertEquals(query.label, 0L, count("SELECT COUNT(*) FROM ($rows)"))
        }
    }
}
