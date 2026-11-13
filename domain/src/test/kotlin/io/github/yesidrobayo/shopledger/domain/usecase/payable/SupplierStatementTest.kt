package io.github.yesidrobayo.shopledger.domain.usecase.payable

import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.payable.SupplierLedgerEntryType
import io.github.yesidrobayo.shopledger.domain.model.purchase.PurchaseRequest
import io.github.yesidrobayo.shopledger.domain.usecase.purchase.RegisterPurchaseResult
import io.github.yesidrobayo.shopledger.domain.usecase.purchase.TODAY
import io.github.yesidrobayo.shopledger.domain.usecase.purchase.atUnitCost
import io.github.yesidrobayo.shopledger.domain.usecase.sale.SaleWorld
import io.github.yesidrobayo.shopledger.domain.usecase.sale.product
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * El extracto de un proveedor: sus filas en el orden del libro, el saldo que guardó cada una y lo
 * que queda abierto, con su vencimiento. Montos inventados, en unidad menor de una moneda
 * cualquiera.
 */
class SupplierStatementTest {
    private val world = SaleWorld().apply {
        addProduct(product(1))
        addSupplier(SUPPLIER, termsDays = 30)
    }

    private val statement = ObserveSupplierStatementUseCase(
        world.supplierRepository,
        world.supplierLedger,
    )

    @Test
    fun `a supplier with no rows has no debt and nothing open`() = runTest {
        val shown = requireNotNull(statement(SUPPLIER).first())

        assertEquals(SUPPLIER, shown.supplier.id)
        assertEquals(emptyList<Any>(), shown.entries)
        assertEquals(Money.ZERO, shown.debt?.owed)
    }

    @Test
    fun `a purchase on credit and a payment show in ledger order with the debt left`() = runTest {
        // 50000 con 20000 pagados deja 30000; un pago de 10000 deja 20000 de ese cargo.
        val registered = world.registerPurchase()(
            PurchaseRequest(
                lines = listOf(atUnitCost(1, 1_000, cost = 50_000)),
                supplierId = SUPPLIER,
                amountPaid = Money(20_000),
            ),
        ) as RegisterPurchaseResult.Registered
        PaySupplierUseCase(
            world.transactions,
            world.settings,
            world.supplierRepository,
            world.supplierLedger,
            world.clock,
        )(SupplierPaymentRequest(SUPPLIER, Money(10_000)))

        val shown = requireNotNull(statement(SUPPLIER).first())

        assertEquals(
            listOf(SupplierLedgerEntryType.CREDIT_PURCHASE, SupplierLedgerEntryType.PAYMENT),
            shown.entries.map { it.content.type },
        )
        assertEquals(
            listOf(30_000L, 20_000L).map(::Money),
            shown.entries.map {
                it.content.balanceAfter
            },
        )
        assertEquals(Money(20_000), shown.supplier.balance)
        val open = requireNotNull(shown.debt).openCharges.single()
        assertEquals(registered.purchaseId, open.purchaseId)
        assertEquals(Money(20_000), open.remaining)
        // Plazo de 30 días desde la fecha de la compra.
        assertEquals(TODAY.plusDays(30), open.dueDate)
    }

    @Test
    fun `what is left of each purchase follows the payments, oldest first`() = runTest {
        // Dos compras de 10000 sin pagar nada; un pago de 12000 salda la primera y deja 8000.
        val register = world.registerPurchase()
        val ids = List(2) {
            (
                register(
                    PurchaseRequest(
                        lines = listOf(atUnitCost(1, 1_000, cost = 10_000)),
                        supplierId = SUPPLIER,
                        amountPaid = Money.ZERO,
                    ),
                ) as RegisterPurchaseResult.Registered
                ).purchaseId
        }
        PaySupplierUseCase(
            world.transactions,
            world.settings,
            world.supplierRepository,
            world.supplierLedger,
            world.clock,
        )(SupplierPaymentRequest(SUPPLIER, Money(12_000)))
        val balance = ObservePurchaseBalanceUseCase(world.supplierLedger)

        assertEquals(Money.ZERO, balance(SUPPLIER, ids[0]).first())
        assertEquals(Money(8_000), balance(SUPPLIER, ids[1]).first())
        assertEquals(Money.ZERO, balance(SUPPLIER, MISSING).first())
    }

    @Test
    fun `a supplier that does not exist reads as null`() = runTest {
        assertNull(statement(MISSING).first())
    }

    private companion object {
        const val SUPPLIER = 5L
        const val MISSING = 404L
    }
}
