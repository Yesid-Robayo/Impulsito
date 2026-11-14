package io.github.yesidrobayo.shopledger.domain.rule.catalog

import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductStockMode
import io.github.yesidrobayo.shopledger.domain.model.stock.CostState
import io.github.yesidrobayo.shopledger.domain.model.stock.LotStock

/**
 * Lo que la regla de modos de stock necesita saber de una línea de venta para decidir qué hace con
 * el stock y cuánto cuesta. Hay una variante por modo, y cada una trae solo lo que su modo usa: así
 * un servicio no puede llegar con un estado de costo, ni un producto con stock con un costo fijo.
 *
 * Las cantidades van en milésimas de la unidad base del producto, ya convertidas desde la unidad en
 * que se vendió.
 */
sealed interface LineStockSubject {
    /** El modo de stock del producto de la línea. */
    val stockMode: ProductStockMode

    /**
     * Un producto con stock propio. [costState] es su estado de costo justo antes de esta línea, ya
     * con las líneas anteriores de la misma venta.
     *
     * Con lotes, [lots] es lo que tiene cada lote en la ubicación de la venta, también ya con las
     * líneas anteriores, y [chosenLotId] el lote que eligió la persona, si eligió uno. Sin lotes,
     * [lots] es `null`.
     */
    data class Tracked(
        val costState: CostState,
        val quantity: QuantityMilli,
        val lots: List<LotStock>? = null,
        val chosenLotId: Long? = null,
    ) : LineStockSubject {
        init {
            requirePositive(quantity)
        }

        override val stockMode: ProductStockMode get() = ProductStockMode.TRACKED
    }

    /**
     * Un servicio o algo que no se cuenta. [fixedUnitCost] es lo que le cuesta al negocio una
     * unidad entera, y cero si no tiene costo.
     */
    data class Untracked(val fixedUnitCost: Money, val quantity: QuantityMilli) :
        LineStockSubject {
        init {
            requirePositive(quantity)
            requireNotNegative(fixedUnitCost)
        }

        override val stockMode: ProductStockMode get() = ProductStockMode.UNTRACKED
    }

    /**
     * Un combo o una receta, ya expandido a sus hojas: los componentes que no son a su vez combos,
     * con la cantidad que consume esta línea entera. Expandir los niveles y multiplicar por lo
     * vendido es tarea de quien arma la línea, no de esta regla.
     *
     * [costStates] trae el estado de costo de cada componente con stock, por id de producto, justo
     * antes de esta línea. Un componente que aparece en dos hojas se calcula con el estado que dejó
     * la primera.
     */
    data class Components(val leaves: List<ComponentLeaf>, val costStates: Map<Long, CostState>) :
        LineStockSubject {
        override val stockMode: ProductStockMode get() = ProductStockMode.COMPONENTS
    }
}

/**
 * Una hoja de un combo: un componente que no es otro combo, con la cantidad que consume la línea
 * entera, en milésimas de su unidad base. Nunca es un combo, porque la línea guarda solo hojas.
 */
sealed interface ComponentLeaf {
    val productId: Long
    val quantity: QuantityMilli

    /**
     * Un componente con stock propio: escribe su salida en su libro. Con lotes, [lots] es lo que
     * tiene cada lote antes de esta línea, y la salida sigue el orden de vencimiento.
     */
    data class Tracked(
        override val productId: Long,
        override val quantity: QuantityMilli,
        val lots: List<LotStock>? = null,
    ) : ComponentLeaf {
        init {
            requirePositive(quantity)
        }
    }

    /** Un componente sin stock, como un servicio: cuesta su costo fijo y no mueve stock. */
    data class Untracked(
        override val productId: Long,
        override val quantity: QuantityMilli,
        val fixedUnitCost: Money,
    ) : ComponentLeaf {
        init {
            requirePositive(quantity)
            requireNotNegative(fixedUnitCost)
        }
    }
}

private fun requirePositive(quantity: QuantityMilli) {
    require(quantity.milli > 0L) { "La cantidad de una línea siempre es positiva" }
}

private fun requireNotNegative(cost: Money) {
    require(cost.minor >= 0L) { "Un costo fijo nunca es negativo" }
}
