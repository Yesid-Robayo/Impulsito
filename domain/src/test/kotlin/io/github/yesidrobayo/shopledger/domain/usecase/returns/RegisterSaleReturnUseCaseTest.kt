package io.github.yesidrobayo.shopledger.domain.usecase.returns

import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.DocumentStatus
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.VoidReason
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductStockMode
import io.github.yesidrobayo.shopledger.domain.model.customer.LoyaltyEntrySource
import io.github.yesidrobayo.shopledger.domain.model.customer.LoyaltyEntryType
import io.github.yesidrobayo.shopledger.domain.model.customer.LoyaltyLedgerEntry
import io.github.yesidrobayo.shopledger.domain.model.money.AccountCurrency
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyMovementType
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyOrigin
import io.github.yesidrobayo.shopledger.domain.model.returns.ReturnLineRequest
import io.github.yesidrobayo.shopledger.domain.model.returns.ReturnResolution
import io.github.yesidrobayo.shopledger.domain.model.returns.ReturnSettlement
import io.github.yesidrobayo.shopledger.domain.model.returns.SaleReturnRequest
import io.github.yesidrobayo.shopledger.domain.model.sale.LineDiscount
import io.github.yesidrobayo.shopledger.domain.model.sale.LineStockEffect
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleLineRequest
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleRequest
import io.github.yesidrobayo.shopledger.domain.model.settings.CurrencyCode
import io.github.yesidrobayo.shopledger.domain.model.settings.TaxMode
import io.github.yesidrobayo.shopledger.domain.model.stock.StockMovementSource
import io.github.yesidrobayo.shopledger.domain.model.stock.StockMovementType
import io.github.yesidrobayo.shopledger.domain.port.StorageFailure
import io.github.yesidrobayo.shopledger.domain.port.StorageFailureException
import io.github.yesidrobayo.shopledger.domain.usecase.money.AccountIssue
import io.github.yesidrobayo.shopledger.domain.usecase.sale.InjectedSaleFailure
import io.github.yesidrobayo.shopledger.domain.usecase.sale.RegisterSaleResult
import io.github.yesidrobayo.shopledger.domain.usecase.sale.SaleWorld
import io.github.yesidrobayo.shopledger.domain.usecase.sale.VoidSaleResult
import io.github.yesidrobayo.shopledger.domain.usecase.sale.method
import io.github.yesidrobayo.shopledger.domain.usecase.sale.product
import io.github.yesidrobayo.shopledger.domain.usecase.sale.taxRate
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Devolver parte de una venta: cada línea se lleva su parte de lo vendido con el costo congelado,
 * lo que vuelve entra al libro como una entrada, el dinero sale de la cuenta elegida y cualquier
 * rechazo o falla no deja nada. Los valores esperados están escritos a mano.
 */
class RegisterSaleReturnUseCaseTest {
    private val world = SaleWorld().apply { addMethod(method()) }

    private suspend fun sell(vararg lines: SaleLineRequest, date: BusinessDate? = null): Long {
        val request = SaleRequest(lines.toList(), SaleWorld.CASH, date = date)
        return (world.registerSale()(request) as RegisterSaleResult.Registered).saleId
    }

    private fun line(productId: Long, milli: Long, price: Long? = null, discount: Long = 0) =
        SaleLineRequest(
            productId,
            QuantityMilli(milli),
            unitPrice = price?.let(::Money),
            discount = if (discount ==
                0L
            ) {
                LineDiscount.None
            } else {
                LineDiscount.Amount(Money(discount))
            },
        )

    private fun lineIds(saleId: Long): List<Long> =
        world.tables.lines.values.filter { it.saleId == saleId }.sortedBy { it.id }.map { it.id }

    private suspend fun giveBack(
        saleId: Long,
        vararg lines: ReturnLineRequest,
        accountId: Long? = null,
        note: String? = null,
    ): RegisterSaleReturnResult = world.registerSaleReturn()(
        SaleReturnRequest(saleId, lines.toList(), ReturnSettlement.Refund(accountId), note),
    )

    private fun ret(lineId: Long, milli: Long, restock: Boolean = true) =
        ReturnLineRequest(lineId, QuantityMilli(milli), restock)

    private fun registered(result: RegisterSaleReturnResult): Long =
        (result as RegisterSaleReturnResult.Registered).saleReturnId

