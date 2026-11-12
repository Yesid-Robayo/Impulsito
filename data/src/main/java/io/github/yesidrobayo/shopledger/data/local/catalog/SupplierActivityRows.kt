package io.github.yesidrobayo.shopledger.data.local.catalog

import androidx.room3.ColumnInfo

/** Lo comprado a un proveedor, y si tiene historia, tal como lo lee [SupplierDao.observeTotals]. */
internal data class SupplierTotalsRow(
    @ColumnInfo(name = "purchases_count") val purchasesCount: Long,
    @ColumnInfo(name = "active_total") val activeTotal: Long,
    @ColumnInfo(name = "has_history") val hasHistory: Boolean,
)

/** Una compra en el detalle de su proveedor. */
internal data class SupplierPurchaseRow(
    @ColumnInfo(name = "id") val id: Long,
    @ColumnInfo(name = "status") val status: String,
    @ColumnInfo(name = "total") val total: Long,
    @ColumnInfo(name = "invoice_reference") val invoiceReference: String?,
    @ColumnInfo(name = "occurred_at") val occurredAt: Long,
    @ColumnInfo(name = "business_date") val businessDate: String,
)

/** Un producto que tiene al proveedor como habitual. */
internal data class SupplierProductRow(
    @ColumnInfo(name = "id") val id: Long,
    @ColumnInfo(name = "name") val name: String,
)

/** Cuántos productos vigentes tienen a un proveedor como habitual. */
internal data class SupplierProductCountRow(
    @ColumnInfo(name = "supplier_id") val supplierId: Long,
    @ColumnInfo(name = "products") val products: Long,
)
