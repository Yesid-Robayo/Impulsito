package io.github.yesidrobayo.shopledger.domain.usecase.returns

import io.github.yesidrobayo.shopledger.domain.model.DocumentStatus
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.VoidReason
import io.github.yesidrobayo.shopledger.domain.model.Voiding
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductStockMode
import io.github.yesidrobayo.shopledger.domain.model.money.AccountCurrency
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyMovementType
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyOrigin
import io.github.yesidrobayo.shopledger.domain.model.payable.NewSupplierLedgerEntry
import io.github.yesidrobayo.shopledger.domain.model.payable.SupplierLedgerEntry
import io.github.yesidrobayo.shopledger.domain.model.payable.SupplierLedgerEntryType
import io.github.yesidrobayo.shopledger.domain.model.purchase.PurchaseRequest
import io.github.yesidrobayo.shopledger.domain.model.returns.PurchaseReturnResolution
import io.github.yesidrobayo.shopledger.domain.model.returns.PurchaseReturnResolution.CASH_REFUND
import io.github.yesidrobayo.shopledger.domain.model.returns.PurchaseReturnResolution.SUPPLIER_CREDIT
import io.github.yesidrobayo.shopledger.domain.model.returns.SupplierReturnLineRejection
import io.github.yesidrobayo.shopledger.domain.model.returns.SupplierReturnLineRequest
import io.github.yesidrobayo.shopledger.domain.model.returns.SupplierReturnRequest
import io.github.yesidrobayo.shopledger.domain.model.settings.CurrencyCode
import io.github.yesidrobayo.shopledger.domain.model.stock.StockMovementType
import io.github.yesidrobayo.shopledger.domain.port.StorageFailure
import io.github.yesidrobayo.shopledger.domain.port.StorageFailureException
import io.github.yesidrobayo.shopledger.domain.usecase.money.AccountIssue
import io.github.yesidrobayo.shopledger.domain.usecase.purchase.RegisterPurchaseResult
import io.github.yesidrobayo.shopledger.domain.usecase.purchase.TODAY
import io.github.yesidrobayo.shopledger.domain.usecase.purchase.VoidPurchaseResult
import io.github.yesidrobayo.shopledger.domain.usecase.purchase.atUnitCost
import io.github.yesidrobayo.shopledger.domain.usecase.purchase.buy
import io.github.yesidrobayo.shopledger.domain.usecase.purchase.cost
import io.github.yesidrobayo.shopledger.domain.usecase.purchase.costOf
import io.github.yesidrobayo.shopledger.domain.usecase.purchase.forTotal
import io.github.yesidrobayo.shopledger.domain.usecase.purchase.movements
import io.github.yesidrobayo.shopledger.domain.usecase.purchase.open
import io.github.yesidrobayo.shopledger.domain.usecase.sale.InjectedSaleFailure
import io.github.yesidrobayo.shopledger.domain.usecase.sale.SaleWorld
import io.github.yesidrobayo.shopledger.domain.usecase.sale.product
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Devolver mercancía a un proveedor: sale del stock al costo de su línea de compra, el costo
 * promedio se recalcula con lo que queda, y el reembolso entra a la cuenta elegida o el crédito baja
 * lo que se le debe al proveedor, todo en una transacción. Los valores son el ejemplo de la regla de
 * la devolución a proveedor, escrito a mano: `Q = 12000`, `V = 14400` y `C = 1200`, y 2 unidades de
 * una línea de 1400. Montos en unidad menor de una moneda cualquiera.
 */
class RegisterSupplierReturnUseCaseTest {
    private val world = SaleWorld().apply {
        addProduct(product(1))
        addSupplier(SUPPLIER)
        addSupplier(OTHER)
        addMoneyAccount(BANK, balance = 10_000)
    }

    /**
     * Deja el producto 1 con el ejemplo de la regla: 10 unidades de inventario inicial por 11600 y una
     * compra de 2 a 1400 del proveedor. Devuelve la compra.
     */
    private suspend fun example(supplierId: Long? = SUPPLIER): Long {
        world.open(1, 10_000, total = 11_600)
        val purchaseId = world.buy(atUnitCost(1, 2_000, cost = 1_400), supplierId = supplierId)
        assertEquals(cost(12_000, 1_200, 14_400), world.costOf(1))
        return purchaseId
    }

