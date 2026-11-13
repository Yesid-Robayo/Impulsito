package io.github.yesidrobayo.shopledger.domain.usecase.purchase

import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.purchase.PurchaseRequest
import io.github.yesidrobayo.shopledger.domain.usecase.sale.SaleWorld
import io.github.yesidrobayo.shopledger.domain.usecase.sale.product
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Una compra pone el costo de reposición de cada producto: lo que costó una unidad base, fechado con
 * la compra. Es una vista adicional: el costo promedio sigue saliendo del libro. Montos inventados.
 */
class PurchaseReplacementCostTest {
    private val world = SaleWorld()

    private fun replacementOf(productId: Long): Pair<Money, Instant?> =
        world.tables.products.getValue(productId).let { it.replacementCost to it.replacementCostAt }

    @Test
    fun `a purchase sets the replacement cost per base unit, dated with the purchase`() = runTest {
        world.addProduct(product(1))
        world.open(1, milli = 10_000, total = 8_000)

        world.buy(forTotal(1, milli = 3_000, total = 4_500))

        assertEquals(Money(1_500) to NOW, replacementOf(1))
        // El promedio sale del libro: (8000 + 4500) / 13.
        assertEquals(Money(962), world.costOf(1).averageCost)
    }

    @Test
    fun `the last line with cost of a repeated product wins, and a free line erases nothing`() =
        runTest {
            world.addProduct(product(1))
            world.addProduct(product(2))

            world.buy(
                atUnitCost(1, 1_000, cost = 1_200),
                atUnitCost(1, 1_000, cost = 1_250),
                atUnitCost(1, 1_000, cost = 0),
                atUnitCost(2, 1_000, cost = 0),
            )

            assertEquals(Money(1_250) to NOW, replacementOf(1))
            assertEquals(Money.ZERO to null, replacementOf(2))
        }

    @Test
    fun `a backdated purchase does not replace a newer replacement cost`() = runTest {
        world.addProduct(product(1, replacementCost = 2_000).copy(replacementCostAt = NOW))

        world.buy(atUnitCost(1, 1_000, cost = 1_000), date = YESTERDAY)

        assertEquals(Money(2_000) to NOW, replacementOf(1))
    }

    @Test
    fun `a backdated purchase sets the replacement cost when none was known`() = runTest {
        world.addProduct(product(1))

        world.buy(atUnitCost(1, 1_000, cost = 1_000), date = YESTERDAY)

        val (cost, at) = replacementOf(1)
        assertEquals(Money(1_000), cost)
        assertEquals(world.tables.purchases.values.single().content.occurredAt, at)
    }

    @Test
    fun `a purchase replaces an older replacement cost, and the average cost stays its own`() =
        runTest {
            val older = Instant.fromEpochMilliseconds(SaleWorld.NOW_MILLIS - 86_400_000L)
            world.addProduct(product(1, replacementCost = 900).copy(replacementCostAt = older))

            world.buy(atUnitCost(1, 2_000, cost = 1_100))

            assertEquals(Money(1_100) to NOW, replacementOf(1))
            assertEquals(Money(1_100), world.costOf(1).averageCost)
        }

    @Test
    fun `a rejected purchase leaves the replacement cost as it was`() = runTest {
        world.addProduct(product(1))

        world.registerPurchase()(
            PurchaseRequest(
                listOf(atUnitCost(1, 1_000, cost = 1_000), atUnitCost(99, 1_000, cost = 1)),
            ),
        )

        assertEquals(Money.ZERO to null, replacementOf(1))
        assertNull(world.tables.purchases.values.firstOrNull())
    }

    private companion object {
        val YESTERDAY = BusinessDate(LocalDate(2026, 11, 1))
    }
}
