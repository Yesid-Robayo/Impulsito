package io.github.yesidrobayo.shopledger.domain.usecase.catalog

import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.catalog.HistoryLockedField
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductForm
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.CatalogFixture.Companion.CATEGORY_ID
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.CatalogFixture.Companion.KILOGRAM_ID
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.CatalogFixture.Companion.UNIT_ID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Lo que lee el formulario de un producto: el producto, sus bloqueos y lo que se puede elegir. */
class ProductEditorReadsTest {
    private val fixture = CatalogFixture()
    private val load =
        LoadProductForEditUseCase(fixture.transactions, fixture.products, fixture.ledger)
    private val choices = ObserveCatalogChoicesUseCase(fixture.categories, fixture.units)

    @Test
    fun `a product without movements can change everything`() = runTest {
        fixture.create(ProductForm(name = "Arroz"))

        val editing = requireNotNull(load(1L))

        assertEquals("Arroz", editing.product.details.name)
        assertTrue(editing.lockedFields.isEmpty())
        assertFalse(editing.costLocked)
        assertEquals(1, fixture.transactions.readCalls)
    }

    @Test
    fun `a product with an opening balance locks its unit, mode, lots and cost`() = runTest {
        fixture.create(ProductForm(name = "Arroz"), QuantityMilli(2_000L))

        val editing = requireNotNull(load(1L))

        assertEquals(HistoryLockedField.entries.toSet(), editing.lockedFields)
        assertTrue(editing.costLocked)
    }

    @Test
    fun `a missing product reads as null`() = runTest {
        assertNull(load(9L))
    }

    @Test
    fun `the choices are the categories and the active units`() = runTest {
        val first = choices().first()

        assertEquals(listOf(CATEGORY_ID), first.categories.map { it.id })
        assertEquals(listOf(UNIT_ID, KILOGRAM_ID), first.units.map { it.id })
    }

    @Test
    fun `a name used by another active product is reported with its stored name`() = runTest {
        val clash = FindProductNameClashUseCase(fixture.transactions, fixture.products)
        fixture.create(ProductForm(name = "Jabón Líquido"))
        fixture.seedProduct(id = 5L, name = "Viejo", archived = true)

        assertEquals("Jabón Líquido", clash(" jabon LIQUIDO ", editingId = null))
        assertNull(clash("Jabón Líquido", editingId = 1L))
        assertNull(clash("viejo", editingId = null))
        assertNull(clash("   ", editingId = null))
    }
}
