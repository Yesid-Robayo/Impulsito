package io.github.yesidrobayo.shopledger.domain.model.customer

import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.Money
import kotlin.time.Instant

/**
 * De dónde sale una fila del libro del cliente. Cada tipo tiene el suyo: la venta de un cargo a
 * crédito, la devolución de un saldo a favor y la fila que compensa un `ENTRY_VOID`. Los demás no
 * salen de otro documento.
 */
sealed interface CustomerEntrySource {
    data object None : CustomerEntrySource

    data class Sale(val saleId: Long) : CustomerEntrySource

    data class SaleReturn(val saleReturnId: Long) : CustomerEntrySource

    data class Reverses(val entryId: Long) : CustomerEntrySource
}

/**
 * Una fila que se va a agregar al libro de [customerId], antes de saber su saldo.
 *
 * [amount] va con signo y nunca es cero: positivo aumenta la deuda. [dueDate] es el vencimiento
 * opcional de un cargo. [note] es libre, hasta 200 caracteres, y obligatoria en un ajuste.
 */
data class CustomerEntryDraft(
    val customerId: Long,
    val type: CustomerLedgerEntryType,
    val amount: Money,
    val source: CustomerEntrySource,
    val dueDate: BusinessDate?,
    val note: String?,
)

/**
 * Una fila lista para guardar: el borrador con el saldo que deja, [balanceAfter], que es el de la
 * fila anterior del cliente más su monto, y el instante y la fecha de negocio de la operación.
 */
data class NewCustomerLedgerEntry(
    val draft: CustomerEntryDraft,
    val balanceAfter: Money,
    val occurredAt: Instant,
    val businessDate: BusinessDate,
)

/** Una fila guardada del libro del cliente. El orden del libro es el de [id]. */
data class CustomerLedgerEntry(
    val id: Long,
    val customerId: Long,
    val type: CustomerLedgerEntryType,
    val amount: Money,
    val balanceAfter: Money,
    val source: CustomerEntrySource,
    val dueDate: BusinessDate?,
    val note: String?,
    val occurredAt: Instant,
    val businessDate: BusinessDate,
)
