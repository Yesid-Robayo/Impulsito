package io.github.yesidrobayo.shopledger.domain.port

import io.github.yesidrobayo.shopledger.domain.model.sale.HeldSale
import io.github.yesidrobayo.shopledger.domain.model.sale.NewHeldSale
import kotlin.time.Instant
import kotlinx.coroutines.flow.Flow

/**
 * Las ventas en espera. No son historia: se reemplazan, se renombran y se borran libremente.
 *
 * Las escrituras solo se llaman dentro de [TransactionRunner.write], y se confirman sin contar como
 * cambio sin respaldar: una venta en espera no es un hecho del negocio. Las lecturas que devuelven
 * [Flow] vuelven a emitir con cada transacción que cambia lo leído.
 */
interface HeldSaleRepository {
    /** Todas, de la más antigua a la más reciente, cada una con sus líneas en orden. */
    fun observeAll(): Flow<List<HeldSale>>

    suspend fun findById(id: Long): HeldSale?

    /** Guarda una venta en espera nueva con sus líneas y devuelve su id. */
    suspend fun insert(sale: NewHeldSale): Long

    /**
     * Cambia el nombre y todas las líneas de la venta [id], que conserva cuándo se puso en espera
     * por primera vez. Devuelve `false` si ya no existe.
     */
    suspend fun replace(id: Long, sale: NewHeldSale): Boolean

    /** Cambia solo el nombre. Devuelve `false` si ya no existe. */
    suspend fun rename(id: Long, label: String?, updatedAt: Instant): Boolean

    /** Borra la venta en espera con sus líneas. Devuelve `false` si ya no existía. */
    suspend fun delete(id: Long): Boolean
}
