package io.github.yesidrobayo.shopledger.domain.port

import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.pricing.PriceList
import io.github.yesidrobayo.shopledger.domain.model.pricing.PriceListDetails
import kotlin.time.Instant
import kotlinx.coroutines.flow.Flow

/**
 * Las listas de precios: la inicial, que usa el precio de venta, y las fijas o relativas que crea la
 * persona.
 *
 * Las funciones que escriben solo se llaman dentro de [TransactionRunner.write]. La inicial nunca se
 * archiva ni se borra, y siempre es fija: la base la protege, y los casos de uso no lo piden. Las
 * lecturas que devuelven [Flow] vuelven a emitir cada vez que se confirma una transacción que cambia
 * lo leído. Las listas van en el orden en que se crearon, la inicial primero.
 */
interface PriceListRepository {
    /** Las vigentes: las que se ofrecen en una venta o para un cliente. */
    fun observeActive(): Flow<List<PriceList>>

    /** Las archivadas, que se pueden reactivar. */
    fun observeArchived(): Flow<List<PriceList>>

    /**
     * Los ids de las listas con uso: de una venta o una línea, de una venta en espera, de un cliente,
     * base de otra lista, con precios propios o con filas en el historial de precios.
     */
    fun observeInUse(): Flow<Set<Long>>

    /**
     * Cuántos clientes vigentes tiene cada lista, por id. Al archivarla, esos clientes vuelven a la
     * inicial. Las listas sin clientes no salen.
     */
    fun observeCustomerCounts(): Flow<Map<Long, Int>>

    suspend fun findById(id: Long): PriceList?

    /** Todas, vigentes y archivadas, leídas una vez: una relativa puede partir de una archivada. */
    suspend fun findAll(): List<PriceList>

    /** La vigente con ese nombre normalizado, para no repetir nombres. */
    suspend fun findActiveByNormalizedName(normalizedName: String): PriceList?

    /**
     * Si tomar [baseId] como base de [id] formaría un ciclo: si [id] ya está en la cadena de bases
     * de [baseId], o es ella misma.
     */
    suspend fun wouldCreateCycle(id: Long, baseId: Long): Boolean

    /** Si la lista tiene uso, como dice [observeInUse]. Con uso no se elimina: se archiva. */
    suspend fun isInUse(id: Long): Boolean

    /** Crea una lista vigente y devuelve su id. */
    suspend fun insert(details: PriceListDetails, createdAt: Instant): Long

    /**
     * Guarda lo que escribió la persona, sin tocar la clave ni el archivo. Devuelve `false` sin
     * cambiar nada si la nueva base formaría un ciclo: la base lo comprueba en la misma sentencia.
     */
    suspend fun update(id: Long, details: PriceListDetails, updatedAt: Instant): Boolean

    /** La archiva con [archivedAt], o la reactiva con `null`. */
    suspend fun setArchivedAt(id: Long, archivedAt: Instant?, updatedAt: Instant)

    /** Elimina una lista sin uso. */
    suspend fun delete(id: Long)
}

/**
 * Los precios propios de cada producto en las listas fijas distintas de la inicial. La inicial usa
 * el precio de venta del producto y una relativa se calcula: ninguna de las dos guarda precios
 * aquí, y la base lo impone.
 *
 * Las funciones que escriben solo se llaman dentro de [TransactionRunner.write], en la misma
 * transacción que deja el cambio en el historial de precios.
 */
interface ProductPriceRepository {
    /** Los precios propios de una lista, por id de producto. */
    fun observeByList(priceListId: Long): Flow<Map<Long, Money>>

    /** Todos los precios propios: por id de lista, y dentro, por id de producto. */
    fun observeAll(): Flow<Map<Long, Map<Long, Money>>>

    /** Los precios propios de un producto, por id de lista. */
    suspend fun findByProduct(productId: Long): Map<Long, Money>

    /** El precio propio del producto en esa lista, o `null` si toma el de la inicial. */
    suspend fun find(priceListId: Long, productId: Long): Money?

    /** Si la lista tiene algún precio propio. */
    suspend fun hasAny(priceListId: Long): Boolean

    /** Le da al producto su primer precio propio en esa lista. */
    suspend fun insert(priceListId: Long, productId: Long, price: Money, at: Instant)

    /** Cambia el precio propio que el producto ya tiene en esa lista. */
    suspend fun update(priceListId: Long, productId: Long, price: Money, at: Instant)

    /** Quita el precio propio: el producto vuelve a tomar el de la inicial en esa lista. */
    suspend fun delete(priceListId: Long, productId: Long)
}
