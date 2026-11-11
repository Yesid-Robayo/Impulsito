package io.github.yesidrobayo.shopledger.domain.port

import io.github.yesidrobayo.shopledger.domain.model.catalog.MeasureUnit
import io.github.yesidrobayo.shopledger.domain.model.catalog.UnitDetails
import kotlin.time.Instant
import kotlinx.coroutines.flow.Flow

/**
 * Las unidades tal como las administra su pantalla: crearlas, editarlas, archivarlas y eliminarlas.
 * Las lecturas de todos los días, como las de un formulario o una venta, son de [UnitRepository].
 *
 * Las funciones que escriben solo se llaman dentro de [TransactionRunner.write]. La de clave `UNIT`
 * nunca se archiva ni se elimina: la base la protege, y los casos de uso no lo piden.
 */
interface UnitCatalogRepository {
    /**
     * Los ids de las unidades con uso: base de algún producto, unidad de alguna conversión o de
     * alguna línea de compra, de venta, de cotización, de pedido o de una venta en espera. Vuelve a
     * emitir cada vez que se confirma una transacción que lo cambia.
     */
    fun observeInUse(): Flow<Set<Long>>

    suspend fun findById(id: Long): MeasureUnit?

    /** La vigente con ese nombre normalizado, para no repetir nombres. */
    suspend fun findActiveByNormalizedName(normalizedName: String): MeasureUnit?

    /** Si la unidad tiene uso, como dice [observeInUse]. Con uso no se elimina: se archiva. */
    suspend fun isInUse(id: Long): Boolean

    /** Crea una unidad vigente y devuelve su id. */
    suspend fun insert(details: UnitDetails, createdAt: Instant): Long

    /** Guarda lo que escribió la persona. No toca la clave ni el archivo. */
    suspend fun update(id: Long, details: UnitDetails, updatedAt: Instant)

    /** La archiva con [archivedAt], o la reactiva con `null`. */
    suspend fun setArchivedAt(id: Long, archivedAt: Instant?, updatedAt: Instant)

    /** Elimina una unidad sin uso. */
    suspend fun delete(id: Long)
}