    private fun lineOf(purchaseId: Long, index: Int = 0): Long =
        world.tables.purchaseLines.values.filter { it.purchaseId == purchaseId }
            .sortedBy { it.id }[index].id

    private fun fromLine(lineId: Long, milli: Long) =
        SupplierReturnLineRequest.FromPurchaseLine(lineId, QuantityMilli(milli))

    private fun loose(productId: Long, milli: Long) =
        SupplierReturnLineRequest.Loose(productId, QuantityMilli(milli))

    private suspend fun register(
        vararg lines: SupplierReturnLineRequest,
        resolution: PurchaseReturnResolution = SUPPLIER_CREDIT,
        purchaseId: Long? = null,
        supplierId: Long? = null,
        accountId: Long? = null,
        note: String? = null,
    ): RegisterSupplierReturnResult = world.registerSupplierReturn()(
        SupplierReturnRequest(
            lines = lines.toList(),
            resolution = resolution,
            purchaseId = purchaseId,
            supplierId = supplierId,
            moneyAccountId = accountId,
            note = note,
        ),
    )

    private suspend fun registered(
        vararg lines: SupplierReturnLineRequest,
        resolution: PurchaseReturnResolution = SUPPLIER_CREDIT,
        purchaseId: Long? = null,
        supplierId: Long? = null,
        accountId: Long? = null,
    ): Long = (
        register(
            *lines,
            resolution = resolution,
            purchaseId = purchaseId,
            supplierId = supplierId,
            accountId = accountId,
        ) as RegisterSupplierReturnResult.Registered
        ).purchaseReturnId

    private fun supplierEntries() = world.tables.supplierEntries.values.sortedBy { it.id }

    private fun balanceOf(supplierId: Long): Money =
        world.tables.suppliers.getValue(supplierId).balance

    @Test
    fun `two units of a line of 1400 leave V at 11600 and the average at 1160`() = runTest {
        val purchaseId = example()

        val result = register(fromLine(lineOf(purchaseId), 2_000), purchaseId = purchaseId)

        val returnId = (result as RegisterSupplierReturnResult.Registered).purchaseReturnId
        assertEquals(Money(2_800), result.total)
        assertEquals(cost(10_000, 1_160, 11_600), world.costOf(1))
        val header = world.tables.purchaseReturns.getValue(returnId)
        assertEquals(purchaseId, header.content.purchaseId)
        assertEquals(SUPPLIER, header.content.supplierId)
        assertEquals(SUPPLIER_CREDIT, header.content.resolution)
        assertEquals(Money(2_800), header.content.total)
        assertEquals(DocumentStatus.ACTIVE, header.status)
        val line = world.tables.purchaseReturnLines.values.single()
        assertEquals(lineOf(purchaseId), line.content.purchaseLineId)
        assertEquals(QuantityMilli(2_000), line.content.quantity)
        assertEquals(Money(1_400), line.content.unitCost)
        assertEquals(Money(2_800), line.content.lineTotal)
        val row = world.movements().last()
        assertEquals(StockMovementType.PURCHASE_RETURN, row.movement.type)
        assertEquals(QuantityMilli(-2_000), row.movement.quantity)
        assertEquals(Money(1_400), row.unitCost)
        assertEquals(Money(-2_800), row.totalCost)
        assertEquals(line.id, row.source.purchaseReturnLineId)
        assertEquals(Money(1_160), row.averageCostAfter)
        assertEquals(Money(11_600), row.stockValueAfter)
        // La compra sigue activa: devolver no la anula.
        assertEquals(DocumentStatus.ACTIVE, world.tables.purchases.getValue(purchaseId).status)
        assertEquals(1, world.commits - COMMITS_OF_EXAMPLE)
    }

