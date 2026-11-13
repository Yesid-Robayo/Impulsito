package io.github.yesidrobayo.shopledger.domain.usecase.sale

import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.DocumentStatus
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.VoidReason
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductStockMode
import io.github.yesidrobayo.shopledger.domain.model.money.AccountCurrency
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyAccountKind
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyMovement
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyMovementType
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyOrigin
import io.github.yesidrobayo.shopledger.domain.model.payment.PaymentMethodKind
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleLineRequest
import io.github.yesidrobayo.shopledger.domain.model.sale.SalePaymentRequest
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleRequest
import io.github.yesidrobayo.shopledger.domain.model.settings.CurrencyCode
import io.github.yesidrobayo.shopledger.domain.testing.FixedClock
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Una venta mueve la cuenta de dinero del método con que se cobra: lo cobrado entra como
 * `SALE_RECEIPT` y la comisión sale como `FEE`, en la misma transacción que la venta. Sin cuenta no
 * se escribe nada ni se lee ninguna cuenta. Anular la venta compensa cada movimiento.
 *
 * Los montos van en una moneda de prueba de 0 decimales, y los del ejemplo de tarjeta salen de las
 * reglas de negocio, escritos a mano.
 */
class RegisterSaleMoneyTest {
    private val world = SaleWorld().apply {
        addMoneyAccount(BANK)
        addMoneyAccount(DRAWER, kind = MoneyAccountKind.CASH)
        addMethod(method())
        addMethod(
            method(
                id = CARD,
                kind = PaymentMethodKind.CARD,
                systemKey = null,
                name = "Invented card",
                feeBp = 275,
                settlementDays = 2,
                moneyAccountId = BANK,
            ),
        )
        addMethod(
            method(
                id = TRANSFER,
                kind = PaymentMethodKind.TRANSFER,
                systemKey = null,
                name = "Invented transfer",
                moneyAccountId = BANK,
            ),
        )
        addMethod(
            method(
                id = DRAWER_CASH,
                systemKey = null,
                name = "Invented drawer",
                moneyAccountId = DRAWER,
            ),
        )
        addProduct(product(PRODUCT, stockMode = ProductStockMode.UNTRACKED))
    }

    private fun sale(price: Long, vararg payments: Pair<Long, Long>, date: BusinessDate? = null) =
        SaleRequest(
            lines = listOf(SaleLineRequest(PRODUCT, QuantityMilli.ofUnits(1L), Money(price))),
            paymentMethodId = payments.first().first,
            date = date,
            payments = payments.map { (method, amount) ->
                SalePaymentRequest(method, Money(amount))
            },
        )

    private fun singleSale(price: Long, methodId: Long) = SaleRequest(
        lines = listOf(SaleLineRequest(PRODUCT, QuantityMilli.ofUnits(1L), Money(price))),
        paymentMethodId = methodId,
    )

    private suspend fun sell(request: SaleRequest): RegisterSaleResult.Registered =
        world.registerSale()(request) as RegisterSaleResult.Registered

    private fun movements(): List<MoneyMovement> =
        world.tables.moneyMovements.values.sortedBy { it.id }

    private fun balanceOf(accountId: Long): Money =
        world.tables.moneyAccounts.getValue(accountId).balance

    @Test
    fun theCardExampleWritesItsReceiptAndItsFeeInTheAccount() = runTest {
        // 5281 por cobrar: 3001 con tarjeta al 2,75 %, comisión 83, y 2280 en efectivo sin cuenta.
        val registered = sell(sale(5_281, CARD to 3_001, CASH to 2_280))

        val card = world.tables.payments.values.single { it.content.paymentMethodId == CARD }
        val (receipt, fee) = movements()
        assertEquals(MoneyMovementType.SALE_RECEIPT, receipt.type)
        assertEquals(Money(3_001), receipt.amount)
        assertEquals(MoneyMovementType.FEE, fee.type)
        assertEquals(Money(-83), fee.amount)
        for (movement in listOf(receipt, fee)) {
            assertEquals(BANK, movement.accountId)
            assertEquals(MoneyOrigin.SalePayment(card.id), movement.draft.origin)
            // La tarjeta acredita a 2 días: el lunes 2 de noviembre queda para el miércoles 4.
            assertEquals(BusinessDate(LocalDate(2026, 11, 4)), movement.draft.availableDate)
            assertEquals(BusinessDate(LocalDate(2026, 11, 2)), movement.time.businessDate)
            assertNull(movement.draft.cashSessionId)
        }
        assertEquals(Money(3_001), receipt.balanceAfter)
        // Neto recibido 2918, como en el ejemplo: el saldo es la suma del libro.
        assertEquals(Money(2_918), fee.balanceAfter)
        assertEquals(Money(2_918), balanceOf(BANK))
        assertEquals(BANK, card.content.moneyAccountId)
        assertFalse(registered.moneyAccountSkipped)
        assertEquals(1L, world.tables.changeSeq)
    }

