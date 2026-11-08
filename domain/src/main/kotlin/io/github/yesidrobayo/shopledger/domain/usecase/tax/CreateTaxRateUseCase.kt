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
 * Crea una tasa con el nombre y el porcentaje que escribió la persona, y la deja por defecto si lo
 * pidió. Todo en una sola transacción: nunca queda la tasa creada sin la marca pedida. Es un cambio
 * del negocio: se cuenta para la copia.
 */
class CreateTaxRateUseCase(
    private val transactions: TransactionRunner,
    private val rates: TaxRateRepository,
    private val taxSettings: TaxSettingsRepository,
    private val clock: Clock,
) {
    suspend operator fun invoke(draft: TaxRateDraft): SaveTaxRateResult = try {
        transactions.write { create(draft) }
    } catch (failure: StorageFailureException) {
        SaveTaxRateResult.StorageFailed(failure.failure)
    }

    private suspend fun create(draft: TaxRateDraft): TxResult<SaveTaxRateResult> {
        val details =
            when (val checked = checkDraft(rates, draft, editingId = null)) {
                is TaxRateCheck.Invalid ->
                    return TxResult.Rollback(SaveTaxRateResult.Invalid(checked.errors))

                is TaxRateCheck.Valid -> checked.details
            }
        val now = clock.now()
        val id = rates.insert(details, now)
        if (draft.useAsDefault) taxSettings.setDefault(taxSettings.get(), id, now)
        return TxResult.Commit(SaveTaxRateResult.Saved(id))
    }
}
