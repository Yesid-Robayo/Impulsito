package io.github.yesidrobayo.shopledger.domain.usecase.purchase

import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.DocumentStatus
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.VoidReason
import io.github.yesidrobayo.shopledger.domain.model.money.AccountCurrency
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyMovementType
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyOrigin
import io.github.yesidrobayo.shopledger.domain.model.purchase.PurchaseRequest
import io.github.yesidrobayo.shopledger.domain.model.settings.CurrencyCode
import io.github.yesidrobayo.shopledger.domain.testing.FixedClock
import io.github.yesidrobayo.shopledger.domain.usecase.money.AccountIssue
import io.github.yesidrobayo.shopledger.domain.usecase.sale.InjectedSaleFailure
import io.github.yesidrobayo.shopledger.domain.usecase.sale.SaleWorld
import io.github.yesidrobayo.shopledger.domain.usecase.sale.product
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Lo pagado al confirmar una compra sale de la cuenta elegida como `PURCHASE_PAYMENT`, en la misma
 * transacción, y anular la compra lo devuelve con un `MOVEMENT_VOID`. Los valores son el ejemplo de
 * la compra a plazo de las reglas, escrito a mano: 50000 con 20000 pagados.
 */
class PurchaseMoneyTest {
    private val world = SaleWorld().apply {
        addProduct(product(1))
        addSupplier(SUPPLIER, termsDays = 30)
        addMoneyAccount(BANK, balance = 100_000)
    }

    private suspend fun register(
        paid: Long?,
        accountId: Long?,
        date: BusinessDate? = null,
    ): RegisterPurchaseResult = world.registerPurchase()(
        PurchaseRequest(
            lines = listOf(atUnitCost(1, 1_000, cost = 50_000)),
            supplierId = SUPPLIER,
            date = date,
            amountPaid = paid?.let(::Money),
            moneyAccountId = accountId,
        ),
    )

    private suspend fun registered(paid: Long?, accountId: Long? = BANK): Long =
        (register(paid, accountId) as RegisterPurchaseResult.Registered).purchaseId

    private fun movements() = world.tables.moneyMovements.values.sortedBy { it.id }

    private fun balanceOf(id: Long): Money = world.tables.moneyAccounts.getValue(id).balance

    @Test
    fun whatIsPaidAtConfirmingLeavesTheChosenAccount() = runTest {
        val purchaseId = registered(paid = 20_000)

        val payment = movements().single()
        assertEquals(MoneyMovementType.PURCHASE_PAYMENT, payment.type)
        assertEquals(Money(-20_000), payment.amount)
        assertEquals(MoneyOrigin.Purchase(purchaseId), payment.draft.origin)
        assertEquals(TODAY, payment.time.businessDate)
        assertEquals(Money(80_000), balanceOf(BANK))
        assertEquals(BANK, world.tables.purchases.getValue(purchaseId).content.moneyAccountId)
        // La deuda con el proveedor sigue igual: 30000 en su libro.
        assertEquals(Money(30_000), world.tables.suppliers.getValue(SUPPLIER).balance)
    }

    @Test
    fun aPurchasePaidInFullTakesTheWholeTotal() = runTest {
        registered(paid = null)

        assertEquals(listOf(-50_000L), movements().map { it.amount.minor })
        assertEquals(Money(50_000), balanceOf(BANK))
    }

    @Test
    fun aPurchaseWithNothingPaidNeitherMovesNorKeepsTheAccount() = runTest {
        val purchaseId = registered(paid = 0)

        assertTrue(movements().isEmpty())
        assertNull(world.tables.purchases.getValue(purchaseId).content.moneyAccountId)
    }

    @Test
    fun aPurchaseWithoutAccountWritesNoMovement() = runTest {
        val purchaseId = registered(paid = 20_000, accountId = null)

        assertTrue(movements().isEmpty())
        assertNull(world.tables.purchases.getValue(purchaseId).content.moneyAccountId)
        assertEquals(Money(100_000), balanceOf(BANK))
    }

    @Test
    fun anUnusableAccountRejectsThePurchaseAndWritesNothing() = runTest {
        world.addMoneyAccount(ARCHIVED, archived = true)
        world.addMoneyAccount(FOREIGN, currency = AccountCurrency(code("XAU"), 0))
        val cases = mapOf(
            MISSING to AccountIssue.NOT_FOUND,
            ARCHIVED to AccountIssue.ARCHIVED,
            FOREIGN to AccountIssue.OTHER_CURRENCY,
        )
        for ((accountId, issue) in cases) {
            assertEquals(
                RegisterPurchaseResult.AccountUnavailable(issue),
                register(20_000, accountId),
            )
        }
        assertTrue(world.tables.purchases.isEmpty())
        assertTrue(world.tables.supplierEntries.isEmpty())
        assertTrue(movements().isEmpty())
    }

