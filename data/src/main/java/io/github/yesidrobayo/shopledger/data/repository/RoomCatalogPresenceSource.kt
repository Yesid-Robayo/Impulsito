package io.github.yesidrobayo.shopledger.data.repository

import io.github.yesidrobayo.shopledger.data.local.AppDatabase
import io.github.yesidrobayo.shopledger.domain.port.CatalogPresenceSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Si el negocio tiene algún producto, con una consulta que se detiene en la primera fila: con miles
 * de productos, saber si el catálogo está vacío no lo lee entero.
 */
internal class RoomCatalogPresenceSource(database: AppDatabase) : CatalogPresenceSource {
    private val products = database.productDao()

    override fun observeHasProducts(): Flow<Boolean> = products.observeAny().distinctUntilChanged()
}
