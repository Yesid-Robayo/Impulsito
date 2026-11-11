package io.github.yesidrobayo.shopledger.domain.model.sale

import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import kotlin.time.Instant

/**
 * El comprobante de una venta, tal como se comparte con quien compró: el nombre del negocio si lo
 * tiene, cuándo fue, qué se vendió y el total.
 *
 * Es un modelo aparte a propósito: no tiene costo, margen, ganancia, comisión ni la nota de la
 * venta, así que ningún comprobante puede llevarlos aunque la venta sí los tenga. Tampoco se llama
 * factura: quien lo muestra le agrega la leyenda que lo dice.
 */
data class SaleReceipt(
    val businessName: String?,
    val businessDate: BusinessDate,
    val occurredAt: Instant,
    val lines: List<SaleReceiptLine>,
    val total: Money,
) {
    companion object {
        /**
         * El comprobante de [detail], encabezado por [businessName]. Un nombre en blanco no
         * encabeza nada. Las líneas van en el orden en que se guardaron, con el nombre que tenía
         * cada producto al venderse y lo que cobró cada una.
         */
        fun of(detail: SaleDetail, businessName: String?): SaleReceipt {
            val content = detail.sale.content
            return SaleReceipt(
                businessName = businessName?.let { name -> name.trim().takeIf { it.isNotEmpty() } },
                businessDate = content.businessDate,
                occurredAt = content.occurredAt,
                lines = detail.lines.map { line ->
                    val row = line.content
                    SaleReceiptLine(
                        productName = row.productNameSnapshot,
                        quantity = row.enteredQuantity,
                        unitId = row.unitId,
                        total = row.lineTotal,
                    )
                },
                total = content.total,
            )
        }
    }
}

/** Una línea del comprobante: cuánto, de qué y lo que cobró, sin su costo. */
data class SaleReceiptLine(
    val productName: String,
    val quantity: QuantityMilli,
    val unitId: Long,
    val total: Money,
)
