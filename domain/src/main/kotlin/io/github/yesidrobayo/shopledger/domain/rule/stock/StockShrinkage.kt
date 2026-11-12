package io.github.yesidrobayo.shopledger.domain.rule.stock

import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.ReportPeriod
import io.github.yesidrobayo.shopledger.domain.model.stock.StockMovementRecord
import io.github.yesidrobayo.shopledger.domain.model.stock.StockMovementType

/**
 * La merma: lo que salió del inventario sin venderse, valorado al costo de cada ajuste.
 *
 * No es un gasto que la persona registra: sale del libro de stock, así que no se duplica, y un
 * sobrante puede restarla. Comprar no es gasto, porque el dinero pasa al inventario; sin la merma,
 * lo dañado o regalado nunca llegaría a ningún reporte y la ganancia neta quedaría inflada.
 */
object StockShrinkage {
    /**
     * La merma neta de [period]: la suma, con el signo cambiado, del valor de los ajustes vigentes
     * con fecha de negocio dentro del período. Positiva es pérdida; negativa, un sobrante, que
     * reduce los gastos del período.
     *
     * [movements] es el libro, con sus anulaciones: un ajuste anulado deja de contar en su propia
     * fecha, aunque se haya anulado después, y su compensatorio no cuenta en ninguna. Un inventario
     * inicial nunca es merma.
     *
     * Lanza [ArithmeticException] si la suma no cabe en `Long`, como toda suma de dinero.
     */
    fun net(movements: Iterable<StockMovementRecord>, period: ReportPeriod): Money {
        val records = movements.toList()
        val effectiveIds = StockLedger.effective(records.map { it.movement })
            .mapTo(HashSet()) { it.id }
        var total = Money.ZERO
        for (record in records) {
            val counts = record.movement.type == StockMovementType.ADJUSTMENT &&
                record.id in effectiveIds && record.businessDate in period
            if (counts) total -= record.totalCost
        }
        return total
    }
}
