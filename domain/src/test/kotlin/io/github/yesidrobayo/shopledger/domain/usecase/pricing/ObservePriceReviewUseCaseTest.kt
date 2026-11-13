package io.github.yesidrobayo.shopledger.domain.usecase.pricing

import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.catalog.Category
import io.github.yesidrobayo.shopledger.domain.model.catalog.CategoryDetails
import io.github.yesidrobayo.shopledger.domain.model.catalog.Product
import io.github.yesidrobayo.shopledger.domain.model.pricing.PriceReviewReason
import io.github.yesidrobayo.shopledger.domain.port.StorageFailure
import io.github.yesidrobayo.shopledger.domain.port.StorageFailureException
import io.github.yesidrobayo.shopledger.domain.port.TransactionRunner
import io.github.yesidrobayo.shopledger.domain.port.TxResult
import io.github.yesidrobayo.shopledger.domain.testing.FakeTaxRateRepository
import io.github.yesidrobayo.shopledger.domain.testing.FixedClock
import io.github.yesidrobayo.shopledger.domain.testing.ProductStockHeader
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.CatalogFixture
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.ObservePricingContextUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.region.BusinessCalendarSource
import io.github.yesidrobayo.shopledger.domain.usecase.settings.ObserveCostStaleDaysUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.settings.SetCostStaleDaysResult
import io.github.yesidrobayo.shopledger.domain.usecase.settings.SetCostStaleDaysUseCase
import kotlin.time.Instant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * La lista `Revisar` sobre el catálogo en memoria, y los días para costo viejo que la gobiernan:
 * cero apaga el costo viejo, el margen objetivo se hereda de la categoría y un cambio en los
 * ajustes vuelve a armar la lista. Productos y montos inventados.
 */
class ObservePriceReviewUseCaseTest {
    private val fixture = CatalogFixture()
    private val clock = FixedClock.atEpochMillis(NOV_FIRST.toEpochMilliseconds())
    private val review = ObservePriceReviewUseCase(
        products = fixture.products,
        pricing = ObservePricingContextUseCase(
            fixture.settings,
            fixture.categories,
            FakeTaxRateRepository(fixture.database),
        ),
        calendar = BusinessCalendarSource(fixture.settings),
        settings = fixture.settings,
        clock = clock,
    )
    private val setDays = SetCostStaleDaysUseCase(fixture.transactions, fixture.settings, clock)

    init {
        fixture.database.seed { tables ->
            tables.copy(
                categories = mapOf(
                    7L to Category(
                        7L,
                        CategoryDetails("Bebidas", "bebidas", BasisPoints(4_000)),
                        AUGUST_FIRST,
                        AUGUST_FIRST,
                    ),
                ),
            )
        }
        // 35 % de margen en una categoría que pide 40 %.
        seed(1L, "Jugo", Money(10_000), Money(6_500), category = 7L, costAt = OCTOBER_FIRST)
        // Buen margen, costo del 1 de agosto: con 90 días es viejo.
        seed(2L, "Galletas", Money(1_000), Money(500), costAt = AUGUST_FIRST)
        // Buen margen y costo reciente: no aparece.
        seed(3L, "Avena", Money(1_000), Money(500), costAt = OCTOBER_FIRST)
    }

    @Test
    fun theListJoinsTheLowMarginOfTheCategoryAndTheOldCost() = runTest {
        val reviews = review().first()

        assertEquals(listOf(1L, 2L), reviews.map { it.product.id })
        assertEquals(listOf(PriceReviewReason.LOW_MARGIN), reviews[0].reasons)
        assertEquals(BasisPoints(4_000), reviews[0].target)
        assertEquals(listOf(PriceReviewReason.OLD_COST), reviews[1].reasons)
        assertEquals(BusinessDate(LocalDate(2026, 8, 1)), reviews[1].costSince)
    }

    @Test
    fun zeroDaysTurnsTheOldCostOffAndTheListFollowsTheSetting() = runTest {
        assertEquals(SetCostStaleDaysResult.Saved, setDays(0))

        assertEquals(listOf(1L), review().first().map { it.product.id })
        assertEquals(0, ObserveCostStaleDaysUseCase(fixture.settings)().first())
        assertEquals(listOf(0 to NOV_FIRST), fixture.settings.costStaleWrites)
    }

    @Test
    fun theDayIsLookedAtAgainWithEachCheck() = runTest {
        // Con 120 días el costo del 1 de agosto todavía no es viejo el 1 de noviembre.
        setDays(120)
        val checks = MutableStateFlow(Unit)

        assertEquals(listOf(1L), review(checks).first().map { it.product.id })
    }

    @Test
    fun anUnknownZoneLeavesOnlyTheOtherReasons() = runTest {
        fixture.settings.settings.value = fixture.settings.settings.value.let {
            it.copy(regional = it.regional.copy(businessTimeZoneId = "Nowhere/Unknown"))
        }

        assertEquals(listOf(1L), review().first().map { it.product.id })
    }

    @Test
    fun theSameDaysOrOnesOutOfRangeWriteNothing() = runTest {
        assertEquals(SetCostStaleDaysResult.Saved, setDays(90))
        assertEquals(SetCostStaleDaysResult.OutOfRange, setDays(-1))
        assertEquals(SetCostStaleDaysResult.OutOfRange, setDays(36_501))
        assertTrue(fixture.settings.costStaleWrites.isEmpty())

        assertEquals(SetCostStaleDaysResult.Saved, setDays(36_500))
        assertEquals(listOf(36_500 to NOV_FIRST), fixture.settings.costStaleWrites)
    }

    @Test
    fun aFailedWriteSaysSoAndChangesNothing() = runTest {
        val full = object : TransactionRunner {
            override suspend fun <T> write(block: suspend () -> TxResult<T>): T =
                throw StorageFailureException(StorageFailure.DISK_FULL)

            override suspend fun <T> read(block: suspend () -> T): T = block()
        }
        val failing = SetCostStaleDaysUseCase(full, fixture.settings, clock)

        assertEquals(SetCostStaleDaysResult.Failed(StorageFailure.DISK_FULL), failing(30))
        assertTrue(fixture.settings.costStaleWrites.isEmpty())
    }

    private fun seed(
        id: Long,
        name: String,
        price: Money,
        cost: Money,
        category: Long? = null,
        costAt: Instant,
    ) {
        val stored = Product(
            id = id,
            details = CatalogFixture.details(name).copy(categoryId = category),
            salePrice = price,
            initialCost = cost,
            averageCost = cost,
            stockValue = Money.ZERO,
            onHand = QuantityMilli.ZERO,
            replacementCost = cost,
            replacementCostAt = costAt,
            priceReviewSince = null,
            archivedAt = null,
            createdAt = AUGUST_FIRST,
            updatedAt = AUGUST_FIRST,
        )
        fixture.database.seed { tables ->
            tables.copy(
                products = tables.products + (id to stored),
                productStock = tables.productStock +
                    (id to ProductStockHeader(QuantityMilli.ZERO, cost, Money.ZERO)),
            )
        }
    }

    private companion object {
        val AUGUST_FIRST: Instant = Instant.parse("2026-08-01T15:00:00Z")
        val OCTOBER_FIRST: Instant = Instant.parse("2026-10-01T15:00:00Z")
        val NOV_FIRST: Instant = Instant.parse("2026-11-01T15:00:00Z")
    }
}
