package io.github.yesidrobayo.shopledger.domain.usecase.returns

import io.github.yesidrobayo.shopledger.domain.model.DocumentStatus
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.VoidReason
import io.github.yesidrobayo.shopledger.domain.model.customer.CustomerEntrySource
import io.github.yesidrobayo.shopledger.domain.model.customer.CustomerLedgerEntryType
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyMovementType
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyOrigin
import io.github.yesidrobayo.shopledger.domain.model.payment.PaymentMethodKind
import io.github.yesidrobayo.shopledger.domain.model.returns.ReturnLineRequest
import io.github.yesidrobayo.shopledger.domain.model.returns.ReturnSettlement
import io.github.yesidrobayo.shopledger.domain.model.returns.SaleReturnRequest
import io.github.yesidrobayo.shopledger.domain.model.sale.LineDiscount
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleLineRequest
import io.github.yesidrobayo.shopledger.domain.model.sale.SalePaymentRequest
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleRequest
import io.github.yesidrobayo.shopledger.domain.model.stock.StockMovementType
import io.github.yesidrobayo.shopledger.domain.port.StorageFailure
import io.github.yesidrobayo.shopledger.domain.port.StorageFailureException
import io.github.yesidrobayo.shopledger.domain.usecase.purchase.TODAY
import io.github.yesidrobayo.shopledger.domain.usecase.purchase.atUnitCost
import io.github.yesidrobayo.shopledger.domain.usecase.purchase.buy
import io.github.yesidrobayo.shopledger.domain.usecase.purchase.cost
import io.github.yesidrobayo.shopledger.domain.usecase.purchase.costOf
import io.github.yesidrobayo.shopledger.domain.usecase.purchase.movements
import io.github.yesidrobayo.shopledger.domain.usecase.purchase.open
import io.github.yesidrobayo.shopledger.domain.usecase.sale.InjectedSaleFailure
import io.github.yesidrobayo.shopledger.domain.usecase.sale.RegisterSaleResult
import io.github.yesidrobayo.shopledger.domain.usecase.sale.SaleWorld
import io.github.yesidrobayo.shopledger.domain.usecase.sale.VoidSaleResult
import io.github.yesidrobayo.shopledger.domain.usecase.sale.method
import io.github.yesidrobayo.shopledger.domain.usecase.sale.product
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Anular una devolución de venta: la mercancía vuelve a salir, el costo sale de reproducir el
 * libro, el reembolso o el saldo a favor se revierte, y da igual el orden en que se anulen. Los
 * valores esperados están escritos a mano; montos en unidad menor de una moneda cualquiera.
 *
 * El ejemplo de la regla del costo congelado se arma con un libro real: inventario inicial de 4
 * unidades por 5000 (costo 1250), venta de 1 unidad, que congela 1250, y compra de 2 unidades a
 * 625. Queda `Q = 5000`, `V = 5000` y `C = 1000`, y devolver la unidad lleva a `V = 6250` y
 * `C = 1042`. Anular la devolución vuelve a 5000, 5000 y 1000.
 */
class VoidSaleReturnUseCaseTest {
    private val world = SaleWorld().apply {
        addMethod(method())
        addMethod(method(id = ON_ACCOUNT, kind = PaymentMethodKind.ON_ACCOUNT))
        addMoneyAccount(BANK, balance = 10_000)
        addProduct(product(1, salePrice = 2_000))
        addCustomer(CUSTOMER)
    }

    /** El ejemplo de la regla en [target]: devuelve la venta de 1 unidad. */
    private suspend fun example(target: SaleWorld = world, customerId: Long? = null): Long {
        target.open(1, 4_000, total = 5_000)
        val saleId = sellOne(target, customerId)
        target.buy(atUnitCost(1, 2_000, cost = 625))
        return saleId
    }

    private suspend fun sellOne(target: SaleWorld = world, customerId: Long? = null): Long {
        val request = SaleRequest(
            listOf(SaleLineRequest(1, QuantityMilli(1_000))),
            SaleWorld.CASH,
            customerId = customerId,
        )
        return (target.registerSale()(request) as RegisterSaleResult.Registered).saleId
    }

    private fun lineOf(saleId: Long, target: SaleWorld = world): Long =
        target.tables.lines.values.single { it.saleId == saleId }.id