    /** Un producto con stock: [onHandMilli] milésimas que valen [value]. */
    private fun stocked(id: Long, onHandMilli: Long, value: Long, price: Long = 2_000) {
        val average = value * 1_000 / onHandMilli
        world.addProduct(
            product(
                id,
                salePrice = price,
                onHandMilli = onHandMilli,
                averageCost = average,
                stockValue = value,
            ),
        )
    }

    private companion object {
        val TODAY = BusinessDate(LocalDate(2026, 11, 2))
    }

    @Test
    fun theRuleExampleEntersTheReturnAtTheFrozenCost() = runTest {
        // Se vende 1 unidad con costo 1250; después el producto queda con Q = 5000 y V = 5000.
        stocked(1, onHandMilli = 6_000, value = 7_500)
        val saleId = sell(line(1, 1_000))
        assertEquals(Money(1_250), world.tables.lines.values.single().content.lineCost)
        world.arrange {
            val now = it.products.getValue(1)
            it.copy(
                products = it.products + (
                    1L to now.copy(averageCost = Money(1_000), stockValue = Money(5_000))
                    ),
            )
        }

        val returnId = registered(giveBack(saleId, ret(lineIds(saleId).single(), 1_000)))

        val product = world.tables.products.getValue(1)
        assertEquals(QuantityMilli(6_000), product.onHand)
        assertEquals(Money(6_250), product.stockValue)
        assertEquals(Money(1_042), product.averageCost)
        val entry = world.tables.movements.values.maxBy { it.id }
        assertEquals(StockMovementType.SALE_RETURN, entry.movement.type)
        assertEquals(QuantityMilli(1_000), entry.movement.quantity)
        assertEquals(SaleWorld.MAIN_LOCATION, entry.movement.key.locationId)
        assertNull(entry.movement.reversesMovementId)
        assertEquals(Money(1_250), entry.unitCost)
        assertEquals(Money(1_250), entry.totalCost)
        assertEquals(Money(1_042), entry.averageCostAfter)
        assertEquals(Money(6_250), entry.stockValueAfter)
        val returnLine = world.tables.saleReturnLines.values.single()
        assertEquals(StockMovementSource(saleReturnLineId = returnLine.id), entry.source)
        val header = world.tables.saleReturns.getValue(returnId).content
        assertEquals(ReturnResolution.REFUND, header.resolution)
        assertEquals(Money(2_000), header.netRefund)
        assertEquals(Money(1_250), header.costReturned)
        assertEquals(TODAY, header.businessDate)
        assertEquals(TODAY, entry.businessDate)
    }

    @Test
    fun withoutRestockThereIsNoMovementAndTheCostStaysSold() = runTest {
        stocked(1, onHandMilli = 6_000, value = 7_500)
        val saleId = sell(line(1, 1_000))
        val movements = world.tables.movements
        val productBefore = world.tables.products.getValue(1)

        val returnId = registered(
            giveBack(saleId, ret(lineIds(saleId).single(), 1_000, restock = false)),
        )

        assertEquals(movements, world.tables.movements)
        assertEquals(productBefore, world.tables.products.getValue(1))
        val header = world.tables.saleReturns.getValue(returnId).content
        assertEquals(Money(2_000), header.totalRefund)
        assertEquals(Money.ZERO, header.costReturned)
        val returnLine = world.tables.saleReturnLines.values.single().content
        assertEquals(Money(1_250), returnLine.lineCost)
        assertEquals(false, returnLine.restock)
    }