    @Test
    fun `a credit writes a RETURN_CREDIT and lowers what is owed to the supplier`() = runTest {
        val purchaseId = example()

        val returnId = registered(fromLine(lineOf(purchaseId), 2_000), purchaseId = purchaseId)

        val credit = supplierEntries().single().content
        assertEquals(SupplierLedgerEntryType.RETURN_CREDIT, credit.type)
        assertEquals(Money(-2_800), credit.amount)
        assertEquals(Money(-2_800), credit.balanceAfter)
        assertEquals(returnId, credit.purchaseReturnId)
        assertEquals(TODAY, credit.businessDate)
        // Sin deuda previa, el saldo queda a favor del negocio.
        assertEquals(Money(-2_800), balanceOf(SUPPLIER))
        assertTrue(world.tables.moneyMovements.isEmpty())
    }

    @Test
    fun `a credit chains on what was already owed`() = runTest {
        world.open(1, 10_000, total = 11_600)
        val purchaseId = (
            world.registerPurchase()(
                PurchaseRequest(
                    lines = listOf(atUnitCost(1, 2_000, cost = 1_400)),
                    supplierId = SUPPLIER,
                    amountPaid = Money.ZERO,
                ),
            ) as RegisterPurchaseResult.Registered
            ).purchaseId

        registered(fromLine(lineOf(purchaseId), 2_000), purchaseId = purchaseId)

        assertEquals(listOf(2_800L, 0L), supplierEntries().map { it.content.balanceAfter.minor })
        assertEquals(Money.ZERO, balanceOf(SUPPLIER))
    }

    @Test
    fun `a refund to an account writes a SUPPLIER_REFUND and keeps the supplier ledger`() =
        runTest {
            val purchaseId = example()

            val returnId = registered(
                fromLine(lineOf(purchaseId), 2_000),
                resolution = CASH_REFUND,
                purchaseId = purchaseId,
                accountId = BANK,
            )

            val refund = world.tables.moneyMovements.values.single()
            assertEquals(MoneyMovementType.SUPPLIER_REFUND, refund.type)
            assertEquals(Money(2_800), refund.amount)
            assertEquals(MoneyOrigin.PurchaseReturn(returnId), refund.draft.origin)
            assertEquals(TODAY, refund.time.businessDate)
            assertEquals(Money(12_800), world.tables.moneyAccounts.getValue(BANK).balance)
            assertEquals(
                BANK,
                world.tables.purchaseReturns.getValue(returnId).content.moneyAccountId,
            )
            assertTrue(supplierEntries().isEmpty())
            assertEquals(cost(10_000, 1_160, 11_600), world.costOf(1))
        }

    @Test
    fun `a refund without account only moves the stock`() = runTest {
        val purchaseId = example()

        val returnId =
            registered(
                fromLine(lineOf(purchaseId), 2_000),
                resolution = CASH_REFUND,
                purchaseId = purchaseId,
            )

        assertTrue(world.tables.moneyMovements.isEmpty())
        assertTrue(supplierEntries().isEmpty())
        assertNull(world.tables.purchaseReturns.getValue(returnId).content.moneyAccountId)
        assertEquals(cost(10_000, 1_160, 11_600), world.costOf(1))
    }

    @Test
    fun `a credit ignores the account it was given`() = runTest {
        val purchaseId = example()

        val returnId =
            registered(
                fromLine(lineOf(purchaseId), 2_000),
                purchaseId = purchaseId,
                accountId = BANK,
            )

        assertTrue(world.tables.moneyMovements.isEmpty())
        assertNull(world.tables.purchaseReturns.getValue(returnId).content.moneyAccountId)
    }

    @Test
    fun `a return of zero writes neither money nor credit`() = runTest {
        // Una bonificación: la línea entró gratis, y devolverla no deja nada que devolver.
        val purchaseId = world.buy(atUnitCost(1, 1_000, cost = 0), supplierId = SUPPLIER)

        val credit = registered(fromLine(lineOf(purchaseId), 1_000), purchaseId = purchaseId)
        val refund = registered(
            loose(1, 1_000),
            resolution = CASH_REFUND,
            supplierId = SUPPLIER,
            accountId = BANK,
        )

        assertEquals(Money.ZERO, world.tables.purchaseReturns.getValue(credit).content.total)
        assertNull(world.tables.purchaseReturns.getValue(refund).content.moneyAccountId)
        assertTrue(supplierEntries().isEmpty())
        assertTrue(world.tables.moneyMovements.isEmpty())
    }

