package io.github.yesidrobayo.shopledger.domain.usecase.document

import io.github.yesidrobayo.shopledger.domain.port.Clock
import io.github.yesidrobayo.shopledger.domain.port.QuoteRepository
import io.github.yesidrobayo.shopledger.domain.port.StorageFailure
import io.github.yesidrobayo.shopledger.domain.port.StorageFailureException
import io.github.yesidrobayo.shopledger.domain.port.TransactionRunner
import io.github.yesidrobayo.shopledger.domain.port.TxResult

/** Resultado de [CancelQuoteUseCase]. */
sealed interface CancelQuoteResult {
    data object Cancelled : CancelQuoteResult

    /** Ya no existe, o ya se convirtió o canceló: una cancelada no se reabre. */
    data object NotOpen : CancelQuoteResult

    /** La base no pudo escribir, por [failure]. */
    data class StorageFailed(val failure: StorageFailure) : CancelQuoteResult
}

/**
 * Cancela una cotización abierta. Queda en el historial, sin acciones: no se borra, porque la
 * persona puede querer ver qué ofreció. No toca stock ni dinero, porque la cotización nunca los tocó.
 */
class CancelQuoteUseCase(
    private val transactions: TransactionRunner,
    private val quotes: QuoteRepository,
    private val clock: Clock,
) {
    suspend operator fun invoke(quoteId: Long): CancelQuoteResult = try {
        transactions.write {
            if (quotes.markCancelled(quoteId, clock.now())) {
                TxResult.Commit(CancelQuoteResult.Cancelled)
            } else {
                TxResult.Rollback(CancelQuoteResult.NotOpen)
            }
        }
    } catch (failure: StorageFailureException) {
        CancelQuoteResult.StorageFailed(failure.failure)
    }
}
