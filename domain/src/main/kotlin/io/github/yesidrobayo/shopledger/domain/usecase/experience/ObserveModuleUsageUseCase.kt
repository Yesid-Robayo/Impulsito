package io.github.yesidrobayo.shopledger.domain.usecase.experience

import io.github.yesidrobayo.shopledger.domain.model.experience.BusinessTool
import io.github.yesidrobayo.shopledger.domain.port.ModuleUsageSource
import io.github.yesidrobayo.shopledger.domain.port.StorageFailureException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Las herramientas en uso, para ordenar el centro `Negocio`.
 *
 * Si la base no se puede leer, todas quedan como no usadas, sin aviso: ninguna deja de estar a un
 * toque en `Más herramientas`, y saber cuáles se usan solo ordena la pantalla. No escribe nada.
 */
class ObserveModuleUsageUseCase(private val source: ModuleUsageSource) {
    operator fun invoke(): Flow<Set<BusinessTool>> = source
        .observe()
        .catch { failure ->
            if (failure is StorageFailureException) emit(emptySet()) else throw failure
        }.distinctUntilChanged()
}
