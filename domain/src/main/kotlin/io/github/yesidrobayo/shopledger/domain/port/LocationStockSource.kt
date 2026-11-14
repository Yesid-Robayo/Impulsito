package io.github.yesidrobayo.shopledger.domain.port

import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import kotlinx.coroutines.flow.Flow

/**
 * El stock repartido por ubicación, leído de los saldos que el libro mantiene por producto,
 * ubicación y lote. Solo lee: cada saldo es la suma de las filas del libro de su parte.
 */
interface LocationStockSource {
    /**
     * Cuánto hay de [productId] en cada ubicación donde alguna fila del libro cayó alguna vez, por
     * id de ubicación, sumando sus lotes. Una ubicación sin filas no aparece.
     */
    fun observeProduct(productId: Long): Flow<Map<Long, QuantityMilli>>

    /**
     * Cuántos productos vigentes tienen stock, una suma de saldos mayor que cero, en cada
     * ubicación, por id de ubicación. Una ubicación sin ninguno no aparece.
     */
    fun observeStockedProductCounts(): Flow<Map<Long, Int>>

    /**
     * Si en [locationId] queda algún saldo distinto de cero, de cualquier producto o lote. Un
     * negativo también cuenta: archivarla lo escondería sin resolverlo.
     */
    suspend fun hasStock(locationId: Long): Boolean
}
