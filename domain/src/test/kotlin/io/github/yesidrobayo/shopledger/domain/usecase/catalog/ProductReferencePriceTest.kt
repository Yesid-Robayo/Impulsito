package io.github.yesidrobayo.shopledger.domain.usecase.catalog

import io.github.yesidrobayo.shopledger.domain.model.Limit
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductForm
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductFormIssue
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.CatalogFixture.Companion.UNIT_ID
import io.github.yesidrobayo.shopledger.domain.usecase.pricing.ObserveReferencedProductsUseCase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * El precio de referencia escrito al crear o editar un producto, en la moneda de referencia: cero
 * es sin dato, `null` lo deja como está, y no cambia el precio de venta. Montos inventados.
 */
class ProductReferencePriceTest {
    private val fixture = CatalogFixture()
    private val referenced = ObserveReferencedProductsUseCase(fixture.products)

    private fun form(reference: Money?) = ProductForm(
        name = "Arroz",
        baseUnitId = UNIT_ID,
        salePrice = Money(2_000L),
        referencePrice = reference,
    )

    @Test
    fun `a new product keeps its reference price and its sale price apart`() = runTest {
        fixture.create(form(Money(1_250L)), QuantityMilli.ZERO)

        assertEquals(Money(1_250L), fixture.product(1L).details.referencePrice)
        assertEquals(Money(2_000L), fixture.product(1L).salePrice)
        assertEquals(1, referenced().first())
    }

    @Test
    fun `without a reference price a new product has none`() = runTest {
        fixture.create(form(null), QuantityMilli.ZERO)
        fixture.create(form(Money.ZERO).copy(name = "Sal"), QuantityMilli.ZERO)

        assertNull(fixture.product(1L).details.referencePrice)
        assertNull(fixture.product(2L).details.referencePrice)
        assertEquals(0, referenced().first())
    }

    @Test
    fun `editing changes it, null keeps it and zero clears it`() = runTest {
        fixture.create(form(Money(1_250L)), QuantityMilli.ZERO)

        assertEquals(SaveProductResult.Saved(1L), fixture.edit(1L, form(Money(1_300L))))
        assertEquals(Money(1_300L), fixture.product(1L).details.referencePrice)

        val before = fixture.tables
        assertEquals(SaveProductResult.Saved(1L), fixture.edit(1L, form(null)))
        assertEquals(before, fixture.tables)

        fixture.edit(1L, form(Money.ZERO))
        assertNull(fixture.product(1L).details.referencePrice)
    }

    @Test
    fun `an out of range reference price is rejected`() = runTest {
        assertEquals(
            SaveProductResult.Invalid(setOf(ProductFormIssue.REFERENCE_PRICE_OUT_OF_RANGE)),
            fixture.create(form(Money(Limit.UNIT_MONEY.max + 1)), QuantityMilli.ZERO),
        )
        assertEquals(
            SaveProductResult.Invalid(setOf(ProductFormIssue.REFERENCE_PRICE_OUT_OF_RANGE)),
            fixture.create(form(Money(-1L)), QuantityMilli.ZERO),
        )
    }
}
