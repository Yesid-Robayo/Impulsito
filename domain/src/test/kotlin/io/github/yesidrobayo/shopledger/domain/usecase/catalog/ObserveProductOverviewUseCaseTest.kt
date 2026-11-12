package io.github.yesidrobayo.shopledger.domain.usecase.catalog

import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.catalog.MovementDocument
import io.github.yesidrobayo.shopledger.domain.model.stock.StockMovementType
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.CatalogFixture.Companion.UNIT_ID
import io.github.yesidrobayo.shopledger.domain.usecase.region.BusinessCalendarSource
import io.github.yesidrobayo.shopledger.domain.usecase.region.CurrencyLock
import io.github.yesidrobayo.shopledger.domain.usecase.stock.CorrectCostUseCase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** El detalle de un producto: su libro del más reciente al más antiguo, con lo que explica cada fila. */
class ObserveProductOverviewUseCaseTest {
    private val fixture = CatalogFixture()
    private val correct =
        CorrectCostUseCase(
            fixture.transactions,
            fixture.products,
            fixture.systemRows,
            fixture.ledger,
            BusinessCalendarSource(fixture.settings),
            CurrencyLock(fixture.settings),
            fixture.clock,
        )

    private fun overview(links: Map<Long, MovementDocument> = emptyMap()) =
        ObserveProductOverviewUseCase(
            fixture.products,
            fixture.units,
            fixture.ledger,
            fixture.priceHistory,
            { flowOf(links) },
        )

    @Test
    fun `a cost correction shows the cost before and after, newest first`() = runTest {
        fixture.seedProduct(1L, "Arroz", initialCost = Money(800L), onHand = QuantityMilli(2_000L))
        correct(1L, Money(1_000L))
        correct(1L, Money(1_200L))

        val shown = requireNotNull(overview()(1L).first())

        assertEquals(UNIT_ID, shown.unit?.id)
        assertEquals(listOf(2L, 1L), shown.movements.map { it.id })
        val (newest, oldest) = shown.movements
        assertEquals(Money(800L), oldest.averageCostBefore)
        assertEquals(Money(1_000L), oldest.record.averageCostAfter)
        assertEquals(Money(1_000L), newest.averageCostBefore)
        assertEquals(Money(1_200L), newest.record.averageCostAfter)
        assertEquals(StockMovementType.COST_CORRECTION, newest.record.movement.type)
        assertNull(newest.compensatedById)
    }

    @Test
    fun `each row carries its document and the row that voids it`() = runTest {
        fixture.seedProduct(1L, "Arroz", onHand = QuantityMilli(2_000L))
        correct(1L, Money(1_000L))
        val purchase = MovementDocument.Purchase(7L)

        val shown = requireNotNull(overview(mapOf(1L to purchase))(1L).first())
        assertEquals(purchase, shown.movements.single().document)

        val original = fixture.tables.movements.getValue(1L)
        val voiding = original.copy(
            movement = original.movement.copy(id = 2L, reversesMovementId = 1L),
        )
        val rows = listOf(original, voiding)
        val entries = ObserveProductOverviewUseCase.movementsOf(
            fixture.product(1L),
            rows,
            emptyMap(),
        )
        assertEquals(2L, entries.single { it.id == 1L }.compensatedById)
        assertNull(entries.single { it.id == 2L }.compensatedById)
    }

    @Test
    fun `a product that does not exist has nothing to show`() = runTest {
        assertNull(overview()(99L).first())
    }
}
