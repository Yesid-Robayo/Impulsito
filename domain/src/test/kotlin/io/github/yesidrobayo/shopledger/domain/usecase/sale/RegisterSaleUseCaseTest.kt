package io.github.yesidrobayo.shopledger.domain.usecase.sale

import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductStockMode
import io.github.yesidrobayo.shopledger.domain.model.catalog.SystemRow
import io.github.yesidrobayo.shopledger.domain.model.payment.PaymentMethodKind
import io.github.yesidrobayo.shopledger.domain.model.sale.LineDiscount
import io.github.yesidrobayo.shopledger.domain.model.sale.LineStockEffect
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleLineRequest
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleRequest
import io.github.yesidrobayo.shopledger.domain.model.stock.StockMovementType
import io.github.yesidrobayo.shopledger.domain.port.StorageFailure
import io.github.yesidrobayo.shopledger.domain.port.StorageFailureException
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Confirmar una venta: los ejemplos de las reglas de negocio, escritos a mano, y que cualquier
 * rechazo o falla no deje nada guardado.
 */
class RegisterSaleUseCaseTest {
    private val world = SaleWorld().apply { addMethod(method()) }

    private suspend fun sell(vararg lines: SaleLineRequest, date: BusinessDate? = null): Long {
        val result = world.registerSale()(
            SaleRequest(lines.toList(), SaleWorld.CASH, date = date),
        )
        return (result as RegisterSaleResult.Registered).saleId
    }

    private fun line(
        productId: Long,
        milli: Long,
        unitPrice: Long? = null,
        discount: LineDiscount = LineDiscount.None,
    ) = SaleLineRequest(productId, QuantityMilli(milli), unitPrice?.let(::Money), discount)

    private suspend fun rejectionOf(request: SaleRequest): RegisterSaleResult {
        val result = world.registerSale()(request)
        assertEquals("Un rechazo no confirma nada", 0, world.commits)
        assertTrue(world.tables.sales.isEmpty())
        assertTrue(world.tables.movements.isEmpty())
        assertTrue(world.tables.payments.isEmpty())
        return result
    }

    private suspend fun lineRejectionOf(vararg lines: SaleLineRequest): RegisterSaleResult =
        rejectionOf(SaleRequest(lines.toList(), SaleWorld.CASH))

    @Test
    fun theWorkedExampleOfTheSaleTotalGivesItsTotalsLinesAndMovements() = runTest {
        // Cada producto declara su valor de stock redondeado desde su saldo y su costo.
        world.addProduct(
            product(
                1,
                salePrice = 3500,
                onHandMilli = 10_000,
                averageCost = 1355,
                stockValue = 13_550,
            ),
        )
        world.addProduct(
            product(
                2,
                salePrice = 18_500,
                unitId = SaleWorld.KILOGRAM,
                onHandMilli = 5_000,
                averageCost = 12_000,
                stockValue = 60_000,
            ),
        )
        world.addProduct(
            product(
                3,
                salePrice = 20_000,
                onHandMilli = 3_000,
                averageCost = 11_111,
                stockValue = 33_333,
            ),
        )

        val saleId = sell(
            line(1, 2_000),
            line(2, 333, discount = LineDiscount.Amount(Money(161))),
            line(3, 1_000, discount = LineDiscount.Percent(BasisPoints(1_000))),
        )

        val sale = world.tables.sales.getValue(saleId).content
        assertEquals(Money(31_000), sale.total)
        assertEquals(Money(31_000), sale.netTotal)
        assertEquals(Money.ZERO, sale.taxTotal)
        assertEquals(Money(17_817), sale.totalCost)
        assertEquals(Money(31_000), sale.amountDue)
        val lines = world.tables.lines.values.sortedBy { it.id }.map { it.content }
        assertEquals(listOf(7_000L, 6_000L, 18_000L), lines.map { it.lineTotal.minor })
        assertEquals(listOf(0L, 161L, 2_000L), lines.map { it.lineDiscount.minor })
        assertEquals(listOf(1_355L, 12_000L, 11_111L), lines.map { it.unitCost.minor })
        assertEquals(listOf(2_710L, 3_996L, 11_111L), lines.map { it.lineCost.minor })
        val movements = world.tables.movements.values.sortedBy { it.id }
        assertEquals(3, movements.size)
        assertTrue(movements.all { it.movement.type == StockMovementType.SALE })
        assertEquals(listOf(-2_000L, -333L, -1_000L), movements.map { it.movement.quantity.milli })
        assertEquals(listOf(-2_710L, -3_996L, -11_111L), movements.map { it.totalCost.minor })
        assertEquals(1, world.writeCalls)
        assertEquals(1, world.commits)
        assertEquals(1L, world.tables.changeSeq)
    }

