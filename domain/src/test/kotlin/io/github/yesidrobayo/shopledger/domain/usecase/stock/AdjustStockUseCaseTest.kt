package io.github.yesidrobayo.shopledger.domain.usecase.stock

import io.github.yesidrobayo.shopledger.domain.model.Limit
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.ReportPeriod
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductStockMode
import io.github.yesidrobayo.shopledger.domain.model.stock.LowStockNotificationStatus
import io.github.yesidrobayo.shopledger.domain.model.stock.StockAdjustmentReason
import io.github.yesidrobayo.shopledger.domain.model.stock.StockAdjustmentRequest
import io.github.yesidrobayo.shopledger.domain.model.stock.StockMovementType
import io.github.yesidrobayo.shopledger.domain.port.StorageFailure
import io.github.yesidrobayo.shopledger.domain.port.StorageFailureException
import io.github.yesidrobayo.shopledger.domain.rule.UnitQuantityCheck
import io.github.yesidrobayo.shopledger.domain.rule.stock.StockLedger
import io.github.yesidrobayo.shopledger.domain.rule.stock.StockShrinkage
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.CatalogFixture
import io.github.yesidrobayo.shopledger.domain.usecase.stock.StockFixture.Companion.PRODUCT_ID
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** El ajuste de stock: crea filas, nunca edita, y su valor es la merma de las reglas de negocio. */
class AdjustStockUseCaseTest {
    private val fixture = StockFixture()

    private fun change(
        reason: StockAdjustmentReason,
        units: Long,
        increase: Boolean = false,
        note: String? = null,
    ) = StockAdjustmentRequest.Change(PRODUCT_ID, reason, QuantityMilli(units), increase, note)

    private fun count(milli: Long, note: String? = null) =
        StockAdjustmentRequest.Count(PRODUCT_ID, QuantityMilli(milli), note)

    @Test
    fun `three expired units leave an adjustment worth 4065`() = runTest {
        // Q = 11000, C = 1355 y V = 14905, el ejemplo de las reglas.
        fixture.seedStock(units = 11_000L, unitCost = 1_355L)

        val result = fixture.adjust(change(StockAdjustmentReason.EXPIRED, 3_000L))

        val row = fixture.movementsOf(PRODUCT_ID).last()
        assertEquals(StockAdjustmentResult.Adjusted(row.id, QuantityMilli(8_000L)), result)
        assertEquals(StockMovementType.ADJUSTMENT, row.movement.type)
        assertEquals(StockAdjustmentReason.EXPIRED, row.reason)
        assertEquals(QuantityMilli(-3_000L), row.movement.quantity)
        assertEquals(Money(-4_065L), row.totalCost)
        assertEquals(Money(1_355L), row.unitCost)
        assertEquals(QuantityMilli(8_000L), row.balanceAfter)
        assertEquals(Money(10_840L), row.stockValueAfter)
        assertEquals(CatalogFixture.NOW, row.occurredAt)
        val product = fixture.product(PRODUCT_ID)
        assertEquals(QuantityMilli(8_000L), product.onHand)
        assertEquals(Money(1_355L), product.averageCost)
        assertEquals(Money(10_840L), product.stockValue)
    }

    @Test
    fun `the shrinkage sequence of the rules adds 4065, 6775 and 5420`() = runTest {
        fixture.seedStock(units = 11_000L, unitCost = 1_355L)
        val today = ReportPeriod.Day(fixture.movementsOf(PRODUCT_ID).first().businessDate)
        val shrinkage = { StockShrinkage.net(fixture.movementsOf(PRODUCT_ID), today) }

        fixture.adjust(change(StockAdjustmentReason.EXPIRED, 3_000L))
        assertEquals(Money(4_065L), shrinkage())

        fixture.adjust(count(6_000L))
        assertEquals(Money(-2_710L), fixture.movementsOf(PRODUCT_ID).last().totalCost)
        assertEquals(Money(6_775L), shrinkage())

        fixture.adjust(count(7_000L))
        assertEquals(Money(1_355L), fixture.movementsOf(PRODUCT_ID).last().totalCost)
        assertEquals(Money(5_420L), shrinkage())
    }

