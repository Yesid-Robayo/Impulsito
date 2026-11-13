package io.github.yesidrobayo.shopledger.domain.port

import io.github.yesidrobayo.shopledger.domain.model.ArchiveFilter
import io.github.yesidrobayo.shopledger.domain.model.customer.Customer
import io.github.yesidrobayo.shopledger.domain.model.customer.CustomerDetails
import io.github.yesidrobayo.shopledger.domain.model.customer.PointsSummary
import kotlin.time.Instant
import kotlinx.coroutines.flow.Flow

/**
 * Los clientes.
 *
 * Las lecturas que devuelven [Flow] vuelven a emitir cada vez que se confirma una transacción que
 * cambia lo leído. Las funciones que escriben solo se llaman dentro de [TransactionRunner.write].
 * Los saldos no se escriben aquí: los mueven sus libros.
 */
interface CustomerRepository {
    /**
     * Los clientes de [filter], en el orden de su nombre normalizado y después por id. Los
     * anonimizados van al final, porque ya no tienen nombre.
     */
    fun observe(filter: ArchiveFilter): Flow<List<Customer>>

    /**
     * Los que se pueden elegir al vender: ni archivados ni anonimizados, en el orden de su nombre.
     */
    fun observeSelectable(): Flow<List<Customer>>

    /** Cuántos puntos vigentes hay entre todos los clientes, y cuántos clientes los tienen. */
    fun observePointsSummary(): Flow<PointsSummary>

    /** El cliente [id], o `null` si no existe o deja de existir. */
    fun observeById(id: Long): Flow<Customer?>

    suspend fun findById(id: Long): Customer?

    /**
     * Los clientes sin archivar con ese nombre normalizado, por id. El nombre no es único: sirve para
     * avisar de un posible duplicado, nunca para impedirlo.
     */
    suspend fun findActiveByNormalizedName(normalizedName: String): List<Customer>

    /**
     * Si tiene ventas, pedidos, cotizaciones o filas en sus libros, aunque estén anuladas. Con
     * historia ya no se borra: solo se archiva.
     */
    suspend fun hasHistory(id: Long): Boolean

    /** Crea el cliente, con saldos en cero, y devuelve su id. */
    suspend fun insert(details: CustomerDetails, createdAt: Instant): Long

    /** Cambia lo que la persona escribe. Un cliente anonimizado no se edita. */
    suspend fun update(id: Long, details: CustomerDetails, updatedAt: Instant)

    /** Lo archiva con [archivedAt], o lo reactiva con `null`. */
    suspend fun setArchivedAt(id: Long, archivedAt: Instant?, updatedAt: Instant)

    /**
     * Borra los datos personales del cliente y fija [anonymizedAt], que no se deshace: nombre,
     * nombre normalizado, teléfono, correo y notas, y la dirección y la nota de entrega de todos sus
     * pedidos. No toca sus libros, sus saldos ni sus ventas.
     */
    suspend fun anonymize(id: Long, anonymizedAt: Instant)

    /** Elimina un cliente sin historia. */
    suspend fun delete(id: Long)
}
