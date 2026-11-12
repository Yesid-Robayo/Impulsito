package io.github.yesidrobayo.shopledger.domain.usecase.settings

import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.settings.BusinessProfile
import io.github.yesidrobayo.shopledger.domain.port.AppSettingsRepository
import io.github.yesidrobayo.shopledger.domain.port.Clock
import io.github.yesidrobayo.shopledger.domain.port.StorageFailure
import io.github.yesidrobayo.shopledger.domain.port.StorageFailureException
import io.github.yesidrobayo.shopledger.domain.port.TransactionRunner
import io.github.yesidrobayo.shopledger.domain.port.TxResult
import io.github.yesidrobayo.shopledger.domain.rule.catalog.CatalogText
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** El perfil del negocio, de nuevo cada vez que cambia. */
class ObserveBusinessProfileUseCase(private val settings: AppSettingsRepository) {
    operator fun invoke(): Flow<BusinessProfile> =
        settings.observe().map { it.profile }.distinctUntilChanged()
}

/** Un cambio de un solo dato del perfil, para no pisar el otro con un valor viejo. */
sealed interface BusinessProfileChange {
    /** El nombre tal como se escribió. En blanco, el negocio queda sin nombre. */
    data class Name(val raw: String) : BusinessProfileChange

    /** El margen objetivo del negocio, de 0 a 99 %. */
    data class TargetMargin(val value: BasisPoints) : BusinessProfileChange
}

/** Resultado de [SaveBusinessProfileUseCase]. */
sealed interface SaveBusinessProfileResult {
    /** Quedó así, o ya estaba así y no hacía falta escribir. */
    data object Saved : SaveBusinessProfileResult

    /** El nombre pasa de [BUSINESS_NAME_MAX] caracteres. No cambió nada. */
    data object NameTooLong : SaveBusinessProfileResult

    /** El margen es negativo o pasa del 99 %: con 100 % el precio sería infinito. No cambió nada. */
    data object TargetMarginOutOfRange : SaveBusinessProfileResult

    /** La base no pudo escribir, por [failure]. No cambió nada. */
    data class Failed(val failure: StorageFailure) : SaveBusinessProfileResult
}

/** Largo máximo del nombre del negocio, el mismo que admite la base. */
const val BUSINESS_NAME_MAX: Int = 80

/**
 * Cambia un dato del perfil del negocio: su nombre, que encabeza los comprobantes, o su margen
 * objetivo, del que parten las sugerencias de precio de los productos y categorías que no fijan uno.
 *
 * Es un dato del negocio: va en su propia transacción y cuenta como cambio sin respaldar. Lee el
 * perfil dentro de ella y cambia solo el dato pedido, así dos hojas abiertas no se pisan. Si ya
 * estaba así, no escribe ni cuenta un cambio. Un nombre se guarda recortado.
 */
class SaveBusinessProfileUseCase(
    private val transactions: TransactionRunner,
    private val settings: AppSettingsRepository,
    private val clock: Clock,
) {
    suspend operator fun invoke(change: BusinessProfileChange): SaveBusinessProfileResult {
        val rejection = rejectionOf(change)
        if (rejection != null) return rejection
        return try {
            transactions.write {
                val current = settings.get().profile
                val next = when (change) {
                    is BusinessProfileChange.Name -> current.copy(businessName = nameOf(change.raw))

                    is BusinessProfileChange.TargetMargin ->
                        current.copy(defaultTargetMargin = change.value)
                }
                if (next == current) {
                    TxResult.Rollback(SaveBusinessProfileResult.Saved)
                } else {
                    settings.updateProfile(next, clock.now())
                    TxResult.Commit(SaveBusinessProfileResult.Saved)
                }
            }
        } catch (failure: StorageFailureException) {
            SaveBusinessProfileResult.Failed(failure.failure)
        }
    }

    private fun rejectionOf(change: BusinessProfileChange): SaveBusinessProfileResult? =
        when (change) {
            is BusinessProfileChange.Name ->
                SaveBusinessProfileResult.NameTooLong.takeIf {
                    CatalogText.length(change.raw.trim()) > BUSINESS_NAME_MAX
                }

            is BusinessProfileChange.TargetMargin ->
                SaveBusinessProfileResult.TargetMarginOutOfRange.takeIf {
                    change.value < BasisPoints.ZERO || change.value > BasisPoints.MAX_TARGET_MARGIN
                }
        }

    /** El nombre recortado, o `null` si queda en blanco. */
    private fun nameOf(raw: String): String? = raw.trim().ifEmpty { null }
}