    @Test
    fun aMethodWithoutAccountWritesNoMovementAndReadsNoAccount() = runTest {
        val registered = sell(singleSale(1_200, CASH))

        assertTrue(world.tables.moneyMovements.isEmpty())
        assertEquals(0, world.moneyAccountReads)
        assertNull(world.tables.sales.getValue(registered.saleId).content.cashSessionId)
        assertFalse(registered.moneyAccountSkipped)
        assertEquals(Money.ZERO, balanceOf(BANK))
    }

    @Test
    fun aPaymentWithoutFeeWritesOnlyItsReceipt() = runTest {
        sell(singleSale(1_200, TRANSFER))

        val receipt = movements().single()
        assertEquals(MoneyMovementType.SALE_RECEIPT, receipt.type)
        assertEquals(Money(1_200), receipt.amount)
        // Sin días de acreditación, el dinero está disponible el mismo día.
        assertEquals(BusinessDate(LocalDate(2026, 11, 2)), receipt.draft.availableDate)
        assertEquals(Money(1_200), balanceOf(BANK))
    }

    @Test
    fun aSaleOfZeroMovesNothingEvenWithAnAccount() = runTest {
        val registered = sell(singleSale(0, TRANSFER))

        assertTrue(world.tables.moneyMovements.isEmpty())
        assertFalse(registered.moneyAccountSkipped)
        assertEquals(BANK, world.tables.payments.values.single().content.moneyAccountId)
    }

    @Test
    fun twoPaymentsIntoTheSameAccountChainTheirBalances() = runTest {
        sell(sale(1_000, CARD to 600, TRANSFER to 400))

        // 600 con comisión de 17 (600 al 2,75 % son 16,5, que se redondea a 17) y 400 sin comisión.
        assertEquals(listOf(600L, -17L, 400L), movements().map { it.amount.minor })
        assertEquals(listOf(600L, 583L, 983L), movements().map { it.balanceAfter.minor })
        assertEquals(Money(983), balanceOf(BANK))
    }

    @Test
    fun aBackdatedSaleDatesItsMovementsOnItsDay() = runTest {
        val friday = BusinessDate(LocalDate(2026, 10, 30))

        sell(sale(1_000, CARD to 1_000, date = friday))

        val (receipt, fee) = movements()
        assertEquals(friday, receipt.time.businessDate)
        assertEquals(friday, fee.time.businessDate)
        assertEquals(BusinessDate(LocalDate(2026, 11, 1)), receipt.draft.availableDate)
    }

    @Test
    fun anAccountInAnotherCurrencyIsSkippedAndTheSaleSaysSo() = runTest {
        world.addMoneyAccount(FOREIGN, currency = AccountCurrency(code("XAU"), 0))
        addForeignMethod()

        val registered = sell(singleSale(1_500, FOREIGN_METHOD))

        assertTrue(registered.moneyAccountSkipped)
        assertTrue(world.tables.moneyMovements.isEmpty())
        assertEquals(Money.ZERO, balanceOf(FOREIGN))
        // El pago no dice haber entrado en una cuenta que no se movió.
        assertNull(world.tables.payments.values.single().content.moneyAccountId)
        assertEquals(1L, world.tables.changeSeq)
    }

    @Test
    fun theSameCodeWithOtherDecimalsIsAnotherCurrency() = runTest {
        world.addMoneyAccount(FOREIGN, currency = AccountCurrency(code("XTS"), 2))
        addForeignMethod()

        val registered = sell(singleSale(1_500, FOREIGN_METHOD))

        assertTrue(registered.moneyAccountSkipped)
        assertTrue(world.tables.moneyMovements.isEmpty())
    }

    @Test
    fun anArchivedAccountIsSkippedAndTheSaleSaysSo() = runTest {
        world.addMoneyAccount(FOREIGN, archived = true)
        addForeignMethod()

        val registered = sell(singleSale(1_500, FOREIGN_METHOD))

        assertTrue(registered.moneyAccountSkipped)
        assertTrue(world.tables.moneyMovements.isEmpty())
        assertEquals(Money.ZERO, balanceOf(FOREIGN))
    }

