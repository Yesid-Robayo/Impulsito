package io.github.yesidrobayo.shopledger.domain.rule.catalog

import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.sale.LineStockEffect
import io.github.yesidrobayo.shopledger.domain.model.stock.CostState
import io.github.yesidrobayo.shopledger.domain.model.stock.StockKey
import io.github.yesidrobayo.shopledger.domain.model.stock.StockMovementDraft
import io.github.yesidrobayo.shopledger.domain.model.stock.StockMovementType

/**
 * Lo que una línea de venta hace con el stock, decidido por el modo de su producto: qué efecto
 * queda guardado en la línea, cuánto cuesta y qué salidas escribe en el libro.
 *
 * [lineCost] es el costo de la línea, que queda congelado: una compra posterior nunca cambia la
 * ganancia de esta venta.
 */
sealed interface LineStockPlan {
    /** El efecto que la línea guarda, copiado del modo del producto. */
    val stockEffect: LineStockEffect
    val lineCost: Money

    /**
     * Un producto con stock propio: una sola salida de su libro, y la línea cuesta lo que se lleva
     * esa salida.
     */
    data class Direct(val exit: StockExit) : LineStockPlan {
        override val stockEffect: LineStockEffect get() = LineStockEffect.DIRECT
        override val lineCost: Money get() = exit.cost
    }

    /**
     * Un servicio: ninguna fila en el libro, y la línea cuesta su costo fijo por la cantidad.
     * [unitCost] es ese costo fijo, el único que el producto tiene para mostrar.
     */
    data class NoStock(val unitCost: Money, override val lineCost: Money) : LineStockPlan {
        override val stockEffect: LineStockEffect get() = LineStockEffect.NONE
    }

    /**
     * Un combo: el producto vendido no mueve stock propio, y cada hoja se resuelve según su modo. La
     * línea cuesta la suma de sus hojas.
     *
     * [costStatesAfter] es el mapa de estados de costo recibido, con cada componente movido ya en su
     * estado tras esta línea: es el que recibe la línea siguiente de la venta.
     */
    data class Components(
        val leaves: List<LeafStockPlan>,
        override val lineCost: Money,
        val costStatesAfter: Map<Long, CostState>,
    ) : LineStockPlan {
        override val stockEffect: LineStockEffect get() = LineStockEffect.COMPONENTS
    }
}

/**
 * Una hoja de un combo vendido, tal como queda guardada en la línea: cantidad, si lleva stock, costo
 * unitario para mostrar y costo de la hoja.
 */
sealed interface LeafStockPlan {
    val productId: Long
    val quantity: QuantityMilli
    val unitCost: Money
    val lineCost: Money

    /** Si la hoja escribe sus filas de venta en el libro de su producto. */
    val tracked: Boolean

    /** Un componente con stock: una salida de su libro. */
    data class Tracked(override val productId: Long, val exit: StockExit) : LeafStockPlan {
        override val quantity: QuantityMilli get() = exit.quantity
        override val unitCost: Money get() = exit.unitCost
        override val lineCost: Money get() = exit.cost
        override val tracked: Boolean get() = true
    }

    /** Un componente sin stock: cuesta su costo fijo y no escribe ninguna fila. */
    data class Untracked(
        override val productId: Long,
        override val quantity: QuantityMilli,
        override val unitCost: Money,
        override val lineCost: Money,
    ) : LeafStockPlan {
        override val tracked: Boolean get() = false
    }
}

/**
 * La parte de una salida que sale de un lote: [quantity] milésimas, positivas, del lote [lotId], o
 * del lote sin asignar si es `null`. [cost] es su costo de salida, calculado en orden desde el
 * estado que dejó la parte anterior, y [stateAfter] el estado que deja. Cada parte es una fila.
 */
data class LotExit(
    val lotId: Long?,
    val quantity: QuantityMilli,
    val cost: Money,
    val stateAfter: CostState,
)

/**
 * Una salida del libro de un producto con stock al venderlo.
 *
 * [quantity] es lo que sale, positivo. [cost] es lo que se lleva del valor del stock, y es el costo
 * real de la línea. [unitCost] es el costo promedio del momento, que la línea congela solo para
 * mostrarlo: un [unitCost] en cero no significa que la salida no tenga costo. [stateAfter] es el
 * estado de costo del producto tras la salida, el que queda en su fila del libro y en el producto.
 * Con lotes, [lots] son sus partes, una fila por lote: el costo es la suma de sus costos, y el
 * estado, el que deja la última. Sin lotes está vacía y la salida es una sola fila.
 */
data class StockExit(
    val quantity: QuantityMilli,
    val unitCost: Money,
    val cost: Money,
    val stateAfter: CostState,
    val lots: List<LotExit> = emptyList(),
) {
    /**
     * La fila de venta que esta salida agrega al libro, en la parte del stock [key]. Lleva la
     * cantidad con signo negativo, porque resta stock.
     */
    fun saleMovement(key: StockKey): StockMovementDraft = StockMovementDraft(
        key = key,
        type = StockMovementType.SALE,
        quantity = -quantity,
        reversesMovementId = null,
    )
}
