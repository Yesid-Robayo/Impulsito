package io.github.yesidrobayo.shopledger.domain.usecase.money

import io.github.yesidrobayo.shopledger.domain.model.money.MoneyMovementType
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
    /** Quedó compensado por el movimiento [voidMovementId]. */
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

    /** Es una parte de una transferencia: se anula la transferencia entera, nunca una sola parte. */
    data object TransferPart : VoidMoneyMovementResult

    /** El saldo de la cuenta no cabría en `Long` con la anulación. */
    data object Overflow : VoidMoneyMovementResult

    /** Este teléfono no conoce la zona del negocio [storedZoneId]: no hay cómo fechar la anulación. */
    data class UnknownTimeZone(val storedZoneId: String) : VoidMoneyMovementResult

    /** La base no pudo escribir, por [failure]. No quedó nada guardado. */
    data class StorageFailed(val failure: StorageFailure) : VoidMoneyMovementResult
}

/**
 * Anula un movimiento que la persona registró a mano en una cuenta: un saldo inicial, un retiro, un
 * aporte u otro ingreso. Agrega un `MOVEMENT_VOID` con el monto opuesto, en una sola transacción, y
 * el movimiento original queda en el libro, marcado como anulado. Nada se borra.
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
        when (movement.type) {
            MoneyMovementType.TRANSFER_OUT, MoneyMovementType.TRANSFER_IN ->
                return TxResult.Rollback(VoidMoneyMovementResult.TransferPart)

            else -> if (!movement.type.isManual) {
                return TxResult.Rollback(VoidMoneyMovementResult.FromDocument)
            }
        }
        if (ledger.isCompensated(movementId)) {
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
        return when (val appended = writer.void(movement, calendar.stamp(now), now, note)) {
            is MoneyAppendResult.Appended ->
                TxResult.Commit(VoidMoneyMovementResult.Voided(appended.movementId))

            MoneyAppendResult.AccountNotFound -> TxResult.Rollback(VoidMoneyMovementResult.NotFound)

            MoneyAppendResult.Overflow -> TxResult.Rollback(VoidMoneyMovementResult.Overflow)
        }
    }
}
