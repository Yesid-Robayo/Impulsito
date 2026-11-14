package io.github.yesidrobayo.shopledger.domain.model.document

import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.Money

/**
 * Una venta activa en la lista de pendientes de facturar, con su marca. No lleva costo, margen ni
 * ganancia: la lista y su resumen solo muestran lo que el cliente ya conoce.
 *
 * - [saleId] es también el número de la venta.
 * - [customerName] es el nombre del cliente de la venta, o `null` si no tiene.
 * - [status] es [FiscalMarkStatus.PENDING] también si la venta no tiene marca.
 * - [hasReturn] dice si la venta tiene una devolución activa: conviene revisar el otro registro.
 */
data class FiscalSale(
    val saleId: Long,
    val businessDate: BusinessDate,
    val total: Money,
    val customerName: String?,
    val status: FiscalMarkStatus,
    val externalReference: String?,
    val hasReturn: Boolean,
)