    @Test
    fun `a count of 10 over a ledger of 12 corrects minus 2`() = runTest {
        fixture.seedStock(units = 12_000L, unitCost = 1_355L)
        assertEquals(Money(16_260L), fixture.product(PRODUCT_ID).stockValue)

        fixture.adjust(count(10_000L))

        val row = fixture.movementsOf(PRODUCT_ID).last()
        assertEquals(StockAdjustmentReason.COUNT_CORRECTION, row.reason)
        assertEquals(QuantityMilli(-2_000L), row.movement.quantity)
        assertEquals(Money(-2_710L), row.totalCost)
    }

    @Test
    fun `a count equal to the ledger writes nothing`() = runTest {
        fixture.seedStock(units = 12_000L, unitCost = 1_355L)
        val before = fixture.catalog.tables

        assertEquals(StockAdjustmentResult.NoDifference, fixture.adjust(count(12_000L)))

        assertEquals(before, fixture.catalog.tables)
    }

    @Test
    fun `an adjustment can leave the stock negative`() = runTest {
        fixture.seedStock(units = 2_000L, unitCost = 1_000L)

        val result = fixture.adjust(change(StockAdjustmentReason.LOST_OR_STOLEN, 5_000L))

        assertEquals(QuantityMilli(-3_000L), (result as StockAdjustmentResult.Adjusted).onHandAfter)
        // Lo que había vale 2000, y lo que falta sale al costo promedio.
        assertEquals(Money(-5_000L), fixture.movementsOf(PRODUCT_ID).last().totalCost)
        assertEquals(Money.ZERO, fixture.product(PRODUCT_ID).stockValue)
    }

    @Test
    fun `other admits an entry, at the current cost, with its note`() = runTest {
        fixture.seedStock(units = 4_000L, unitCost = 250L)

        fixture.adjust(change(StockAdjustmentReason.OTHER, 2_000L, true, "  Apareció en bodega  "))

        val row = fixture.movementsOf(PRODUCT_ID).last()
        assertEquals(QuantityMilli(2_000L), row.movement.quantity)
        assertEquals(Money(500L), row.totalCost)
        assertEquals("Apareció en bodega", row.note)
        assertEquals(Money(1_500L), fixture.product(PRODUCT_ID).stockValue)
    }

    @Test
    fun `a blank note on another reason is no note`() = runTest {
        fixture.seedStock(units = 4_000L, unitCost = 250L)

        fixture.adjust(change(StockAdjustmentReason.DAMAGED, 1_000L, note = "   "))

        assertNull(fixture.movementsOf(PRODUCT_ID).last().note)
    }

    @Test
    fun `other without a note asks for it`() = runTest {
        fixture.seedStock(units = 4_000L, unitCost = 250L)
        val before = fixture.catalog.tables

        val result = fixture.adjust(change(StockAdjustmentReason.OTHER, 1_000L, note = " "))

        assertEquals(StockAdjustmentResult.NoteRequired, result)
        assertEquals(before, fixture.catalog.tables)
    }

    @Test
    fun `a note over 200 characters is rejected`() = runTest {
        fixture.seedStock(units = 4_000L, unitCost = 250L)

        val result =
            fixture.adjust(change(StockAdjustmentReason.DAMAGED, 1_000L, note = "a".repeat(201)))

        assertEquals(StockAdjustmentResult.NoteTooLong(200), result)
    }

    @Test
    fun `a reason that only removes never adds`() = runTest {
        fixture.seedStock(units = 4_000L, unitCost = 250L)
        for (reason in StockAdjustmentReason.entries.filterNot { it.allowsIncrease }) {
            assertEquals(
                StockAdjustmentResult.IncreaseNotAllowed,
                fixture.adjust(change(reason, 1_000L, increase = true)),
            )
        }
        assertEquals(1, fixture.movementsOf(PRODUCT_ID).size)
    }

    @Test
    fun `a count correction can also add`() = runTest {
        fixture.seedStock(units = 4_000L, unitCost = 250L)

        val result =
            fixture.adjust(change(StockAdjustmentReason.COUNT_CORRECTION, 1_000L, true))

        assertEquals(QuantityMilli(5_000L), (result as StockAdjustmentResult.Adjusted).onHandAfter)
    }

