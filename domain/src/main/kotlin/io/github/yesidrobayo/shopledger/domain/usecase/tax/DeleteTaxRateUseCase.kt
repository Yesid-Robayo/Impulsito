package io.github.yesidrobayo.shopledger.domain.usecase.tax

import io.github.yesidrobayo.shopledger.domain.port.StorageFailureException
import io.github.yesidrobayo.shopledger.domain.port.TaxRateRepository
import io.github.yesidrobayo.shopledger.domain.port.TaxSettingsRepository
import io.github.yesidrobayo.shopledger.domain.port.TransactionRunner
import io.github.yesidrobayo.shopledger.domain.port.TxResult

/**
 * Elimina una tasa sin uso, como una creada por error.
 *
 * Una tasa que usa algún producto, aunque esté archivado, o que es la tasa por defecto, no se
 * elimina: se archiva, para no dejar a nadie sin tasa sin que lo pida. Las ventas y compras que la
 * usaron no cambian, porque cada línea guarda su copia de la tasa y del nombre.
 */
class DeleteTaxRateUseCase(
    private val transactions: TransactionRunner,
    private val rates: TaxRateRepository,
    private val taxSettings: TaxSettingsRepository,
) {
    suspend operator fun invoke(id: Long): DeleteTaxRateResult = try {
        transactions.write { delete(id) }
    } catch (failure: StorageFailureException) {
        DeleteTaxRateResult.StorageFailed(failure.failure)
    }

    private suspend fun delete(id: Long): TxResult<DeleteTaxRateResult> {
        rates.findById(id) ?: return TxResult.Rollback(DeleteTaxRateResult.NotFound)
        val inUse = taxSettings.get().defaultTaxRateId == id || rates.countProducts(id) > 0
        if (inUse) return TxResult.Rollback(DeleteTaxRateResult.InUse)
        rates.delete(id)
        return TxResult.Commit(DeleteTaxRateResult.Deleted)
    }
}
