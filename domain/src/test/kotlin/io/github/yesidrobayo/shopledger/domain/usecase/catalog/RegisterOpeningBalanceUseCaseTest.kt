package io.github.yesidrobayo.shopledger.domain.usecase.catalog

import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.Limit
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductForm
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductFormIssue
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductStockMode
import io.github.yesidrobayo.shopledger.domain.model.stock.LowStockAlert
import io.github.yesidrobayo.shopledger.domain.model.stock.LowStockNotificationStatus
import io.github.yesidrobayo.shopledger.domain.model.stock.StockKey
import io.github.yesidrobayo.shopledger.domain.model.stock.StockMovementDraft
import io.github.yesidrobayo.shopledger.domain.model.stock.StockMovementRecord
import io.github.yesidrobayo.shopledger.domain.model.stock.StockMovementSource
import io.github.yesidrobayo.shopledger.domain.model.stock.StockMovementType
import io.github.yesidrobayo.shopledger.domain.port.StorageFailure
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.CatalogFixture.Companion.EARLIER
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.CatalogFixture.Companion.KILOGRAM_ID
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.CatalogFixture.Companion.MAIN_LOCATION_ID
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.CatalogFixture.Companion.NOW
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** El inventario inicial desde el ajuste de stock, sobre un producto que ya existe. */
class RegisterOpeningBalanceUseCaseTest {
    private val fixture = CatalogFixture()

    private fun record(id: Long, quantity: Long, reverses: Long?, type: StockMovementType) =
        StockMovementRecord(
            movement = StockMovementDraft(
                key = StockKey(1L, MAIN_LOCATION_ID, lotId = null),
                type = type,
                quantity = QuantityMilli(quantity),
                reversesMovementId = reverses,
            ).recordedAs(id),
            reason = null,
            unitCost = Money.ZERO,
            totalCost = Money.ZERO,
            balanceAfter = QuantityMilli.ZERO,
            averageCostAfter = Money.ZERO,
            stockValueAfter = Money.ZERO,
            source = StockMovementSource.NONE,
            note = null,
            occurredAt = EARLIER,
            businessDate = requireNotNull(BusinessDate.parse("2023-11-13")),
            createdAt = EARLIER,
        )

    @Test
    fun `24 units at 1200 enter as the opening balance`() = runTest {
        fixture.seedProduct(id = 1L, name = "Jabón Líquido", initialCost = Money(1_200L))

        val result = fixture.opening(1L, QuantityMilli(24_000L), Money(1_200L))

        val movement = fixture.movementsOf(1L).single()
        assertEquals(OpeningBalanceResult.Registered(movement.id), result)
        assertEquals(StockMovementType.OPENING_BALANCE, movement.movement.type)
        assertEquals(Money(28_800L), movement.totalCost)
        val product = fixture.product(1L)
        assertEquals(QuantityMilli(24_000L), product.onHand)
        assertEquals(Money(1_200L), product.averageCost)
        assertEquals(Money(28_800L), product.stockValue)
        assertEquals(NOW, movement.occurredAt)
        assertTrue(fixture.tables.purchases.isEmpty())
    }

    @Test
    fun `a ledger whose rows were all voided takes an opening balance again`() = runTest {
        fixture.seedProduct(id = 1L, name = "Arroz")
        fixture.database.seed { tables ->
            tables.copy(
                movements = mapOf(
                    1L to record(1L, 5_000L, null, StockMovementType.OPENING_BALANCE),
                    2L to record(2L, -5_000L, 1L, StockMovementType.ADJUSTMENT_VOID),
                ),
                lastIds = tables.lastIds + ("stock_movements" to 2L),
            )
        }

        val result = fixture.opening(1L, QuantityMilli(2_000L), Money(100L))

        assertEquals(OpeningBalanceResult.Registered(3L), result)
    }

    @Test
    fun `a product with effective movements is rejected`() = runTest {
        fixture.seedProduct(id = 1L, name = "Arroz")
        fixture.database.seed { tables ->
            tables.copy(
                movements = mapOf(
                    1L to record(1L, 5_000L, null, StockMovementType.OPENING_BALANCE),
                ),
            )
        }

        assertEquals(
            OpeningBalanceResult.AlreadyHasMovements,
            fixture.opening(1L, QuantityMilli(1_000L), Money.ZERO),
        )
    }