    @Test
    fun `quantities follow the base unit`() = runTest {
        fixture.seedStock(units = 4_000L, unitCost = 250L)

        assertEquals(
            StockAdjustmentResult.InvalidQuantity(UnitQuantityCheck.FractionNotAllowed),
            fixture.adjust(change(StockAdjustmentReason.DAMAGED, 1_500L)),
        )
        assertEquals(
            StockAdjustmentResult.InvalidQuantity(UnitQuantityCheck.NotPositive),
            fixture.adjust(change(StockAdjustmentReason.DAMAGED, 0L)),
        )
        assertEquals(
            StockAdjustmentResult.InvalidQuantity(UnitQuantityCheck.FractionNotAllowed),
            fixture.adjust(count(3_500L)),
        )
        assertEquals(
            StockAdjustmentResult.InvalidQuantity(UnitQuantityCheck.NotPositive),
            fixture.adjust(count(-1_000L)),
        )
        assertEquals(1, fixture.movementsOf(PRODUCT_ID).size)
    }

    @Test
    fun `a weighed product adjusts by thousandths`() = runTest {
        fixture.seedStock(
            units = 2_500L,
            unitCost = 30_000L,
            baseUnitId = CatalogFixture.KILOGRAM_ID,
        )

        val result = fixture.adjust(count(2_250L))

        assertEquals(QuantityMilli(2_250L), (result as StockAdjustmentResult.Adjusted).onHandAfter)
        assertEquals(Money(-7_500L), fixture.movementsOf(PRODUCT_ID).last().totalCost)
    }

    @Test
    fun `a count to zero empties the stock and its value`() = runTest {
        fixture.seedStock(units = 3_000L, unitCost = 1_000L)

        fixture.adjust(count(0L))

        assertEquals(QuantityMilli.ZERO, fixture.product(PRODUCT_ID).onHand)
        assertEquals(Money.ZERO, fixture.product(PRODUCT_ID).stockValue)
        assertEquals(Money(-3_000L), fixture.movementsOf(PRODUCT_ID).last().totalCost)
    }

    @Test
    fun `limits protect the stock, the line and the count`() = runTest {
        fixture.seedStock(units = 1_000L, unitCost = 1L)

        assertEquals(
            StockAdjustmentResult.AboveLimit(Limit.ON_HAND_MILLI),
            fixture.adjust(count(Limit.ON_HAND_MILLI.max + 1_000L)),
        )
        assertEquals(
            StockAdjustmentResult.AboveLimit(Limit.LINE_QUANTITY_MILLI),
            fixture.adjust(
                change(StockAdjustmentReason.DAMAGED, Limit.LINE_QUANTITY_MILLI.max + 1_000L),
            ),
        )
        // El conteo cabe, pero la entrada sumada al saldo pasaría el tope del stock.
        fixture.adjust(count(Limit.ON_HAND_MILLI.max))
        assertEquals(
            StockAdjustmentResult.AboveLimit(Limit.ON_HAND_MILLI),
            fixture.adjust(change(StockAdjustmentReason.OTHER, 1_000L, true, "Sobra")),
        )
    }

    @Test
    fun `a value above its limit is rejected`() = runTest {
        // Una unidad que ya vale el tope del stock: contar una más lo pasaría.
        fixture.catalog.seedProduct(id = PRODUCT_ID, name = "Arroz", onHand = QuantityMilli(1_000L))
        fixture.seedValue(PRODUCT_ID, QuantityMilli(1_000L), Money(Limit.STOCK_VALUE.max))

        val result = fixture.adjust(count(2_000L))

        assertEquals(StockAdjustmentResult.AboveLimit(Limit.STOCK_VALUE), result)
    }

    @Test
    fun `archived, untracked and missing products are rejected`() = runTest {
        fixture.catalog.seedProduct(id = 2L, name = "Archivado", archived = true)
        fixture.catalog.seedProduct(
            id = 3L,
            name = "Servicio",
            stockMode = ProductStockMode.UNTRACKED,
        )
        fun dropped(id: Long) =
            StockAdjustmentRequest.Change(id, StockAdjustmentReason.DAMAGED, QuantityMilli(1_000L))

        assertEquals(StockAdjustmentResult.ProductArchived, fixture.adjust(dropped(2L)))
        assertEquals(StockAdjustmentResult.NotTracked, fixture.adjust(dropped(3L)))
        assertEquals(StockAdjustmentResult.NotFound, fixture.adjust(dropped(9L)))
    }

    @Test
    fun `an unknown zone writes nothing`() = runTest {
        val unknown = StockFixture(zoneId = "Lugar/Inventado")
        unknown.catalog.seedProduct(id = PRODUCT_ID, name = "Arroz", onHand = QuantityMilli(5_000L))

        val result = unknown.adjust(change(StockAdjustmentReason.DAMAGED, 1_000L))

        assertEquals(StockAdjustmentResult.UnknownTimeZone, result)
        assertTrue(unknown.movementsOf(PRODUCT_ID).isEmpty())
    }