    private suspend fun returned(
        saleId: Long,
        milli: Long = 1_000,
        settlement: ReturnSettlement = ReturnSettlement.Refund(BANK),
        restock: Boolean = true,
        target: SaleWorld = world,
    ): Long {
        val result = target.registerSaleReturn()(
            SaleReturnRequest(
                saleId,
                listOf(ReturnLineRequest(lineOf(saleId, target), QuantityMilli(milli), restock)),
                settlement,
            ),
        )
        return (result as RegisterSaleReturnResult.Registered).saleReturnId
    }

    private suspend fun void(
        returnId: Long,
        reason: VoidReason = VoidReason.ENTRY_ERROR,
        note: String? = null,
        target: SaleWorld = world,
    ) = target.voidSaleReturn()(returnId, reason, note)

    private fun bank(target: SaleWorld = world): Money =
        target.tables.moneyAccounts.getValue(BANK).balance

    @Test
    fun `voiding a refund takes the goods out again at the replayed cost and the money back`() =
        runTest {
            val saleId = example()
            assertEquals(cost(5_000, 1_000, 5_000), world.costOf(1))
            val returnId = returned(saleId)
            assertEquals(cost(6_000, 1_042, 6_250), world.costOf(1))
            assertEquals(Money(8_000), bank())

            assertEquals(VoidSaleReturnResult.Voided, void(returnId, note = "  wrong line  "))

            assertEquals(cost(5_000, 1_000, 5_000), world.costOf(1))
            val header = world.tables.saleReturns.getValue(returnId)
            assertEquals(DocumentStatus.VOIDED, header.status)
            assertEquals(VoidReason.ENTRY_ERROR, header.voiding?.reason)
            assertEquals("wrong line", header.voiding?.note)
            val (entry, row) = world.movements().takeLast(2)
            assertEquals(StockMovementType.SALE_RETURN, entry.movement.type)
            assertEquals(StockMovementType.SALE_RETURN_VOID, row.movement.type)
            assertEquals(QuantityMilli(-1_000), row.movement.quantity)
            assertEquals(entry.id, row.movement.reversesMovementId)
            assertEquals(entry.source, row.source)
            assertEquals(Money(1_250), row.unitCost)
            assertEquals(Money(-1_250), row.totalCost)
            assertEquals(Money(1_000), row.averageCostAfter)
            assertEquals(Money(5_000), row.stockValueAfter)
            assertEquals(TODAY, row.businessDate)
            val (refund, reversal) = world.tables.moneyMovements.values.sortedBy { it.id }
            assertEquals(MoneyMovementType.REFUND, refund.type)
            assertEquals(MoneyMovementType.MOVEMENT_VOID, reversal.type)
            assertEquals(MoneyOrigin.Void(refund.id), reversal.draft.origin)
            assertEquals(Money(2_000), reversal.amount)
            assertEquals(Money(10_000), bank())
        }

    @Test
    fun `a blank note is not kept`() = runTest {
        val returnId = returned(example())

        assertEquals(VoidSaleReturnResult.Voided, void(returnId, VoidReason.OTHER, "   "))

        val voiding = world.tables.saleReturns.getValue(returnId).voiding
        assertEquals(VoidReason.OTHER, voiding?.reason)
        assertEquals(null, voiding?.note)
    }

    @Test
    fun `voiding a credit return raises the customer balance again`() = runTest {
        val returnId =
            returned(example(customerId = CUSTOMER), settlement = CREDIT)
        assertEquals(Money(-2_000), balance())

        assertEquals(VoidSaleReturnResult.Voided, void(returnId))

        val (credit, reversal) = world.tables.customerEntries.values.sortedBy { it.id }
        assertEquals(CustomerLedgerEntryType.RETURN_CREDIT, credit.type)
        assertEquals(CustomerLedgerEntryType.ENTRY_VOID, reversal.type)
        assertEquals(CustomerEntrySource.Reverses(credit.id), reversal.source)
        assertEquals(Money(2_000), reversal.amount)
        assertEquals(Money.ZERO, balance())
        assertTrue(world.tables.moneyMovements.isEmpty())
        assertEquals(cost(5_000, 1_000, 5_000), world.costOf(1))
    }

    @Test
    fun `a credit already spent is owed again`() = runTest {
        val returnId =
            returned(example(customerId = CUSTOMER), settlement = CREDIT)
        // La unidad devuelta se compra otra vez a crédito, y el saldo a favor la paga entera.
        val request = SaleRequest(
            listOf(SaleLineRequest(1, QuantityMilli(1_000))),
            ON_ACCOUNT,
            payments = listOf(SalePaymentRequest(ON_ACCOUNT, Money(2_000))),
            customerId = CUSTOMER,
        )
        world.registerSale()(request)
        assertEquals(Money.ZERO, balance())

        void(returnId)

        assertEquals(Money(2_000), balance())
    }

