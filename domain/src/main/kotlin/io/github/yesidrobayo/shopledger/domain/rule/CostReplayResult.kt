package io.github.yesidrobayo.shopledger.domain.rule

import io.github.yesidrobayo.shopledger.domain.model.stock.CostState

/**
 * Resultado de [WeightedAverageCost.replayCostState].
 *
 * El libro que se reproduce puede venir de una copia restaurada, que nunca es de confianza. Por eso
 * una fila imposible no lanza una excepción: se devuelve como resultado, con su identificador, y
 * quien valida la copia decide qué hacer con ella.
 */
sealed interface CostReplayResult {
    /** El libro efectivo se reprodujo entero, y este es el estado que deja. */
    data class Replayed(val state: CostState) : CostReplayResult

    /**
     * La fila [movementId] contradice su tipo: una entrada que no suma, una salida que no resta,
     * una corrección de costo que mueve unidades o con costo negativo, o una anulación que no
     * apunta a ninguna fila. La app nunca escribe una así.
     */
    data class InvalidMovement(val movementId: Long) : CostReplayResult

    /** Las cifras de la fila [movementId] no caben en `Long` al reproducirla. */
    data class Overflow(val movementId: Long) : CostReplayResult
}
