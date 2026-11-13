package io.github.yesidrobayo.shopledger.domain.usecase.expense

import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.VoidReason
import io.github.yesidrobayo.shopledger.domain.model.expense.ExpenseRequest
import io.github.yesidrobayo.shopledger.domain.model.money.AccountCurrency
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyAccount
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyAccountDetails
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyAccountKind
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyMovementType
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyOrigin
import io.github.yesidrobayo.shopledger.domain.model.settings.CurrencyCode
import io.github.yesidrobayo.shopledger.domain.testing.InjectedFailure
import io.github.yesidrobayo.shopledger.domain.usecase.money.AccountIssue
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Un gasto pagado desde una cuenta saca el dinero de ella con un `EXPENSE_PAYMENT`, en la misma
 * transacción, y su anulación lo devuelve con un `MOVEMENT_VOID`. Sin cuenta no se escribe nada.
 * Montos inventados, en una moneda de prueba de 0 decimales.
 */
class ExpenseMoneyTest {
    private val world = ExpenseWorld()
    private val others: Long get() = world.seeded.getValue("OTHER")

    private fun addAccount(
        id: Long,
        balance: Long = 0L,
        archived: Boolean = false,
        currency: AccountCurrency = AccountCurrency(code("XTS"), 0),
    ) = world.database.seed { tables ->
        val account = MoneyAccount(
            id = id,
            systemKey = null,
            details = MoneyAccountDetails("Account $id", "account $id", MoneyAccountKind.BANK),
            currency = currency,
            balance = Money(balance),
            archivedAt = if (archived) ExpenseWorld.NOW else null,
            createdAt = ExpenseWorld.NOW,
            updatedAt = ExpenseWorld.NOW,
        )
        tables.copy(
            moneyAccounts = tables.moneyAccounts + (id to account),
            lastIds = tables.lastIds + ("money_accounts" to id),
        )
    }

    private fun request(amount: Long, accountId: Long?, date: BusinessDate? = null) =
        ExpenseRequest(Money(amount), others, "Invented rent", date, moneyAccountId = accountId)

    private fun balanceOf(id: Long): Money =
        world.database.read().moneyAccounts.getValue(id).balance

    @Test
    fun anExpenseFromAnAccountTakesTheMoneyOutOfIt() = runTest {
        addAccount(BANK, balance = 50_000)

        // El ejemplo del libro: 3000 de gasto dejan 47000 desde 50000.
        val result = world.register()(request(3_000, BANK))

        val id = (result as RegisterExpenseResult.Registered).expenseId
        val movement = world.database.read().moneyMovements.values.single()
        assertEquals(MoneyMovementType.EXPENSE_PAYMENT, movement.type)
        assertEquals(Money(-3_000), movement.amount)
        assertEquals(MoneyOrigin.Expense(id), movement.draft.origin)
        assertEquals(Money(47_000), movement.balanceAfter)
        assertEquals(Money(47_000), balanceOf(BANK))
        assertEquals(BANK, world.database.read().expenses.getValue(id).content.moneyAccountId)
    }

    @Test
    fun aBackdatedExpenseDatesItsMovementOnItsDay() = runTest {
        addAccount(BANK)
        val day = BusinessDate(LocalDate(2026, 3, 10))

        world.register()(request(3_000, BANK, day))

        assertEquals(day, world.database.read().moneyMovements.values.single().time.businessDate)
    }

    @Test
    fun anExpenseWithoutAccountWritesNoMovement() = runTest {
        addAccount(BANK)

        val result = world.register()(request(3_000, null))

        assertTrue(result is RegisterExpenseResult.Registered)
        assertTrue(world.database.read().moneyMovements.isEmpty())
        val expense = world.database.read().expenses.values.single()
        assertNull(expense.content.moneyAccountId)
    }

    @Test
    fun anUnusableAccountRejectsTheExpenseAndWritesNothing() = runTest {
        addAccount(ARCHIVED, archived = true)
        addAccount(FOREIGN, currency = AccountCurrency(code("XAU"), 0))
        addAccount(DECIMALS, currency = AccountCurrency(code("XTS"), 2))

        val cases = mapOf(
            MISSING to AccountIssue.NOT_FOUND,
            ARCHIVED to AccountIssue.ARCHIVED,
            FOREIGN to AccountIssue.OTHER_CURRENCY,
            DECIMALS to AccountIssue.OTHER_CURRENCY,
        )
        for ((accountId, issue) in cases) {
            assertEquals(
                RegisterExpenseResult.AccountUnavailable(issue),
                world.register()(request(3_000, accountId)),
            )
        }
        assertTrue(world.database.read().expenses.isEmpty())
        assertTrue(world.database.read().moneyMovements.isEmpty())
    }

    @Test
    fun aBalanceThatWouldNotFitRejectsTheExpense() = runTest {
        addAccount(BANK, balance = Long.MIN_VALUE + 10)

        assertEquals(
            RegisterExpenseResult.AccountOverflow,
            world.register()(request(3_000, BANK)),
        )
        assertTrue(world.database.read().expenses.isEmpty())
    }

