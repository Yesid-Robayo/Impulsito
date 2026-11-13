package io.github.yesidrobayo.shopledger.domain.rule.money

import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyMovement
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyOrigin

/**
 * Una fila del extracto de una cuenta: el movimiento tal como quedó en el libro, y si otro ya lo
 * compensó. Una fila compensada no se borra: se muestra anulada, y su anulación aparece aparte.
 */
data class MoneyStatementLine(val movement: MoneyMovement, val voided: Boolean) {
    /**
     * Si se anula desde la cuenta: solo lo registrado a mano y todavía vigente. Lo que viene de un
     * documento se anula desde ese documento, y una anulación nunca se anula.
     */
    val voidable: Boolean get() = movement.type.isManual && !voided
}

/** Los movimientos de un mismo día de negocio, del más reciente al más viejo. */
data class MoneyStatementDay(val date: BusinessDate, val lines: List<MoneyStatementLine>)

/** El extracto de una cuenta de dinero, tal como lo lee la persona. */
object MoneyStatement {
    /**
     * Los movimientos de una cuenta agrupados por día de negocio, del día más reciente al más viejo,
     * y dentro de cada día del último al primero en el orden del libro, nunca por hora: el libro
     * manda. Cada fila dice si una anulación del mismo libro la compensa; la anulación siempre va a
     * la cuenta del movimiento que compensa, así que basta el libro de la cuenta.
     */
    fun byDay(movements: List<MoneyMovement>): List<MoneyStatementDay> {
        val compensated = movements
            .mapNotNull { (it.draft.origin as? MoneyOrigin.Void)?.reversesMovementId }
            .toSet()
        return movements
            .sortedByDescending { it.id }
            .map { MoneyStatementLine(it, voided = it.id in compensated) }
            .groupBy { it.movement.time.businessDate }
            .map { (date, lines) -> MoneyStatementDay(date, lines) }
            .sortedWith { a, b -> b.date.compareTo(a.date) }
    }
}
