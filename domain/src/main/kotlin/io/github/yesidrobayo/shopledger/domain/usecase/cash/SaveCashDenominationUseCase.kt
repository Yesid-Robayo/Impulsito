package io.github.yesidrobayo.shopledger.domain.usecase.cash

import io.github.yesidrobayo.shopledger.domain.model.Limit
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.port.CashDenominationRepository
import io.github.yesidrobayo.shopledger.domain.port.Clock
import io.github.yesidrobayo.shopledger.domain.port.StorageFailure
import io.github.yesidrobayo.shopledger.domain.port.StorageFailureException
import io.github.yesidrobayo.shopledger.domain.port.TransactionRunner
import io.github.yesidrobayo.shopledger.domain.port.TxResult

/**
 * Crea o cambia un billete o una moneda de la persona. Los valores los escribe ella, en la unidad
 * menor de su moneda: la app no trae ninguno. Una denominación no se borra, porque los conteos
 * viejos la nombran: se desactiva.
 */
class SaveCashDenominationUseCase(
    private val transactions: TransactionRunner,
    private val denominations: CashDenominationRepository,
    private val clock: Clock,
) {
    /** Crea una nueva con [value], o cambia la [id] si no es `null`. */
    suspend operator fun invoke(
        id: Long?,
        value: Money,
        isActive: Boolean = true,
        sortOrder: Int = 0,
    ): SaveCashDenominationResult = try {
        transactions.write { save(id, value, isActive, sortOrder) }
    } catch (failure: StorageFailureException) {
        SaveCashDenominationResult.StorageFailed(failure.failure)
    }

    private suspend fun save(
        id: Long?,
        value: Money,
        isActive: Boolean,
        sortOrder: Int,
    ): TxResult<SaveCashDenominationResult> {
        if (value <= Money.ZERO || !Limit.UNIT_MONEY.allows(value.minor)) {
            return TxResult.Rollback(SaveCashDenominationResult.ValueOutOfRange)
        }
        val now = clock.now()
        if (id == null) {
            return TxResult.Commit(
                SaveCashDenominationResult.Saved(denominations.insert(value, sortOrder, now)),
            )
        }
        denominations.findById(id) ?: return TxResult.Rollback(SaveCashDenominationResult.NotFound)
        denominations.update(id, value, isActive, sortOrder, now)
        return TxResult.Commit(SaveCashDenominationResult.Saved(id))
    }
}

/** Cómo termina guardar una denominación. Salvo [Saved], no se escribió nada. */
sealed interface SaveCashDenominationResult {
    data class Saved(val id: Long) : SaveCashDenominationResult

    /** El valor es cero, negativo o pasa del máximo de un precio. */
    data object ValueOutOfRange : SaveCashDenominationResult

    /** La denominación ya no existe. */
    data object NotFound : SaveCashDenominationResult

    data class StorageFailed(val failure: StorageFailure) : SaveCashDenominationResult
}