    @Test
    fun `returning a line of three units one by one adds up to what it cost`() = runTest {
        val purchaseId = world.buy(forTotal(1, 3_000, total = 10_000), supplierId = SUPPLIER)
        val line = lineOf(purchaseId)

        val totals = (1..3).map {
            val id = registered(fromLine(line, 1_000), purchaseId = purchaseId)
            world.tables.purchaseReturns.getValue(id).content.total.minor
        }

        assertEquals(listOf(3_333L, 3_334L, 3_333L), totals)
        assertEquals(cost(0, 3_333, 0), world.costOf(1))
        assertEquals(Money(-10_000), balanceOf(SUPPLIER))
        assertEquals(
            RegisterSupplierReturnResult.AboveRemaining(0, QuantityMilli.ZERO),
            register(fromLine(line, 1_000), purchaseId = purchaseId),
        )
    }

    @Test
    fun `two lines of one purchase line cannot pass what was bought`() = runTest {
        val purchaseId = example()
        val line = lineOf(purchaseId)
        val before = world.tables

        val result =
            register(fromLine(line, 1_000), fromLine(line, 2_000), purchaseId = purchaseId)

        assertEquals(RegisterSupplierReturnResult.AboveRemaining(1, QuantityMilli(1_000)), result)
        assertEquals(before, world.tables)
    }

    @Test
    fun `a voided return no longer counts against what remains`() = runTest {
        val purchaseId = example()
        val line = lineOf(purchaseId)
        val returnId = registered(fromLine(line, 2_000), purchaseId = purchaseId)
        world.arrange { tables ->
            val voided = tables.purchaseReturns.getValue(returnId)
                .copy(voiding = Voiding(SaleWorld.EPOCH, VoidReason.ENTRY_ERROR, null))
            tables.copy(purchaseReturns = tables.purchaseReturns + (returnId to voided))
        }

        val result = register(fromLine(line, 2_000), purchaseId = purchaseId)

        assertTrue(result is RegisterSupplierReturnResult.Registered)
    }

    @Test
    fun `the supplier gives back the tax with the line while the stock leaves at its cost`() =
        runTest {
            // Una línea de 10 unidades que pagó 12100 con impuesto recuperable: al stock entraron 10000.
            val purchaseId = world.buy(forTotal(1, 10_000, total = 10_000), supplierId = SUPPLIER)
            world.arrange { tables ->
                val line = tables.purchaseLines.values.single()
                val taxed = line.copy(content = line.content.copy(lineTotal = Money(12_100)))
                tables.copy(purchaseLines = tables.purchaseLines + (line.id to taxed))
            }

            val returnId = registered(fromLine(lineOf(purchaseId), 5_000), purchaseId = purchaseId)

            assertEquals(
                Money(6_050),
                world.tables.purchaseReturns.getValue(returnId).content.total,
            )
            assertEquals(Money(-5_000), world.movements().last().totalCost)
            assertEquals(cost(5_000, 1_000, 5_000), world.costOf(1))
            assertEquals(Money(-6_050), balanceOf(SUPPLIER))
        }

    @Test
    fun `without a purchase it leaves with the exit cost and freezes the average`() = runTest {
        // 24 unidades por 10000: el promedio redondea a 417, y 6 se llevan la cuarta parte exacta.
        world.buy(forTotal(1, 24_000, total = 10_000))

        val returnId = registered(loose(1, 6_000), supplierId = SUPPLIER)

        val line = world.tables.purchaseReturnLines.values.single().content
        assertNull(line.purchaseLineId)
        assertEquals(Money(417), line.unitCost)
        assertEquals(Money(2_500), line.lineTotal)
        assertNull(world.tables.purchaseReturns.getValue(returnId).content.purchaseId)
        assertEquals(cost(18_000, 417, 7_500), world.costOf(1))
    }

    @Test
    fun `two lines of one product chain their cost`() = runTest {
        world.buy(forTotal(1, 24_000, total = 10_000))

        registered(loose(1, 6_000), loose(1, 6_000), supplierId = SUPPLIER)

        val totals = world.tables.purchaseReturnLines.values.sortedBy { it.id }
            .map { it.content.lineTotal.minor }
        assertEquals(listOf(2_500L, 2_500L), totals)
        assertEquals(cost(12_000, 417, 5_000), world.costOf(1))
        assertEquals(Money(-5_000), balanceOf(SUPPLIER))
    }

