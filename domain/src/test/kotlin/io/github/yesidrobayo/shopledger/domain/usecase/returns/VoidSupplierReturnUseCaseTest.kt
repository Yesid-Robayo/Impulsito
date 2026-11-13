package io.github.yesidrobayo.shopledger.domain.usecase.returns

import io.github.yesidrobayo.shopledger.domain.model.DocumentStatus
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.VoidReason
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyMovementType
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyOrigin
import io.github.yesidrobayo.shopledger.domain.model.payable.SupplierLedgerEntryType
import io.github.yesidrobayo.shopledger.domain.model.returns.PurchaseReturnResolution
import io.github.yesidrobayo.shopledger.domain.model.returns.SupplierReturnLineRequest
import io.github.yesidrobayo.shopledger.domain.model.returns.SupplierReturnRequest
import io.github.yesidrobayo.shopledger.domain.model.stock.StockMovementType
import io.github.yesidrobayo.shopledger.domain.port.StorageFailure
import io.github.yesidrobayo.shopledger.domain.port.StorageFailureException
import io.github.yesidrobayo.shopledger.domain.usecase.purchase.TODAY
import io.github.yesidrobayo.shopledger.domain.usecase.purchase.VoidPurchaseResult
import io.github.yesidrobayo.shopledger.domain.usecase.purchase.atUnitCost
import io.github.yesidrobayo.shopledger.domain.usecase.purchase.buy
import io.github.yesidrobayo.shopledger.domain.usecase.purchase.cost
import io.github.yesidrobayo.shopledger.domain.usecase.purchase.costOf
import io.github.yesidrobayo.shopledger.domain.usecase.purchase.movements
import io.github.yesidrobayo.shopledger.domain.usecase.purchase.open
import io.github.yesidrobayo.shopledger.domain.usecase.sale.InjectedSaleFailure
import io.github.yesidrobayo.shopledger.domain.usecase.sale.SaleWorld
import io.github.yesidrobayo.shopledger.domain.usecase.sale.product
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Anular una devolución a proveedor: el stock vuelve, el costo sale de reproducir el libro, el
 * reembolso o el crédito se revierte, y da igual el orden en que se anulen. Los valores son el
 * ejemplo de la regla, escrito a mano: `Q = 12000`, `V = 14400` y `C = 1200`, y 2 unidades de una
 * línea de 1400. Montos en unidad menor de una moneda cualquiera.
 */
class VoidSupplierReturnUseCaseTest {
    private val world = SaleWorld().apply {
        addProduct(product(1))
        addSupplier(SUPPLIER)
        addMoneyAccount(BANK, balance = 10_000)
    }

    /** El ejemplo de la regla en [target]: devuelve la compra y su línea. */
    private suspend fun example(target: SaleWorld = world): Pair<Long, Long> {
        target.open(1, 10_000, total = 11_600)
        val purchaseId = target.buy(atUnitCost(1, 2_000, cost = 1_400), supplierId = SUPPLIER)
        val lineId = target.tables.purchaseLines.values.single { it.purchaseId == purchaseId }.id
        return purchaseId to lineId
    }

    private suspend fun returned(
        purchaseId: Long,
        lineId: Long,
        milli: Long = 2_000,
        resolution: PurchaseReturnResolution = PurchaseReturnResolution.SUPPLIER_CREDIT,
        accountId: Long? = null,
        target: SaleWorld = world,
    ): Long = (
        target.registerSupplierReturn()(
            SupplierReturnRequest(
                lines = listOf(
                    SupplierReturnLineRequest.FromPurchaseLine(lineId, QuantityMilli(milli)),
                ),
                resolution = resolution,
                purchaseId = purchaseId,
                moneyAccountId = accountId,
            ),
        ) as RegisterSupplierReturnResult.Registered
        ).purchaseReturnId

    private suspend fun void(
        returnId: Long,
        reason: VoidReason = VoidReason.ENTRY_ERROR,
        note: String? = null,
        target: SaleWorld = world,
    ) = target.voidSupplierReturn()(returnId, reason, note)

