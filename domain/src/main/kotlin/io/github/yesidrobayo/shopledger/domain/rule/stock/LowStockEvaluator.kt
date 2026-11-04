package io.github.yesidrobayo.shopledger.domain.rule.stock

import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductStockMode
import io.github.yesidrobayo.shopledger.domain.model.stock.StockMovementDraft

/**
 * Cuándo un producto está bajo de stock y cuándo eso merece un aviso.
 *
 * Avisa solo al cruzar el mínimo hacia abajo, y no repite hasta que el stock vuelve a quedar por
 * encima del mínimo. Un aviso por cada venta de un producto que ya estaba bajo sería ruido, y la
 * persona dejaría de leerlos. El aviso es una notificación local, sin servidor.
 *
 * La lista de productos por agotarse no depende de estas alertas: se calcula con [isLow] sobre los
 * productos, y por eso funciona aunque las notificaciones estén negadas.
 */
object LowStockEvaluator {
    /**
     * Si un producto está bajo: lleva stock propio, no está archivado y su saldo [onHand] no supera
     * su mínimo [minStock]. Llegar exactamente al mínimo ya es estar bajo.
     */
    fun isLow(
        stockMode: ProductStockMode,
        isArchived: Boolean,
        onHand: QuantityMilli,
        minStock: QuantityMilli,
    ): Boolean = isWatched(stockMode, isArchived) && onHand <= minStock

    /**
     * Compara el saldo de [subject] al empezar la transacción con [onHandAfter], su saldo al
     * terminarla.
     *
     * Con una alerta activa nunca crea otra, aunque el saldo inicial no fuera bajo: hay a lo sumo
     * una por producto, y un aviso repetido es peor que uno de menos. Y si el producto quedó por
     * encima del mínimo, resuelve la alerta activa que tenga.
     */
    fun evaluate(subject: LowStockSubject, onHandAfter: QuantityMilli): LowStockTransition {
        if (!isWatched(subject.stockMode, subject.isArchived)) return LowStockTransition.NotWatched
        val wasLow = subject.onHandBefore <= subject.minStock
        val isLowNow = onHandAfter <= subject.minStock
        return when {
            !isLowNow && subject.hasActiveAlert -> LowStockTransition.Rearmed
            !isLowNow || wasLow || subject.hasActiveAlert -> LowStockTransition.Unchanged
            else -> LowStockTransition.Crossed(onHand = onHandAfter, minStock = subject.minStock)
        }
    }

    /**
     * Evalúa una vez cada producto que mueven las filas [movements] de una transacción, y devuelve
     * qué le pasa a su alerta, por id de producto.
     *
     * Compara el saldo antes de la primera fila con el saldo después de la última, así que dos
     * líneas del mismo producto cuentan como un solo cambio y avisan a lo sumo una vez. Lo que pase
     * entre una línea y otra no cuenta: solo importa cómo queda el producto al confirmar.
     *
     * [subjects] trae el estado inicial de cada producto movido. Lanza [IllegalArgumentException]
     * si falta uno o si uno aparece dos veces: el caso de uso leyó mal los productos.
     */
    fun evaluateTransaction(
        subjects: Collection<LowStockSubject>,
        movements: Iterable<StockMovementDraft>,
    ): Map<Long, LowStockTransition> {
        val subjectsById = subjects.associateBy { it.productId }
        require(subjectsById.size == subjects.size) { "Un producto aparece dos veces" }
        val netChange = mutableMapOf<Long, QuantityMilli>()
        for (movement in movements) {
            val productId = movement.key.productId
            netChange[productId] =
                netChange.getOrElse(productId) { QuantityMilli.ZERO } + movement.quantity
        }
        return netChange.mapValues { (productId, change) ->
            val subject = requireNotNull(subjectsById[productId]) {
                "Falta el estado inicial de un producto que la transacción mueve"
            }
            evaluate(subject, subject.onHandBefore + change)
        }
    }

    private fun isWatched(stockMode: ProductStockMode, isArchived: Boolean): Boolean =
        stockMode == ProductStockMode.TRACKED && !isArchived
}