    @Test
    fun aSkippedPaymentDoesNotStopTheOtherAccountFromMoving() = runTest {
        world.addMoneyAccount(FOREIGN, archived = true)
        addForeignMethod()

        val registered = sell(sale(1_000, FOREIGN_METHOD to 300, TRANSFER to 700))

        assertTrue(registered.moneyAccountSkipped)
        assertEquals(listOf(700L), movements().map { it.amount.minor })
        assertEquals(Money(700), balanceOf(BANK))
    }

    @Test
    fun aSkippedPaymentOfZeroHasNothingToWarnAbout() = runTest {
        world.addMoneyAccount(FOREIGN, archived = true)
        addForeignMethod()

        val registered = sell(sale(1_000, FOREIGN_METHOD to 0, TRANSFER to 1_000))

        assertFalse(registered.moneyAccountSkipped)
        assertEquals(listOf(1_000L), movements().map { it.amount.minor })
    }

    @Test
    fun withACashSessionOpenTheSaleAndItsMovementsFallInIt() = runTest {
        world.openCashSession(DRAWER, SESSION)

        val registered = sell(sale(1_000, TRANSFER to 400, DRAWER_CASH to 600))

        assertEquals(SESSION, world.tables.sales.getValue(registered.saleId).content.cashSessionId)
        val (bank, drawer) = movements()
        assertNull(bank.draft.cashSessionId)
        assertEquals(SESSION, drawer.draft.cashSessionId)
        assertEquals(Money(600), balanceOf(DRAWER))
    }

    @Test
    fun aSessionOfASkippedAccountDoesNotTakeTheSale() = runTest {
        world.addMoneyAccount(FOREIGN, archived = true, kind = MoneyAccountKind.CASH)
        addForeignMethod()
        world.openCashSession(FOREIGN, SESSION)

        val registered = sell(singleSale(1_000, FOREIGN_METHOD))

        assertNull(world.tables.sales.getValue(registered.saleId).content.cashSessionId)
    }

    @Test
    fun aBalanceThatWouldNotFitRejectsTheWholeSale() = runTest {
        world.addMoneyAccount(FOREIGN, balance = Long.MAX_VALUE - 100)
        addForeignMethod()

        val result = world.registerSale()(singleSale(1_000, FOREIGN_METHOD))

        assertEquals(RegisterSaleResult.SaleTotalTooLarge, result)
        assertTrue(world.tables.sales.isEmpty())
        assertTrue(world.tables.payments.isEmpty())
        assertTrue(world.tables.moneyMovements.isEmpty())
        assertEquals(Money(Long.MAX_VALUE - 100), balanceOf(FOREIGN))
        assertEquals(0L, world.tables.changeSeq)
    }

    @Test
    fun aFailureAtEachWriteLeavesNoSaleWithoutItsMoney() = runTest {
        val request = sale(1_000, CARD to 600, CASH to 400)
        var failAt = 1
        while (true) {
            world.failAtWrite = failAt
            val failed = runCatching { world.registerSale()(request) }.exceptionOrNull() ?: break
            assertTrue("Escritura $failAt", failed is InjectedSaleFailure)
            assertTrue(world.tables.sales.isEmpty())
            assertTrue(world.tables.payments.isEmpty())
            assertTrue(world.tables.moneyMovements.isEmpty())
            assertEquals(Money.ZERO, balanceOf(BANK))
            assertEquals(0L, world.tables.changeSeq)
            failAt++
        }
        // Venta con su id, línea con su id, dos pagos con sus ids, y dos movimientos con su id y el
        // saldo de su cuenta: catorce escrituras, cada una probada, y la quince ya no falla.
        assertEquals(15, failAt)
        assertEquals(Money(583), balanceOf(BANK))
    }

    @Test
    fun voidingTheSaleCompensatesEachMovementOnTheDayOfTheVoid() = runTest {
        val saleId = sell(sale(5_281, CARD to 3_001, TRANSFER to 2_280)).saleId
        val original = movements()
        world.clock = FixedClock.atEpochMillis(SaleWorld.NOW_MILLIS + DAY_MILLIS)

        val result = world.voidSale()(saleId, VoidReason.ENTRY_ERROR, null)

        assertEquals(VoidSaleResult.Voided, result)
        val voids = movements().drop(original.size)
        assertEquals(original.size, voids.size)
        for ((movement, void) in original.zip(voids)) {
            assertEquals(MoneyMovementType.MOVEMENT_VOID, void.type)
            assertEquals(MoneyOrigin.Void(movement.id), void.draft.origin)
            assertEquals(-movement.amount, void.amount)
            assertEquals(movement.accountId, void.accountId)
            assertEquals(BusinessDate(LocalDate(2026, 11, 3)), void.time.businessDate)
        }
        assertEquals(Money.ZERO, balanceOf(BANK))
        assertEquals(DocumentStatus.VOIDED, world.tables.sales.getValue(saleId).status)
    }