    @Test
    fun `a return may leave the stock negative, as any exit`() = runTest {
        world.open(1, 1_000, total = 1_000)

        registered(loose(1, 3_000), supplierId = SUPPLIER)

        // La unidad que había sale con su valor, y las que faltan al costo promedio.
        assertEquals(Money(3_000), world.tables.purchaseReturns.values.single().content.total)
        assertEquals(cost(-2_000, 1_000, 0), world.costOf(1))
    }

    @Test
    fun `a purchase without supplier takes the chosen one, and a credit needs one`() = runTest {
        val purchaseId = example(supplierId = null)
        val line = lineOf(purchaseId)
        val before = world.tables

        assertEquals(
            RegisterSupplierReturnResult.SupplierRequiredForCredit,
            register(fromLine(line, 1_000), purchaseId = purchaseId),
        )
        assertEquals(before, world.tables)

        val returnId =
            registered(fromLine(line, 1_000), purchaseId = purchaseId, supplierId = OTHER)
        assertEquals(OTHER, world.tables.purchaseReturns.getValue(returnId).content.supplierId)
        assertEquals(Money(-1_400), balanceOf(OTHER))
    }

    @Test
    fun `the supplier of the purchase wins and another one is refused`() = runTest {
        val purchaseId = example()
        val line = lineOf(purchaseId)

        assertEquals(
            RegisterSupplierReturnResult.SupplierMismatch,
            register(fromLine(line, 1_000), purchaseId = purchaseId, supplierId = OTHER),
        )
        val returnId =
            registered(fromLine(line, 1_000), purchaseId = purchaseId, supplierId = SUPPLIER)
        assertEquals(SUPPLIER, world.tables.purchaseReturns.getValue(returnId).content.supplierId)
    }

    @Test
    fun `an archived supplier and an archived product can still take a return`() = runTest {
        world.addSupplier(ARCHIVED, archived = true)
        world.addProduct(
            product(2, archived = true, onHandMilli = 1_000, averageCost = 10, stockValue = 10),
        )

        registered(loose(2, 1_000), supplierId = ARCHIVED)

        assertEquals(Money(-10), balanceOf(ARCHIVED))
        assertEquals(cost(0, 10, 0), world.costOf(2))
    }

    @Test
    fun `a refund needs no supplier`() = runTest {
        world.open(1, 1_000, total = 500)

        val returnId = registered(loose(1, 1_000), resolution = CASH_REFUND, accountId = BANK)

        assertNull(world.tables.purchaseReturns.getValue(returnId).content.supplierId)
        assertEquals(Money(10_500), world.tables.moneyAccounts.getValue(BANK).balance)
    }

    @Test
    fun `the shape of the return is checked before reading anything`() = runTest {
        assertEquals(RegisterSupplierReturnResult.EmptyReturn, register())
        val tooMany = Array(201) { loose(1, 1_000) }
        assertEquals(RegisterSupplierReturnResult.TooManyLines, register(*tooMany))
        assertEquals(
            RegisterSupplierReturnResult.NoteTooLong,
            register(loose(1, 1_000), supplierId = SUPPLIER, note = "n".repeat(201)),
        )
        assertEquals(0, world.commits)
    }

    @Test
    fun `a note is trimmed and a blank one is not kept`() = runTest {
        world.open(1, 2_000, total = 2_000)

        val noted = (
            register(loose(1, 1_000), supplierId = SUPPLIER, note = "  defective  ")
                as RegisterSupplierReturnResult.Registered
            ).purchaseReturnId
        val blank = (
            register(loose(1, 1_000), supplierId = SUPPLIER, note = "   ")
                as RegisterSupplierReturnResult.Registered
            ).purchaseReturnId

        assertEquals("defective", world.tables.purchaseReturns.getValue(noted).content.note)
        assertNull(world.tables.purchaseReturns.getValue(blank).content.note)
    }

