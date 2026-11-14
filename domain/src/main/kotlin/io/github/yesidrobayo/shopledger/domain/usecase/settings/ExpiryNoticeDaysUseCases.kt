package io.github.yesidrobayo.shopledger.domain.usecase.settings

import io.github.yesidrobayo.shopledger.domain.model.settings.NoticeSettings
import io.github.yesidrobayo.shopledger.domain.port.AppSettingsRepository
import io.github.yesidrobayo.shopledger.domain.port.Clock
import io.github.yesidrobayo.shopledger.domain.port.StorageFailure
import io.github.yesidrobayo.shopledger.domain.port.StorageFailureException
import io.github.yesidrobayo.shopledger.domain.port.TransactionRunner
import io.github.yesidrobayo.shopledger.domain.port.TxResult

/** Resultado de [SetExpiryNoticeDaysUseCase]. */
sealed interface SetExpiryNoticeDaysResult {
    /** Quedó así, o ya estaba así y no hacía falta escribir. */
    data object Saved : SetExpiryNoticeDaysResult

    /** Negativo o mayor que [NoticeSettings.MAX_EXPIRY_NOTICE_DAYS]. No cambió nada. */
    data object OutOfRange : SetExpiryNoticeDaysResult

    /** La base no pudo escribir, por [failure]. No cambió nada. */
    data class Failed(val failure: StorageFailure) : SetExpiryNoticeDaysResult
}

/**
 * Cambia los días antes del vencimiento en que se avisa de un lote, para los productos que no
 * fijan los suyos. Cero avisa el mismo día que vence.
 *
 * Es un dato del negocio y viaja en la copia: va en su propia transacción y cuenta como cambio sin
 * respaldar. Si ya estaba así, no escribe ni cuenta un cambio. Los lotes ya avisados no se vuelven
 * a avisar: la lista `Por vencer` sí cambia al momento.
 */
class SetExpiryNoticeDaysUseCase(
    private val transactions: TransactionRunner,
    private val settings: AppSettingsRepository,
    private val clock: Clock,
) {
    suspend operator fun invoke(days: Int): SetExpiryNoticeDaysResult {
        if (days !in 0..NoticeSettings.MAX_EXPIRY_NOTICE_DAYS) {
            return SetExpiryNoticeDaysResult.OutOfRange
        }
        return try {
            transactions.write {
                if (settings.get().notices.expiryNoticeDays == days) {
                    TxResult.Rollback(SetExpiryNoticeDaysResult.Saved)
                } else {
                    settings.updateExpiryNoticeDays(days, clock.now())
                    TxResult.Commit(SetExpiryNoticeDaysResult.Saved)
                }
            }
        } catch (failure: StorageFailureException) {
            SetExpiryNoticeDaysResult.Failed(failure.failure)
        }
    }
}
