package io.github.yesidrobayo.shopledger.data.local.money

import androidx.room3.ColumnInfo

/** Lo que falta por llegar a una cuenta, en la unidad menor de su moneda. */
internal data class PendingSettlementRow(
    @ColumnInfo(name = "account_id") val accountId: Long,
    @ColumnInfo(name = "amount") val amount: Long,
)

/**
 * Los documentos que un movimiento nombra a través de otra tabla. Cada columna es nula si el
 * movimiento no viene de ese documento.
 */
internal data class MovementDocumentLinks(
    @ColumnInfo(name = "sale_id") val saleId: Long?,
    @ColumnInfo(name = "returned_sale_id") val returnedSaleId: Long?,
    @ColumnInfo(name = "customer_id") val customerId: Long?,
    @ColumnInfo(name = "supplier_id") val supplierId: Long?,
    @ColumnInfo(name = "returned_purchase_id") val returnedPurchaseId: Long?,
    @ColumnInfo(name = "returned_supplier_id") val returnedSupplierId: Long?,
)
