package io.github.yesidrobayo.shopledger.data.repository

import io.github.yesidrobayo.shopledger.data.local.AppDatabase
import io.github.yesidrobayo.shopledger.data.local.catalog.ProductDao
import io.github.yesidrobayo.shopledger.data.local.stock.StockBalanceDao
import io.github.yesidrobayo.shopledger.data.local.stock.StockBalanceEntity
import io.github.yesidrobayo.shopledger.data.local.stock.StockMovementDao
import io.github.yesidrobayo.shopledger.data.local.stock.StockMovementEntity
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.stock.NewStockMovement
import io.github.yesidrobayo.shopledger.domain.model.stock.StockAdjustmentReason
import io.github.yesidrobayo.shopledger.domain.model.stock.StockKey
import io.github.yesidrobayo.shopledger.domain.model.stock.StockMovement
import io.github.yesidrobayo.shopledger.domain.model.stock.StockMovementRecord
import io.github.yesidrobayo.shopledger.domain.model.stock.StockMovementSource
import io.github.yesidrobayo.shopledger.domain.model.stock.StockMovementType
import io.github.yesidrobayo.shopledger.domain.port.StockLedgerRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * El libro de stock sobre `stock_movements`, con sus dos copias derivadas: el saldo, el costo y el
 * valor de `products`, y el saldo de cada parte en `stock_balances`.
 *
 * Las tres escrituras de [append] van en la transacción del caso de uso: si cualquiera falla, la
 * transacción se deshace entera, y ningún saldo queda sin su fila ni una fila sin su saldo. Así el
 * saldo de cada producto es siempre la suma de su libro, y también la de sus partes.
 *
 * Recibe los DAO por separado para que las pruebas puedan hacer fallar cada paso.
 */