    @Test
    fun `an adjustment with value waits for the currency, and one without value does not`() =
        runTest {
            val unconfirmed = StockFixture(currencyConfirmed = false)
            val catalog = unconfirmed.catalog
            catalog.seedProduct(id = PRODUCT_ID, name = "Arroz", onHand = QuantityMilli(5_000L))
            unconfirmed.seedValue(PRODUCT_ID, QuantityMilli(5_000L), Money(500L))

            assertEquals(
                StockAdjustmentResult.CurrencyUnconfirmed,
                unconfirmed.adjust(change(StockAdjustmentReason.DAMAGED, 1_000L)),
            )
            catalog.seedProduct(id = 2L, name = "Sin costo", onHand = QuantityMilli(5_000L))
            val free = StockAdjustmentRequest.Change(
                2L,
                StockAdjustmentReason.DAMAGED,
                QuantityMilli(1_000L),
            )
            assertTrue(unconfirmed.adjust(free) is StockAdjustmentResult.Adjusted)
            assertEquals(Money.ZERO, unconfirmed.movementsOf(2L).single().totalCost)
        }

    @Test
    fun `crossing the minimum creates one alert pending its notice`() = runTest {
        fixture.seedStock(units = 6_000L, unitCost = 100L, minStock = 5_000L)

        fixture.adjust(change(StockAdjustmentReason.DAMAGED, 1_000L))
        fixture.adjust(change(StockAdjustmentReason.DAMAGED, 1_000L))

        val alert = fixture.alertsOf(PRODUCT_ID).single()
        assertEquals(LowStockNotificationStatus.PENDING, alert.notificationStatus)
        assertEquals(QuantityMilli(5_000L), alert.onHandAtTrigger)
        assertEquals(QuantityMilli(5_000L), alert.minStockAtTrigger)
        assertTrue(alert.isActive)
    }

    @Test
    fun `rising above the minimum resolves the alert`() = runTest {
        fixture.seedStock(units = 6_000L, unitCost = 100L, minStock = 5_000L)
        fixture.adjust(change(StockAdjustmentReason.DAMAGED, 2_000L))

        fixture.adjust(count(5_000L))
        assertTrue(fixture.alertsOf(PRODUCT_ID).single().isActive)
        fixture.adjust(count(6_000L))

        assertEquals(CatalogFixture.NOW, fixture.alertsOf(PRODUCT_ID).single().resolvedAt)
    }

    @Test
    fun `the sum of the ledger is the stock after every adjustment`() = runTest {
        fixture.seedStock(units = 9_000L, unitCost = 333L)

        fixture.adjust(change(StockAdjustmentReason.GIFT_OR_SAMPLE, 1_000L))
        fixture.adjust(change(StockAdjustmentReason.OWN_USE, 2_000L))
        fixture.adjust(count(11_000L))
        fixture.adjust(change(StockAdjustmentReason.OTHER, 20_000L, note = "Inventado"))

        val movements = fixture.movementsOf(PRODUCT_ID)
        assertEquals(
            StockLedger.onHandByProduct(movements.map { it.movement })[PRODUCT_ID],
            fixture.product(PRODUCT_ID).onHand,
        )
        assertEquals(QuantityMilli(-9_000L), fixture.product(PRODUCT_ID).onHand)
    }

    @Test
    fun `each write that fails leaves nothing`() = runTest {
        CatalogFixture.assertEachWriteFailureLeavesNothing(
            prepare = {
                seedProduct(
                    id = PRODUCT_ID,
                    name = "Arroz",
                    initialCost = Money(100L),
                    onHand = QuantityMilli(6_000L),
                    minStock = QuantityMilli(5_000L),
                )
            },
            run = {
                val stock = StockFixture(this)
                stock.adjust(change(StockAdjustmentReason.DAMAGED, 2_000L))
            },
        )
    }

    @Test
    fun `a storage failure comes back as a result`() = runTest {
        fixture.seedStock(units = 6_000L, unitCost = 100L)
        fixture.catalog.database.failAtWrite = 1
        fixture.catalog.database.failure = { StorageFailureException(StorageFailure.DISK_FULL) }

        val result = fixture.adjust(change(StockAdjustmentReason.DAMAGED, 1_000L))

        assertEquals(StockAdjustmentResult.StorageFailed(StorageFailure.DISK_FULL), result)
    }
}