    @Test
    fun `a missing or voided purchase is refused`() = runTest {
        val purchaseId = example()
        world.voidPurchase()(purchaseId, VoidReason.ENTRY_ERROR, null)

        assertEquals(
            RegisterSupplierReturnResult.PurchaseNotFound,
            register(fromLine(1, 1_000), purchaseId = 99),
        )
        assertEquals(
            RegisterSupplierReturnResult.PurchaseVoided,
            register(fromLine(lineOf(purchaseId), 1_000), purchaseId = purchaseId),
        )
    }

    @Test
    fun `a missing supplier is refused`() = runTest {
        assertEquals(
            RegisterSupplierReturnResult.SupplierNotFound,
            register(loose(1, 1_000), supplierId = 99),
        )
    }

    @Test
    fun `each line is checked with its reason`() = runTest {
        val purchaseId = example()
        world.addProduct(product(3, stockMode = ProductStockMode.UNTRACKED))
        world.addProduct(product(4, tracksLots = true))
        world.addProduct(product(5, unitId = SaleWorld.KILOGRAM))
        val line = lineOf(purchaseId)

        fun rejected(index: Int, reason: SupplierReturnLineRejection) =
            RegisterSupplierReturnResult.LineRejected(index, reason)

        assertEquals(
            rejected(0, SupplierReturnLineRejection.PURCHASE_REQUIRED),
            register(fromLine(line, 1_000), supplierId = SUPPLIER),
        )
        assertEquals(
            rejected(1, SupplierReturnLineRejection.NOT_IN_PURCHASE),
            register(fromLine(line, 1_000), fromLine(99, 1_000), purchaseId = purchaseId),
        )
        assertEquals(
            rejected(0, SupplierReturnLineRejection.NOT_IN_PURCHASE),
            register(loose(1, 1_000), purchaseId = purchaseId),
        )
        assertEquals(
            rejected(0, SupplierReturnLineRejection.PRODUCT_NOT_FOUND),
            register(loose(99, 1_000), supplierId = SUPPLIER),
        )
        assertEquals(
            rejected(0, SupplierReturnLineRejection.NOT_STOCKED),
            register(loose(3, 1_000), supplierId = SUPPLIER),
        )
        assertEquals(
            rejected(0, SupplierReturnLineRejection.LOTS_NOT_SUPPORTED),
            register(loose(4, 1_000), supplierId = SUPPLIER),
        )
        for (milli in listOf(0L, -1_000L, 1_000_000_001L)) {
            assertEquals(
                rejected(0, SupplierReturnLineRejection.INVALID_QUANTITY),
                register(loose(1, milli), supplierId = SUPPLIER),
            )
        }
        assertEquals(
            rejected(0, SupplierReturnLineRejection.FRACTION_NOT_ALLOWED),
            register(loose(1, 1_500), supplierId = SUPPLIER),
        )
        // Por peso, la fracción sí vale.
        assertTrue(
            register(
                loose(5, 1_500),
                supplierId = SUPPLIER,
            ) is RegisterSupplierReturnResult.Registered,
        )
        assertEquals(1, world.tables.purchaseReturns.size)
    }

    @Test
    fun `figures that do not fit are refused without writing`() = runTest {
        world.addProduct(
            product(6, onHandMilli = 0, averageCost = Long.MAX_VALUE / 2, stockValue = 0),
        )
        val purchaseId = world.buy(atUnitCost(1, 1_000, cost = 1), atUnitCost(1, 1_000, cost = 1))
        world.arrange { tables ->
            // Dos líneas que pagaron 600 billones cada una: juntas pasan del máximo de un documento.
            val big = tables.purchaseLines.mapValues { (_, line) ->
                line.copy(content = line.content.copy(lineTotal = Money(600_000_000_000_000)))
            }
            tables.copy(purchaseLines = big)
        }
        val before = world.tables

        assertEquals(
            RegisterSupplierReturnResult.LineRejected(
                0,
                SupplierReturnLineRejection.AMOUNT_TOO_LARGE,
            ),
            register(loose(6, 1_000_000), supplierId = SUPPLIER),
        )
        assertEquals(
            RegisterSupplierReturnResult.TotalTooLarge,
            register(
                fromLine(lineOf(purchaseId, 0), 1_000),
                fromLine(lineOf(purchaseId, 1), 1_000),
                purchaseId = purchaseId,
                resolution = CASH_REFUND,
            ),
        )
        assertEquals(before, world.tables)
    }

