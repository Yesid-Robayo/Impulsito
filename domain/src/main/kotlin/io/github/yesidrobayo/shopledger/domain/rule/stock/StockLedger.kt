package io.github.yesidrobayo.shopledger.domain.rule.stock

import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.stock.StockKey
import io.github.yesidrobayo.shopledger.domain.model.stock.StockMovement

/**
 * Lo que se deriva del libro de stock: saldos, saldos parciales y libro efectivo.
 *
 * El libro es la verdad. El saldo que guarda cada producto es una copia que se actualiza en la
 * misma transacción que sus filas, y siempre debe ser igual a la suma de ellas. Estas funciones son
 * esa suma, escrita una sola vez: la usan las pruebas y la validación de una copia para comprobar
 * que el saldo guardado no se desvió.
 *
 * Las sumas son exactas y lanzan [ArithmeticException] si desbordan. Con los topes de cada entrada
 * no ocurre; si ocurre, el libro está dañado y la transacción debe revertirse.
 */
object StockLedger {
    /**
     * Las filas en el orden del libro: por id ascendente.
     *
     * Nunca se ordena por fecha. Un registro con fecha pasada, o un cambio del reloj del teléfono,
     * reordenaría filas ya calculadas y movería costos ya congelados en ventas. El id es el orden
     * en que se registraron, y ese orden no cambia.
     *
     * Lanza [IllegalArgumentException] si dos filas comparten id: un libro así está roto.
     */
    fun inOrder(movements: Iterable<StockMovement>): List<StockMovement> {
        val ordered = movements.sortedBy { it.id }
        for (index in 1 until ordered.size) {
            require(ordered[index].id != ordered[index - 1].id) {
                "Dos filas del libro con el mismo id"
            }
        }
        return ordered
    }

    /**
     * El saldo de cada producto: la suma de las cantidades de todas sus filas.
     *
     * Las filas anuladas y sus compensatorios cuentan, porque cada par suma cero. Un producto sin
     * filas no aparece: su saldo es cero.
     */
    fun onHandByProduct(movements: Iterable<StockMovement>): Map<Long, QuantityMilli> =
        sumBy(movements) { it.key.productId }

    /**
     * El saldo de cada parte del stock, por producto, ubicación y lote. La suma de las partes de un
     * producto es su saldo.
     */
    fun onHandByKey(movements: Iterable<StockMovement>): Map<StockKey, QuantityMilli> =
        sumBy(movements) { it.key }

    /**
     * El saldo de su producto justo después de cada fila, por id de fila: el que la fila guarda al
     * escribirse. Se acumula en el orden del libro, por id, sin importar el orden de [movements].
     */
    fun balancesAfter(movements: Iterable<StockMovement>): Map<Long, QuantityMilli> {
        val running = mutableMapOf<Long, QuantityMilli>()
        val after = mutableMapOf<Long, QuantityMilli>()
        for (movement in inOrder(movements)) {
            val productId = movement.key.productId
            val balance = running.getOrElse(productId) { QuantityMilli.ZERO } + movement.quantity
            running[productId] = balance
            after[movement.id] = balance
        }
        return after
    }

    /**
     * El libro efectivo: las filas que siguen vigentes, en el orden del libro.
     *
     * Quita las filas anuladas y sus compensatorios. Es el libro que habría si los documentos
     * anulados nunca hubieran existido, y de él sale el costo tras una anulación. Su suma por
     * producto es igual al saldo, porque lo que quita son pares que suman cero.
     */
    fun effective(movements: Iterable<StockMovement>): List<StockMovement> {
        val ordered = inOrder(movements)
        val compensatedIds = ordered.mapNotNullTo(HashSet()) { it.reversesMovementId }
        return ordered.filter { it.reversesMovementId == null && it.id !in compensatedIds }
    }

    private fun <K> sumBy(
        movements: Iterable<StockMovement>,
        keyOf: (StockMovement) -> K,
    ): Map<K, QuantityMilli> {
        val totals = mutableMapOf<K, QuantityMilli>()
        for (movement in inOrder(movements)) {
            val key = keyOf(movement)
            totals[key] = totals.getOrElse(key) { QuantityMilli.ZERO } + movement.quantity
        }
        return totals
    }
}
