package io.github.yesidrobayo.shopledger.domain.rule.document

import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductStockMode
import io.github.yesidrobayo.shopledger.domain.model.catalog.RecipeComponent
import io.github.yesidrobayo.shopledger.domain.rule.catalog.ComboExpansion
import io.github.yesidrobayo.shopledger.domain.rule.catalog.ComboExpansionResult

/**
 * Lo reservado y lo disponible de cada producto. Se calcula desde las líneas de los pedidos sin
 * entregar ni cancelar, y nunca toca el libro de stock: el stock solo baja al entregar.
 *
 * - **Disponible es lo que hay menos lo reservado**, con signo: con 10 en stock y 4 reservadas,
 *   quedan 6; con 3 y 4 reservadas, faltan 1. Es un aviso: vender nunca se bloquea.
 * - **Un combo reserva sus hojas.** Un combo no tiene stock propio: lo que reserva un pedido de un
 *   combo cae en los productos con que se arma, con la misma cuenta con que la venta los descuenta.
 *   Un combo que ya no se puede expandir no reserva nada.
 * - **Un producto sin libro no reserva:** un servicio no tiene stock que apartar.
 */
object OrderReservations {
    /** Lo que queda de [onHand] después de apartar [reserved], con signo. */
    fun available(onHand: QuantityMilli, reserved: QuantityMilli): QuantityMilli =
        QuantityMilli(Math.subtractExact(onHand.milli, reserved.milli))

    /**
     * Lo reservado por producto con libro, desde [lines], la suma de las líneas de los pedidos sin
     * entregar por producto, en milésimas de su unidad base. [modes] es el modo de stock de cada
     * producto que aparece, y de los componentes de sus combos; [recipes] la receta de cada combo.
     * Solo vuelven los productos que reservan algo.
     */
    fun byProduct(
        lines: Map<Long, QuantityMilli>,
        modes: Map<Long, ProductStockMode>,
        recipes: Map<Long, List<RecipeComponent>>,
    ): Map<Long, QuantityMilli> {
        val reserved = HashMap<Long, Long>()
        for ((productId, quantity) in lines) {
            // Un producto que no se encuentra no reserva nada.
            val mode = modes[productId] ?: continue
            when (mode) {
                ProductStockMode.TRACKED -> reserved.add(productId, quantity)

                ProductStockMode.COMPONENTS ->
                    when (val result = ComboExpansion.expand(productId, quantity, recipes, modes)) {
                        is ComboExpansionResult.Leaves -> for (leaf in result.leaves) {
                            if (modes[leaf.productId] == ProductStockMode.TRACKED) {
                                reserved.add(leaf.productId, leaf.quantity)
                            }
                        }

                        is ComboExpansionResult.LeafRoundsToZero,
                        ComboExpansionResult.QuantityOutOfRange,
                        ComboExpansionResult.TooDeep,
                        -> Unit
                    }

                ProductStockMode.UNTRACKED -> Unit
            }
        }
        // Cada línea y cada hoja reservan al menos una milésima: ninguna suma queda en cero.
        return reserved.mapValues { QuantityMilli(it.value) }
    }

    private fun HashMap<Long, Long>.add(productId: Long, quantity: QuantityMilli) {
        this[productId] = Math.addExact(this[productId] ?: 0L, quantity.milli)
    }
}