    @Test
    fun sellingWithoutStockLeavesTheBalanceNegativeAndNeverBlocks() = runTest {
        world.addProduct(
            product(1, salePrice = 1_000, onHandMilli = 1_000, averageCost = 400, stockValue = 400),
        )

        sell(line(1, 3_000))

        val after = world.tables.products.getValue(1)
        assertEquals(QuantityMilli(-2_000), after.onHand)
        assertEquals(Money(400), after.averageCost)
        assertEquals(Money.ZERO, after.stockValue)
        val movement = world.tables.movements.values.single()
        assertEquals(QuantityMilli(-2_000), movement.balanceAfter)
        // Lo que había se lleva su valor, y lo que falta sale al costo promedio.
        assertEquals(Money(-1_200), movement.totalCost)
        assertEquals(Money(1_200), world.tables.lines.values.single().content.lineCost)
    }

    @Test
    fun aRepeatedProductChainsItsCostFromTheLineBefore() = runTest {
        world.addProduct(
            product(1, salePrice = 500, onHandMilli = 3_000, averageCost = 333, stockValue = 1_000),
        )

        sell(line(1, 1_000), line(1, 2_000))

        val costs = world.tables.lines.values.sortedBy { it.id }.map { it.content.lineCost.minor }
        // Vaciar el stock se lleva exactamente su valor, aunque el promedio redondee.
        assertEquals(listOf(333L, 667L), costs)
        val after = world.tables.products.getValue(1)
        assertEquals(QuantityMilli.ZERO, after.onHand)
        assertEquals(Money.ZERO, after.stockValue)
        assertEquals(
            listOf(2_000L, 0L),
            world.tables.movements.values.sortedBy { it.id }.map { it.balanceAfter.milli },
        )
    }

    @Test
    fun aServiceWritesNoMovementAndCostsItsFixedCost() = runTest {
        world.addProduct(
            product(
                1,
                salePrice = 5_000,
                stockMode = ProductStockMode.UNTRACKED,
                fixedUnitCost = 1_250,
            ),
        )

        sell(line(1, 2_000))

        val saleLine = world.tables.lines.values.single().content
        assertEquals(LineStockEffect.NONE, saleLine.stockEffect)
        assertEquals(Money(1_250), saleLine.unitCost)
        assertEquals(Money(2_500), saleLine.lineCost)
        assertTrue(world.tables.movements.isEmpty())
        assertEquals(QuantityMilli.ZERO, world.tables.products.getValue(1).onHand)
    }

