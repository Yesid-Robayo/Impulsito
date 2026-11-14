package io.github.yesidrobayo.shopledger.domain.port

import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
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

    /**
     * El lote de la última actualización masiva, mientras se pueda deshacer, o `null`. Deja de
     * emitirlo cuando se deshace, porque la última fila con lote pasa a ser un `BULK_UNDO`, y cuando
     * llega otra actualización masiva, que pasa a ser la última.
     */
    fun observeUndoableBatch(): Flow<Uuid?>

    /**
     * Lo mismo, leído una vez, dentro de la transacción que deshace. Sin implementación propia, lee
     * lo primero que emite [observeUndoableBatch].
     */
    suspend fun findUndoableBatch(): Uuid? = observeUndoableBatch().first()

    /**
     * Los productos con al menos una fila de precio en la lista [priceListId] con fecha de negocio
     * [from] o posterior. Es lo que imprime `Solo lo que cambió de precio`: un producto nuevo
     * también cuenta, porque su precio inicial es una fila.
     */
    suspend fun findProductsRepricedSince(priceListId: Long, from: BusinessDate): Set<Long>

    /** Agrega la fila y devuelve su id. */
    suspend fun append(change: NewPriceChange): Long
}