    @Test
    fun `a line that did not go back to inventory leaves no stock to take out`() = runTest {
        val returnId = returned(example(), restock = false)
        val rows = world.movements().size
        assertEquals(cost(5_000, 1_000, 5_000), world.costOf(1))

        assertEquals(VoidSaleReturnResult.Voided, void(returnId))

        assertEquals(rows, world.movements().size)
        assertEquals(cost(5_000, 1_000, 5_000), world.costOf(1))
        assertEquals(Money(10_000), bank())
    }

    @Test
    fun `a refund without account moves no money`() = runTest {
        val returnId = returned(example(), settlement = ReturnSettlement.Refund(null))

        assertEquals(VoidSaleReturnResult.Voided, void(returnId))

        assertTrue(world.tables.moneyMovements.isEmpty())
        assertEquals(Money(10_000), bank())
    }

    /**
     * Una línea de 3 unidades con neto 10000, con costo congelado 3000 y reposición posterior. Dos
     * devoluciones de 1 unidad dejan 3333 y 3334 de dinero y 1000 y 1000 de costo.
     */
    private suspend fun twoReturns(target: SaleWorld): Pair<Long, List<Long>> {
        target.open(1, 10_000, total = 10_000)
        val request = SaleRequest(
            listOf(
                SaleLineRequest(
                    1,
                    QuantityMilli(3_000),
                    unitPrice = Money(3_334),
                    discount = LineDiscount.Amount(Money(2)),
                ),
            ),
            SaleWorld.CASH,
        )
        val saleId = (target.registerSale()(request) as RegisterSaleResult.Registered).saleId
        target.buy(atUnitCost(1, 3_000, cost = 1_500))
        val ids = listOf(returned(saleId, target = target), returned(saleId, target = target))
        return saleId to ids
    }

    @Test
    fun `the order in which two returns of a sale are voided does not matter`() = runTest {
        val first = SaleWorld().apply {
            addMethod(method())
            addMoneyAccount(BANK, balance = 10_000)
            addProduct(product(1))
        }
        val second = SaleWorld().apply {
            addMethod(method())
            addMoneyAccount(BANK, balance = 10_000)
            addProduct(product(1))
        }
        for ((target, order) in listOf(first to listOf(0, 1), second to listOf(1, 0))) {
            val (_, ids) = twoReturns(target)
            // 10 unidades por 10000, menos 3 al costo 1000, más 3 a 1500, más 2 devueltas a 1000.
            assertEquals(cost(12_000, 1_125, 13_500), target.costOf(1))
            assertEquals(
                listOf(Money(3_333), Money(3_334)),
                ids.map { target.tables.saleReturns.getValue(it).content.totalRefund },
            )
            assertEquals(Money(3_333), bank(target))

            assertEquals(VoidSaleReturnResult.Voided, void(ids[order[0]], target = target))
            // Con una sola devolución activa, de 1 unidad a 1000: 11000 por 12500.
            assertEquals(cost(11_000, 1_136, 12_500), target.costOf(1))

            assertEquals(VoidSaleReturnResult.Voided, void(ids[order[1]], target = target))
        }

        assertEquals(cost(10_000, 1_150, 11_500), first.costOf(1))
        assertEquals(first.costOf(1), second.costOf(1))
        assertEquals(Money(10_000), bank(first))
        assertEquals(bank(first), bank(second))
    }

    @Test
    fun `what was returned is counted again without the voided return`() = runTest {
        val (saleId, ids) = twoReturns(world)

        void(ids[0])
        val third = returned(saleId, milli = 2_000)

        // Quedan activas la de 3334 y la nueva, que se lleva el resto exacto de la línea.
        val header = world.tables.saleReturns.getValue(third).content
        assertEquals(Money(6_666), header.totalRefund)
        assertEquals(Money(2_000), header.costReturned)
        val taken = world.saleReturns.takenBySale(saleId).values.single()
        assertEquals(QuantityMilli(3_000), taken.quantity)
        assertEquals(Money(10_000), taken.net)
        assertEquals(Money(3_000), taken.cost)
    }

    @Test
    fun `without active returns the sale can be returned again and voided`() = runTest {
        val saleId = example()
        val returnId = returned(saleId)
        assertEquals(
            VoidSaleResult.HasActiveReturns,
            world.voidSale()(saleId, VoidReason.ENTRY_ERROR, null),
        )

        void(returnId)
        assertTrue(world.saleReturns.takenBySale(saleId).isEmpty())
        void(returned(saleId))

        assertEquals(VoidSaleResult.Voided, world.voidSale()(saleId, VoidReason.ENTRY_ERROR, null))
        // Sin la venta ni sus devoluciones queda el inventario inicial y la compra: 6 por 6250.
        assertEquals(cost(6_000, 1_042, 6_250), world.costOf(1))
    }