    @Test
    fun returningOneOfThreeAtATimeRefundsTheRuleExampleWithItsTax() = runTest {
        world.taxRates = mapOf(5L to taxRate(5, "Rate", 1_900))
        world.setTaxes(TaxMode.ADDED, defaultTaxRateId = 5)
        stocked(1, onHandMilli = 10_000, value = 20_000)
        // Tres unidades a 3334 con 2 de descuento: lo neto de la línea es 10000.
        val saleId = sell(line(1, 3_000, price = 3_334, discount = 2))
        val sold = world.tables.lines.values.single().content
        assertEquals(Money(10_000), sold.netAmount)
        assertEquals(Money(1_900), sold.taxAmount)
        assertEquals(Money(6_000), sold.lineCost)
        val lineId = lineIds(saleId).single()

        val ids = (1..3).map { registered(giveBack(saleId, ret(lineId, 1_000))) }

        val headers = ids.map { world.tables.saleReturns.getValue(it).content }
        assertEquals(listOf(3_333L, 3_334L, 3_333L), headers.map { it.netRefund.minor })
        assertEquals(listOf(633L, 634L, 633L), headers.map { it.taxRefund.minor })
        assertEquals(listOf(3_966L, 3_968L, 3_966L), headers.map { it.totalRefund.minor })
        assertEquals(listOf(2_000L, 2_000L, 2_000L), headers.map { it.costReturned.minor })
        assertEquals(Money(11_900), headers.fold(Money.ZERO) { sum, it -> sum + it.totalRefund })
        assertEquals(QuantityMilli(10_000), world.tables.products.getValue(1).onHand)
        assertEquals(Money(20_000), world.tables.products.getValue(1).stockValue)
    }

    @Test
    fun nothingPassesWhatWasSold() = runTest {
        stocked(1, onHandMilli = 10_000, value = 10_000)
        val saleId = sell(line(1, 3_000))
        val lineId = lineIds(saleId).single()
        registered(giveBack(saleId, ret(lineId, 2_000)))
        val before = world.tables

        assertEquals(
            RegisterSaleReturnResult.AboveRemaining(0, QuantityMilli(1_000)),
            giveBack(saleId, ret(lineId, 2_000)),
        )

        assertEquals(before, world.tables)
        registered(giveBack(saleId, ret(lineId, 1_000)))
        assertEquals(
            RegisterSaleReturnResult.AboveRemaining(0, QuantityMilli.ZERO),
            giveBack(saleId, ret(lineId, 1_000)),
        )
    }

    @Test
    fun twoLinesOfTheSameProductChainTheirCost() = runTest {
        stocked(1, onHandMilli = 4_000, value = 4_000)
        val saleId = sell(line(1, 1_000), line(1, 1_000, price = 1_500))
        val (first, second) = lineIds(saleId)

        val returnId = registered(giveBack(saleId, ret(first, 1_000), ret(second, 1_000)))

        val product = world.tables.products.getValue(1)
        assertEquals(QuantityMilli(4_000), product.onHand)
        assertEquals(Money(4_000), product.stockValue)
        val entries = world.tables.movements.values.filter {
            it.movement.type == StockMovementType.SALE_RETURN
        }.sortedBy { it.id }
        assertEquals(
            listOf(3_000L, 4_000L),
            entries.map { it.balanceAfter.milli },
        )
        assertEquals(Money(3_500), world.tables.saleReturns.getValue(returnId).content.netRefund)
    }

    @Test
    fun aProductWithoutStockLeavesNoMovementButItsCostFollowsTheChoice() = runTest {
        world.addProduct(
            product(
                2,
                salePrice = 5_000,
                stockMode = ProductStockMode.UNTRACKED,
                fixedUnitCost = 1_200,
            ),
        )
        val saleId = sell(line(2, 2_000))
        assertEquals(
            LineStockEffect.NONE,
            world.tables.lines.values.single().content.stockEffect,
        )
        val lineId = lineIds(saleId).single()

        val kept = registered(giveBack(saleId, ret(lineId, 1_000)))
        val lost = registered(giveBack(saleId, ret(lineId, 1_000, restock = false)))

        assertTrue(world.tables.movements.isEmpty())
        assertEquals(Money(1_200), world.tables.saleReturns.getValue(kept).content.costReturned)
        assertEquals(Money.ZERO, world.tables.saleReturns.getValue(lost).content.costReturned)
    }

    @Test
    fun anArchivedProductComesBackToStock() = runTest {
        stocked(1, onHandMilli = 2_000, value = 2_000)
        val saleId = sell(line(1, 1_000))
        world.arrange {
            val now = it.products.getValue(1)
            it.copy(products = it.products + (1L to now.copy(archivedAt = SaleWorld.EPOCH)))
        }

        registered(giveBack(saleId, ret(lineIds(saleId).single(), 1_000)))

        assertEquals(QuantityMilli(2_000), world.tables.products.getValue(1).onHand)
    }

