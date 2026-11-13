package io.github.yesidrobayo.shopledger.domain.model.customer

import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import kotlin.time.Instant

/**
 * Por qué cambian los puntos de un cliente. Los puntos no son dinero: ninguno de estos tipos mueve
 * una cuenta, una deuda ni un saldo a favor.
 */
enum class LoyaltyEntryType {
    /** Lo que gana una venta con cliente. Siempre suma, y lleva la venta. */
    EARN,

    /** Lo que se canjea en una venta como descuento. Siempre resta, y lleva la venta. */
    REDEEM,

    /** Una corrección hecha a mano. Cualquier signo. */
    ADJUSTMENT,

    /** Compensa otra fila con los puntos opuestos. Una fila se compensa una sola vez. */
    ENTRY_VOID,
    ;

    /** Si los puntos pueden ser [positive]: ganar solo suma y canjear solo resta. */
    fun admits(positive: Boolean): Boolean = when (this) {
        EARN -> positive
        REDEEM -> !positive
        ADJUSTMENT, ENTRY_VOID -> true
    }
}

/**
 * De dónde sale una fila del libro de puntos: la venta en que se ganó o se canjeó, o la fila que
 * compensa un `ENTRY_VOID`. Un ajuste no sale de otro documento.
 */
sealed interface LoyaltyEntrySource {
    data object None : LoyaltyEntrySource

    data class Sale(val saleId: Long) : LoyaltyEntrySource

    data class Reverses(val entryId: Long) : LoyaltyEntrySource
}

/**
 * Una fila que se va a agregar al libro de puntos de [customerId], antes de saber su saldo.
 * [points] van con signo y nunca son cero. [note] es libre, hasta 200 caracteres.
 */
data class LoyaltyEntryDraft(
    val customerId: Long,
    val type: LoyaltyEntryType,
    val points: Long,
    val source: LoyaltyEntrySource,
    val note: String?,
)

/**
 * Una fila lista para guardar: el borrador con el saldo de puntos que deja, [balanceAfter], que es
 * el de la fila anterior del cliente más sus puntos, y el instante y la fecha de la operación.
 */
data class NewLoyaltyLedgerEntry(
    val draft: LoyaltyEntryDraft,
    val balanceAfter: Long,
    val occurredAt: Instant,
    val businessDate: BusinessDate,
)

/** Una fila guardada del libro de puntos. El orden del libro es el de [id]. */
data class LoyaltyLedgerEntry(
    val id: Long,
    val customerId: Long,
    val type: LoyaltyEntryType,
    val points: Long,
    val balanceAfter: Long,
    val source: LoyaltyEntrySource,
    val note: String?,
    val occurredAt: Instant,
    val businessDate: BusinessDate,
)
