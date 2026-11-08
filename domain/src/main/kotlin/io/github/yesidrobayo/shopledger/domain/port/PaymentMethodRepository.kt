package io.github.yesidrobayo.shopledger.domain.port

import io.github.yesidrobayo.shopledger.domain.model.ArchiveFilter
import io.github.yesidrobayo.shopledger.domain.model.payment.PaymentMethodDetails
import io.github.yesidrobayo.shopledger.domain.model.payment.PaymentMethodRecord
import kotlin.time.Instant
import kotlinx.coroutines.flow.Flow

/**
 * Los métodos de pago. Los seis iniciales los crea la base al nacer, con su clave y sin nombre; los
 * demás, la persona.
 *
 * Las listas van en el orden que eligió la persona y después por `id`. Las lecturas que devuelven
 * [Flow] vuelven a emitir cada vez que se confirma una transacción que cambia lo leído. Las
 * funciones que escriben solo se llaman dentro de [TransactionRunner.write].
 *
 * Un tipo guardado que esta versión no conoce se lee como `OTHER`: los métodos son datos de la
 * persona, no una lista cerrada de la app.
 */
interface PaymentMethodRepository {
    /** Los métodos de [filter], en su orden. */
    fun observe(filter: ArchiveFilter): Flow<List<PaymentMethodRecord>>

    /** Los vigentes, en su orden: los que se ofrecen al cobrar. */
    suspend fun findActive(): List<PaymentMethodRecord>

    suspend fun findById(id: Long): PaymentMethodRecord?

    /** El vigente con ese nombre normalizado, para no repetir nombres. */
    suspend fun findActiveByNormalizedName(normalizedName: String): PaymentMethodRecord?

    /** Si alguna venta, aunque esté anulada, se cobró con él. Con ventas ya no se borra. */
    suspend fun hasSalePayments(id: Long): Boolean

    /** Si la cuenta de dinero existe y no está archivada: solo a esas se asigna un método. */
    suspend fun isActiveMoneyAccount(id: Long): Boolean

    /** El mayor orden guardado, archivados incluidos, o `null` si no hay ningún método. */
    suspend fun maxSortOrder(): Long?

    /** Crea un método de la persona, sin clave inicial ni archivo, y devuelve su id. */
    suspend fun insert(details: PaymentMethodDetails, sortOrder: Long, createdAt: Instant): Long

    /** Guarda lo que la persona escribe. No toca la clave, el orden ni el archivo. */
    suspend fun update(id: Long, details: PaymentMethodDetails, updatedAt: Instant)

    /** Lo mueve a [sortOrder] en la lista. */
    suspend fun setSortOrder(id: Long, sortOrder: Long, updatedAt: Instant)

    /** Lo archiva con [archivedAt], o lo reactiva con `null`. */
    suspend fun setArchivedAt(id: Long, archivedAt: Instant?, updatedAt: Instant)

    /** Elimina un método sin ventas. */
    suspend fun delete(id: Long)
}
