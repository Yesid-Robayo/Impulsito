package io.github.yesidrobayo.shopledger.domain.port

import io.github.yesidrobayo.shopledger.domain.model.ArchiveFilter
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.catalog.CatalogLink
import io.github.yesidrobayo.shopledger.domain.model.catalog.NewProduct
import io.github.yesidrobayo.shopledger.domain.model.catalog.Product
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductDetails
import kotlin.time.Instant
import kotlinx.coroutines.flow.Flow

/**
 * Los productos del catálogo.
 *
 * Las lecturas que devuelven [Flow] vuelven a emitir cada vez que se confirma una transacción que
 * cambia lo leído. Las funciones que escriben solo se llaman dentro de [TransactionRunner.write].
 *
 * Ninguna función de aquí escribe el saldo, el costo promedio ni el valor del stock: los fija
 * [StockLedgerRepository.append] con cada fila del libro, para que nunca se aparten de él.
 */
interface ProductRepository {
    /** Los productos de [filter], en el orden de su nombre normalizado. */
    fun observe(filter: ArchiveFilter): Flow<List<Product>>

    fun observeById(id: Long): Flow<Product?>

    suspend fun findById(id: Long): Product?

    /**
     * Los productos de [ids] que existen, en cualquier orden. Una venta o una compra los lee todos
     * de una vez, al empezar su transacción.
     */
    suspend fun findByIds(ids: Collection<Long>): List<Product>

    /** El producto sin archivar con ese nombre normalizado, para no repetir nombres. */
    suspend fun findActiveByNormalizedName(normalizedName: String): Product?

    /** El producto sin archivar con ese código de barras. */
    suspend fun findActiveByBarcode(barcode: String): Product?

    /**
     * Si tiene alguna fila en el libro de stock o en una línea de documento, aunque esté anulada.
     * Con historia ya no se borra, y su unidad base y su modo de stock ya no cambian.
     */
    suspend fun hasHistory(id: Long): Boolean

    /**
     * Cómo lo usan otros productos del catálogo: como componente, como extra o como su producto
     * principal de variantes. No es historia, pero mientras exista impide eliminarlo. Vacío si
     * nada lo usa.
     */
    suspend fun catalogLinks(id: Long): Set<CatalogLink>

    /** Crea el producto, sin stock, y devuelve su id. */
    suspend fun insert(product: NewProduct): Long

    suspend fun updateDetails(id: Long, details: ProductDetails, updatedAt: Instant)

    /** Cambia el precio de venta. La fila de su historial la agrega [PriceHistoryRepository]. */
    suspend fun updateSalePrice(id: Long, salePrice: Money, updatedAt: Instant)

    /**
     * Cambia el costo inicial y, con él, el costo promedio. Solo vale mientras el producto no tenga
     * filas en el libro: después, el costo solo lo mueve el libro.
     */
    suspend fun updateInitialCost(id: Long, initialCost: Money, updatedAt: Instant)

    /** Cambia el costo de reposición, que [replacementCostAt] fecha. Cero es sin dato. */
    suspend fun updateReplacementCost(
        id: Long,
        replacementCost: Money,
        replacementCostAt: Instant?,
        updatedAt: Instant,
    )

    /** Lo pone a esperar una revisión de precio desde [since], o lo saca de esa espera con `null`. */
    suspend fun setPriceReviewSince(id: Long, since: Instant?, updatedAt: Instant)

    /** Lo archiva con [archivedAt], o lo reactiva con `null`. */
    suspend fun setArchivedAt(id: Long, archivedAt: Instant?, updatedAt: Instant)

    /**
     * Elimina un producto sin historia. Su historial de precios y sus alertas se borran con él, en
     * la misma transacción.
     */
    suspend fun delete(id: Long)
}
