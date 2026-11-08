package io.github.yesidrobayo.shopledger.domain.usecase.tax

import io.github.yesidrobayo.shopledger.domain.model.tax.TaxRateDraft
import io.github.yesidrobayo.shopledger.domain.port.Clock
import io.github.yesidrobayo.shopledger.domain.port.StorageFailureException
import io.github.yesidrobayo.shopledger.domain.port.TaxRateRepository
import io.github.yesidrobayo.shopledger.domain.port.TaxSettingsRepository
import io.github.yesidrobayo.shopledger.domain.port.TransactionRunner
import io.github.yesidrobayo.shopledger.domain.port.TxResult
import io.github.yesidrobayo.shopledger.domain.rule.tax.TaxRateCheck

/**
 * Cambia el nombre o el porcentaje de una tasa vigente, y si es la tasa por defecto.
 *
 * Ninguna venta ni compra pasada cambia: cada línea guardó su propia copia de la tasa y del nombre,
 * y solo los documentos nuevos usan lo nuevo. Marcarla por defecto reemplaza a la anterior, y
 * desmarcarla deja al negocio sin tasa por defecto.
 */
class EditTaxRateUseCase(
    private val transactions: TransactionRunner,
    private val rates: TaxRateRepository,
    private val taxSettings: TaxSettingsRepository,
    private val clock: Clock,
) {
    suspend operator fun invoke(id: Long, draft: TaxRateDraft): SaveTaxRateResult = try {
        transactions.write { edit(id, draft) }
    } catch (failure: StorageFailureException) {
        SaveTaxRateResult.StorageFailed(failure.failure)
    }

    private suspend fun edit(id: Long, draft: TaxRateDraft): TxResult<SaveTaxRateResult> {
        val rate = rates.findById(id) ?: return TxResult.Rollback(SaveTaxRateResult.NotFound)
        if (rate.isArchived) return TxResult.Rollback(SaveTaxRateResult.Archived)
        val details =
            when (val checked = checkDraft(rates, draft, editingId = id)) {
                is TaxRateCheck.Invalid ->
                    return TxResult.Rollback(SaveTaxRateResult.Invalid(checked.errors))

                is TaxRateCheck.Valid -> checked.details
            }
        val settings = taxSettings.get()
        val wasDefault = settings.defaultTaxRateId == id
        val detailsChanged = details != rate.details
        if (!detailsChanged && draft.useAsDefault == wasDefault) {
            // Nada cambió: no se escribe ni se cuenta un cambio para la copia.
            return TxResult.Rollback(SaveTaxRateResult.Saved(id))
        }
        val now = clock.now()
        if (detailsChanged) rates.update(id, details, now)
        val newDefault =
            when {
                draft.useAsDefault -> id
                wasDefault -> null
                else -> settings.defaultTaxRateId
            }
        taxSettings.setDefault(settings, newDefault, now)
        return TxResult.Commit(SaveTaxRateResult.Saved(id))
    }
}