    @Test
    fun aReturnOfAnOldSaleCountsTodayAndNotOnTheSaleDay() = runTest {
        stocked(1, onHandMilli = 2_000, value = 2_000)
        val saleId = sell(line(1, 1_000), date = BusinessDate(LocalDate(2026, 10, 15)))

        val returnId = registered(giveBack(saleId, ret(lineIds(saleId).single(), 1_000)))

        assertEquals(TODAY, world.tables.saleReturns.getValue(returnId).content.businessDate)
        assertEquals(
            BusinessDate(LocalDate(2026, 10, 15)),
            world.tables.sales.getValue(saleId).content.businessDate,
        )
    }

    @Test
    fun theRefundLeavesTheChosenAccountInItsOpenCashSession() = runTest {
        world.addMoneyAccount(10, balance = 50_000)
        world.openCashSession(10, sessionId = 7)
        stocked(1, onHandMilli = 2_000, value = 2_000)
        val saleId = sell(line(1, 1_000))

        val returnId = registered(
            giveBack(saleId, ret(lineIds(saleId).single(), 1_000), accountId = 10, note = "Box"),
        )

        val movement = world.tables.moneyMovements.values.single()
        assertEquals(MoneyMovementType.REFUND, movement.type)
        assertEquals(Money(-2_000), movement.amount)
        assertEquals(MoneyOrigin.SaleReturn(returnId), movement.draft.origin)
        assertEquals(7L, movement.draft.cashSessionId)
        assertEquals(TODAY, movement.time.businessDate)
        assertEquals(Money(48_000), world.tables.moneyAccounts.getValue(10).balance)
        val header = world.tables.saleReturns.getValue(returnId).content
        assertEquals(10L, header.moneyAccountId)
        assertEquals("Box", header.note)
    }

    @Test
    fun withoutAnAccountNoMoneyMovesAndTheReturnKeepsNone() = runTest {
        stocked(1, onHandMilli = 2_000, value = 2_000)
        val saleId = sell(line(1, 1_000))

        val returnId = registered(
            giveBack(saleId, ret(lineIds(saleId).single(), 1_000), note = "  "),
        )

        assertTrue(world.tables.moneyMovements.isEmpty())
        assertEquals(0, world.moneyAccountReads)
        val header = world.tables.saleReturns.getValue(returnId).content
        assertNull(header.moneyAccountId)
        assertNull(header.note)
    }

    @Test
    fun aFreeLineReturnsNothingAndTouchesNoAccount() = runTest {
        world.addMoneyAccount(10, archived = true)
        stocked(1, onHandMilli = 2_000, value = 2_000)
        val saleId = sell(line(1, 1_000, price = 0))

        val returnId = registered(
            giveBack(saleId, ret(lineIds(saleId).single(), 1_000), accountId = 10),
        )

        assertTrue(world.tables.moneyMovements.isEmpty())
        assertNull(world.tables.saleReturns.getValue(returnId).content.moneyAccountId)
    }

    @Test
    fun anAccountThatCannotPayRejectsWithoutWritingAnything() = runTest {
        world.addMoneyAccount(10, archived = true)
        world.addMoneyAccount(
            11,
            currency = AccountCurrency(requireNotNull(CurrencyCode.of("XTS")), 2),
        )
        stocked(1, onHandMilli = 2_000, value = 2_000)
        val saleId = sell(line(1, 1_000))
        val lineId = lineIds(saleId).single()
        val before = world.tables

        assertEquals(
            RegisterSaleReturnResult.AccountUnavailable(AccountIssue.ARCHIVED),
            giveBack(saleId, ret(lineId, 1_000), accountId = 10),
        )
        assertEquals(
            RegisterSaleReturnResult.AccountUnavailable(AccountIssue.OTHER_CURRENCY),
            giveBack(saleId, ret(lineId, 1_000), accountId = 11),
        )
        assertEquals(
            RegisterSaleReturnResult.AccountUnavailable(AccountIssue.NOT_FOUND),
            giveBack(saleId, ret(lineId, 1_000), accountId = 99),
        )
        assertEquals(before, world.tables)
    }

