package io.github.yesidrobayo.shopledger.domain.rule.stock

import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductStockMode
import io.github.yesidrobayo.shopledger.domain.model.stock.StockKey
import io.github.yesidrobayo.shopledger.domain.model.stock.StockMovement
import io.github.yesidrobayo.shopledger.domain.model.stock.StockMovementDraft

/** Lo que devuelve [InMemoryStockBook] al intentar escribir una transacción. */
internal sealed interface BookOutcome {
    /** Se escribió todo, y esto le pasó a la alerta de cada producto movido. */
    data class Committed(val transitions: Map<Long, LowStockTransition>) : BookOutcome

    /** Una fila no pasó las reglas: no se escribió nada. */
    data class Rejected(val check: StockMovementCheck) : BookOutcome

    /** Una fila del documento no se puede anular: no se escribió nada. */
    data class NotVoidable(val compensation: StockCompensation) : BookOutcome
}

/**
 * Un libro de stock en memoria que hace lo que harán la base y los casos de uso, con las reglas del
 * dominio: revisa cada fila con [StockMovementRules], escribe todo o nada, mueve en la misma
 * transacción el saldo de cada producto y de cada parte, guarda el saldo después de cada fila y
 * aplica a las alertas lo que decide [LowStockEvaluator].
 *
 * Solo existe para la prueba de propiedades. Lleva los saldos de forma incremental, como la base,
 * para compararlos después con lo que se deriva del libro.
 */
internal class InMemoryStockBook private constructor(
    private val minStock: Map<Long, QuantityMilli>,
    private val rows: MutableList<StockMovement>,
    private val compensatedIds: MutableSet<Long>,
    private val onHand: MutableMap<Long, QuantityMilli>,
    private val onHandByKey: MutableMap<StockKey, QuantityMilli>,
    private val balanceAfter: MutableMap<Long, QuantityMilli>,
    private val activeAlerts: MutableSet<Long>,
) {
    /**
     * Un negocio nuevo con los productos de [minStock], todos con saldo cero. Un producto que nace
     * bajo, como cualquiera con saldo cero, empieza con su alerta activa y sin aviso.
     */
    constructor(minStock: Map<Long, QuantityMilli>) : this(
        minStock = minStock,
        rows = mutableListOf(),
        compensatedIds = mutableSetOf(),
        onHand = minStock.keys.associateWithTo(mutableMapOf()) { QuantityMilli.ZERO },
        onHandByKey = mutableMapOf(),
        balanceAfter = mutableMapOf(),
        activeAlerts = minStock.filterValues { QuantityMilli.ZERO <= it }.keys.toMutableSet(),
    )

    val productIds: Set<Long> get() = minStock.keys
    val movements: List<StockMovement> get() = rows.toList()
    val balances: Map<Long, QuantityMilli> get() = onHand.toMap()
    val balancesByKey: Map<StockKey, QuantityMilli> get() = onHandByKey.toMap()
    val recordedBalancesAfter: Map<Long, QuantityMilli> get() = balanceAfter.toMap()
    val productsWithActiveAlert: Set<Long> get() = activeAlerts.toSet()

    fun minStockOf(productId: Long): QuantityMilli = minStock.getValue(productId)

    fun isCompensated(movementId: Long): Boolean = movementId in compensatedIds

    fun movement(id: Long): StockMovement? =
        if (id in 1L..rows.size) rows[(id - 1L).toInt()] else null

    /** Escribe [drafts] en una sola transacción, o nada si una fila no pasa las reglas. */
    fun record(drafts: List<StockMovementDraft>): BookOutcome {
        val subjects =
            drafts.map { it.key.productId }.distinct().map { productId ->
                LowStockSubject(
                    productId = productId,
                    stockMode = ProductStockMode.TRACKED,
                    isArchived = false,
                    minStock = minStock.getValue(productId),
                    onHandBefore = onHand.getValue(productId),
                    hasActiveAlert = productId in activeAlerts,
                )
            }
        val running = onHand.toMutableMap()
        val compensatedInThisTransaction = mutableSetOf<Long>()
        for (draft in drafts) {
            val productId = draft.key.productId
            val original = draft.reversesMovementId?.let { movement(it) }
            val isAlreadyCompensated =
                original != null &&
                    (original.id in compensatedIds || original.id in compensatedInThisTransaction)
            val check =
                StockMovementRules.check(
                    draft,
                    running.getValue(productId),
                    original,
                    isAlreadyCompensated,
                )
            if (check != StockMovementCheck.Valid) return BookOutcome.Rejected(check)
            running[productId] = running.getValue(productId) + draft.quantity
            draft.reversesMovementId?.let { compensatedInThisTransaction += it }
        }

        for (draft in drafts) {
            val row = draft.recordedAs(rows.size + 1L)
            val productId = row.key.productId
            rows += row
            onHand[productId] = onHand.getValue(productId) + row.quantity
            onHandByKey[row.key] =
                onHandByKey.getOrElse(row.key) { QuantityMilli.ZERO } + row.quantity
            balanceAfter[row.id] = onHand.getValue(productId)
            row.reversesMovementId?.let { compensatedIds += it }
        }
        val transitions = LowStockEvaluator.evaluateTransaction(subjects, drafts)
        for ((productId, transition) in transitions) {
            when (transition) {
                is LowStockTransition.Crossed ->
                    check(activeAlerts.add(productId)) { "Una segunda alerta activa" }

                LowStockTransition.Rearmed ->
                    check(activeAlerts.remove(productId)) { "Se resolvió una alerta inexistente" }

                LowStockTransition.Unchanged, LowStockTransition.NotWatched -> Unit
            }
        }
        return BookOutcome.Committed(transitions)
    }

    /** Anula las filas [movementIds] de un documento en una sola transacción. */
    fun void(movementIds: List<Long>): BookOutcome {
        val drafts =
            movementIds.map { id ->
                val original = requireNotNull(movement(id)) { "La fila $id no existe" }
                val compensation = StockMovementRules.compensationOf(original, isCompensated(id))
                when (compensation) {
                    is StockCompensation.Ready -> compensation.draft

                    StockCompensation.NotCompensable,
                    StockCompensation.AlreadyCompensated,
                    -> return BookOutcome.NotVoidable(compensation)
                }
            }
        return record(drafts)
    }

    /** Una copia independiente, para seguir dos caminos desde el mismo punto. */
    fun copy(): InMemoryStockBook = InMemoryStockBook(
        minStock = minStock,
        rows = rows.toMutableList(),
        compensatedIds = compensatedIds.toMutableSet(),
        onHand = onHand.toMutableMap(),
        onHandByKey = onHandByKey.toMutableMap(),
        balanceAfter = balanceAfter.toMutableMap(),
        activeAlerts = activeAlerts.toMutableSet(),
    )
}
