package io.github.yesidrobayo.shopledger.domain.usecase.catalog

import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductForm
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductFormIssue
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductStockMode
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.CatalogFixture.Companion.NOW
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.CatalogFixture.Companion.UNIT_ID
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * El costo de reposición escrito a mano al crear o editar un producto: se fecha al guardarlo, cero
 * es sin dato y el costo promedio no cambia. Montos inventados.
 */
class ProductReplacementCostTest {
    private val fixture = CatalogFixture()

    private fun form(replacement: Money?, stockMode: ProductStockMode = ProductStockMode.TRACKED) =
        ProductForm(
            name = "Arroz",
            baseUnitId = UNIT_ID,
            stockMode = stockMode,
            salePrice = Money(2_000L),
            cost = Money(1_000L),
            replacementCost = replacement,
        )

    @Test
    fun `a new product keeps the written replacement cost, dated, apart from its cost`() = runTest {
        fixture.create(form(Money(1_300L)), QuantityMilli(5_000L))

        val product = fixture.product(1L)
        assertEquals(Money(1_300L), product.replacementCost)
        assertEquals(NOW, product.replacementCostAt)
        assertEquals(Money(1_000L), product.averageCost)
    }

    @Test
    fun `a new product without a replacement cost has no value and no date`() = runTest {
        fixture.create(form(null), QuantityMilli.ZERO)

        assertEquals(Money.ZERO, fixture.product(1L).replacementCost)
        assertNull(fixture.product(1L).replacementCostAt)
    }

    @Test
    fun `correcting it by hand changes neither the average cost nor the ledger`() = runTest {
        fixture.create(form(null), QuantityMilli(5_000L))
        val ledger = fixture.movementsOf(1L)

        val result = fixture.edit(1L, form(Money(1_400L)))

        assertEquals(SaveProductResult.Saved(1L), result)
        val product = fixture.product(1L)
        assertEquals(Money(1_400L), product.replacementCost)
        assertEquals(NOW, product.replacementCostAt)
        assertEquals(Money(1_000L), product.averageCost)
        assertEquals(ledger, fixture.movementsOf(1L))
    }

    @Test
    fun `clearing it leaves no value and no date`() = runTest {
        fixture.create(form(Money(1_300L)), QuantityMilli.ZERO)

        fixture.edit(1L, form(Money.ZERO))

        assertEquals(Money.ZERO, fixture.product(1L).replacementCost)
        assertNull(fixture.product(1L).replacementCostAt)
    }

    @Test
    fun `an edit that does not say it keeps the replacement cost and writes nothing`() = runTest {
        fixture.create(form(Money(1_300L)), QuantityMilli.ZERO)
        val before = fixture.tables

        val result = fixture.edit(1L, form(null))

        assertEquals(SaveProductResult.Saved(1L), result)
        assertEquals(before, fixture.tables)
    }

    @Test
    fun `turning into a service drops the replacement cost, because it is no longer bought`() =
        runTest {
            fixture.create(form(Money(1_300L)), QuantityMilli.ZERO)

            fixture.edit(1L, form(null, ProductStockMode.UNTRACKED))

            assertEquals(Money.ZERO, fixture.product(1L).replacementCost)
            assertNull(fixture.product(1L).replacementCostAt)
        }

    @Test
    fun `a service does not take a replacement cost, and an out of range one is rejected`() =
        runTest {
            assertEquals(
                SaveProductResult.Invalid(setOf(ProductFormIssue.REPLACEMENT_COST_NOT_APPLICABLE)),
                fixture.create(form(Money(1L), ProductStockMode.UNTRACKED), QuantityMilli.ZERO),
            )
            assertEquals(
                SaveProductResult.Invalid(setOf(ProductFormIssue.REPLACEMENT_COST_OUT_OF_RANGE)),
                fixture.create(form(Money(-1L)), QuantityMilli.ZERO),
            )
            assertEquals(
                SaveProductResult.Invalid(setOf(ProductFormIssue.REPLACEMENT_COST_OUT_OF_RANGE)),
                fixture.create(form(Money(1_000_000_000_001L)), QuantityMilli.ZERO),
            )
        }

    @Test
    fun `a service may say zero, which is no value`() = runTest {
        val result = fixture.create(
            form(Money.ZERO, ProductStockMode.UNTRACKED),
            QuantityMilli.ZERO,
        )

        assertEquals(SaveProductResult.Saved(1L), result)
        assertEquals(Money.ZERO, fixture.product(1L).replacementCost)
    }

    @Test
    fun `an archived product does not change its replacement cost`() = runTest {
        fixture.seedProduct(1L, "Arroz", archived = true, salePrice = Money(2_000L))
        val before = fixture.tables

        val result = fixture.edit(
            1L,
            form(Money(1_400L)).copy(cost = Money.ZERO),
        )

        assertEquals(SaveProductResult.ArchivedPriceLocked, result)
        assertEquals(before, fixture.tables)
    }

    @Test
    fun `a replacement cost alone needs the currency confirmed`() = runTest {
        val unconfirmed = CatalogFixture(currencyConfirmed = false)

        val result = unconfirmed.create(
            form(Money(1_300L)).copy(salePrice = Money.ZERO, cost = Money.ZERO),
            QuantityMilli.ZERO,
        )

        assertEquals(SaveProductResult.CurrencyUnconfirmed, result)
    }
}
