package io.github.yesidrobayo.shopledger.domain.model.payable

import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.Money
import kotlin.time.Instant

/**
 * Una fila del libro de un proveedor que el caso de uso va a agregar, todavía sin id.
 *
 * [amount] va con signo y nunca es cero: positivo aumenta lo que se le debe. [balanceAfter] es lo
 * que se le debe al terminar esta fila: el de la fila anterior del mismo proveedor más [amount].
 * [purchaseId] lo lleva solo un cargo de compra, [purchaseReturnId] solo un crédito por devolución
 * y [reversesEntryId] solo una compensación. [dueDate] es el vencimiento de un cargo.
 */
data class NewSupplierLedgerEntry(
    val supplierId: Long,
    val type: SupplierLedgerEntryType,
    val amount: Money,
    val balanceAfter: Money,
    val purchaseId: Long? = null,
    val purchaseReturnId: Long? = null,
    val reversesEntryId: Long? = null,
    val dueDate: BusinessDate? = null,
    val note: String? = null,
    val occurredAt: Instant,
    val businessDate: BusinessDate,
    val createdAt: Instant,
)

/** Una fila guardada del libro de un proveedor. Nunca se edita ni se borra: se compensa. */
data class SupplierLedgerEntry(val id: Long, val content: NewSupplierLedgerEntry)