    @Test
    fun theLineFreezesItsPriceNameUnitAndReplacementCost() = runTest {
        world.addProduct(
            product(
                1,
                name = "Invented soap",
                salePrice = 3_500,
                onHandMilli = 5_000,
                averageCost = 1_000,
                stockValue = 5_000,
                replacementCost = 1_500,
            ),
        )
        world.addProduct(
            product(2, salePrice = 800, onHandMilli = 1_000, averageCost = 300, stockValue = 300),
        )

        sell(line(1, 2_000, unitPrice = 3_000), line(2, 1_000))

        val lines = world.tables.lines.values.sortedBy { it.id }.map { it.content }
        assertEquals(Money(3_000), lines[0].unitPrice)
        assertEquals(Money(6_000), lines[0].lineTotal)
        assertEquals("Invented soap", lines[0].productNameSnapshot)
        assertEquals(SaleWorld.UNIT, lines[0].unitId)
        assertEquals(QuantityMilli(1_000), lines[0].unitFactor)
        assertEquals(LineStockEffect.DIRECT, lines[0].stockEffect)
        assertEquals(SaleWorld.DEFAULT_PRICE_LIST, lines[0].priceListId)
        assertEquals(Money(1_500), lines[0].unitReplacementCost)
        assertEquals(Money(3_000), lines[0].lineReplacementCost)
        // Sin costo de reposición, la reposición es el costo que congela la línea.
        assertEquals(Money(300), lines[1].unitReplacementCost)
        assertEquals(Money(300), lines[1].lineReplacementCost)
        val sale = world.tables.sales.values.single().content
        assertEquals(Money(3_300), sale.totalReplacementCost)
        assertEquals(SaleWorld.MAIN_LOCATION, sale.locationId)
        // Cambiar el precio en la línea no cambia el precio del producto.
        assertEquals(Money(3_500), world.tables.products.getValue(1).salePrice)
    }

    @Test
    fun theSaleWritesItsSinglePaymentWithTheFrozenMethod() = runTest {
        world.addMethod(
            method(
                id = 7,
                kind = PaymentMethodKind.CARD,
                systemKey = null,
                name = "Invented card",
                feeBp = 299,
                feeFixed = 15,
                settlementDays = 2,
                moneyAccountId = 4,
            ),
        )
        world.addProduct(
            product(
                1,
                salePrice = 10_000,
                onHandMilli = 1_000,
                averageCost = 100,
                stockValue = 100,
            ),
        )

        val result = world.registerSale()(SaleRequest(listOf(line(1, 1_000)), paymentMethodId = 7))

        val saleId = (result as RegisterSaleResult.Registered).saleId
        val payment = world.tables.payments.values.single()
        assertEquals(saleId, payment.saleId)
        assertEquals(7L, payment.content.paymentMethodId)
        assertEquals("Invented card", payment.content.methodNameSnapshot)
        assertEquals(PaymentMethodKind.CARD, payment.content.methodKind)
        assertEquals(Money(10_000), payment.content.amount)
        // La primera fila de la tabla de comisiones: 10000 al 2,99 % más 15 son 314.
        assertEquals(Money(314), payment.content.feeAmount)
        assertEquals(BusinessDate(LocalDate(2026, 11, 4)), payment.content.settlementDate)
        assertEquals(4L, payment.content.moneyAccountId)
    }

    @Test
    fun anUnrenamedInitialMethodFreezesTheNameOfItsKey() = runTest {
        world.addProduct(product(1, salePrice = 0, stockMode = ProductStockMode.UNTRACKED))

        sell(line(1, 1_000))

        val payment = world.tables.payments.values.single().content
        assertEquals("name of cash", payment.methodNameSnapshot)
        assertEquals(Money.ZERO, payment.amount)
        assertEquals(Money.ZERO, payment.feeAmount)
        assertNull(payment.moneyAccountId)
        // Una venta de total cero también guarda su pago.
        assertEquals(Money.ZERO, world.tables.sales.values.single().content.total)
    }

    @Test
    fun aMethodNameLongerThanTheSnapshotIsCut() = runTest {
        world.addMethod(
            method(id = 8, kind = PaymentMethodKind.OTHER, systemKey = null, name = "x".repeat(60)),
        )
        world.addProduct(product(1, salePrice = 100, stockMode = ProductStockMode.UNTRACKED))

        world.registerSale()(SaleRequest(listOf(line(1, 1_000)), paymentMethodId = 8))

        assertEquals(
            "x".repeat(40),
            world.tables.payments.values.single().content.methodNameSnapshot,
        )
    }