    @Test
    fun undoingTheLastSaleGivesTheMoneyBack() = runTest {
        val saleId = sell(singleSale(1_000, CARD)).saleId

        assertEquals(VoidSaleResult.Voided, UndoSaleUseCase(world.voidSale())(saleId))

        assertEquals(4, world.tables.moneyMovements.size)
        assertEquals(Money.ZERO, balanceOf(BANK))
    }

    @Test
    fun voidingASaleWithoutAccountsWritesNoMoney() = runTest {
        val saleId = sell(singleSale(1_000, CASH)).saleId

        assertEquals(VoidSaleResult.Voided, world.voidSale()(saleId, VoidReason.ENTRY_ERROR, null))

        assertTrue(world.tables.moneyMovements.isEmpty())
        assertEquals(0, world.moneyAccountReads)
    }

    @Test
    fun aCompensatedMovementIsNotCompensatedTwice() = runTest {
        val saleId = sell(singleSale(1_000, TRANSFER)).saleId
        val receipt = movements().single()
        // Una anulación que ya está en el libro, como la que dejaría una copia restaurada a medias.
        world.voidSale()(saleId, VoidReason.ENTRY_ERROR, null)
        world.arrange { tables ->
            val sale = tables.sales.getValue(saleId)
            tables.copy(sales = tables.sales + (saleId to sale.copy(voiding = null)))
        }

        assertEquals(VoidSaleResult.Voided, world.voidSale()(saleId, VoidReason.ENTRY_ERROR, null))

        assertEquals(1, movements().count { it.draft.origin == MoneyOrigin.Void(receipt.id) })
        assertEquals(Money.ZERO, balanceOf(BANK))
    }

    @Test
    fun aVoidThatWouldOverflowTheAccountVoidsNothing() = runTest {
        val saleId = sell(singleSale(1_000, TRANSFER)).saleId
        world.arrange { tables ->
            val account = tables.moneyAccounts.getValue(BANK)
            tables.copy(
                moneyAccounts = tables.moneyAccounts +
                    (BANK to account.copy(balance = Money(Long.MIN_VALUE + 10))),
            )
        }

        val result = world.voidSale()(saleId, VoidReason.ENTRY_ERROR, null)

        assertEquals(VoidSaleResult.LedgerDamaged, result)
        assertEquals(DocumentStatus.ACTIVE, world.tables.sales.getValue(saleId).status)
        assertEquals(1, world.tables.moneyMovements.size)
    }

    @Test
    fun aFailureAtEachWriteOfTheVoidLeavesTheMoneyAsItWas() = runTest {
        val saleId = sell(singleSale(1_000, CARD)).saleId
        val before = world.tables
        var failAt = 1
        while (true) {
            world.failAtWrite = failAt
            val failed = runCatching {
                world.voidSale()(saleId, VoidReason.ENTRY_ERROR, null)
            }.exceptionOrNull() ?: break
            assertTrue("Escritura $failAt", failed is InjectedSaleFailure)
            assertEquals(before.moneyMovements, world.tables.moneyMovements)
            assertEquals(before.moneyAccounts, world.tables.moneyAccounts)
            assertEquals(DocumentStatus.ACTIVE, world.tables.sales.getValue(saleId).status)
            failAt++
        }
        // La marca de anulada, y dos anulaciones con su id y el saldo de su cuenta: siete.
        assertEquals(8, failAt)
        assertEquals(Money.ZERO, balanceOf(BANK))
    }

    /** Un método escrito por la persona que cobra en la cuenta [FOREIGN]. */
    private fun addForeignMethod() = world.addMethod(
        method(id = FOREIGN_METHOD, systemKey = null, name = "Other", moneyAccountId = FOREIGN),
    )

    private fun code(value: String): CurrencyCode = requireNotNull(CurrencyCode.of(value))

    private companion object {
        const val PRODUCT = 1L
        const val CASH = SaleWorld.CASH
        const val CARD = 2L
        const val TRANSFER = 3L
        const val DRAWER_CASH = 4L
        const val FOREIGN_METHOD = 5L
        const val BANK = 10L
        const val DRAWER = 11L
        const val FOREIGN = 12L
        const val SESSION = 30L
        const val DAY_MILLIS = 86_400_000L
    }
}
