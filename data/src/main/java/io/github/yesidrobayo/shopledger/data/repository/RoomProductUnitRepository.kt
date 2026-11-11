package io.github.yesidrobayo.shopledger.data.repository

import io.github.yesidrobayo.shopledger.data.local.AppDatabase
import io.github.yesidrobayo.shopledger.data.local.catalog.ProductUnitEntity
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.catalog.ConversionDetails
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductConversion
import io.github.yesidrobayo.shopledger.domain.port.ProductUnitRepository
import kotlin.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Las conversiones de compra y venta sobre `product_units`.
 *
 * Las líneas de venta y de compra copian el factor, así que nada de lo que se escribe aquí cambia un
 * documento pasado. La unicidad del código entre conversiones y con los productos vigentes la impone
 * la guarda de la tabla.
 */
internal class RoomProductUnitRepository(database: AppDatabase) : ProductUnitRepository {
    private val conversions = database.productUnitDao()
    private val writes = RepositoryWrites(database)

    // Room vuelve a emitir con cualquier cambio de la tabla: una lista igual no se repite.
    override fun observeAll(): Flow<List<ProductConversion>> =
        conversions.observeAll().map { rows -> rows.map(::toConversion) }.distinctUntilChanged()

    override fun observeByProduct(productId: Long): Flow<List<ProductConversion>> =
        conversions.observeByProduct(productId)
            .map { rows -> rows.map(::toConversion) }
            .distinctUntilChanged()

    override suspend fun findById(id: Long): ProductConversion? =
        conversions.findById(id)?.let(::toConversion)

    override suspend fun findByProductAndUnit(productId: Long, unitId: Long): ProductConversion? =
        conversions.findByProductAndUnit(productId, unitId)?.let(::toConversion)

    override suspend fun findByBarcode(barcode: String): ProductConversion? =
        conversions.findByBarcode(barcode)?.let(::toConversion)

    override suspend fun insert(
        productId: Long,
        details: ConversionDetails,
        createdAt: Instant,
    ): Long = writes.inTransaction {
        val at = createdAt.toStoredMillis()
        conversions.insert(
            ProductUnitEntity(
                productId = productId,
                unitId = details.unitId,
                factorMilli = details.factorMilli,
                salePrice = details.salePrice?.minor,
                barcode = details.barcode,
                forSale = details.forSale.toStoredFlag(),
                forPurchase = details.forPurchase.toStoredFlag(),
                createdAt = at,
                updatedAt = at,
            ),
        )
    }

    override suspend fun update(id: Long, details: ConversionDetails, updatedAt: Instant) {
        writes.inTransaction {
            requireOneRow(
                conversions.update(
                    id = id,
                    factorMilli = details.factorMilli,
                    salePrice = details.salePrice?.minor,
                    barcode = details.barcode,
                    forSale = details.forSale.toStoredFlag(),
                    forPurchase = details.forPurchase.toStoredFlag(),
                    updatedAt = updatedAt.toStoredMillis(),
                ),
            )
        }
    }

    override suspend fun delete(id: Long) {
        writes.inTransaction { requireOneRow(conversions.delete(id)) }
    }

    private fun toConversion(row: ProductUnitEntity): ProductConversion = ProductConversion(
        id = row.id,
        productId = row.productId,
        details =
            ConversionDetails(
                unitId = row.unitId,
                factorMilli = row.factorMilli,
                salePrice = row.salePrice?.let(::Money),
                barcode = row.barcode,
                forSale = row.forSale.toFlag(),
                forPurchase = row.forPurchase.toFlag(),
            ),
    )
}
