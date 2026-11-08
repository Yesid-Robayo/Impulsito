package io.github.yesidrobayo.shopledger.domain.port

import io.github.yesidrobayo.shopledger.domain.model.experience.BusinessTool
import kotlinx.coroutines.flow.Flow

/**
 * Qué herramientas del centro `Negocio` están en uso, según los datos: una consulta de solo lectura
 * sobre las tablas del esquema, sin ninguna columna ni archivo propio.
 *
 * Nada se enciende ni se apaga a mano. Una herramienta está en uso con su primer registro, y deja de
 * estarlo sola si ya no tiene datos activos. Una herramienta que no deja datos, como una acción
 * puntual, nunca está en uso.
 */
fun interface ModuleUsageSource {
    /** Las herramientas en uso, de nuevo cada vez que cambian los datos que las deciden. */
    fun observe(): Flow<Set<BusinessTool>>
}
