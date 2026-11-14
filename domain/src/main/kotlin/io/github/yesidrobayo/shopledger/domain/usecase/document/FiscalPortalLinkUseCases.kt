package io.github.yesidrobayo.shopledger.domain.usecase.document

import io.github.yesidrobayo.shopledger.domain.port.AppSettingsRepository
import io.github.yesidrobayo.shopledger.domain.port.Clock
import io.github.yesidrobayo.shopledger.domain.port.StorageFailure
import io.github.yesidrobayo.shopledger.domain.port.StorageFailureException
import io.github.yesidrobayo.shopledger.domain.port.TransactionRunner
import io.github.yesidrobayo.shopledger.domain.port.TxResult
import io.github.yesidrobayo.shopledger.domain.rule.document.FiscalInputs
import io.github.yesidrobayo.shopledger.domain.rule.document.PortalUrlInput
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** Cómo terminó guardar el enlace al sistema externo. */
sealed interface SavePortalLinkResult {
    /** Quedó guardado [url], o quitado si es `null`. */
    data class Saved(val url: String?) : SavePortalLinkResult

    /** No es una dirección `https` con servidor. Nada se guardó. */
    data object NotHttps : SavePortalLinkResult

    /** Pasa del largo permitido. Nada se guardó. */
    data object TooLong : SavePortalLinkResult

    /** La base no pudo escribir. Lo escrito sigue en el campo. */
    data class Failed(val failure: StorageFailure) : SavePortalLinkResult
}

/**
 * Guarda el enlace que la persona escribió para llegar al sistema que usa fuera de la app, o lo
 * quita si el campo queda vacío. Solo acepta `https`.
 *
 * La app nunca visita el enlace ni lee nada de él: solo lo entrega a otra app cuando la persona
 * toca `Abrir enlace`. Si no cambia, no escribe ni cuenta un cambio.
 */
class SaveFiscalPortalLinkUseCase(
    private val transactions: TransactionRunner,
    private val settings: AppSettingsRepository,
    private val clock: Clock,
) {
    suspend operator fun invoke(text: String): SavePortalLinkResult {
        val url = when (val input = FiscalInputs.portalUrl(text)) {
            PortalUrlInput.NotHttps -> return SavePortalLinkResult.NotHttps
            PortalUrlInput.TooLong -> return SavePortalLinkResult.TooLong
            is PortalUrlInput.Valid -> input.value
        }
        return try {
            transactions.write {
                if (settings.get().receipt.fiscalPortalUrl == url) {
                    TxResult.Rollback(SavePortalLinkResult.Saved(url))
                } else {
                    settings.updateFiscalPortalUrl(url, clock.now())
                    TxResult.Commit(SavePortalLinkResult.Saved(url))
                }
            }
        } catch (failure: StorageFailureException) {
            SavePortalLinkResult.Failed(failure.failure)
        }
    }
}

/** El enlace al sistema externo que guardó la persona, o `null` si no hay. No usa la red. */
class ObserveFiscalPortalLinkUseCase(private val settings: AppSettingsRepository) {
    operator fun invoke(): Flow<String?> =
        settings.observe().map { it.receipt.fiscalPortalUrl }.distinctUntilChanged()
}
