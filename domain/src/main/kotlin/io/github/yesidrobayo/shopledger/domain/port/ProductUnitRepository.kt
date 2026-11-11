package io.github.yesidrobayo.shopledger.domain.port

import io.github.yesidrobayo.shopledger.domain.model.catalog.ConversionDetails
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductConversion
import kotlin.time.Instant
import kotlinx.coroutines.flow.Flow

/**
 * Las conversiones de compra y venta de los productos: cuánto trae una unidad distinta de la base.
 *
 * Un producto tiene a lo sumo una conversión por unidad, y un código no se repite entre
 * conversiones. Las listas van en el orden en que se crearon. Las funciones que escriben solo se
 * llaman dentro de [TransactionRunner.write]. Cada línea copia el factor, así que nada de lo que se
 * escribe aquí cambia un documento pasado.
 */
interface ProductUnitRepository {
    /** Todas las conversiones, de todos los productos. */
    fun observeAll(): Flow<List<ProductConversion>>

    /** Las de [productId], en el orden en que se crearon. */
    fun observeByProduct(productId: Long): Flow<List<ProductConversion>>

    suspend fun findById(id: Long): ProductConversion?

    /** La conversión de [productId] a [unitId]: hay a lo sumo una. */
    suspend fun findByProductAndUnit(productId: Long, unitId: Long): ProductConversion?

    /** La conversión con ese código, si alguna lo tiene. */
    suspend fun findByBarcode(barcode: String): ProductConversion?

    /** Crea una conversión de [productId] y devuelve su id. */
    suspend fun insert(productId: Long, details: ConversionDetails, createdAt: Instant): Long

    /** Cambia el factor, el precio, el código y dónde se ofrece. No cambia el producto ni la unidad. */
    suspend fun update(id: Long, details: ConversionDetails, updatedAt: Instant)

    /** Quita la conversión. Las líneas que la usaron conservan su factor. */
    suspend fun delete(id: Long)
}
