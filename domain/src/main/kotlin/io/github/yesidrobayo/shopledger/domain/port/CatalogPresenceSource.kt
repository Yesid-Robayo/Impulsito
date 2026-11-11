package io.github.yesidrobayo.shopledger.domain.port

import kotlinx.coroutines.flow.Flow

/**
 * Si el negocio ya tiene algún producto, archivado o no. Solo lee.
 *
 * Inicio lo usa para orientar el primer uso: sin productos, la primera acción es crear uno. Una
 * consulta propia evita leer todo el catálogo solo para saber si está vacío.
 */
fun interface CatalogPresenceSource {
    /** `true` desde que existe el primer producto, de nuevo con cada cambio en el catálogo. */
    fun observeHasProducts(): Flow<Boolean>
}
