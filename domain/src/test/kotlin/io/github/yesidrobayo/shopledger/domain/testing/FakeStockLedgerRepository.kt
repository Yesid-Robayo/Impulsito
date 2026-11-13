package io.github.yesidrobayo.shopledger.domain.testing

import io.github.yesidrobayo.shopledger.domain.model.stock.NewStockMovement
import io.github.yesidrobayo.shopledger.domain.model.stock.StockMovementRecord
import io.github.yesidrobayo.shopledger.domain.port.StockLedgerRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Un [StockLedgerRepository] sobre [InMemoryDatabase]: agrega la fila y deja al producto con los
 * valores que la fila dice tener después, en dos escrituras de la misma transacción.
 */
internal class FakeStockLedgerRepository(private val database: InMemoryDatabase) :
    StockLedgerRepository {
    override fun observeByProduct(productId: Long): Flow<List<StockMovementRecord>> =
        database.tables.map { ofProduct(it, productId) }

    override suspend fun findById(id: Long): StockMovementRecord? = database.read().movements[id]

    override suspend fun findByProduct(productId: Long): List<StockMovementRecord> =
        ofProduct(database.read(), productId)

    override suspend fun findBySale(saleId: Long): List<StockMovementRecord> =
        error("Este caso de uso falso no vende")

    override suspend fun findByPurchase(purchaseId: Long): List<StockMovementRecord> {
        val tables = database.read()
        val lineIds = tables.purchaseLines.values.filter {
            it.purchaseId == purchaseId
        }.map { it.id }
        return tables.movements.values
            .filter { it.source.purchaseLineId in lineIds }
            .sortedBy { it.id }
    }

    override suspend fun findByPurchaseReturn(purchaseReturnId: Long): List<StockMovementRecord> =
        error("Este caso de uso falso no devuelve a proveedor")

    override suspend fun isCompensated(movementId: Long): Boolean =
        database.read().movements.values.any { it.movement.reversesMovementId == movementId }

    override suspend fun append(movement: NewStockMovement): Long {
        val id = database.nextId(TABLE)
        database.write { it.copy(movements = it.movements + (id to movement.recordedAs(id))) }
        val header = ProductStockHeader(
            onHand = movement.balanceAfter,
            averageCost = movement.averageCostAfter,
            stockValue = movement.stockValueAfter,
        )
        database.write {
            it.copy(productStock = it.productStock + (movement.movement.key.productId to header))
        }
        return id
    }

    private fun ofProduct(tables: Tables, productId: Long): List<StockMovementRecord> =
        tables.movements.values.filter { it.movement.key.productId == productId }.sortedBy { it.id }

    private companion object {
        const val TABLE = "stock_movements"
    }
}