    @Test
    fun todayKeepsTheCurrentInstantAndAPastDateGoesAtNoonInTheBusinessZone() = runTest {
        world.addProduct(
            product(1, salePrice = 100, onHandMilli = 5_000, averageCost = 10, stockValue = 50),
        )
        val now = Instant.fromEpochMilliseconds(SaleWorld.NOW_MILLIS)
        val today = BusinessDate(LocalDate(2026, 11, 2))
        val yesterday = BusinessDate(LocalDate(2026, 11, 1))

        val todayId = sell(line(1, 1_000), date = today)
        val pastId = sell(line(1, 1_000), date = yesterday)

        val todaySale = world.tables.sales.getValue(todayId).content
        assertEquals(now, todaySale.occurredAt)
        assertEquals(today, todaySale.businessDate)
        val pastSale = world.tables.sales.getValue(pastId).content
        // Las 12:00 del 2026-11-01 cinco horas detrás de UTC son las 17:00 UTC.
        assertEquals(Instant.parse("2026-11-01T17:00:00Z"), pastSale.occurredAt)
        assertEquals(yesterday, pastSale.businessDate)
        assertEquals(now, pastSale.createdAt)
        val pastMovement = world.tables.movements.values.maxBy { it.id }
        assertEquals(yesterday, pastMovement.businessDate)
        assertEquals(pastSale.occurredAt, pastMovement.occurredAt)
        assertEquals(now, pastMovement.createdAt)
    }

    @Test
    fun aBlankNoteIsNotSavedAndAWrittenOneIs() = runTest {
        world.addProduct(product(1, salePrice = 100, stockMode = ProductStockMode.UNTRACKED))

        val blank =
            world.registerSale()(SaleRequest(listOf(line(1, 1_000)), SaleWorld.CASH, note = "  "))
        val written =
            world.registerSale()(
                SaleRequest(listOf(line(1, 1_000)), SaleWorld.CASH, note = "Invented note"),
            )

        val blankId = (blank as RegisterSaleResult.Registered).saleId
        val writtenId = (written as RegisterSaleResult.Registered).saleId
        assertNull(world.tables.sales.getValue(blankId).content.note)
        assertEquals("Invented note", world.tables.sales.getValue(writtenId).content.note)
    }

    @Test
    fun theShapeOfTheSaleIsCheckedFirst() = runTest {
        assertEquals(
            RegisterSaleResult.EmptySale,
            rejectionOf(SaleRequest(emptyList(), SaleWorld.CASH)),
        )
        assertEquals(
            RegisterSaleResult.TooManyLines,
            rejectionOf(SaleRequest(List(201) { line(1, 1_000) }, SaleWorld.CASH)),
        )
        assertEquals(
            RegisterSaleResult.NoteTooLong,
            rejectionOf(
                SaleRequest(listOf(line(1, 1_000)), SaleWorld.CASH, note = "n".repeat(201)),
            ),
        )
    }

    @Test
    fun withoutAConfirmedCurrencyOrAKnownZoneNothingIsSold() = runTest {
        val unconfirmed = SaleWorld(currencyConfirmed = false).apply { addMethod(method()) }
        val unknownZone = SaleWorld(zoneId = "Invented/Zone").apply { addMethod(method()) }
        for (target in listOf(unconfirmed, unknownZone)) {
            target.addProduct(product(1, salePrice = 100, stockMode = ProductStockMode.UNTRACKED))
        }
        val request = SaleRequest(listOf(line(1, 1_000)), SaleWorld.CASH)

        assertEquals(RegisterSaleResult.CurrencyNotConfirmed, unconfirmed.registerSale()(request))
        assertEquals(
            RegisterSaleResult.UnknownTimeZone("Invented/Zone"),
            unknownZone.registerSale()(request),
        )
        assertEquals(0, unconfirmed.commits + unknownZone.commits)
        assertTrue(unconfirmed.tables.sales.isEmpty())
    }

