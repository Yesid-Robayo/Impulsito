package io.github.yesidrobayo.shopledger.domain.model.sale

import io.github.yesidrobayo.shopledger.domain.model.DocumentStatus
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.returns.LineAmounts
import io.github.yesidrobayo.shopledger.domain.model.returns.SaleReturn

/**
 * Una venta guardada con todo lo que muestra su detalle: la cabecera, sus líneas en el orden en que
 * se guardaron y sus pagos. [archivedProductIds] son los productos de sus líneas que hoy están
 * archivados: la línea conserva su nombre congelado, y el detalle lo marca.
 *
 * [returns] son sus devoluciones, activas y anuladas, en el orden en que se registraron, y
 * [returned] lo que ya devolvieron las activas de cada línea, por id de línea.
 *
 * [notSoldProductIds] son los productos de sus líneas que hoy son insumos. La venta guardada no
 * cambia, pero una corrección no puede volver a venderlos.
 */
data class SaleDetail(
    val sale: Sale,
    val lines: List<SaleLine>,
    val payments: List<SalePayment>,
    val archivedProductIds: Set<Long>,
    val returns: List<SaleReturn> = emptyList(),
    val returned: Map<Long, LineAmounts> = emptyMap(),
    val notSoldProductIds: Set<Long> = emptySet(),
) {
    /** Si alguna devolución sigue activa: entonces la venta no se anula, y se devuelve el resto. */
    val hasActiveReturns: Boolean
        get() = returns.any { it.status == DocumentStatus.ACTIVE }

    /** Si la venta se puede anular: está activa y no tiene devoluciones activas. */
    val canBeVoided: Boolean
        get() = sale.status == DocumentStatus.ACTIVE && !hasActiveReturns

    /** Si queda algo por devolver: la venta está activa y alguna línea tiene algo pendiente. */
    val canBeReturned: Boolean
        get() = sale.status == DocumentStatus.ACTIVE &&
            lines.any { remainingOf(it) > QuantityMilli.ZERO }

    /** Lo que falta por devolver de [line]: lo vendido menos lo devuelto en devoluciones activas. */
    fun remainingOf(line: SaleLine): QuantityMilli =
        line.content.quantity - (returned[line.id]?.quantity ?: QuantityMilli.ZERO)
}
