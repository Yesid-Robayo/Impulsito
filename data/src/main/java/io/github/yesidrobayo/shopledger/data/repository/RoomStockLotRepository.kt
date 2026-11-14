package io.github.yesidrobayo.shopledger.data.repository

import io.github.yesidrobayo.shopledger.data.local.AppDatabase
import io.github.yesidrobayo.shopledger.data.local.stock.LotStockRow
import io.github.yesidrobayo.shopledger.data.local.stock.StockLotEntity
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.stock.LotStock
import io.github.yesidrobayo.shopledger.domain.model.stock.NewStockLot
import io.github.yesidrobayo.shopledger.domain.model.stock.StockLot
import io.github.yesidrobayo.shopledger.domain.port.StockLotRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Los lotes sobre `stock_lots`, y su saldo por ubicación sobre `stock_balances`.
 *
 * El lote sin asignar no se crea aquí: lo crea el libro de stock cuando una fila lo necesita. Un
 * código repetido en el mismo producto lo rechaza el índice único, y sale como regla rota.
 */
internal class RoomStockLotRepository(database: AppDatabase) : StockLotRepository {
    private val lots = database.stockLotDao()
    private val writes = RepositoryWrites(database)

    // Room vuelve a emitir con cualquier cambio de la tabla: una lista igual no se repite.
    override fun observeByProduct(productId: Long): Flow<List<StockLot>> = lots
        .observeByProduct(productId)
        .map { rows -> rows.map(::toLot) }
        .distinctUntilChanged()

    override suspend fun findById(id: Long): StockLot? = lots.findById(id)?.let(::toLot)

    override suspend fun findByCode(productId: Long, code: String): StockLot? =
        lots.findByCode(productId, code)?.let(::toLot)

    override suspend fun findStock(productId: Long, locationId: Long): List<LotStock> =
        lots.findStock(productId, locationId).map(::toStock)

    override suspend fun insert(lot: NewStockLot): Long = writes.inTransaction {
        lots.insert(
            StockLotEntity(
                productId = lot.productId,
                lotCode = lot.code,
                isUnassigned = 0L,
                expiryDate = lot.expiryDate?.toStored(),
                purchaseLineId = lot.purchaseLineId,
                expiryNotifiedAt = null,
                createdAt = lot.createdAt.toStoredMillis(),
                closedAt = null,
            ),
        )
    }

    private fun toLot(row: StockLotEntity): StockLot = StockLot(
        id = row.id,
        productId = row.productId,
        code = row.lotCode,
        isUnassigned = row.isUnassigned.toFlag(),
        expiryDate = row.expiryDate?.let(::storedDate),
        purchaseLineId = row.purchaseLineId,
        expiryNotifiedAt = row.expiryNotifiedAt?.toInstant(),
        createdAt = row.createdAt.toInstant(),
        closedAt = row.closedAt?.toInstant(),
    )

    private fun toStock(row: LotStockRow): LotStock = LotStock(
        lotId = row.id,
        code = row.lotCode,
        expiryDate = row.expiryDate?.let(::storedDate),
        isUnassigned = row.isUnassigned.toFlag(),
        onHand = QuantityMilli(row.onHandMilli),
    )
}
