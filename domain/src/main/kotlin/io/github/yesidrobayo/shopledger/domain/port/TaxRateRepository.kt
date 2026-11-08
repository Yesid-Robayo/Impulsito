package io.github.yesidrobayo.shopledger.domain.port

import io.github.yesidrobayo.shopledger.domain.model.ArchiveFilter
import io.github.yesidrobayo.shopledger.domain.model.tax.TaxRate
import io.github.yesidrobayo.shopledger.domain.model.tax.TaxRateDetails
import kotlin.time.Instant
import kotlinx.coroutines.flow.Flow

/**
 * Las tasas de impuesto que escribió la persona. La base nace sin ninguna.
 *
 * Las listas van en el orden en que se crearon, por `id`. Las lecturas que devuelven [Flow]
 * vuelven a emitir cada vez que se confirma una transacción que cambia lo leído. Las funciones que
 * escriben solo se llaman dentro de [TransactionRunner.write].
 */
interface TaxRateRepository {
    /** Las tasas de [filter], en el orden en que se crearon. */
    fun observe(filter: ArchiveFilter): Flow<List<TaxRate>>

    /**
     * Cuántos productos, archivados o no, usa cada tasa. Una tasa sin productos no aparece en el
     * mapa.
     */
    fun observeProductCounts(): Flow<Map<Long, Int>>

    suspend fun findById(id: Long): TaxRate?

    /** La vigente con ese nombre normalizado, para no repetir nombres. */
    suspend fun findActiveByNormalizedName(normalizedName: String): TaxRate?

    /** Cuántos productos, archivados o no, la usan. Con alguno no se elimina: se archiva. */
    suspend fun countProducts(id: Long): Int

    /** Crea una tasa vigente y devuelve su id. */
    suspend fun insert(details: TaxRateDetails, createdAt: Instant): Long

    /** Guarda lo que la persona escribe. No toca el archivo ni las ventas o compras pasadas. */
    suspend fun update(id: Long, details: TaxRateDetails, updatedAt: Instant)

    /** La archiva con [archivedAt], o la reactiva con `null`. */
    suspend fun setArchivedAt(id: Long, archivedAt: Instant?, updatedAt: Instant)

    /**
     * Elimina una tasa sin productos. Si era la tasa por defecto, el negocio queda sin tasa por
     * defecto en la misma sentencia.
     */
    suspend fun delete(id: Long)
}
