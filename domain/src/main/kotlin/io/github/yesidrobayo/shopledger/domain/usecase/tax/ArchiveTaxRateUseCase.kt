package io.github.yesidrobayo.shopledger.domain.usecase.tax

import io.github.yesidrobayo.shopledger.domain.port.Clock
import io.github.yesidrobayo.shopledger.domain.port.StorageFailureException
import io.github.yesidrobayo.shopledger.domain.port.TaxRateRepository
import io.github.yesidrobayo.shopledger.domain.port.TaxSettingsRepository
import io.github.yesidrobayo.shopledger.domain.port.TransactionRunner
import io.github.yesidrobayo.shopledger.domain.port.TxResult

/**
 * Archiva una tasa: deja de ofrecerse en productos y como tasa por defecto, y se puede reactivar.
 *
 * Las ventas y compras pasadas no cambian, porque cada línea guarda su copia de la tasa y del
 * nombre. Los productos que la tienen la conservan. Si era la tasa por defecto, el negocio queda
 * sin tasa por defecto en la misma transacción: una tasa archivada no se aplica a ventas nuevas.
 */
class ArchiveTaxRateUseCase(
    private val transactions: TransactionRunner,
    private val rates: TaxRateRepository,
    private val taxSettings: TaxSettingsRepository,
    private val clock: Clock,
) {
    suspend operator fun invoke(id: Long): ArchiveTaxRateResult = try {
        transactions.write { archive(id) }
    } catch (failure: StorageFailureException) {
        ArchiveTaxRateResult.StorageFailed(failure.failure)
    }

    private suspend fun archive(id: Long): TxResult<ArchiveTaxRateResult> {
        val rate = rates.findById(id) ?: return TxResult.Rollback(ArchiveTaxRateResult.NotFound)
        if (rate.isArchived) return TxResult.Rollback(ArchiveTaxRateResult.AlreadyArchived)
        val now = clock.now()
        rates.setArchivedAt(id, archivedAt = now, updatedAt = now)
        val settings = taxSettings.get()
        if (settings.defaultTaxRateId == id) taxSettings.setDefault(settings, null, now)
        return TxResult.Commit(ArchiveTaxRateResult.Archived)
    }
}
