package io.github.yesidrobayo.shopledger.domain.usecase.catalog

import io.github.yesidrobayo.shopledger.domain.model.ArchiveFilter
import io.github.yesidrobayo.shopledger.domain.model.catalog.MeasureUnit
import io.github.yesidrobayo.shopledger.domain.model.catalog.Product
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductStockMode
import io.github.yesidrobayo.shopledger.domain.model.catalog.RecipeComponent
import io.github.yesidrobayo.shopledger.domain.model.catalog.RecipeLine
import io.github.yesidrobayo.shopledger.domain.port.ProductComponentRepository
import io.github.yesidrobayo.shopledger.domain.port.ProductRepository
import io.github.yesidrobayo.shopledger.domain.port.UnitRepository
import io.github.yesidrobayo.shopledger.domain.rule.catalog.ComboFigures
import io.github.yesidrobayo.shopledger.domain.rule.catalog.RecipePreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/**
 * Un combo en la lista de combos: el producto, cuántos componentes lleva y las cifras de una
 * unidad con su receta guardada.
 */
data class ComboSummary(val product: Product, val componentCount: Int, val figures: RecipePreview)

/**
 * Lo que necesita la pantalla de una receta: el combo, todas las recetas guardadas, y todos los
 * productos y las unidades, vigentes y archivados, por id. Las recetas de los demás dicen qué
 * lleva cada combo de adentro y en qué combos entra este; los productos dan nombre, stock y costo
 * a cada fila, y las unidades, con qué se escribe su cantidad.
 */
data class RecipeEditorData(
    val combo: Product,
    val stored: List<RecipeComponent>,
    val products: Map<Long, Product>,
    val units: Map<Long, MeasureUnit>,
) {
    /** La receta guardada del combo, en el orden en que se armó. */
    val recipe: List<RecipeComponent> get() = stored.filter { it.parentProductId == combo.id }
}

/**
 * Los combos vigentes, por nombre, con lo que alcanza y lo que cuesta una unidad de cada uno
 * según su receta guardada. Vuelve a emitir con cada cambio en los productos o en las recetas,
 * porque una compra de un componente cambia lo que alcanza y lo que cuesta su combo.
 */
class ObserveCombosUseCase(
    private val products: ProductRepository,
    private val components: ProductComponentRepository,
) {
    operator fun invoke(): Flow<List<ComboSummary>> = combine(
        products.observe(ArchiveFilter.ACTIVE),
        products.observe(ArchiveFilter.ARCHIVED),
        components.observeAll(),
    ) { active, archived, stored ->
        val byId = (active + archived).associateBy { it.id }
        val byParent = stored.groupBy { it.parentProductId }
        active.filter { it.details.stockMode == ProductStockMode.COMPONENTS }
            .sortedWith(compareBy<Product> { it.details.normalizedName }.thenBy { it.id })
            .map { combo ->
                val recipe = byParent[combo.id].orEmpty()
                val lines = recipe.map { RecipeLine(it.componentProductId, it.quantity) }
                ComboSummary(
                    product = combo,
                    componentCount = recipe.size,
                    figures = ComboFigures.preview(combo.id, lines, stored, byId),
                )
            }
    }
}

/**
 * Lo que necesita la pantalla de la receta de [comboId], de nuevo con cada cambio. Emite `null` si
 * el combo ya no existe.
 */
class ObserveRecipeEditorUseCase(
    private val products: ProductRepository,
    private val components: ProductComponentRepository,
    private val units: UnitRepository,
) {
    operator fun invoke(comboId: Long): Flow<RecipeEditorData?> = combine(
        products.observe(ArchiveFilter.ACTIVE),
        products.observe(ArchiveFilter.ARCHIVED),
        components.observeAll(),
        units.observeAll(),
    ) { active, archived, stored, allUnits ->
        val byId = (active + archived).associateBy { it.id }
        val unitsById = allUnits.associateBy { it.id }
        byId[comboId]?.let { RecipeEditorData(it, stored, byId, unitsById) }
    }
}
