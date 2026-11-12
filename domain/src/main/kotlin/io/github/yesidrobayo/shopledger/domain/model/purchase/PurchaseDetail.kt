package io.github.yesidrobayo.shopledger.domain.model.purchase

import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.catalog.MeasureUnit

/**
 * Una compra con lo que hace falta para mostrarla y anularla.
 *
 * [supplierName] es el nombre de hoy del proveedor, o `null` sin proveedor. Cada línea lleva el
 * nombre de hoy de su producto y su unidad. [voidEffects] dice, por producto, cuánto sale del stock
 * al anular y cuánto quedaría: una anulación puede dejar el stock negativo, y se avisa antes.
 * [voidedOn] es la fecha de negocio de la anulación, o `null` si no está anulada o el teléfono no
 * conoce la zona del negocio.
 */
data class PurchaseDetail(
    val purchase: Purchase,
    val supplierName: String?,
    val lines: List<PurchaseDetailLine>,
    val voidEffects: List<VoidStockEffect>,
    val voidedOn: BusinessDate? = null,
)

/** Una línea de la compra, con el nombre de su producto, si está archivado, y su unidad. */
data class PurchaseDetailLine(
    val line: PurchaseLine,
    val productName: String,
    val productArchived: Boolean,
    val unit: MeasureUnit?,
)

/**
 * Lo que anular la compra hace con el stock de un producto: [leaving] milésimas salen, y queda
 * [onHandAfter], que puede ser negativo.
 */
data class VoidStockEffect(
    val productId: Long,
    val productName: String,
    val unit: MeasureUnit?,
    val leaving: QuantityMilli,
    val onHandAfter: QuantityMilli,
) {
    /** Si la anulación deja el stock por debajo de cero. */
    val leavesNegative: Boolean get() = onHandAfter < QuantityMilli.ZERO
}
