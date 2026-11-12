package io.github.yesidrobayo.shopledger.domain.model.catalog

import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.DocumentStatus
import io.github.yesidrobayo.shopledger.domain.model.Money
import kotlin.time.Instant

/**
 * Un proveedor en su lista, con cuántos productos vigentes lo tienen como habitual. La fila muestra
 * su teléfono o, sin teléfono, ese número.
 */
data class SupplierListEntry(val supplier: Supplier, val usualProducts: Int)

/** Los proveedores vigentes y los archivados, cada grupo en el orden de su nombre. */
data class SuppliersOverview(
    val active: List<SupplierListEntry>,
    val archived: List<SupplierListEntry>,
)

/**
 * Lo que se le ha comprado a un proveedor.
 *
 * [purchasesCount] cuenta todas sus compras, también las anuladas, porque el detalle las muestra
 * todas. [activeTotal] suma solo las vigentes: una anulada ya no es dinero gastado. [hasHistory] dice
 * si tiene compras, devoluciones o filas en su libro de cuenta, aunque estén anuladas: con historia
 * solo se archiva.
 */
data class SupplierTotals(
    val purchasesCount: Int,
    val activeTotal: Money,
    val hasHistory: Boolean,
) {
    companion object {
        /** Un proveedor sin nada registrado. */
        val NONE: SupplierTotals = SupplierTotals(0, Money.ZERO, hasHistory = false)
    }
}

/** Una compra en el detalle de su proveedor. Anulada, se muestra marcada y no suma al total. */
data class SupplierPurchase(
    val id: Long,
    val status: DocumentStatus,
    val total: Money,
    val invoiceReference: String?,
    val occurredAt: Instant,
    val businessDate: BusinessDate,
)

/** Un producto vigente que tiene al proveedor como habitual. */
data class SupplierProduct(val id: Long, val name: String)

/**
 * Todo lo que muestra el detalle de un proveedor: sus datos, lo comprado, sus compras de la más
 * nueva a la más antigua y los productos que lo tienen como habitual.
 */
data class SupplierOverview(
    val supplier: Supplier,
    val totals: SupplierTotals,
    val purchases: List<SupplierPurchase>,
    val usualProducts: List<SupplierProduct>,
)