internal class RoomStockLedgerRepository(
    private val movements: StockMovementDao,
    private val products: ProductDao,
    private val balances: StockBalanceDao,
    private val writes: RepositoryWrites,
) : StockLedgerRepository {
    constructor(database: AppDatabase) : this(
        database.stockMovementDao(),
        database.productDao(),
        database.stockBalanceDao(),
        RepositoryWrites(database),
    )

    override fun observeByProduct(productId: Long): Flow<List<StockMovementRecord>> = movements
        .observeByProduct(productId)
        .map { rows -> rows.map(::toRecord) }
        .distinctUntilChanged()

    override suspend fun findById(id: Long): StockMovementRecord? =
        movements.findById(id)?.let(::toRecord)

    override suspend fun findByProduct(productId: Long): List<StockMovementRecord> =
        movements.findByProduct(productId).map(::toRecord)

    override suspend fun findBySale(saleId: Long): List<StockMovementRecord> =
        movements.findBySale(saleId).map(::toRecord)

    override suspend fun findByPurchase(purchaseId: Long): List<StockMovementRecord> =
        movements.findByPurchase(purchaseId).map(::toRecord)

    override suspend fun findByPurchaseReturn(purchaseReturnId: Long): List<StockMovementRecord> =
        movements.findByPurchaseReturn(purchaseReturnId).map(::toRecord)

    override suspend fun isCompensated(movementId: Long): Boolean =
        movements.isCompensated(movementId)

    /**
     * Agrega la fila y mueve sus dos copias, en este orden:
     *
     * 1. Comprueba que el saldo que la fila dice dejar sea el que el producto tenía más su
     *    cantidad. Si no, el caso de uso calculó con un producto viejo o sumó mal, y la fila
     *    dejaría el libro y el saldo descuadrados para siempre, porque el libro no se edita.
     * 2. Inserta la fila. Su guarda revisa tipo, signo, origen, lote y compensación.
     * 3. Deja al producto con el saldo, el costo promedio y el valor del stock de la fila.
     * 4. Suma la cantidad al saldo de su parte: producto, ubicación y lote, o la parte sin lote. Una
     *    corrección de costo no mueve unidades y no toca ninguna parte.
     *
     * Un saldo que no cuadra o una suma que no cabe en `Long` salen como regla rota, y la
     * transacción se deshace.
     */
    override suspend fun append(movement: NewStockMovement): Long = writes.inTransaction {
        val draft = movement.movement
        val key = draft.key
        val before = products.findOnHandMilli(key.productId) ?: brokenRow()
        val expectedAfter =
            try {
                Math.addExact(before, draft.quantity.milli)
            } catch (_: ArithmeticException) {
                brokenRow()
            }
        if (expectedAfter != movement.balanceAfter.milli) brokenRow()

        val id = movements.insert(toEntity(movement))
        requireOneRow(
            products.updateStockState(
                key.productId,
                onHandMilli = movement.balanceAfter.milli,
                averageCost = movement.averageCostAfter.minor,
                stockValue = movement.stockValueAfter.minor,
                updatedAt = movement.createdAt.toStoredMillis(),
            ),
        )
        if (draft.quantity.milli != 0L) {
            balances.add(
                productId = key.productId,
                locationId = key.locationId,
                lotKey = StockBalanceEntity.lotKeyOf(key.lotId),
                deltaMilli = draft.quantity.milli,
            )
        }
        id
    }

    private fun toEntity(movement: NewStockMovement): StockMovementEntity {
        val draft = movement.movement
        val source = movement.source
        return StockMovementEntity(
            productId = draft.key.productId,
            locationId = draft.key.locationId,
            lotId = draft.key.lotId,
            type = draft.type.name,
            reason = movement.reason?.name,
            quantityMilli = draft.quantity.milli,
            unitCost = movement.unitCost.minor,
            totalCost = movement.totalCost.minor,
            averageCostAfter = movement.averageCostAfter.minor,
            stockValueAfter = movement.stockValueAfter.minor,
            balanceAfterMilli = movement.balanceAfter.milli,
            reversesMovementId = draft.reversesMovementId,
            saleLineId = source.saleLineId,
            saleLineComponentId = source.saleLineComponentId,
            purchaseLineId = source.purchaseLineId,
            saleReturnLineId = source.saleReturnLineId,
            purchaseReturnLineId = source.purchaseReturnLineId,
            transferLineId = source.transferLineId,
            productionBatchId = source.productionBatchId,
            countSessionId = source.countSessionId,
            note = movement.note,
            occurredAt = movement.occurredAt.toStoredMillis(),
            createdAt = movement.createdAt.toStoredMillis(),
            businessDate = movement.businessDate.toStored(),
        )
    }

    private fun toRecord(row: StockMovementEntity): StockMovementRecord = StockMovementRecord(
        movement = StockMovement(
            id = row.id,
            key = StockKey(row.productId, row.locationId, row.lotId),
            type = storedEnum<StockMovementType>(row.type),
            quantity = QuantityMilli(row.quantityMilli),
            reversesMovementId = row.reversesMovementId,
        ),
        reason = row.reason?.let { storedEnum<StockAdjustmentReason>(it) },
        unitCost = Money(row.unitCost),
        totalCost = Money(row.totalCost),
        balanceAfter = QuantityMilli(row.balanceAfterMilli),
        averageCostAfter = Money(row.averageCostAfter),
        stockValueAfter = Money(row.stockValueAfter),
        source = StockMovementSource(
            saleLineId = row.saleLineId,
            saleLineComponentId = row.saleLineComponentId,
            purchaseLineId = row.purchaseLineId,
            saleReturnLineId = row.saleReturnLineId,
            purchaseReturnLineId = row.purchaseReturnLineId,
            transferLineId = row.transferLineId,
            productionBatchId = row.productionBatchId,
            countSessionId = row.countSessionId,
        ),
        note = row.note,
        occurredAt = row.occurredAt.toInstant(),
        businessDate = storedDate(row.businessDate),
        createdAt = row.createdAt.toInstant(),
    )
}