    @Test
    fun theFirstSaleLocksTheCurrencyAndARejectedOneDoesNot() = runTest {
        val first = SaleWorld(currencyLocked = false).apply { addMethod(method()) }
        first.addProduct(product(1, salePrice = 100, stockMode = ProductStockMode.UNTRACKED))
        val tomorrow = BusinessDate(LocalDate(2026, 11, 3))

        val rejected = first.registerSale()(
            SaleRequest(listOf(line(1, 1_000)), SaleWorld.CASH, date = tomorrow),
        )
        assertEquals(RegisterSaleResult.FutureDate, rejected)
        assertTrue("Un rechazo no bloquea la moneda", first.settings.lockCalls.isEmpty())

        val sold = first.registerSale()(SaleRequest(listOf(line(1, 1_000)), SaleWorld.CASH))

        assertTrue(sold is RegisterSaleResult.Registered)
        val now = Instant.fromEpochMilliseconds(SaleWorld.NOW_MILLIS)
        assertEquals(listOf(now), first.settings.lockCalls)
        assertEquals(now, first.settings.settings.value.regional.currency.lockedAt)
    }

    @Test
    fun aFutureDateIsRejected() = runTest {
        world.addProduct(product(1, salePrice = 100, stockMode = ProductStockMode.UNTRACKED))
        val tomorrow = BusinessDate(LocalDate(2026, 11, 3))

        assertEquals(
            RegisterSaleResult.FutureDate,
            rejectionOf(SaleRequest(listOf(line(1, 1_000)), SaleWorld.CASH, date = tomorrow)),
        )
    }

    @Test
    fun aMissingArchivedOrCreditMethodIsRejected() = runTest {
        world.addProduct(product(1, salePrice = 100, stockMode = ProductStockMode.UNTRACKED))
        world.addMethod(method(id = 2, archived = true))
        world.addMethod(method(id = 3, kind = PaymentMethodKind.ON_ACCOUNT))

        assertEquals(
            RegisterSaleResult.PaymentMethodNotFound,
            rejectionOf(SaleRequest(listOf(line(1, 1_000)), paymentMethodId = 99)),
        )
        assertEquals(
            RegisterSaleResult.PaymentMethodArchived,
            rejectionOf(SaleRequest(listOf(line(1, 1_000)), paymentMethodId = 2)),
        )
        assertEquals(
            RegisterSaleResult.PaymentNeedsCustomer,
            rejectionOf(SaleRequest(listOf(line(1, 1_000)), paymentMethodId = 3)),
        )
    }

    @Test
    fun eachProductThatCannotBeSoldNamesItsLine() = runTest {
        world.addProduct(product(1, salePrice = 100, stockMode = ProductStockMode.UNTRACKED))
        world.addProduct(product(2, archived = true))
        world.addProduct(product(3, stockMode = ProductStockMode.COMPONENTS))
        world.addProduct(product(4, tracksLots = true))

        assertEquals(
            rejected(1, LineRejection.PRODUCT_NOT_FOUND),
            lineRejectionOf(line(1, 1_000), line(99, 1_000)),
        )
        assertEquals(
            rejected(1, LineRejection.PRODUCT_ARCHIVED),
            lineRejectionOf(line(1, 1_000), line(2, 1_000)),
        )
        assertEquals(
            rejected(0, LineRejection.COMPONENTS_NOT_SUPPORTED),
            lineRejectionOf(line(3, 1_000)),
        )
        assertEquals(rejected(0, LineRejection.LOTS_NOT_SUPPORTED), lineRejectionOf(line(4, 1_000)))
    }

