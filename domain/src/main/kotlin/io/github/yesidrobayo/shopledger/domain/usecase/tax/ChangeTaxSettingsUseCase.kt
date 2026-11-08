package io.github.yesidrobayo.shopledger.domain.usecase.tax

import io.github.yesidrobayo.shopledger.domain.model.settings.TaxMode
import io.github.yesidrobayo.shopledger.domain.model.settings.TaxSettings
import io.github.yesidrobayo.shopledger.domain.port.Clock
import io.github.yesidrobayo.shopledger.domain.port.StorageFailureException
import io.github.yesidrobayo.shopledger.domain.port.TaxSettingsRepository
import io.github.yesidrobayo.shopledger.domain.port.TransactionRunner
import io.github.yesidrobayo.shopledger.domain.port.TxResult

/** Un cambio de los ajustes de impuestos que se hace desde la pantalla, uno a la vez. */
sealed interface TaxSettingsChange {
    /**
     * Cómo se cobra el impuesto. Solo afecta a documentos nuevos: cada línea ya registrada guardó
     * su propio neto, impuesto y tasa.
     */
    data class Mode(val mode: TaxMode) : TaxSettingsChange

    /** Si el impuesto de una compra queda fuera del costo del producto. */
    data class PurchaseTaxRecoverable(val recoverable: Boolean) : TaxSettingsChange
}

/**
 * Cambia el modo de impuesto o si el impuesto de compra es recuperable. Es un cambio del negocio:
 * se cuenta para la copia. Las tasas y la tasa por defecto se conservan al pasar a sin impuesto,
 * para que volver a cobrarlo no obligue a escribirlas de nuevo.
 */
class ChangeTaxSettingsUseCase(
    private val transactions: TransactionRunner,
    private val taxSettings: TaxSettingsRepository,
    private val clock: Clock,
) {
    suspend operator fun invoke(change: TaxSettingsChange): ChangeTaxSettingsResult = try {
        transactions.write { apply(change) }
    } catch (failure: StorageFailureException) {
        ChangeTaxSettingsResult.StorageFailed(failure.failure)
    }

    private suspend fun apply(change: TaxSettingsChange): TxResult<ChangeTaxSettingsResult> {
        val current = taxSettings.get()
        val next = changed(current, change)
        if (next == current) return TxResult.Rollback(ChangeTaxSettingsResult.Unchanged)
        taxSettings.update(next, clock.now())
        return TxResult.Commit(ChangeTaxSettingsResult.Changed)
    }

    private fun changed(current: TaxSettings, change: TaxSettingsChange): TaxSettings =
        when (change) {
            is TaxSettingsChange.Mode -> current.copy(mode = change.mode)

            is TaxSettingsChange.PurchaseTaxRecoverable ->
                current.copy(purchaseTaxRecoverable = change.recoverable)
        }
}
