package io.github.yesidrobayo.shopledger.domain.rule.purchase

import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.purchase.PurchaseDetail

/**
 * Una línea de la compra que corrige otra: el producto, la unidad en que se compró, la cantidad en
 * esa unidad y el total de la línea, como lo trae el documento del proveedor.
 */
data class PurchaseCorrectionLine(
    val productId: Long,
    val unitId: Long,
    val enteredQuantity: QuantityMilli,
    val lineTotal: Money,
)

/**
 * Lo que trae una compra nueva que corrige otra ya anulada: sus líneas, el proveedor, la referencia
 * del documento y la fecha. El costo promedio no viaja: la compra nueva lo calcula de nuevo.
 *
 * [leftOut] cuenta las líneas que no se trajeron porque su producto hoy está archivado, y
 * [supplierLeftOut] dice si el proveedor no se trajo porque hoy está archivado: los dos se avisan.
 */
data class PurchaseCorrection(
    val lines: List<PurchaseCorrectionLine>,
    val supplierId: Long?,
    val invoiceReference: String?,
    val date: BusinessDate,
    val leftOut: Int,
    val supplierLeftOut: Boolean,
)

/** Arma la corrección de una compra guardada. No escribe nada. */
object PurchaseCorrections {
    /**
     * La corrección de [detail]. [supplierAvailable] dice si su proveedor hoy se puede elegir; sin
     * proveedor no importa.
     *
     * - Cada línea trae su cantidad en la unidad en que se compró y su total, no el costo unitario:
     *   el total es lo que dice el documento, y el unitario se deduce de nuevo sin redondeos dobles.
     * - Una línea de un producto hoy archivado queda por fuera y se cuenta.
     * - Las líneas van en el orden de la compra. Un producto repetido sigue repetido, como se compró.
     */
    fun of(detail: PurchaseDetail, supplierAvailable: Boolean): PurchaseCorrection {
        val kept = detail.lines.filter { !it.productArchived }
        val content = detail.purchase.content
        val supplierId = content.supplierId?.takeIf { supplierAvailable }
        return PurchaseCorrection(
            lines = kept.map { line ->
                val stored = line.line.content
                PurchaseCorrectionLine(
                    productId = stored.productId,
                    unitId = stored.unitId,
                    enteredQuantity = stored.enteredQuantity,
                    lineTotal = stored.lineTotal,
                )
            },
            supplierId = supplierId,
            invoiceReference = content.invoiceReference,
            date = content.businessDate,
            leftOut = detail.lines.size - kept.size,
            supplierLeftOut = content.supplierId != null && supplierId == null,
        )
    }
}
