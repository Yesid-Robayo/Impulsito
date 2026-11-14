package io.github.yesidrobayo.shopledger.domain.rule.catalog

import io.github.yesidrobayo.shopledger.domain.model.Checked
import io.github.yesidrobayo.shopledger.domain.model.Limit
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.Scale
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductStockMode
import io.github.yesidrobayo.shopledger.domain.model.catalog.RecipeComponent
import io.github.yesidrobayo.shopledger.domain.rule.mulDivRound

/**
 * Una hoja de un combo expandido: un componente que no es otro combo, con lo que consume la
 * cantidad expandida entera, en milésimas de su unidad base.
 *
 * [viaComponentId] es el componente directo del combo por el que se llegó a la hoja: la hoja misma
 * si está en la primera receta, o el combo de adentro si viene de su receta. Sirve para mostrar el
 * costo de cada fila de la receta.
 */
data class ExpandedLeaf(val productId: Long, val quantity: QuantityMilli, val viaComponentId: Long)

/** El resultado de expandir un combo hasta sus hojas. */
sealed interface ComboExpansionResult {
    /** Las hojas, en el orden de la receta, bajando por cada combo de adentro en su lugar. */
    data class Leaves(val leaves: List<ExpandedLeaf>) : ComboExpansionResult

    /**
     * La hoja de [productId] consume menos de media milésima y redondea a cero. Una hoja guardada
     * consume al menos una milésima, así que la línea no se puede guardar.
     */
    data class LeafRoundsToZero(val productId: Long) : ComboExpansionResult

    /** La cantidad de una hoja no cabe en `Long` o pasa del tope de una línea. */
    data object QuantityOutOfRange : ComboExpansionResult

    /**
     * Hay un tercer nivel de combos. La app no deja guardarlo, así que solo llega con una base
     * dañada, y la línea se rechaza en vez de guardar un componente que no es hoja.
     */
    data object TooDeep : ComboExpansionResult
}

/**
 * Cómo se expande un combo a sus hojas al venderlo.
 *
 * La cantidad de cada hoja sale de un solo redondeo sobre el producto de todos los factores del
 * camino: lo vendido por la cantidad del componente y, si el componente es otro combo, por la de
 * su propio componente. Redondear en cada nivel acumularía error al anidar. El producto de lo
 * vendido por el primer factor cabe en `Long` cuando los dos están dentro del tope de una línea, y
 * el segundo se multiplica con 128 bits dentro de [mulDivRound].
 *
 * Un combo de adentro con su receta vacía no aporta hojas. Lo que pasa con un componente archivado
 * no lo decide esta regla: el combo sigue vendiéndose con lo que tiene.
 */
object ComboExpansion {
    /**
     * Las hojas que consume [quantity] milésimas del combo [comboId].
     *
     * [recipes] trae la receta de cada combo que aparece, por id del combo. [modes] trae el modo
     * de stock de cada componente: un componente que no figura en [modes] se trata como hoja.
     */
    fun expand(
        comboId: Long,
        quantity: QuantityMilli,
        recipes: Map<Long, List<RecipeComponent>>,
        modes: Map<Long, ProductStockMode>,
    ): ComboExpansionResult {
        val leaves = ArrayList<ExpandedLeaf>()
        for (first in recipes[comboId].orEmpty()) {
            val firstId = first.componentProductId
            if (modes[firstId] != ProductStockMode.COMPONENTS) {
                val leafQuantity = when (
                    val result = mulDivRound(
                        quantity.milli,
                        first.quantity.milli,
                        Scale.MILLI,
                        Limit.LINE_QUANTITY_MILLI,
                    )
                ) {
                    is Checked.Value -> result.value

                    Checked.Overflow, is Checked.AboveLimit ->
                        return ComboExpansionResult.QuantityOutOfRange
                }
                if (leafQuantity == 0L) return ComboExpansionResult.LeafRoundsToZero(firstId)
                leaves += ExpandedLeaf(firstId, QuantityMilli(leafQuantity), firstId)
                continue
            }
            // Con los dos factores dentro del tope de una línea, su producto cabe en Long.
            val outer = try {
                Math.multiplyExact(quantity.milli, first.quantity.milli)
            } catch (overflow: ArithmeticException) {
                return ComboExpansionResult.QuantityOutOfRange
            }
            for (second in recipes[firstId].orEmpty()) {
                val secondId = second.componentProductId
                if (modes[secondId] == ProductStockMode.COMPONENTS) {
                    return ComboExpansionResult.TooDeep
                }
                val leafQuantity = when (
                    val result = mulDivRound(
                        outer,
                        second.quantity.milli,
                        Scale.MILLI * Scale.MILLI,
                        Limit.LINE_QUANTITY_MILLI,
                    )
                ) {
                    is Checked.Value -> result.value

                    Checked.Overflow, is Checked.AboveLimit ->
                        return ComboExpansionResult.QuantityOutOfRange
                }
                if (leafQuantity == 0L) return ComboExpansionResult.LeafRoundsToZero(secondId)
                leaves += ExpandedLeaf(secondId, QuantityMilli(leafQuantity), firstId)
            }
        }
        return ComboExpansionResult.Leaves(leaves)
    }
}