    @Test
    fun `voiding a credit return brings the stock, the cost and the debt back`() = runTest {
        val (purchaseId, lineId) = example()
        val returnId = returned(purchaseId, lineId)
        assertEquals(cost(10_000, 1_160, 11_600), world.costOf(1))

        assertEquals(VoidSupplierReturnResult.Voided, void(returnId, note = "  wrong product  "))

        assertEquals(cost(12_000, 1_200, 14_400), world.costOf(1))
        val header = world.tables.purchaseReturns.getValue(returnId)
        assertEquals(DocumentStatus.VOIDED, header.status)
        assertEquals("wrong product", header.voiding?.note)
        val row = world.movements().last()
        assertEquals(StockMovementType.PURCHASE_RETURN_VOID, row.movement.type)
        assertEquals(QuantityMilli(2_000), row.movement.quantity)
        assertEquals(Money(2_800), row.totalCost)
        assertEquals(Money(1_400), row.unitCost)
        assertEquals(TODAY, row.businessDate)
        val entries = world.tables.supplierEntries.values.sortedBy { it.id }.map { it.content }
        assertEquals(
            listOf(SupplierLedgerEntryType.RETURN_CREDIT, SupplierLedgerEntryType.ENTRY_VOID),
            entries.map { it.type },
        )
        assertEquals(Money(2_800), entries.last().amount)
        assertEquals(Money.ZERO, world.tables.suppliers.getValue(SUPPLIER).balance)
    }

    @Test
    fun `voiding a refund takes the money back out of its account`() = runTest {
        val (purchaseId, lineId) = example()
        val returnId = returned(
            purchaseId,
            lineId,
            resolution = PurchaseReturnResolution.CASH_REFUND,
            accountId = BANK,
        )
        assertEquals(Money(12_800), world.tables.moneyAccounts.getValue(BANK).balance)

        assertEquals(VoidSupplierReturnResult.Voided, void(returnId))

        val (refund, reversal) = world.tables.moneyMovements.values.sortedBy { it.id }
        assertEquals(MoneyMovementType.MOVEMENT_VOID, reversal.type)
        assertEquals(MoneyOrigin.Void(refund.id), reversal.draft.origin)
        assertEquals(Money(10_000), world.tables.moneyAccounts.getValue(BANK).balance)
        assertTrue(world.tables.supplierEntries.isEmpty())
    }

    @Test
    fun `the order in which two returns are voided does not matter`() = runTest {
        val first = SaleWorld().apply {
            addProduct(product(1))
            addSupplier(SUPPLIER)
        }
        val second = SaleWorld().apply {
            addProduct(product(1))
            addSupplier(SUPPLIER)
        }
        for ((target, order) in listOf(first to listOf(0, 1), second to listOf(1, 0))) {
            val (purchaseId, lineId) = example(target)
            val ids = listOf(
                returned(purchaseId, lineId, milli = 1_000, target = target),
                returned(purchaseId, lineId, milli = 1_000, target = target),
            )
            for (index in order) {
                assertEquals(VoidSupplierReturnResult.Voided, void(ids[index], target = target))
            }
        }

        assertEquals(cost(12_000, 1_200, 14_400), first.costOf(1))
        assertEquals(first.costOf(1), second.costOf(1))
        assertEquals(
            first.tables.suppliers.getValue(SUPPLIER).balance,
            second.tables.suppliers.getValue(SUPPLIER).balance,
        )
    }

    @Test
    fun `with the return voided, its quantity can leave again and the purchase can be voided`() =
        runTest {
            val (purchaseId, lineId) = example()
            val returnId = returned(purchaseId, lineId)
            assertEquals(
                VoidPurchaseResult.HasActiveReturns,
                world.voidPurchase()(purchaseId, VoidReason.ENTRY_ERROR, null),
            )

            void(returnId)

            assertTrue(
                world.registerSupplierReturn()(
                    SupplierReturnRequest(
                        listOf(
                            SupplierReturnLineRequest.FromPurchaseLine(
                                lineId,
                                QuantityMilli(2_000),
                            ),
                        ),
                        PurchaseReturnResolution.CASH_REFUND,
                        purchaseId = purchaseId,
                    ),
                ) is RegisterSupplierReturnResult.Registered,
            )
            void(world.tables.purchaseReturns.keys.max())
            assertEquals(
                VoidPurchaseResult.Voided,
                world.voidPurchase()(purchaseId, VoidReason.ENTRY_ERROR, null),
            )
            // Sin la compra ni las devoluciones queda el inventario inicial: 10 unidades por 11600.
            assertEquals(cost(10_000, 1_160, 11_600), world.costOf(1))
        }