    @Test
    fun `rising over the minimum resolves the active alert`() = runTest {
        fixture.seedProduct(id = 1L, name = "Arroz", minStock = QuantityMilli(5_000L))
        fixture.database.seed { tables ->
            tables.copy(
                alerts = mapOf(
                    1L to LowStockAlert(
                        id = 1L,
                        productId = 1L,
                        onHandAtTrigger = QuantityMilli.ZERO,
                        minStockAtTrigger = QuantityMilli(5_000L),
                        notificationStatus = LowStockNotificationStatus.SUPPRESSED,
                        occurredAt = EARLIER,
                        businessDate = requireNotNull(BusinessDate.parse("2023-11-13")),
                        resolvedAt = null,
                    ),
                ),
                lastIds = tables.lastIds + ("low_stock_alerts" to 1L),
            )
        }

        fixture.opening(1L, QuantityMilli(6_000L), Money(10L))

        assertEquals(NOW, fixture.tables.alerts.getValue(1L).resolvedAt)
    }

    @Test
    fun `staying low keeps the alert`() = runTest {
        fixture.create(
            ProductForm(
                name = "Arroz",
                minStock = QuantityMilli(5_000L),
            ),
        )

        fixture.opening(1L, QuantityMilli(2_000L), Money(10L))

        assertEquals(1, fixture.activeAlertsOf(1L).size)
    }

    @Test
    fun `a weighed product takes fractions and a counted one does not`() = runTest {
        fixture.seedProduct(id = 1L, name = "Queso", baseUnitId = KILOGRAM_ID)
        fixture.seedProduct(id = 2L, name = "Arroz")

        assertEquals(
            OpeningBalanceResult.Invalid(
                setOf(ProductFormIssue.INITIAL_STOCK_FRACTION_NOT_ALLOWED),
            ),
            fixture.opening(2L, QuantityMilli(1_500L), Money(10L)),
        )
        assertTrue(
            fixture.opening(
                1L,
                QuantityMilli(1_500L),
                Money(10L),
            ) is OpeningBalanceResult.Registered,
        )
    }

    @Test
    fun `each rejection writes nothing`() = runTest {
        fixture.seedProduct(id = 1L, name = "Arroz")
        fixture.seedProduct(id = 2L, name = "Viejo", archived = true)
        fixture.seedProduct(id = 3L, name = "Corte", stockMode = ProductStockMode.UNTRACKED)
        val before = fixture.tables

        assertEquals(
            OpeningBalanceResult.NotFound,
            fixture.opening(9L, QuantityMilli(1_000L), Money.ZERO),
        )
        assertEquals(
            OpeningBalanceResult.ProductArchived,
            fixture.opening(2L, QuantityMilli(1_000L), Money.ZERO),
        )
        assertEquals(
            OpeningBalanceResult.NotTracked,
            fixture.opening(3L, QuantityMilli(1_000L), Money.ZERO),
        )
        assertEquals(
            OpeningBalanceResult.Invalid(setOf(ProductFormIssue.INITIAL_STOCK_OUT_OF_RANGE)),
            fixture.opening(1L, QuantityMilli.ZERO, Money.ZERO),
        )
        assertEquals(before, fixture.tables)
    }

    @Test
    fun `a value over the stock limit is rejected`() = runTest {
        fixture.seedProduct(id = 1L, name = "Oro")

        val result = fixture.opening(1L, QuantityMilli(1_000_000_000L), Money(1_000_000_000_000L))

        assertEquals(OpeningBalanceResult.AboveLimit(Limit.DOCUMENT_TOTAL), result)
    }

    @Test
    fun `an unknown zone and a restore in progress write nothing`() = runTest {
        val lost = CatalogFixture(zoneId = "Nowhere/Unknown")
        lost.seedProduct(id = 1L, name = "Arroz")
        assertEquals(
            OpeningBalanceResult.UnknownTimeZone,
            lost.opening(1L, QuantityMilli(1_000L), Money.ZERO),
        )

        fixture.seedProduct(id = 1L, name = "Arroz")
        fixture.transactions.restoreInProgress = true
        assertEquals(
            OpeningBalanceResult.StorageFailed(StorageFailure.RESTORE_IN_PROGRESS),
            fixture.opening(1L, QuantityMilli(1_000L), Money.ZERO),
        )
    }

    @Test
    fun `a cost needs a confirmed currency and a zero cost does not`() = runTest {
        val unconfirmed = CatalogFixture(currencyConfirmed = false)
        unconfirmed.seedProduct(id = 1L, name = "Arroz")

        assertEquals(
            OpeningBalanceResult.CurrencyUnconfirmed,
            unconfirmed.opening(1L, QuantityMilli(1_000L), Money(10L)),
        )
        val free = unconfirmed.opening(1L, QuantityMilli(1_000L), Money.ZERO)
        assertTrue(free is OpeningBalanceResult.Registered)
    }

    @Test
    fun `a failure at each write leaves nothing`() = runTest {
        CatalogFixture.assertEachWriteFailureLeavesNothing(
            prepare = { seedProduct(id = 1L, name = "Arroz") },
        ) { opening(1L, QuantityMilli(3_000L), Money(500L)) }
    }
}