    @Test
    fun `a return that leaves an average out of range is refused`() = runTest {
        // Queda una milésima con un valor enorme: su promedio por unidad no cabe.
        world.addProduct(product(8, unitId = SaleWorld.KILOGRAM))
        val purchaseId = world.buy(atUnitCost(8, 1_000, cost = 0))
        world.arrange { tables ->
            val product = tables.products.getValue(8)
            val huge = product.copy(
                onHand = QuantityMilli(2),
                stockValue = Money(Long.MAX_VALUE / 2),
            )
            tables.copy(products = tables.products + (8L to huge))
        }
        val before = world.tables

        assertEquals(
            RegisterSupplierReturnResult.LineRejected(
                0,
                SupplierReturnLineRejection.AMOUNT_TOO_LARGE,
            ),
            register(
                fromLine(lineOf(purchaseId), 1),
                purchaseId = purchaseId,
                resolution = CASH_REFUND,
            ),
        )
        assertEquals(before, world.tables)
    }

    @Test
    fun `a supplier balance that would not fit is refused`() = runTest {
        world.open(1, 1_000, total = 1_000)
        world.arrange { tables ->
            // El saldo encadenado sale de la última fila del libro: un crédito enorme a favor.
            val entry = SupplierLedgerEntry(
                99,
                NewSupplierLedgerEntry(
                    supplierId = SUPPLIER,
                    type = SupplierLedgerEntryType.OPENING_BALANCE,
                    amount = Money(Long.MIN_VALUE + 10),
                    balanceAfter = Money(Long.MIN_VALUE + 10),
                    occurredAt = SaleWorld.EPOCH,
                    businessDate = TODAY,
                    createdAt = SaleWorld.EPOCH,
                ),
            )
            tables.copy(supplierEntries = tables.supplierEntries + (99L to entry))
        }
        val before = world.tables

        assertEquals(
            RegisterSupplierReturnResult.SupplierBalanceTooLarge,
            register(loose(1, 1_000), supplierId = SUPPLIER),
        )
        assertEquals(before, world.tables)
    }

    @Test
    fun `an account that cannot take the refund is refused`() = runTest {
        world.open(1, 1_000, total = 1_000)
        world.addMoneyAccount(ARCHIVED_ACCOUNT, archived = true)
        world.addMoneyAccount(
            FOREIGN,
            currency = AccountCurrency(requireNotNull(CurrencyCode.of("XAU")), 0),
        )
        world.addMoneyAccount(FULL, balance = Long.MAX_VALUE - 10)

        fun refund(accountId: Long) = SupplierReturnRequest(
            lines = listOf(loose(1, 1_000)),
            resolution = CASH_REFUND,
            moneyAccountId = accountId,
        )
        val use = world.registerSupplierReturn()

        assertEquals(
            RegisterSupplierReturnResult.AccountUnavailable(AccountIssue.NOT_FOUND),
            use(refund(99)),
        )
        assertEquals(
            RegisterSupplierReturnResult.AccountUnavailable(AccountIssue.ARCHIVED),
            use(refund(ARCHIVED_ACCOUNT)),
        )
        assertEquals(
            RegisterSupplierReturnResult.AccountUnavailable(AccountIssue.OTHER_CURRENCY),
            use(refund(FOREIGN)),
        )
        assertEquals(RegisterSupplierReturnResult.AccountOverflow, use(refund(FULL)))
        assertTrue(world.tables.purchaseReturns.isEmpty())
        assertEquals(cost(1_000, 1_000, 1_000), world.costOf(1))
    }

