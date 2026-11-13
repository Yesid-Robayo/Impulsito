package io.github.yesidrobayo.shopledger.domain.usecase.money

import io.github.yesidrobayo.shopledger.domain.model.money.MoneyOrigin
import io.github.yesidrobayo.shopledger.domain.port.AppSettingsRepository
import io.github.yesidrobayo.shopledger.domain.port.Clock
import io.github.yesidrobayo.shopledger.domain.port.MoneyLedgerRepository
import io.github.yesidrobayo.shopledger.domain.port.StorageFailure
import io.github.yesidrobayo.shopledger.domain.port.TransactionRunner
import io.github.yesidrobayo.shopledger.domain.port.TxResult
import io.github.yesidrobayo.shopledger.domain.rule.region.BusinessCalendar
import io.github.yesidrobayo.shopledger.domain.rule.region.BusinessCalendarResult
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.storageSafe

/** Cómo termina anular un movimiento de dinero. Salvo [Voided], no se escribió nada. */
sealed interface VoidMoneyMovementResult {
    /**
     * Quedó compensado por el movimiento [voidMovementId]. En una transferencia, sus dos partes
     * quedaron anuladas, y este es el de la parte pedida.
     */
    data class Voided(val voidMovementId: Long) : VoidMoneyMovementResult

    /** El movimiento ya no existe. */
    data object NotFound : VoidMoneyMovementResult

    /** Ya tiene su anulación: un movimiento se anula una sola vez. */
    data object AlreadyVoided : VoidMoneyMovementResult

    /**
     * Viene de un documento, como una venta o un gasto, o es una anulación: se anula desde ese
     * documento, para que el documento y su dinero digan lo mismo.
     */
    data object FromDocument : VoidMoneyMovementResult

    /** El saldo de la cuenta no cabría en `Long` con la anulación. */
    data object Overflow : VoidMoneyMovementResult

    /** Este teléfono no conoce la zona del negocio [storedZoneId]: no hay cómo fechar la anulación. */
    data class UnknownTimeZone(val storedZoneId: String) : VoidMoneyMovementResult

    /** La base no pudo escribir, por [failure]. No quedó nada guardado. */
    data class StorageFailed(val failure: StorageFailure) : VoidMoneyMovementResult
}

/**
 * Anula un movimiento que la persona registró a mano en una cuenta: un saldo inicial, un retiro, un
 * aporte, otro ingreso o una transferencia. Agrega un `MOVEMENT_VOID` con el monto opuesto, en una
 * sola transacción, y el movimiento original queda en el libro, marcado como anulado. Nada se borra.
 * Una transferencia se anula entera: un `MOVEMENT_VOID` por cada una de sus dos partes, cada uno en
 * su cuenta y su moneda, y si uno no entra no entra ninguno.
 *
 * La anulación ocurre hoy, no en la fecha del original: el saldo de los días pasados no cambia. Puede
 * dejar la cuenta en negativo, y no se bloquea por eso.
 */
class VoidMoneyMovementUseCase(
    private val transactions: TransactionRunner,
    private val settings: AppSettingsRepository,
    private val ledger: MoneyLedgerRepository,
    private val writer: MoneyLedgerWriter,
    private val clock: Clock,
) {
    suspend operator fun invoke(movementId: Long, note: String? = null): VoidMoneyMovementResult =
        storageSafe(VoidMoneyMovementResult::StorageFailed) {
            transactions.write { void(movementId, note) }
        }

    private suspend fun void(movementId: Long, note: String?): TxResult<VoidMoneyMovementResult> {
        val movement = ledger.findById(movementId)
            ?: return TxResult.Rollback(VoidMoneyMovementResult.NotFound)
        if (!movement.type.isManual) {
            return TxResult.Rollback(VoidMoneyMovementResult.FromDocument)
        }
        // Una transferencia se anula entera: sus dos partes, nunca una sola.
        val parts = when (val origin = movement.draft.origin) {
            is MoneyOrigin.Transfer -> ledger.findByTransferGroup(origin.groupId)
            else -> listOf(movement)
        }
        if (parts.any { ledger.isCompensated(it.id) }) {
            return TxResult.Rollback(VoidMoneyMovementResult.AlreadyVoided)
        }
        val calendar = when (val result = BusinessCalendar.of(settings.get().regional)) {
            is BusinessCalendarResult.Ready -> result.calendar

            is BusinessCalendarResult.UnknownZone ->
                return TxResult.Rollback(
                    VoidMoneyMovementResult.UnknownTimeZone(result.storedZoneId),
                )
        }
        val now = clock.now()
        val time = calendar.stamp(now)
        var voidOfRequested = 0L
        for (part in parts) {
            when (val appended = writer.void(part, time, now, note)) {
                is MoneyAppendResult.Appended ->
                    if (part.id == movementId) voidOfRequested = appended.movementId

                // Si una parte no entra, la transacción entera se deshace: ninguna queda anulada.
                MoneyAppendResult.AccountNotFound ->
                    return TxResult.Rollback(VoidMoneyMovementResult.NotFound)

                MoneyAppendResult.Overflow -> return TxResult.Rollback(
                    VoidMoneyMovementResult.Overflow,
                )
            }
        }
        return TxResult.Commit(VoidMoneyMovementResult.Voided(voidOfRequested))
    }
}
