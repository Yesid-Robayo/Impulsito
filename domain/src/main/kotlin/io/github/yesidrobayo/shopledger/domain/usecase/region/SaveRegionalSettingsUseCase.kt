package io.github.yesidrobayo.shopledger.domain.usecase.region

import io.github.yesidrobayo.shopledger.domain.model.settings.RegionalSettings
import io.github.yesidrobayo.shopledger.domain.model.settings.RegionalSettingsInput
import io.github.yesidrobayo.shopledger.domain.port.AppSettingsRepository
import io.github.yesidrobayo.shopledger.domain.port.Clock
import io.github.yesidrobayo.shopledger.domain.port.StorageFailure
import io.github.yesidrobayo.shopledger.domain.port.StorageFailureException
import io.github.yesidrobayo.shopledger.domain.port.TransactionRunner
import io.github.yesidrobayo.shopledger.domain.port.TxResult
import io.github.yesidrobayo.shopledger.domain.rule.region.RegionalSettingsCheck
import io.github.yesidrobayo.shopledger.domain.rule.region.RegionalSettingsProblem
import io.github.yesidrobayo.shopledger.domain.rule.region.RegionalSettingsRules

/** Resultado de [SaveRegionalSettingsUseCase]. */
sealed interface SaveRegionalSettingsResult {
    /** Quedó guardada, o ya estaba así y no hacía falta escribir. */
    data object Saved : SaveRegionalSettingsResult

    /** No se guardó nada, por estos [problems]. Lo elegido en la pantalla se conserva. */
    data class Rejected(val problems: Set<RegionalSettingsProblem>) : SaveRegionalSettingsResult

    /** La base no pudo escribir, por [failure]. No quedó nada a medias. */
    data class Failed(val failure: StorageFailure) : SaveRegionalSettingsResult
}

/**
 * Guarda la configuración regional que confirmó la persona, en una sola transacción.
 *
 * Lee la moneda guardada dentro de esa misma transacción, justo antes de escribir: así un registro
 * con dinero que se confirmó un instante antes ya cuenta, y su bloqueo no se salta. Si no cambió
 * nada, no escribe ni cuenta un cambio: confirmar dos veces no programa otra copia.
 *
 * Cambiar la zona solo afecta a lo que se registre después. Ningún registro guardado cambia su
 * fecha de negocio, y cambiar el formato solo cambia cómo se ven las cifras.
 */
class SaveRegionalSettingsUseCase(
    private val transactions: TransactionRunner,
    private val settings: AppSettingsRepository,
    private val clock: Clock,
) {
    suspend operator fun invoke(input: RegionalSettingsInput): SaveRegionalSettingsResult = try {
        transactions.write {
            val current = settings.get().regional
            when (val check = RegionalSettingsRules.check(input, current.currency)) {
                is RegionalSettingsCheck.Invalid ->
                    TxResult.Rollback(SaveRegionalSettingsResult.Rejected(check.problems))

                is RegionalSettingsCheck.Valid ->
                    if (current.matches(check.input)) {
                        TxResult.Rollback(SaveRegionalSettingsResult.Saved)
                    } else {
                        settings.updateRegional(check.input, clock.now())
                        TxResult.Commit(SaveRegionalSettingsResult.Saved)
                    }
            }
        }
    } catch (failure: StorageFailureException) {
        SaveRegionalSettingsResult.Failed(failure.failure)
    }

    /** Si lo guardado ya es [input]: confirmar sin cambios no escribe nada. */
    private fun RegionalSettings.matches(input: RegionalSettingsInput): Boolean =
        currency.code == input.currencyCode &&
            currency.decimals == input.currencyDecimals &&
            currency.symbol == input.currencySymbol &&
            cashRounding == input.cashRounding &&
            formatLocaleTag == input.formatLocaleTag &&
            businessTimeZoneId == input.businessTimeZoneId &&
            weekStartDay == input.weekStartDay &&
            csvSeparator == input.csvSeparator
}
