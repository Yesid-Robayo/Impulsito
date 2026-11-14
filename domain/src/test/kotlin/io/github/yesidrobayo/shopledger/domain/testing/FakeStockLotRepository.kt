package io.github.yesidrobayo.shopledger.domain.testing

import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.stock.DatedLotStock
import io.github.yesidrobayo.shopledger.domain.model.stock.LotStock
import io.github.yesidrobayo.shopledger.domain.model.stock.NewStockLot
import io.github.yesidrobayo.shopledger.domain.model.stock.StockLot
import io.github.yesidrobayo.shopledger.domain.port.StockLotRepository
import kotlin.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Un [StockLotRepository] sobre [InMemoryDatabase]. El saldo de cada lote sale de sumar sus filas
 * del libro, como lo deja la base real en sus saldos por parte.
 */
internal class FakeStockLotRepository(private val database: InMemoryDatabase) :
    StockLotRepository {
    override fun observeByProduct(productId: Long): Flow<List<StockLot>> =
        database.tables.map { tables -> ofProduct(tables, productId) }

    override fun observeAll(): Flow<List<StockLot>> =
        database.tables.map { tables -> tables.lots.values.sortedBy { it.id } }

    override suspend fun findById(id: Long): StockLot? = database.read().lots[id]

    override suspend fun findByCode(productId: Long, code: String): StockLot? =
        database.read().lots.values.firstOrNull { it.productId == productId && it.code == code }

    override suspend fun findStock(productId: Long, locationId: Long): List<LotStock> {
        val tables = database.read()
        return ofProduct(tables, productId).map { lot ->
            val onHand = tables.movements.values
                .map { it.movement }
                .filter { it.key.lotId == lot.id && it.key.locationId == locationId }
                .fold(QuantityMilli.ZERO) { sum, movement -> sum + movement.quantity }
            LotStock(lot.id, lot.code, lot.expiryDate, lot.isUnassigned, onHand)
        }
    }

    override suspend fun insert(lot: NewStockLot): Long {
        check(findByCode(lot.productId, lot.code) == null) { "El código ya existe en el producto" }
        val id = database.nextId(TABLE)
        val row = StockLot(
            id = id,
            productId = lot.productId,
            code = lot.code,
            isUnassigned = false,
            expiryDate = lot.expiryDate,
            purchaseLineId = lot.purchaseLineId,
            expiryNotifiedAt = null,
            createdAt = lot.createdAt,
            closedAt = null,
        )
        database.write { it.copy(lots = it.lots + (id to row)) }
        return id
    }

    override suspend fun attachPurchaseLine(lotId: Long, purchaseLineId: Long) {
        val lot = checkNotNull(database.read().lots[lotId]) { "El lote no existe" }
        val attached = lot.copy(purchaseLineId = purchaseLineId)
        database.write { it.copy(lots = it.lots + (lotId to attached)) }
    }

    override fun observeDated(): Flow<List<DatedLotStock>> = database.tables.map { tables ->
        tables.lots.values.sortedBy { it.id }.mapNotNull { lot ->
            val product = tables.products[lot.productId]
            val expiry = lot.expiryDate
            val onHand = tables.movements.values
                .map { it.movement }
                .filter { it.key.lotId == lot.id }
                .fold(QuantityMilli.ZERO) { sum, movement -> sum + movement.quantity }
            val open = lot.closedAt == null && !lot.isUnassigned
            if (product == null || product.isArchived || expiry == null || !open) {
                return@mapNotNull null
            }
            if (onHand <= QuantityMilli.ZERO) return@mapNotNull null
            DatedLotStock(
                lotId = lot.id,
                productId = product.id,
                productName = product.details.name,
                code = lot.code,
                expiryDate = expiry,
                onHand = onHand,
                baseUnitId = product.details.baseUnitId,
                noticeDays = product.details.expiryNoticeDays,
                expiryNotifiedAt = lot.expiryNotifiedAt,
            )
        }.sortedWith(compareBy({ it.expiryDate }, { it.lotId }))
    }

    // Como en la base real, anota en su propia escritura, fuera de la transacción de un caso de uso.
    override suspend fun markExpiryNotified(lotIds: List<Long>, notifiedAt: Instant) {
        database.seed { tables ->
            val marked = lotIds.associateWith { id ->
                val lot = checkNotNull(tables.lots[id]) { "El lote no existe" }
                lot.copy(expiryNotifiedAt = notifiedAt)
            }
            tables.copy(lots = tables.lots + marked)
        }
    }

    private fun ofProduct(tables: Tables, productId: Long): List<StockLot> =
        tables.lots.values.filter { it.productId == productId }.sortedBy { it.id }

    private companion object {
        // El mismo nombre que usa el libro falso al crear el lote sin asignar: un solo contador.
        const val TABLE = "stock_lots"
    }
}