    @Test
    fun `what cannot be voided is refused without writing`() = runTest {
        val (purchaseId, lineId) = example()
        val returnId = returned(purchaseId, lineId)
        val before = world.tables

        assertEquals(VoidSupplierReturnResult.NotFound, void(99))
        assertEquals(
            VoidSupplierReturnResult.ReasonNotAllowed,
            void(returnId, VoidReason.CUSTOMER_RETURN),
        )
        assertEquals(
            VoidSupplierReturnResult.ReasonNotAllowed,
            void(returnId, VoidReason.SUPPLIER_RETURN),
        )
        assertEquals(VoidSupplierReturnResult.NoteTooLong, void(returnId, note = "n".repeat(201)))
        assertEquals(before, world.tables)

        void(returnId)
        assertEquals(VoidSupplierReturnResult.AlreadyVoided, void(returnId))
    }

    @Test
    fun `an unknown zone rejects without writing`() = runTest {
        val lost = SaleWorld(zoneId = "Invented/Zone")

        assertEquals(
            VoidSupplierReturnResult.UnknownTimeZone("Invented/Zone"),
            void(1, target = lost),
        )
    }

    @Test
    fun `a balance that would not fit leaves the return active`() = runTest {
        val (purchaseId, lineId) = example()
        val credit = returned(purchaseId, lineId, milli = 1_000)
        val refund = returned(
            purchaseId,
            lineId,
            milli = 1_000,
            resolution = PurchaseReturnResolution.CASH_REFUND,
            accountId = BANK,
        )
        world.arrange { tables ->
            val account = tables.moneyAccounts.getValue(BANK)
            val last = tables.supplierEntries.values.maxBy { it.id }
            val huge = last.copy(
                content = last.content.copy(balanceAfter = Money(Long.MAX_VALUE - 10)),
            )
            tables.copy(
                moneyAccounts = tables.moneyAccounts +
                    (BANK to account.copy(balance = Money(Long.MIN_VALUE + 10))),
                supplierEntries = tables.supplierEntries + (last.id to huge),
            )
        }
        val before = world.tables

        assertEquals(VoidSupplierReturnResult.LedgerDamaged, void(credit))
        assertEquals(VoidSupplierReturnResult.LedgerDamaged, void(refund))
        assertEquals(before, world.tables)
    }

    @Test
    fun `a storage failure comes back as a result`() = runTest {
        val (purchaseId, lineId) = example()
        val returnId = returned(purchaseId, lineId)
        world.failure = { StorageFailureException(StorageFailure.IO_ERROR) }
        world.failAtWrite = 1

        assertEquals(
            VoidSupplierReturnResult.StorageFailed(StorageFailure.IO_ERROR),
            void(returnId),
        )
    }

    @Test
    fun `a failure at each write of the void leaves the return active`() = runTest {
        val (purchaseId, lineId) = example()
        val returnId = returned(
            purchaseId,
            lineId,
            resolution = PurchaseReturnResolution.CASH_REFUND,
            accountId = BANK,
        )
        val before = world.tables
        var failAt = 1
        while (true) {
            world.failAtWrite = failAt
            val failed = try {
                void(returnId)
                false
            } catch (_: InjectedSaleFailure) {
                true
            }
            if (!failed) break
            assertEquals("Escritura $failAt", before, world.tables)
            failAt++
        }
        world.failAtWrite = null
        assertTrue("La marca, el libro de stock y su saldo, y el dinero", failAt > 5)
        assertEquals(DocumentStatus.VOIDED, world.tables.purchaseReturns.getValue(returnId).status)
    }

    @Test
    fun `the void evaluates the low stock alert in the same transaction`() = runTest {
        world.withAlerts = true
        world.addProduct(product(9, minStockMilli = 5_000, onHandMilli = 0))
        val purchaseId = world.buy(atUnitCost(9, 6_000, cost = 10), supplierId = SUPPLIER)
        val lineId = world.tables.purchaseLines.values.single { it.purchaseId == purchaseId }.id
        val returnId = returned(purchaseId, lineId, milli = 2_000)
        assertEquals(
            listOf(9L),
            world.tables.alerts.values.filter {
                it.isActive
            }.map { it.productId },
        )

        void(returnId)

        assertTrue(world.tables.alerts.values.none { it.isActive })
    }

    private companion object {
        const val SUPPLIER = 7L
        const val BANK = 10L
    }
}