    @Test
    fun `an exchange is undone by voiding its new sale first`() = runTest {
        val target = SaleWorld().apply {
            addMoneyAccount(BANK, balance = 10_000)
            // El efectivo entra en la misma cuenta de la que sale el crédito del cambio.
            addMethod(method(moneyAccountId = BANK))
            addProduct(product(SOAP, salePrice = 2_000))
            addProduct(product(RICE, salePrice = 3_000))
        }
        target.open(SOAP, 5_000, total = 5_000)
        target.open(RICE, 5_000, total = 7_500)
        val request =
            SaleRequest(listOf(SaleLineRequest(SOAP, QuantityMilli(2_000))), SaleWorld.CASH)
        val saleId = (target.registerSale()(request) as RegisterSaleResult.Registered).saleId
        val exchanged = target.exchangeSale()(
            ExchangeRequest(
                saleId = saleId,
                lines = listOf(ReturnLineRequest(lineOf(saleId, target), QuantityMilli(1_000))),
                replacement = SaleRequest(
                    listOf(SaleLineRequest(RICE, QuantityMilli(1_000))),
                    SaleWorld.CASH,
                ),
                refundAccountId = BANK,
            ),
        ) as ExchangeResult.Exchanged
        // 10000 + 4000 de la venta + 3000 de la nueva - 2000 del crédito.
        assertEquals(Money(15_000), bank(target))
        val before = target.tables

        assertEquals(
            VoidSaleReturnResult.PartOfExchange(exchanged.replacementSaleId),
            void(exchanged.saleReturnId, target = target),
        )
        assertEquals(before, target.tables)

        assertEquals(
            VoidSaleResult.Voided,
            target.voidSale()(exchanged.replacementSaleId, VoidReason.ENTRY_ERROR, null),
        )
        assertEquals(Money(12_000), bank(target))
        assertEquals(VoidSaleReturnResult.Voided, void(exchanged.saleReturnId, target = target))

        assertEquals(Money(14_000), bank(target))
        assertEquals(cost(3_000, 1_000, 3_000), target.costOf(SOAP))
        assertEquals(cost(5_000, 1_500, 7_500), target.costOf(RICE))
        assertEquals(
            VoidSaleResult.Voided,
            target.voidSale()(saleId, VoidReason.ENTRY_ERROR, null),
        )
        assertEquals(Money(10_000), bank(target))
    }

    @Test
    fun `an exchange whose new sale is missing is a damaged database`() = runTest {
        val returnId = returned(example())
        world.arrange { tables ->
            val header = tables.saleReturns.getValue(returnId)
            val broken = header.copy(content = header.content.copy(replacementSaleId = 99))
            tables.copy(saleReturns = tables.saleReturns + (returnId to broken))
        }

        assertEquals(
            VoidSaleReturnResult.StorageFailed(StorageFailure.INVARIANT_VIOLATION),
            void(returnId),
        )
    }

    @Test
    fun `what cannot be voided is refused without writing`() = runTest {
        val returnId = returned(example())
        val before = world.tables

        assertEquals(VoidSaleReturnResult.NotFound, void(99))
        assertEquals(
            VoidSaleReturnResult.ReasonNotAllowed,
            void(returnId, VoidReason.CUSTOMER_RETURN),
        )
        assertEquals(
            VoidSaleReturnResult.ReasonNotAllowed,
            void(returnId, VoidReason.SUPPLIER_RETURN),
        )
        assertEquals(VoidSaleReturnResult.NoteTooLong, void(returnId, note = "n".repeat(201)))
        assertEquals(before, world.tables)

        assertEquals(VoidSaleReturnResult.Voided, void(returnId, VoidReason.UNDO))
        val voided = world.tables
        assertEquals(VoidSaleReturnResult.AlreadyVoided, void(returnId))
        assertEquals(voided, world.tables)
    }

    @Test
    fun `an unknown zone rejects without writing`() = runTest {
        val lost = SaleWorld(zoneId = "Invented/Zone")

        assertEquals(
            VoidSaleReturnResult.UnknownTimeZone("Invented/Zone"),
            void(1, target = lost),
        )
    }