    @Test
    fun theSalePointsDoNotChange() = runTest {
        world.addCustomer(4)
        stocked(1, onHandMilli = 2_000, value = 2_000)
        val saleId = sell(line(1, 1_000))
        val earned = LoyaltyLedgerEntry(
            id = 1,
            customerId = 4,
            type = LoyaltyEntryType.EARN,
            points = 2,
            balanceAfter = 2,
            source = LoyaltyEntrySource.Sale(saleId),
            note = null,
            occurredAt = SaleWorld.EPOCH,
            businessDate = TODAY,
        )
        world.arrange { it.copy(loyaltyEntries = mapOf(1L to earned)) }

        registered(giveBack(saleId, ret(lineIds(saleId).single(), 1_000)))

        assertEquals(mapOf(1L to earned), world.tables.loyaltyEntries)
    }

    @Test
    fun aSaleWithAnActiveReturnIsNotVoided() = runTest {
        stocked(1, onHandMilli = 2_000, value = 2_000)
        val saleId = sell(line(1, 2_000))
        registered(giveBack(saleId, ret(lineIds(saleId).single(), 1_000)))

        assertEquals(
            VoidSaleResult.HasActiveReturns,
            world.voidSale()(saleId, VoidReason.ENTRY_ERROR, null),
        )
        assertEquals(DocumentStatus.ACTIVE, world.tables.sales.getValue(saleId).status)
    }

    @Test
    fun theShapeIsRejectedBeforeReadingTheSale() = runTest {
        assertEquals(RegisterSaleReturnResult.NoLines, giveBack(1))
        assertEquals(
            RegisterSaleReturnResult.TooManyLines,
            giveBack(1, *Array(201) { ret(it.toLong(), 1_000) }),
        )
        assertEquals(
            RegisterSaleReturnResult.NoteTooLong,
            giveBack(1, ret(1, 1_000), note = "x".repeat(201)),
        )
        assertEquals(
            RegisterSaleReturnResult.LineRejected(1, ReturnLineRejection.DUPLICATE),
            giveBack(1, ret(1, 1_000), ret(1, 1_000)),
        )
        assertEquals(RegisterSaleReturnResult.SaleNotFound, giveBack(1, ret(1, 1_000)))
        assertEquals(0, world.commits)
    }

    @Test
    fun eachRequestedLineIsChecked() = runTest {
        stocked(1, onHandMilli = 2_000, value = 2_000)
        world.addProduct(product(3, salePrice = 1_000, unitId = SaleWorld.KILOGRAM))
        val saleId = sell(line(1, 1_000), line(3, 1_500))
        val other = sell(line(1, 1_000))
        val (unitLine, kiloLine) = lineIds(saleId)

        assertEquals(
            RegisterSaleReturnResult.LineRejected(0, ReturnLineRejection.NOT_IN_SALE),
            giveBack(saleId, ret(lineIds(other).single(), 1_000)),
        )
        assertEquals(
            RegisterSaleReturnResult.LineRejected(0, ReturnLineRejection.INVALID_QUANTITY),
            giveBack(saleId, ret(unitLine, 0)),
        )
        assertEquals(
            RegisterSaleReturnResult.LineRejected(1, ReturnLineRejection.FRACTION_NOT_ALLOWED),
            giveBack(saleId, ret(kiloLine, 250), ret(unitLine, 500)),
        )
        // El kilogramo admite fracciones: un cuarto de lo vendido se devuelve.
        val returnId = registered(giveBack(saleId, ret(kiloLine, 250)))
        assertEquals(Money(250), world.tables.saleReturns.getValue(returnId).content.netRefund)
    }

    @Test
    fun aComboLineIsNotReturned() = runTest {
        stocked(1, onHandMilli = 2_000, value = 2_000)
        val saleId = sell(line(1, 1_000))
        val lineId = lineIds(saleId).single()
        world.arrange {
            val sold = it.lines.getValue(lineId)
            val combo = sold.copy(
                content = sold.content.copy(stockEffect = LineStockEffect.COMPONENTS),
            )
            it.copy(lines = it.lines + (lineId to combo))
        }

        assertEquals(
            RegisterSaleReturnResult.LineRejected(0, ReturnLineRejection.COMPONENTS_NOT_SUPPORTED),
            giveBack(saleId, ret(lineId, 1_000)),
        )
    }

