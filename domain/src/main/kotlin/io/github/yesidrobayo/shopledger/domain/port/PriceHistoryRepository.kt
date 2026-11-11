package io.github.yesidrobayo.shopledger.domain.port

import io.github.yesidrobayo.shopledger.domain.model.catalog.NewPriceChange
import io.github.yesidrobayo.shopledger.domain.model.catalog.PriceChange
import kotlin.uuid.Uuid
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

/**
 * El historial de precios: solo crece. Ninguna función edita ni borra una fila.
 *
 * Las lecturas que devuelven [Flow] vuelven a emitir cada vez que se confirma una transacción que
 * cambia lo leído. [append] solo se llama dentro de [TransactionRunner.write].
 */
interface PriceHistoryRepository {
    /** Los cambios de precio de un producto, en el orden en que se registraron. */
    fun observeByProduct(productId: Long): Flow<List<PriceChange>>

    /**
     * Los cambios de precio de un producto, leídos una vez, en el orden en que se registraron. Se
     * usa dentro de una transacción de lectura, donde no se observa. Sin implementación propia, lee
     * lo primero que emite [observeByProduct].
     */
    suspend fun findByProduct(productId: Long): List<PriceChange> =
        observeByProduct(productId).first()

    /** Las filas de una actualización masiva, o de su deshacer, en el orden en que se registraron. */
    suspend fun findByBatch(batchId: Uuid): List<PriceChange>

    /** Agrega la fila y devuelve su id. */
    suspend fun append(change: NewPriceChange): Long
}
