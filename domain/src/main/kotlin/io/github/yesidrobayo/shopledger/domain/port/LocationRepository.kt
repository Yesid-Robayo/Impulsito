package io.github.yesidrobayo.shopledger.domain.port

import io.github.yesidrobayo.shopledger.domain.model.ArchiveFilter
import io.github.yesidrobayo.shopledger.domain.model.stock.Location
import io.github.yesidrobayo.shopledger.domain.model.stock.LocationDetails
import io.github.yesidrobayo.shopledger.domain.model.stock.NewLocation
import kotlin.time.Instant
import kotlinx.coroutines.flow.Flow

/**
 * Las ubicaciones del stock. El stock de cada una no se escribe aquí: lo mueve solo
 * [StockLedgerRepository.append], en la transacción que agrega cada fila del libro.
 *
 * Las listas van en el orden en que se crearon, por id: la inicial primero, y el orden no depende
 * del idioma. Las lecturas que devuelven [Flow] vuelven a emitir con cada transacción confirmada
 * que cambia lo leído. Las funciones que escriben solo se llaman dentro de
 * [TransactionRunner.write].
 */
interface LocationRepository {
    fun observe(filter: ArchiveFilter): Flow<List<Location>>

    suspend fun findById(id: Long): Location?

    /** La vigente con ese nombre normalizado, si hay una: una archivada no reserva el suyo. */
    suspend fun findActiveByNormalizedName(normalizedName: String): Location?

    /** Guarda la ubicación, vigente, y devuelve su id. */
    suspend fun insert(location: NewLocation): Long

    /** Cambia lo que la persona escribe. No toca la clave inicial ni el archivo. */
    suspend fun update(id: Long, details: LocationDetails, updatedAt: Instant)

    /** La archiva en [archivedAt], o la reactiva con `null`. La inicial nunca se archiva. */
    suspend fun setArchivedAt(id: Long, archivedAt: Instant?, updatedAt: Instant)

    /**
     * La ubicación de la última venta registrada, anulada o no, o `null` sin ventas. La venta
     * siguiente la propone: quien vende todo el día en un puesto no tiene que elegirlo cada vez.
     */
    fun observeLastSaleLocationId(): Flow<Long?>
}
