package io.github.yesidrobayo.shopledger.domain.model.sale

import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.Money
import kotlin.time.Instant

/**
 * La cabecera de una venta que el caso de uso va a confirmar, todavía sin id. El id que le da la
 * base es también su número visible.
 *
 * Los totales se calculan antes, en memoria, y se guardan para que un reporte no tenga que leer las
 * líneas: cada uno es la suma de su columna en las líneas. [netTotal] va sin impuestos y
 * [total] con ellos. [totalCost] es el costo de lo vendido, que congela la venta al confirmarse.
 * [orderAdjustment], con signo, es el descuento o recargo de toda la venta. [tipAmount] es la
 * propina, que no entra en ninguna ganancia, y [cashRoundingAmount], con signo, el redondeo del
 * pago en efectivo. [amountDue] es lo que se cobra: total, propina y redondeo.
 *
 * [customerId], [cashSessionId], [quoteId] y [orderId] solo existen si la venta viene de un
 * cliente, una caja abierta, una cotización o un pedido. [note] la escribe la persona.
 */
data class NewSale(
    val netTotal: Money,
    val taxTotal: Money,
    val total: Money,
    val totalCost: Money,
    val totalReplacementCost: Money,
    val orderAdjustment: Money,
    val tipAmount: Money,
    val cashRoundingAmount: Money,
    val amountDue: Money,
    val customerId: Long?,
    val priceListId: Long,
    val locationId: Long,
    val cashSessionId: Long?,
    val quoteId: Long?,
    val orderId: Long?,
    val note: String?,
    val occurredAt: Instant,
    val businessDate: BusinessDate,
    val createdAt: Instant,
)