    @Test
    fun aFailureAtEachWriteLeavesNoExpenseWithoutItsMoney() = runTest {
        addAccount(BANK, balance = 50_000)
        var failAt = 1
        while (true) {
            world.database.failAtWrite = failAt
            val failed = runCatching { world.register()(request(3_000, BANK)) }.exceptionOrNull()
                ?: break
            assertTrue("Escritura $failAt", failed is InjectedFailure)
            assertTrue(world.database.read().expenses.isEmpty())
            assertTrue(world.database.read().moneyMovements.isEmpty())
            assertEquals(Money(50_000), balanceOf(BANK))
            failAt++
        }
        assertTrue("Al menos el gasto y su movimiento", failAt > 2)
        assertEquals(Money(47_000), balanceOf(BANK))
    }

    @Test
    fun voidingTheExpenseGivesTheMoneyBackOnTheDayOfTheVoid() = runTest {
        addAccount(BANK, balance = 50_000)
        val id = (world.register()(request(3_000, BANK)) as RegisterExpenseResult.Registered)
            .expenseId
        val payment = world.database.read().moneyMovements.values.single()

        val result = world.void(LATER)(id, VoidReason.ENTRY_ERROR, null)

        assertEquals(VoidExpenseResult.Voided, result)
        val void = world.database.read().moneyMovements.values.maxBy { it.id }
        assertEquals(MoneyMovementType.MOVEMENT_VOID, void.type)
        assertEquals(MoneyOrigin.Void(payment.id), void.draft.origin)
        assertEquals(Money(3_000), void.amount)
        assertEquals(BusinessDate(LocalDate(2026, 3, 17)), void.time.businessDate)
        assertEquals(Money(50_000), balanceOf(BANK))
    }

    @Test
    fun undoingTheExpenseGivesTheMoneyBack() = runTest {
        addAccount(BANK, balance = 50_000)
        val id = (world.register()(request(3_000, BANK)) as RegisterExpenseResult.Registered)
            .expenseId

        assertEquals(VoidExpenseResult.Voided, UndoExpenseUseCase(world.void())(id))

        assertEquals(Money(50_000), balanceOf(BANK))
    }

    @Test
    fun voidingAnExpenseWithoutAccountWritesNoMoney() = runTest {
        val id = (world.register()(request(3_000, null)) as RegisterExpenseResult.Registered)
            .expenseId

        assertEquals(VoidExpenseResult.Voided, world.void()(id, VoidReason.ENTRY_ERROR, null))

        assertTrue(world.database.read().moneyMovements.isEmpty())
    }

    @Test
    fun aVoidThatWouldOverflowTheAccountVoidsNothing() = runTest {
        addAccount(BANK)
        val id = (world.register()(request(3_000, BANK)) as RegisterExpenseResult.Registered)
            .expenseId
        world.database.seed { tables ->
            val account = tables.moneyAccounts.getValue(BANK)
            tables.copy(
                moneyAccounts = tables.moneyAccounts +
                    (BANK to account.copy(balance = Money(Long.MAX_VALUE - 10))),
            )
        }

        val result = world.void()(id, VoidReason.ENTRY_ERROR, null)

        assertEquals(VoidExpenseResult.AccountOverflow, result)
        assertNull(world.database.read().expenses.getValue(id).voiding)
        assertEquals(1, world.database.read().moneyMovements.size)
    }

    @Test
    fun aFailureAtEachWriteOfTheVoidLeavesTheExpenseActive() = runTest {
        addAccount(BANK, balance = 50_000)
        val id = (world.register()(request(3_000, BANK)) as RegisterExpenseResult.Registered)
            .expenseId
        var failAt = 1
        while (true) {
            world.database.failAtWrite = failAt
            val failed = runCatching {
                world.void()(id, VoidReason.ENTRY_ERROR, null)
            }.exceptionOrNull() ?: break
            assertTrue("Escritura $failAt", failed is InjectedFailure)
            assertNull(world.database.read().expenses.getValue(id).voiding)
            assertEquals(1, world.database.read().moneyMovements.size)
            assertEquals(Money(47_000), balanceOf(BANK))
            failAt++
        }
        assertTrue("Al menos la marca y la anulación", failAt > 2)
        assertEquals(Money(50_000), balanceOf(BANK))
    }

    private fun code(value: String): CurrencyCode = requireNotNull(CurrencyCode.of(value))

    private companion object {
        const val BANK = 10L
        const val ARCHIVED = 11L
        const val FOREIGN = 12L
        const val DECIMALS = 13L
        const val MISSING = 99L

        /** Dos días después, el martes 17 de marzo a las 10:00 en la zona de prueba. */
        val LATER: Instant = Instant.parse("2026-03-17T15:00:00Z")
    }
}
