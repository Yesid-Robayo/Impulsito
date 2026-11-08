package io.github.yesidrobayo.shopledger.domain.usecase.tax

import io.github.yesidrobayo.shopledger.domain.port.Clock
import io.github.yesidrobayo.shopledger.domain.port.StorageFailureException
import io.github.yesidrobayo.shopledger.domain.port.TaxRateRepository
import io.github.yesidrobayo.shopledger.domain.port.TransactionRunner
import io.github.yesidrobayo.shopledger.domain.port.TxResult

/**
 * Vuelve a ofrecer una tasa archivada, con su nombre y su porcentaje de antes. No vuelve a ser la
 * tasa por defecto por sí sola: eso lo decide la persona al editarla.
 *
 * Una archivada no reserva su nombre: si otra vigente lo tomó mientras tanto, no se reactiva hasta
 * que una de las dos cambie de nombre.
 */
class ReactivateTaxRateUseCase(
    private val transactions: TransactionRunner,
    private val rates: TaxRateRepository,
    private val clock: Clock,
) {
    suspend operator fun invoke(id: Long): ReactivateTaxRateResult = try {
        transactions.write { reactivate(id) }
    } catch (failure: StorageFailureException) {
        ReactivateTaxRateResult.StorageFailed(failure.failure)
    }

    private suspend fun reactivate(id: Long): TxResult<ReactivateTaxRateResult> {
        val rate = rates.findById(id) ?: return TxResult.Rollback(ReactivateTaxRateResult.NotFound)
        val refused =
            when {
                !rate.isArchived -> ReactivateTaxRateResult.AlreadyActive

                rates.findActiveByNormalizedName(rate.details.normalizedName) != null ->
                    ReactivateTaxRateResult.NameTaken

                else -> null
            }
        if (refused != null) return TxResult.Rollback(refused)
        rates.setArchivedAt(id, archivedAt = null, updatedAt = clock.now())
        return TxResult.Commit(ReactivateTaxRateResult.Reactivated)
    }
}