    @Test
    fun aVoidedSaleHasNothingToReturn() = runTest {
        stocked(1, onHandMilli = 2_000, value = 2_000)
        val saleId = sell(line(1, 1_000))
        world.voidSale()(saleId, VoidReason.ENTRY_ERROR, null)

        assertEquals(
            RegisterSaleReturnResult.SaleVoided,
            giveBack(saleId, ret(lineIds(saleId).single(), 1_000)),
        )
    }

    @Test
    fun anUnknownZoneRejects() = runTest {
        val unknown = SaleWorld(zoneId = "Nowhere/Zone")
        val result = unknown.registerSaleReturn()(
            SaleReturnRequest(1, listOf(ret(1, 1_000)), ReturnSettlement.Refund(null)),
        )
        assertEquals(RegisterSaleReturnResult.UnknownTimeZone("Nowhere/Zone"), result)
    }

    @Test
    fun aCostThatDoesNotFitRejectsAsADamagedLedger() = runTest {
        stocked(1, onHandMilli = 2_000, value = 2_000)
        val saleId = sell(line(1, 1_000))
        world.arrange {
            val now = it.products.getValue(1)
            it.copy(products = it.products + (1L to now.copy(stockValue = Money(Long.MAX_VALUE))))
        }
        val before = world.tables

        assertEquals(
            RegisterSaleReturnResult.LedgerDamaged,
            giveBack(saleId, ret(lineIds(saleId).single(), 1_000)),
        )
        assertEquals(before, world.tables)
    }

    @Test
    fun anAccountBalanceThatDoesNotFitRejectsAsADamagedLedger() = runTest {
        world.addMoneyAccount(10, balance = Long.MIN_VALUE + 1)
        stocked(1, onHandMilli = 2_000, value = 2_000)
        val saleId = sell(line(1, 1_000))
        val before = world.tables

        assertEquals(
            RegisterSaleReturnResult.LedgerDamaged,
            giveBack(saleId, ret(lineIds(saleId).single(), 1_000), accountId = 10),
        )
        assertEquals(before, world.tables)
    }

    @Test
    fun aRestockingReturnResolvesTheLowStockAlert() = runTest {
        world.withAlerts = true
        world.addProduct(
            product(
                1,
                salePrice = 2_000,
                onHandMilli = 2_000,
                averageCost = 1_000,
                stockValue = 2_000,
                minStockMilli = 1_500,
            ),
        )
        val saleId = sell(line(1, 1_000))
        assertTrue(world.tables.alerts.values.single().isActive)

        registered(giveBack(saleId, ret(lineIds(saleId).single(), 1_000)))

        assertTrue(world.tables.alerts.values.none { it.isActive })
    }

    @Test
    fun aFailureInAnyWriteLeavesNothing() = runTest {
        world.addMoneyAccount(10, balance = 50_000)
        stocked(1, onHandMilli = 2_000, value = 2_000)
        val saleId = sell(line(1, 1_000), line(1, 1_000))
        val (first, second) = lineIds(saleId)
        val before = world.tables

        var failures = 0
        var result: RegisterSaleReturnResult? = null
        for (failAt in 1..40) {
            world.failAtWrite = failAt
            val attempt = runCatching {
                giveBack(
                    saleId,
                    ret(first, 1_000),
                    ret(second, 1_000, restock = false),
                    accountId = 10,
                )
            }
            if (attempt.isSuccess) {
                result = attempt.getOrThrow()
                break
            }
            assertTrue("Escritura $failAt", attempt.exceptionOrNull() is InjectedSaleFailure)
            assertEquals(before, world.tables)
            failures++
        }
        // La cabecera y sus dos líneas con sus ids, la entrada con el producto, y el dinero con su
        // cuenta.
        assertEquals(12, failures)
        registered(checkNotNull(result))
    }

    @Test
    fun aStorageFailureComesBackAsAResult() = runTest {
        stocked(1, onHandMilli = 2_000, value = 2_000)
        val saleId = sell(line(1, 1_000))
        world.failAtWrite = 1
        world.failure = { StorageFailureException(StorageFailure.DISK_FULL) }

        assertEquals(
            RegisterSaleReturnResult.StorageFailed(StorageFailure.DISK_FULL),
            giveBack(saleId, ret(lineIds(saleId).single(), 1_000)),
        )
        assertTrue(world.tables.saleReturns.isEmpty())
    }
}
