package io.github.yesidrobayo.shopledger.domain.rule.customer

import io.github.yesidrobayo.shopledger.domain.model.Checked
import io.github.yesidrobayo.shopledger.domain.model.Limit
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.customer.LoyaltyEntryDraft
import io.github.yesidrobayo.shopledger.domain.model.customer.LoyaltyEntrySource
import io.github.yesidrobayo.shopledger.domain.model.customer.LoyaltyEntryType
import io.github.yesidrobayo.shopledger.domain.model.customer.LoyaltyLedgerEntry
import io.github.yesidrobayo.shopledger.domain.rule.catalog.CatalogText

/** Lo que impide agregar una fila al libro de puntos. */
enum class LoyaltyEntryIssue {
    /** Los puntos son cero: una fila siempre mueve el saldo. */
    ZERO_POINTS,

    /** El signo no es el de su tipo: ganar suma y canjear resta. */
    WRONG_SIGN,

    /** El origen no es el de su tipo, como ganar puntos sin la venta que los dio. */
    WRONG_SOURCE,

    /** La nota pasa de 200 caracteres. */
    NOTE_TOO_LONG,

    /** El saldo que dejaría pasa del tope de puntos de un cliente, hacia arriba o hacia abajo. */
    BALANCE_ABOVE_LIMIT,
}

/** Resultado de [LoyaltyLedgerRules.next]. */
sealed interface LoyaltyEntryCheck {
    /** Se puede agregar, y deja al cliente con [balanceAfter] puntos. */
    data class Valid(val balanceAfter: Long) : LoyaltyEntryCheck

    data class Invalid(val issue: LoyaltyEntryIssue) : LoyaltyEntryCheck
}

/** Resultado de [LoyaltyLedgerRules.redemption]. */
sealed interface RedemptionCheck {
    /** Canjear [points] descuenta [discount] de la venta. */
    data class Discount(val points: Long, val discount: Money) : RedemptionCheck

    /** Se pidieron más puntos de los que tiene: solo hay [available], que puede ser cero o menos. */
    data class NotEnough(val available: Long) : RedemptionCheck

    /** Cero o menos puntos: no hay nada que canjear. */
    data object NothingToRedeem : RedemptionCheck

    /** El descuento no cabe en el tope de un documento: siempre supera la venta. */
    data object AboveSale : RedemptionCheck
}

/**
 * Las reglas del libro de puntos, que es de solo agregar como el del cliente.
 *
 * El saldo de puntos del cliente es la suma de sus filas, y cada fila guarda el saldo que deja: el
 * de la fila anterior más sus puntos. Puede quedar negativo: anular una venta cuyos puntos ya se
 * canjearon los descuenta igual, y se muestra así, sin bloquear la anulación.
 *
 * Los puntos no son dinero: el canje es un descuento de la venta, nunca un pago.
 */
object LoyaltyLedgerRules {
    /** Largo máximo de la nota de una fila. */
    const val NOTE_MAX: Int = 200

    /** El saldo que deja [draft] sobre [previous], el de la última fila, o por qué no entra. */
    fun next(previous: Long, draft: LoyaltyEntryDraft): LoyaltyEntryCheck {
        val issue = issueOf(draft)
        if (issue != null) return LoyaltyEntryCheck.Invalid(issue)
        val balance = try {
            Math.addExact(previous, draft.points)
        } catch (overflow: ArithmeticException) {
            return LoyaltyEntryCheck.Invalid(LoyaltyEntryIssue.BALANCE_ABOVE_LIMIT)
        }
        return if (withinLimit(balance)) {
            LoyaltyEntryCheck.Valid(balance)
        } else {
            LoyaltyEntryCheck.Invalid(LoyaltyEntryIssue.BALANCE_ABOVE_LIMIT)
        }
    }

    /**
     * La fila que compensa [original]: sus puntos opuestos, que apuntan a ella. `null` si
     * [original] ya es un compensatorio: una anulación no se anula.
     */
    fun reversalOf(original: LoyaltyLedgerEntry, note: String?): LoyaltyEntryDraft? =
        if (original.type == LoyaltyEntryType.ENTRY_VOID) {
            null
        } else {
            LoyaltyEntryDraft(
                customerId = original.customerId,
                type = LoyaltyEntryType.ENTRY_VOID,
                points = -original.points,
                source = LoyaltyEntrySource.Reverses(original.id),
                note = note,
            )
        }

    /** [raw] recortada, o `null` si queda en blanco: una nota vacía no se guarda. */
    fun note(raw: String?): String? = raw?.let { it.trim().ifEmpty { null } }

    /**
     * Si se pueden canjear [points] de un cliente que tiene [available], a [pointValue] cada uno.
     * El canje nunca supera el saldo: un saldo negativo no deja canjear nada.
     */
    fun redemption(points: Long, available: Long, pointValue: Money): RedemptionCheck = when {
        points <= 0L -> RedemptionCheck.NothingToRedeem

        points > available -> RedemptionCheck.NotEnough(available)

        else -> when (val discount = LoyaltyProgramRules.discountFor(points, pointValue)) {
            is Checked.Value -> RedemptionCheck.Discount(points, discount.value)
            Checked.Overflow, is Checked.AboveLimit -> RedemptionCheck.AboveSale
        }
    }

    /**
     * Los puntos que de verdad se suman de los [earned] por una venta a un cliente con [balance]:
     * todos, salvo los que pasarían del tope de puntos. Ganar nunca deshace una venta, así que lo
     * que no cabe no se suma.
     */
    fun earnable(earned: Long, balance: Long): Long {
        if (earned <= 0L) return 0L
        // El saldo está dentro del tope, hacia arriba y hacia abajo: la resta cabe en Long.
        val room = Limit.POINTS.max - balance
        return if (room <= 0L) 0L else minOf(earned, room)
    }

    private fun withinLimit(balance: Long): Boolean =
        balance >= -Limit.POINTS.max && Limit.POINTS.allows(balance)

    private fun issueOf(draft: LoyaltyEntryDraft): LoyaltyEntryIssue? {
        val note = draft.note
        return when {
            draft.points == 0L -> LoyaltyEntryIssue.ZERO_POINTS
            !draft.type.admits(positive = draft.points > 0L) -> LoyaltyEntryIssue.WRONG_SIGN
            !sourceFits(draft.type, draft.source) -> LoyaltyEntryIssue.WRONG_SOURCE
            note != null && CatalogText.length(note) > NOTE_MAX -> LoyaltyEntryIssue.NOTE_TOO_LONG
            else -> null
        }
    }

    private fun sourceFits(type: LoyaltyEntryType, source: LoyaltyEntrySource): Boolean =
        when (type) {
            LoyaltyEntryType.EARN, LoyaltyEntryType.REDEEM -> source is LoyaltyEntrySource.Sale
            LoyaltyEntryType.ENTRY_VOID -> source is LoyaltyEntrySource.Reverses
            LoyaltyEntryType.ADJUSTMENT -> source == LoyaltyEntrySource.None
        }
}
