package io.github.yesidrobayo.shopledger.domain.model.purchase

import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.Money
import kotlin.time.Instant

/**
 * La cabecera de una compra que el caso de uso va a confirmar, todavía sin id.
 *
 * Los totales se calculan antes, en memoria, y se guardan para que un reporte no tenga que leer las
 * líneas. [costTotal] es lo que entra al costo del stock: sin el impuesto si es recuperable.
 * [amountPaid] es lo pagado al registrarla, de cero a [total], y no cambia: lo que se pague después
 * va al libro del proveedor. Pagar menos que el total exige proveedor. [dueDate] es el vencimiento,
 * si queda deuda. [foreignCurrency] solo existe si se compró en otra moneda.
 */
data class NewPurchase(
    val supplierId: Long?,
    val invoiceReference: String?,
    val netTotal: Money,
    val taxTotal: Money,
    val total: Money,
    val costTotal: Money,
    val amountPaid: Money,
    val moneyAccountId: Long?,
    val dueDate: BusinessDate?,
    val locationId: Long,
    val foreignCurrency: PurchaseForeignCurrency?,
    val occurredAt: Instant,
    val businessDate: BusinessDate,
    val createdAt: Instant,
)
