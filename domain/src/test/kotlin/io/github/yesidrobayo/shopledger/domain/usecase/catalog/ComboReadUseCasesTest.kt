package io.github.yesidrobayo.shopledger.domain.usecase.catalog

import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductStockMode
import io.github.yesidrobayo.shopledger.domain.model.catalog.RecipeLine
import io.github.yesidrobayo.shopledger.domain.rule.catalog.ComboAvailability
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Las lecturas de combos sobre el catálogo en memoria. Los nombres son inventados. */
class ComboReadUseCasesTest {
    private val fixture = CatalogFixture().apply {
        seedProduct(TEA, "Té verde", initialCost = Money(1_000L), onHand = QuantityMilli(9_000L))
        seedProduct(OLD, "Taza vieja", archived = true)
        seedProduct(KIT, "Kit de té", stockMode = ProductStockMode.COMPONENTS)
        seedProduct(BOX, "Caja regalo", stockMode = ProductStockMode.COMPONENTS)
        seedProduct(GONE, "Bandeja", stockMode = ProductStockMode.COMPONENTS, archived = true)
    }
    private val save = with(fixture) {
        SaveRecipeUseCase(transactions, products, components, clock)
    }

    @Test
    fun `the active combos come by name with their figures`() = runTest {
        save(KIT, listOf(RecipeLine(TEA, QuantityMilli(2_000L))))

        val combos = ObserveCombosUseCase(fixture.products, fixture.components)().first()

        // La bandeja está archivada: no se lista. La caja no tiene receta: nada la limita.
        assertEquals(listOf(BOX, KIT), combos.map { it.product.id })
        assertEquals(listOf(0, 1), combos.map { it.componentCount })
        assertEquals(ComboAvailability(4L, TEA), combos[1].figures.availability)
        assertEquals(ComboAvailability(null, null), combos[0].figures.availability)
    }

    @Test
    fun `the editor brings the combo, every recipe, product and unit`() = runTest {
        save(KIT, listOf(RecipeLine(TEA, QuantityMilli(2_000L))))
        val observe = ObserveRecipeEditorUseCase(
            fixture.products,
            fixture.components,
            fixture.units,
        )

        val data = requireNotNull(observe(KIT).first())

        assertEquals(KIT, data.combo.id)
        assertEquals(listOf(TEA), data.recipe.map { it.componentProductId })
        // Los archivados también llegan: un componente archivado sigue en su receta.
        assertEquals(setOf(TEA, OLD, KIT, BOX, GONE), data.products.keys)
        assertEquals(true, data.units.containsKey(CatalogFixture.UNIT_ID))
        assertNull(observe(999L).first())
    }

    private companion object {
        const val TEA = 101L
        const val OLD = 102L
        const val KIT = 201L
        const val BOX = 202L
        const val GONE = 203L
    }
}