    @Test
    fun theQuantityFollowsItsUnitAndTheLineLimit() = runTest {
        world.addProduct(product(1, salePrice = 100, stockMode = ProductStockMode.UNTRACKED))
        world.addProduct(
            product(
                2,
                salePrice = 100,
                stockMode = ProductStockMode.UNTRACKED,
                unitId = SaleWorld.KILOGRAM,
            ),
        )

        assertEquals(rejected(0, LineRejection.INVALID_QUANTITY), lineRejectionOf(line(1, 0)))
        assertEquals(rejected(0, LineRejection.INVALID_QUANTITY), lineRejectionOf(line(2, -1)))
        assertEquals(
            rejected(0, LineRejection.INVALID_QUANTITY),
            lineRejectionOf(line(2, 1_000_000_001)),
        )
        assertEquals(
            rejected(0, LineRejection.FRACTION_NOT_ALLOWED),
            lineRejectionOf(line(1, 1_500)),
        )
        // Por peso sí admite milésimas.
        sell(line(2, 1))
        assertEquals(1, world.commits)
    }

    @Test
    fun aPriceOrDiscountOutOfRangeIsRejected() = runTest {
        world.addProduct(product(1, salePrice = 1_000, stockMode = ProductStockMode.UNTRACKED))

        assertEquals(
            rejected(0, LineRejection.INVALID_PRICE),
            lineRejectionOf(line(1, 1_000, unitPrice = -1)),
        )
        assertEquals(
            rejected(0, LineRejection.INVALID_PRICE),
            lineRejectionOf(line(1, 1_000, unitPrice = 1_000_000_000_001)),
        )
        assertEquals(
            rejected(0, LineRejection.INVALID_DISCOUNT),
            lineRejectionOf(line(1, 1_000, discount = LineDiscount.Amount(Money(1_001)))),
        )
        assertEquals(
            rejected(0, LineRejection.INVALID_DISCOUNT),
            lineRejectionOf(line(1, 1_000, discount = LineDiscount.Percent(BasisPoints(10_001)))),
        )
    }

    @Test
    fun aLineCostAboveTheLineLimitIsRejected() = runTest {
        // Un valor de stock por encima del tope de una línea, pero válido como valor del stock.
        world.addProduct(
            product(
                1,
                salePrice = 100,
                onHandMilli = 1_000,
                averageCost = 1,
                stockValue = 2_000_000_000_000_000,
            ),
        )

        assertEquals(rejected(0, LineRejection.AMOUNT_TOO_LARGE), lineRejectionOf(line(1, 1_000)))
    }

    @Test
    fun aReplacementCostAboveTheLineLimitIsRejected() = runTest {
        // El costo de reposición máximo por un millón de kilogramos pasa del tope de una línea.
        world.addProduct(
            product(
                1,
                salePrice = 100,
                stockMode = ProductStockMode.UNTRACKED,
                unitId = SaleWorld.KILOGRAM,
                replacementCost = 1_000_000_000_000,
            ),
        )

        assertEquals(
            rejected(0, LineRejection.AMOUNT_TOO_LARGE),
            lineRejectionOf(line(1, 1_000_000_000)),
        )
    }

    @Test
    fun aGrossAboveTheLineLimitIsRejected() = runTest {
        world.addProduct(
            product(1, stockMode = ProductStockMode.UNTRACKED, unitId = SaleWorld.KILOGRAM),
        )

        assertEquals(
            rejected(0, LineRejection.AMOUNT_TOO_LARGE),
            lineRejectionOf(line(1, 1_000_000_000, unitPrice = 1_000_000_001)),
        )
    }

    @Test
    fun totalsAboveTheDocumentLimitAreRejected() = runTest {
        world.addProduct(
            product(1, stockMode = ProductStockMode.UNTRACKED, unitId = SaleWorld.KILOGRAM),
        )
        world.addProduct(
            product(
                2,
                salePrice = 100,
                stockMode = ProductStockMode.UNTRACKED,
                fixedUnitCost = 600_000_000,
            ),
        )
        // Cada línea llega justo al tope de una línea, y la venta lo pasa.
        val maxLine = line(1, 1_000_000_000, unitPrice = 1_000_000_000)

        assertEquals(RegisterSaleResult.SaleTotalTooLarge, lineRejectionOf(maxLine, maxLine))
        // Dos costos de línea dentro del tope que juntos lo pasan.
        assertEquals(
            RegisterSaleResult.SaleTotalTooLarge,
            lineRejectionOf(line(2, 1_000_000_000), line(2, 1_000_000_000)),
        )
    }

