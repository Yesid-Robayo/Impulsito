package io.github.yesidrobayo.shopledger.domain.usecase.customer

import io.github.yesidrobayo.shopledger.domain.model.customer.PointsSummary
import io.github.yesidrobayo.shopledger.domain.model.settings.LoyaltySettings
import io.github.yesidrobayo.shopledger.domain.port.AppSettingsRepository
import io.github.yesidrobayo.shopledger.domain.port.Clock
import io.github.yesidrobayo.shopledger.domain.port.CustomerRepository
import io.github.yesidrobayo.shopledger.domain.port.StorageFailure
import io.github.yesidrobayo.shopledger.domain.port.TransactionRunner
import io.github.yesidrobayo.shopledger.domain.port.TxResult
import io.github.yesidrobayo.shopledger.domain.rule.customer.LoyaltyProgramCheck
import io.github.yesidrobayo.shopledger.domain.rule.customer.LoyaltyProgramIssue
import io.github.yesidrobayo.shopledger.domain.rule.customer.LoyaltyProgramRules
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.storageSafe
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** El programa de puntos como está guardado, con cuántos puntos hay y cuántos clientes los tienen. */
data class LoyaltyProgramOverview(
    val settings: LoyaltySettings,
    val points: PointsSummary,
    val hasCustomers: Boolean,
)

/** El programa de puntos. Vuelve a emitir con cada cambio del programa o de los saldos de puntos. */
class ObserveLoyaltyProgramUseCase(
    private val settings: AppSettingsRepository,
    private val customers: CustomerRepository,
) {
    operator fun invoke(): Flow<LoyaltyProgramOverview> = combine(
        settings.observe().map { it.loyalty }.distinctUntilChanged(),
        customers.observePointsSummary(),
        customers.observeSelectable().map { it.isNotEmpty() }.distinctUntilChanged(),
    ) { loyalty, points, hasCustomers ->
        LoyaltyProgramOverview(loyalty, points, hasCustomers)
    }.distinctUntilChanged()
}

/** Cómo termina guardar el programa de puntos. Salvo [Saved], no se escribió nada. */
sealed interface SaveLoyaltyProgramResult {
    /** Quedó así, o ya estaba así y no hacía falta escribir. */
    data object Saved : SaveLoyaltyProgramResult

    /** Lo escrito no se puede guardar, por todos estos motivos a la vez. */
    data class Invalid(val issues: Set<LoyaltyProgramIssue>) : SaveLoyaltyProgramResult

    data class StorageFailed(val failure: StorageFailure) : SaveLoyaltyProgramResult
}

/**
 * Activa, apaga o cambia el programa de puntos, en su propia transacción. Es un dato del negocio:
 * cuenta como cambio sin respaldar. Cambiar los valores solo afecta las ventas nuevas: lo ya ganado
 * no se recalcula. Apagarlo conserva los saldos y los valores, y no escribe nada en ningún libro.
 * Si ya estaba así, no escribe ni cuenta un cambio.
 */
class SaveLoyaltyProgramUseCase(
    private val transactions: TransactionRunner,
    private val settings: AppSettingsRepository,
    private val clock: Clock,
) {
    suspend operator fun invoke(program: LoyaltySettings): SaveLoyaltyProgramResult =
        storageSafe(SaveLoyaltyProgramResult::StorageFailed) {
            transactions.write { save(program) }
        }

    private suspend fun save(program: LoyaltySettings): TxResult<SaveLoyaltyProgramResult> {
        val valid = when (val check = LoyaltyProgramRules.check(program)) {
            is LoyaltyProgramCheck.Invalid ->
                return TxResult.Rollback(SaveLoyaltyProgramResult.Invalid(check.issues))

            is LoyaltyProgramCheck.Valid -> check.settings
        }
        if (settings.get().loyalty ==
            valid
        ) {
            return TxResult.Rollback(SaveLoyaltyProgramResult.Saved)
        }
        settings.updateLoyalty(valid, clock.now())
        return TxResult.Commit(SaveLoyaltyProgramResult.Saved)
    }
}