    @Test
    fun `an unknown zone or an unconfirmed currency reject without writing`() = runTest {
        val lost = SaleWorld(zoneId = "Invented/Zone").apply { addProduct(product(1)) }
        val unconfirmed = SaleWorld(currencyConfirmed = false).apply { addProduct(product(1)) }
        val request = SupplierReturnRequest(listOf(loose(1, 1_000)), CASH_REFUND)

        assertEquals(
            RegisterSupplierReturnResult.UnknownTimeZone("Invented/Zone"),
            lost.registerSupplierReturn()(request),
        )
        assertEquals(
            RegisterSupplierReturnResult.CurrencyNotConfirmed,
            unconfirmed.registerSupplierReturn()(request),
        )
        assertTrue(lost.tables.purchaseReturns.isEmpty())
        assertTrue(unconfirmed.tables.purchaseReturns.isEmpty())
    }

    @Test
    fun `the first return locks the currency`() = runTest {
        val unlocked = SaleWorld(currencyLocked = false).apply { addProduct(product(1)) }

        unlocked.registerSupplierReturn()(
            SupplierReturnRequest(listOf(loose(1, 1_000)), CASH_REFUND),
        )

        assertEquals(1, unlocked.settings.lockCalls.size)
    }

    @Test
    fun `a storage failure comes back as a result`() = runTest {
        world.open(1, 1_000, total = 1_000)
        world.failure = { StorageFailureException(StorageFailure.DISK_FULL) }
        world.failAtWrite = 1

        val result = register(loose(1, 1_000), supplierId = SUPPLIER)

        assertEquals(RegisterSupplierReturnResult.StorageFailed(StorageFailure.DISK_FULL), result)
    }

    @Test
    fun `a failure at each write of a credit leaves nothing`() = runTest {
        val purchaseId = example()
        assertFailureAtEachWriteLeavesNothing(minimumWrites = 6) {
            register(fromLine(lineOf(purchaseId), 2_000), purchaseId = purchaseId)
        }
    }

    @Test
    fun `a failure at each write of a refund leaves nothing`() = runTest {
        val purchaseId = example()
        assertFailureAtEachWriteLeavesNothing(minimumWrites = 6) {
            register(
                fromLine(lineOf(purchaseId), 2_000),
                resolution = CASH_REFUND,
                purchaseId = purchaseId,
                accountId = BANK,
            )
        }
    }

    @Test
    fun `a purchase with an active return is not voided`() = runTest {
        val purchaseId = example()
        registered(fromLine(lineOf(purchaseId), 1_000), purchaseId = purchaseId)
        val before = world.tables

        val result = world.voidPurchase()(purchaseId, VoidReason.ENTRY_ERROR, null)

        assertEquals(VoidPurchaseResult.HasActiveReturns, result)
        assertEquals(before, world.tables)
    }

    @Test
    fun `the return evaluates the low stock alert in the same transaction`() = runTest {
        world.withAlerts = true
        world.addProduct(
            product(
                9,
                minStockMilli = 5_000,
                onHandMilli = 6_000,
                averageCost = 1,
                stockValue = 6,
            ),
        )

        registered(loose(9, 2_000), supplierId = SUPPLIER)

        assertEquals(
            listOf(9L),
            world.tables.alerts.values.filter {
                it.isActive
            }.map { it.productId },
        )
    }

    /**
     * Hace fallar cada escritura de [action], una por corrida, y comprueba que nada cambia. Termina
     * cuando la acción ya no llega a la escritura que falla.
     */
    private suspend fun assertFailureAtEachWriteLeavesNothing(
        minimumWrites: Int,
        action: suspend () -> RegisterSupplierReturnResult,
    ) {
        val before = world.tables
        var failAt = 1
        while (true) {
            world.failAtWrite = failAt
            val failed = try {
                action()
                false
            } catch (_: InjectedSaleFailure) {
                true
            }
            if (!failed) break
            assertEquals("Escritura $failAt", before, world.tables)
            failAt++
        }
        world.failAtWrite = null
        assertTrue("Cabecera, línea, libro de stock y su saldo", failAt > minimumWrites)
    }

    private companion object {
        const val SUPPLIER = 7L
        const val OTHER = 8L
        const val ARCHIVED = 9L
        const val BANK = 10L
        const val ARCHIVED_ACCOUNT = 11L
        const val FOREIGN = 12L
        const val FULL = 13L

        /** Las confirmaciones del ejemplo: el inventario inicial y la compra. */
        const val COMMITS_OF_EXAMPLE = 2
    }
}
