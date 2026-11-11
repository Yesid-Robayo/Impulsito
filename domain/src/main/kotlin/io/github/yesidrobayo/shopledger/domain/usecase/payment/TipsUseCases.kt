package io.github.yesidrobayo.shopledger.domain.usecase.payment

import io.github.yesidrobayo.shopledger.domain.port.AppSettingsRepository
import io.github.yesidrobayo.shopledger.domain.port.Clock
import io.github.yesidrobayo.shopledger.domain.port.StorageFailure
import io.github.yesidrobayo.shopledger.domain.port.StorageFailureException
import io.github.yesidrobayo.shopledger.domain.port.TransactionRunner
import io.github.yesidrobayo.shopledger.domain.port.TxResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Si el cobro ofrece propina. Apagadas, la propina no aparece en ninguna pantalla. Vuelve a emitir
 * solo cuando cambia.
 */
class ObserveTipsEnabledUseCase(private val settings: AppSettingsRepository) {
    operator fun invoke(): Flow<Boolean> =
        settings.observe().map { it.sales.tipsEnabled }.distinctUntilChanged()
}

/** Resultado de [SetTipsEnabledUseCase]. */
sealed interface SetTipsEnabledResult {
    /** Quedó así, o ya estaba así y no hacía falta escribir. */
    data object Saved : SetTipsEnabledResult

    /** La base no pudo escribir, por [failure]. No cambió nada. */
    data class Failed(val failure: StorageFailure) : SetTipsEnabledResult
}

/**
 * Enciende o apaga las propinas del cobro. Es un dato del negocio, no una preferencia de uso: va en
 * su propia transacción y cuenta como cambio sin respaldar, porque la copia debe llevar cómo cobra el
 * negocio. Si ya estaba así, no escribe ni cuenta un cambio.
 */
class SetTipsEnabledUseCase(
    private val transactions: TransactionRunner,
    private val settings: AppSettingsRepository,
    private val clock: Clock,
) {
    suspend operator fun invoke(enabled: Boolean): SetTipsEnabledResult = try {
        transactions.write {
            if (settings.get().sales.tipsEnabled == enabled) {
                TxResult.Rollback(SetTipsEnabledResult.Saved)
            } else {
                settings.updateTipsEnabled(enabled, clock.now())
                TxResult.Commit(SetTipsEnabledResult.Saved)
            }
        }
    } catch (failure: StorageFailureException) {
        SetTipsEnabledResult.Failed(failure.failure)
    }
}
