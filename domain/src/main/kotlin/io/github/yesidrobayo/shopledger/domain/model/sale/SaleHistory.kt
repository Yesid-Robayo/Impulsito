package io.github.yesidrobayo.shopledger.domain.model.sale

import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.Money
import kotlin.time.Instant

/**
 * Qué ventas muestra el historial. [from] y [to] son fechas de negocio incluidas, o `null` sin
 * límite. Con [paymentMethodId], solo las que tienen un pago con ese método. Con [onlyVoided], solo
 * las anuladas.
 */
data class SaleHistoryFilter(
    val from: BusinessDate? = null,
    val to: BusinessDate? = null,
    val paymentMethodId: Long? = null,
    val onlyVoided: Boolean = false,
) {
    /** Si filtra algo: sin filtros, el historial vacío invita a vender. */
    val isFiltered: Boolean
        get() = from != null || to != null || paymentMethodId != null || onlyVoided
}

/**
 * Dónde sigue el historial: la última venta del tramo anterior. Las ventas van de la más nueva a la
 * más antigua por [occurredAt] y luego por [id], porque varias ventas atrasadas comparten la misma
 * hora y sin el id el tramo siguiente repetiría o saltaría alguna.
 */
data class SaleHistoryCursor(val occurredAt: Instant, val id: Long)

/**
 * Una fila del historial: lo justo para encontrar una venta. [firstProductName] es el nombre de la
 * primera línea tal como se vendió, y [lineCount] cuántas líneas tiene, sin contar extras.
 * [methodName] es el método del primer pago, y [paymentCount] cuántos pagos tuvo.
 */
data class SaleHistoryRow(
    val id: Long,
    val occurredAt: Instant,
    val businessDate: BusinessDate,
    val total: Money,
    val voided: Boolean,
    val firstProductName: String?,
    val lineCount: Int,
    val methodName: String?,
    val paymentCount: Int,
) {
    val cursor: SaleHistoryCursor get() = SaleHistoryCursor(occurredAt, id)
}
