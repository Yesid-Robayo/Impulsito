package io.github.yesidrobayo.shopledger.domain.port

import io.github.yesidrobayo.shopledger.domain.model.catalog.NewPriceChange
import io.github.yesidrobayo.shopledger.domain.model.catalog.PriceChange
import kotlin.uuid.Uuid
import kotlinx.coroutines.flow.Flow

/**
 * El historial de precios: solo crece. Ninguna función edita ni borra una fila.
 *
 * Las lecturas que devuelven [Flow] vuelven a emitir cada vez que se confirma una transacción que
 * cambia lo leído. [append] solo se llama dentro de [TransactionRunner.write].
 */
interface PriceHistoryRepository {
    /** Los cambios de precio de un producto, en el orden en que se registraron. */
    fun observeByProduct(productId: Long): Flow<List<PriceChange>>

    /** Las filas de una actualización masiva, o de su deshacer, en el orden en que se registraron. */
    suspend fun findByBatch(batchId: Uuid): List<PriceChange>

    /** Agrega la fila y devuelve su id. */
    suspend fun append(change: NewPriceChange): Long
}