    @Test
    fun aFeeAboveTheDocumentLimitIsRejected() = runTest {
        world.addMethod(
            method(
                id = 5,
                kind = PaymentMethodKind.CARD,
                feeBp = 10_000,
                feeFixed = 1_000_000_000_000_000,
            ),
        )
        world.addProduct(product(1, salePrice = 100, stockMode = ProductStockMode.UNTRACKED))

        assertEquals(
            RegisterSaleResult.SaleTotalTooLarge,
            rejectionOf(SaleRequest(listOf(line(1, 1_000)), paymentMethodId = 5)),
        )
    }

    @Test
    fun aFailureAtAnyWriteLeavesNothing() = runTest {
        world.addProduct(
            product(1, salePrice = 500, onHandMilli = 3_000, averageCost = 100, stockValue = 300),
        )
        world.addProduct(product(2, salePrice = 100, stockMode = ProductStockMode.UNTRACKED))
        val request = SaleRequest(listOf(line(1, 1_000), line(2, 1_000)), SaleWorld.CASH)
        // Cabecera, dos líneas, una fila con su saldo y el pago: cada uno con su id.
        val writes = 11
        for (failAt in 1..writes) {
            world.failAtWrite = failAt
            val failed = runCatching { world.registerSale()(request) }
            assertTrue("Escritura $failAt", failed.exceptionOrNull() is InjectedSaleFailure)
            assertTrue(world.tables.sales.isEmpty())
            assertTrue(world.tables.lines.isEmpty())
            assertTrue(world.tables.movements.isEmpty())
            assertTrue(world.tables.payments.isEmpty())
            assertEquals(QuantityMilli(3_000), world.tables.products.getValue(1).onHand)
            assertEquals(0L, world.tables.changeSeq)
        }
        world.failAtWrite = null
        sell(line(1, 1_000), line(2, 1_000))
        // Los ids de lo que se deshizo no se consumieron.
        assertEquals(setOf(1L), world.tables.sales.keys)
        assertEquals(1L, world.tables.changeSeq)
    }

    @Test
    fun aMissingInitialRowIsADamagedDatabase() = runTest {
        world.addProduct(product(1, salePrice = 100, stockMode = ProductStockMode.UNTRACKED))
        world.systemRows = world.systemRows - SystemRow.MAIN_LOCATION

        assertEquals(
            RegisterSaleResult.StorageFailed(StorageFailure.INVARIANT_VIOLATION),
            world.registerSale()(SaleRequest(listOf(line(1, 1_000)), SaleWorld.CASH)),
        )
        world.systemRows = world.systemRows + (SystemRow.MAIN_LOCATION to SaleWorld.MAIN_LOCATION)
        world.units = emptyMap()
        assertEquals(
            RegisterSaleResult.StorageFailed(StorageFailure.INVARIANT_VIOLATION),
            world.registerSale()(SaleRequest(listOf(line(1, 1_000)), SaleWorld.CASH)),
        )
        assertTrue(world.tables.sales.isEmpty())
    }

    @Test
    fun aStorageFailureComesBackAsAResult() = runTest {
        world.addProduct(product(1, salePrice = 100, stockMode = ProductStockMode.UNTRACKED))
        world.failAtWrite = 1
        world.failure = { StorageFailureException(StorageFailure.DISK_FULL) }

        val result = world.registerSale()(SaleRequest(listOf(line(1, 1_000)), SaleWorld.CASH))

        assertEquals(RegisterSaleResult.StorageFailed(StorageFailure.DISK_FULL), result)
        assertTrue(world.tables.sales.isEmpty())
    }

    private fun rejected(index: Int, reason: LineRejection) =
        RegisterSaleResult.LineRejected(index, reason)
}
