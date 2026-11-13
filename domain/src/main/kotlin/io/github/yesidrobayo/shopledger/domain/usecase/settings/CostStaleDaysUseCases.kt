package io.github.yesidrobayo.shopledger.domain.usecase.settings

import io.github.yesidrobayo.shopledger.domain.model.settings.NoticeSettings
import io.github.yesidrobayo.shopledger.domain.port.AppSettingsRepository
import io.github.yesidrobayo.shopledger.domain.port.Clock
import io.github.yesidrobayo.shopledger.domain.port.StorageFailure
import io.github.yesidrobayo.shopledger.domain.port.StorageFailureException
import io.github.yesidrobayo.shopledger.domain.port.TransactionRunner
import io.github.yesidrobayo.shopledger.domain.port.TxResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** Los días tras los que un costo se considera viejo, de nuevo cada vez que cambian. */
class ObserveCostStaleDaysUseCase(private val settings: AppSettingsRepository) {
    operator fun invoke(): Flow<Int> =
        settings.observe().map { it.notices.costStaleDays }.distinctUntilChanged()
}

/** Resultado de [SetCostStaleDaysUseCase]. */
sealed interface SetCostStaleDaysResult {
    /** Quedó así, o ya estaba así y no hacía falta escribir. */
    data object Saved : SetCostStaleDaysResult

    /** Negativo o mayor que [NoticeSettings.MAX_COST_STALE_DAYS]. No cambió nada. */
    data object OutOfRange : SetCostStaleDaysResult

    /** La base no pudo escribir, por [failure]. No cambió nada. */
    data class Failed(val failure: StorageFailure) : SetCostStaleDaysResult
}

/**
 * Cambia los días tras los que un costo se marca como viejo en `Revisar`. Cero apaga ese motivo.
 *
 * Es un dato del negocio y viaja en la copia: va en su propia transacción y cuenta como cambio sin
 * respaldar. Si ya estaba así, no escribe ni cuenta un cambio.
 */
class SetCostStaleDaysUseCase(
    private val transactions: TransactionRunner,
    private val settings: AppSettingsRepository,
    private val clock: Clock,
) {
    suspend operator fun invoke(days: Int): SetCostStaleDaysResult {
        if (days !in 0..NoticeSettings.MAX_COST_STALE_DAYS) {
            return SetCostStaleDaysResult.OutOfRange
        }
        return try {
            transactions.write {
                if (settings.get().notices.costStaleDays == days) {
                    TxResult.Rollback(SetCostStaleDaysResult.Saved)
                } else {
                    settings.updateCostStaleDays(days, clock.now())
                    TxResult.Commit(SetCostStaleDaysResult.Saved)
                }
            }
        } catch (failure: StorageFailureException) {
            SetCostStaleDaysResult.Failed(failure.failure)
        }
    }
}
