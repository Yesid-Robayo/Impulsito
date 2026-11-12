package io.github.yesidrobayo.shopledger.domain.model.purchase

import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.Money
import kotlin.time.Instant

/** De qué proveedor son las compras que muestra el historial. */
sealed interface SupplierFilter {
    /** Las de todos los proveedores y las que no tienen. */
    data object Any : SupplierFilter

    /** Solo las compras sin proveedor, la entrada `Sin proveedor` de la lista de proveedores. */
    data object None : SupplierFilter

    /** Solo las de [supplierId]. */
    data class Of(val supplierId: Long) : SupplierFilter
}

/**
 * Qué compras muestra el historial. [from] y [to] son fechas de negocio incluidas, o `null` sin
 * límite. Con [onlyVoided], solo las anuladas.
 */
data class PurchaseHistoryFilter(
    val from: BusinessDate? = null,
    val to: BusinessDate? = null,
    val supplier: SupplierFilter = SupplierFilter.Any,
    val onlyVoided: Boolean = false,
) {
    /** Si filtra algo: sin filtros, el historial vacío invita a comprar. */
    val isFiltered: Boolean
        get() = from != null || to != null || supplier != SupplierFilter.Any || onlyVoided
}

/**
 * Dónde sigue el historial: la última compra del tramo anterior. Las compras van de la más nueva a
 * la más antigua por [businessDate] y luego por [id], porque varias comparten la fecha y sin el id
 * el tramo siguiente repetiría o saltaría alguna.
 */
data class PurchaseHistoryCursor(val businessDate: BusinessDate, val id: Long)

/**
 * Una fila del historial: lo justo para encontrar una compra. [supplierName] es el nombre de hoy del
 * proveedor, o `null` sin proveedor. [lineCount] es cuántas líneas tiene, y [invoiceReference] el
 * número del documento del proveedor, si se escribió.
 */
data class PurchaseHistoryRow(
    val id: Long,
    val occurredAt: Instant,
    val businessDate: BusinessDate,
    val total: Money,
    val voided: Boolean,
    val supplierName: String?,
    val lineCount: Int,
    val invoiceReference: String?,
) {
    val cursor: PurchaseHistoryCursor get() = PurchaseHistoryCursor(businessDate, id)
}
