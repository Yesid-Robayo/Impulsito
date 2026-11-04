package io.github.yesidrobayo.shopledger.domain.port

import io.github.yesidrobayo.shopledger.domain.model.ArchiveFilter
import io.github.yesidrobayo.shopledger.domain.model.catalog.Supplier
import io.github.yesidrobayo.shopledger.domain.model.catalog.SupplierDetails
import kotlin.time.Instant
import kotlinx.coroutines.flow.Flow

/**
 * Los proveedores.
 *
 * Las lecturas que devuelven [Flow] vuelven a emitir cada vez que se confirma una transacción que
 * cambia lo leído. Las funciones que escriben solo se llaman dentro de [TransactionRunner.write].
 * El saldo no se escribe aquí: lo mueve el libro del proveedor.
 */
interface SupplierRepository {
    /** Los proveedores de [filter], en el orden de su nombre normalizado. */
    fun observe(filter: ArchiveFilter): Flow<List<Supplier>>

    suspend fun findById(id: Long): Supplier?

    /** El proveedor sin archivar con ese nombre normalizado, para no repetir nombres. */
    suspend fun findActiveByNormalizedName(normalizedName: String): Supplier?

    /**
     * Si tiene compras o filas en su libro, aunque estén anuladas. Con historia ya no se borra:
     * solo se archiva.
     */
    suspend fun hasHistory(id: Long): Boolean

    /** Crea el proveedor, con saldo cero, y devuelve su id. */
    suspend fun insert(details: SupplierDetails, createdAt: Instant): Long

    suspend fun update(id: Long, details: SupplierDetails, updatedAt: Instant)

    /** Lo archiva con [archivedAt], o lo reactiva con `null`. */
    suspend fun setArchivedAt(id: Long, archivedAt: Instant?, updatedAt: Instant)

    /**
     * Elimina un proveedor sin historia. Los productos que lo tenían como habitual quedan sin
     * proveedor en la misma transacción.
     */
    suspend fun delete(id: Long)
}