    @Test
    fun aBalanceThatWouldNotFitRejectsThePurchase() = runTest {
        world.addMoneyAccount(FULL, balance = Long.MIN_VALUE + 10)

        assertEquals(RegisterPurchaseResult.AccountOverflow, register(20_000, FULL))
        assertTrue(world.tables.purchases.isEmpty())
        assertTrue(world.tables.movements.isEmpty())
    }

    @Test
    fun aFailureAtEachWriteLeavesNoPurchaseWithoutItsMoney() = runTest {
        var failAt = 1
        while (true) {
            world.failAtWrite = failAt
            val failed = runCatching { register(20_000, BANK) }.exceptionOrNull() ?: break
            assertTrue("Escritura $failAt", failed is InjectedSaleFailure)
            assertTrue(world.tables.purchases.isEmpty())
            assertTrue(world.tables.supplierEntries.isEmpty())
            assertTrue(movements().isEmpty())
            assertEquals(Money(100_000), balanceOf(BANK))
            failAt++
        }
        assertTrue("Al menos la compra, su cargo y su pago", failAt > 3)
        assertEquals(Money(80_000), balanceOf(BANK))
    }

    @Test
    fun voidingThePurchaseGivesThePaymentBackOnTheDayOfTheVoid() = runTest {
        val purchaseId = registered(paid = 20_000)
        val payment = movements().single()
        world.clock = FixedClock.atEpochMillis(SaleWorld.NOW_MILLIS + DAY_MILLIS)

        val result = world.voidPurchase()(purchaseId, VoidReason.ENTRY_ERROR, null)

        assertEquals(VoidPurchaseResult.Voided, result)
        val void = movements().last()
        assertEquals(MoneyMovementType.MOVEMENT_VOID, void.type)
        assertEquals(MoneyOrigin.Void(payment.id), void.draft.origin)
        assertEquals(Money(20_000), void.amount)
        assertEquals(BusinessDate(LocalDate(2026, 11, 3)), void.time.businessDate)
        assertEquals(Money(100_000), balanceOf(BANK))
        // El cargo del proveedor también se compensa: le debe 0.
        assertEquals(Money.ZERO, world.tables.suppliers.getValue(SUPPLIER).balance)
    }

    @Test
    fun voidingAPurchaseWithoutAccountWritesNoMoney() = runTest {
        val purchaseId = registered(paid = null, accountId = null)

        val result = world.voidPurchase()(purchaseId, VoidReason.ENTRY_ERROR, null)

        assertEquals(VoidPurchaseResult.Voided, result)
        assertTrue(movements().isEmpty())
    }

    @Test
    fun aVoidThatWouldOverflowTheAccountVoidsNothing() = runTest {
        val purchaseId = registered(paid = 20_000)
        world.arrange { tables ->
            val account = tables.moneyAccounts.getValue(BANK)
            tables.copy(
                moneyAccounts = tables.moneyAccounts +
                    (BANK to account.copy(balance = Money(Long.MAX_VALUE - 10))),
            )
        }

        val result = world.voidPurchase()(purchaseId, VoidReason.ENTRY_ERROR, null)

        assertEquals(VoidPurchaseResult.LedgerDamaged, result)
        assertEquals(DocumentStatus.ACTIVE, world.tables.purchases.getValue(purchaseId).status)
        assertEquals(1, movements().size)
    }

    @Test
    fun aFailureAtEachWriteOfTheVoidLeavesThePurchaseAndItsMoney() = runTest {
        val purchaseId = registered(paid = 20_000)
        val before = world.tables
        var failAt = 1
        while (true) {
            world.failAtWrite = failAt
            val failed = runCatching {
                world.voidPurchase()(purchaseId, VoidReason.ENTRY_ERROR, null)
            }.exceptionOrNull() ?: break
            assertTrue("Escritura $failAt", failed is InjectedSaleFailure)
            assertEquals(before.moneyMovements, world.tables.moneyMovements)
            assertEquals(before.moneyAccounts, world.tables.moneyAccounts)
            assertEquals(DocumentStatus.ACTIVE, world.tables.purchases.getValue(purchaseId).status)
            failAt++
        }
        assertTrue("Al menos la marca, el stock, el cargo y el pago", failAt > 4)
        assertEquals(Money(100_000), balanceOf(BANK))
    }

    private fun code(value: String): CurrencyCode = requireNotNull(CurrencyCode.of(value))

    private companion object {
        const val SUPPLIER = 7L
        const val BANK = 10L
        const val ARCHIVED = 11L
        const val FOREIGN = 12L
        const val FULL = 13L
        const val MISSING = 99L
        const val DAY_MILLIS = 86_400_000L

        /** El lunes 2 de noviembre de 2026, el día del reloj de prueba. */
        val TODAY = BusinessDate(LocalDate(2026, 11, 2))
    }
}