    @Test
    fun `a balance that would not fit leaves the return active`() = runTest {
        val refund = returned(example())
        val credit =
            returned(sellOne(customerId = CUSTOMER), settlement = CREDIT)
        world.arrange { tables ->
            val account = tables.moneyAccounts.getValue(BANK)
            val last = tables.customerEntries.values.maxBy { it.id }
            tables.copy(
                moneyAccounts = tables.moneyAccounts +
                    (BANK to account.copy(balance = Money(Long.MAX_VALUE - 10))),
                customerEntries = tables.customerEntries +
                    (last.id to last.copy(balanceAfter = Money(Long.MAX_VALUE - 10))),
            )
        }
        val before = world.tables

        assertEquals(VoidSaleReturnResult.LedgerDamaged, void(refund))
        assertEquals(VoidSaleReturnResult.LedgerDamaged, void(credit))
        assertEquals(before, world.tables)
    }

    @Test
    fun `a book that cannot be replayed leaves the return active`() = runTest {
        val returnId = returned(example())
        world.arrange { tables ->
            // Un inventario inicial que resta: contradice su tipo, como una copia dañada.
            val opening = tables.movements.values.minBy { it.id }
            val bad = opening.copy(movement = opening.movement.copy(quantity = QuantityMilli(-1)))
            tables.copy(movements = tables.movements + (opening.id to bad))
        }
        val before = world.tables

        assertEquals(VoidSaleReturnResult.LedgerDamaged, void(returnId))
        assertEquals(before, world.tables)
    }

    @Test
    fun `a storage failure comes back as a result`() = runTest {
        val returnId = returned(example())
        world.failure = { StorageFailureException(StorageFailure.IO_ERROR) }
        world.failAtWrite = 1

        assertEquals(VoidSaleReturnResult.StorageFailed(StorageFailure.IO_ERROR), void(returnId))
    }

    @Test
    fun `a failure at each write of the void leaves the return active`() = runTest {
        for (settlement in listOf(ReturnSettlement.Refund(BANK), CREDIT)) {
            val target = SaleWorld().apply {
                addMethod(method())
                addMoneyAccount(BANK, balance = 10_000)
                addProduct(product(1, salePrice = 2_000))
                addCustomer(CUSTOMER)
            }
            val returnId = returned(
                example(target, customerId = CUSTOMER),
                settlement = settlement,
                target = target,
            )
            val before = target.tables
            var failAt = 1
            while (true) {
                target.failAtWrite = failAt
                val failed = try {
                    void(returnId, target = target)
                    false
                } catch (_: InjectedSaleFailure) {
                    true
                }
                if (!failed) break
                assertEquals("Escritura $failAt", before, target.tables)
                failAt++
            }
            target.failAtWrite = null
            // La marca, la fila de stock y su producto, y el dinero o el saldo con su cabecera.
            assertTrue("Escrituras: $failAt", failAt > 5)
            val status = target.tables.saleReturns.getValue(returnId).status
            assertEquals(DocumentStatus.VOIDED, status)
        }
    }

    @Test
    fun `the void evaluates the low stock alert in the same transaction`() = runTest {
        world.withAlerts = true
        world.addProduct(product(9, salePrice = 100, minStockMilli = 5_000))
        world.open(9, 6_000, total = 600)
        val request =
            SaleRequest(listOf(SaleLineRequest(9, QuantityMilli(2_000))), SaleWorld.CASH)
        val saleId = (world.registerSale()(request) as RegisterSaleResult.Registered).saleId
        assertEquals(
            listOf(9L),
            world.tables.alerts.values.filter { it.isActive }.map {
                it.productId
            },
        )
        val lineId = lineOf(saleId)
        val returnId = (
            world.registerSaleReturn()(
                SaleReturnRequest(
                    saleId,
                    listOf(ReturnLineRequest(lineId, QuantityMilli(2_000))),
                    ReturnSettlement.Refund(null),
                ),
            ) as RegisterSaleReturnResult.Registered
            ).saleReturnId
        assertTrue(world.tables.alerts.values.none { it.isActive })

        void(returnId)

        assertEquals(
            listOf(9L),
            world.tables.alerts.values.filter { it.isActive }.map {
                it.productId
            },
        )
    }

    private fun balance(): Money = world.tables.customers.getValue(CUSTOMER).balance

    private companion object {
        const val BANK = 10L
        const val CUSTOMER = 5L
        const val ON_ACCOUNT = 2L
        const val SOAP = 1L
        const val RICE = 2L

        // Saldo a favor para el cliente de la venta.
        val CREDIT: ReturnSettlement = ReturnSettlement.StoreCredit(null)
    }
}
