package io.github.yesidrobayo.shopledger.data.repository

import io.github.yesidrobayo.shopledger.data.local.AppDatabase
import io.github.yesidrobayo.shopledger.data.local.stock.ProductionBatchEntity
import io.github.yesidrobayo.shopledger.data.local.stock.ProductionBatchLineEntity
import io.github.yesidrobayo.shopledger.domain.model.DocumentStatus
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.Voiding
import io.github.yesidrobayo.shopledger.domain.model.production.NewProductionBatch
import io.github.yesidrobayo.shopledger.domain.model.production.NewProductionBatchLine
import io.github.yesidrobayo.shopledger.domain.model.production.ProductionBatch
import io.github.yesidrobayo.shopledger.domain.model.production.ProductionBatchLine
import io.github.yesidrobayo.shopledger.domain.port.ProductionBatchRepository

/**
 * Las producciones y sus insumos sobre `production_batches` y `production_batch_lines`. Una
 * producción nace activa y lo único que cambia después es su anulación, una sola vez. Sus insumos
 * no cambian nunca.
 */
internal class RoomProductionBatchRepository(database: AppDatabase) : ProductionBatchRepository {
    private val batches = database.productionBatchDao()
    private val writes = RepositoryWrites(database)

    override suspend fun findById(id: Long): ProductionBatch? = batches.findById(id)?.let(::toBatch)

    override suspend fun findLines(productionBatchId: Long): List<ProductionBatchLine> =
        batches.findLines(productionBatchId).map(::toLine)

    override suspend fun insert(batch: NewProductionBatch): Long = writes.inTransaction {
        batches.insert(
            ProductionBatchEntity(
                productId = batch.productId,
                locationId = batch.locationId,
                quantityMilli = batch.quantity.milli,
                totalCost = batch.totalCost.minor,
                note = batch.note,
                occurredAt = batch.occurredAt.toStoredMillis(),
                businessDate = batch.businessDate.toStored(),
                createdAt = batch.createdAt.toStoredMillis(),
                status = DocumentStatus.ACTIVE.name,
                voidedAt = null,
                voidReason = null,
                voidNote = null,
            ),
        )
    }

    override suspend fun insertLine(productionBatchId: Long, line: NewProductionBatchLine): Long =
        writes.inTransaction {
            batches.insertLine(
                ProductionBatchLineEntity(
                    productionBatchId = productionBatchId,
                    componentProductId = line.componentProductId,
                    lotId = null,
                    quantityMilli = line.quantity.milli,
                    unitCost = line.unitCost.minor,
                    lineCost = line.lineCost.minor,
                ),
            )
        }

    /**
     * Una producción que no existe o ya estaba anulada no cambia, y la transacción se deshace como
     * regla rota: anularla dos veces duplicaría sus compensatorios.
     */
    override suspend fun markVoided(id: Long, voiding: Voiding) {
        writes.inTransaction {
            requireOneRow(
                batches.markVoided(
                    id,
                    voiding.voidedAt.toStoredMillis(),
                    voiding.reason.name,
                    voiding.note,
                ),
            )
        }
    }

    private fun toBatch(row: ProductionBatchEntity): ProductionBatch = ProductionBatch(
        id = row.id,
        content = NewProductionBatch(
            productId = row.productId,
            locationId = row.locationId,
            quantity = QuantityMilli(row.quantityMilli),
            totalCost = Money(row.totalCost),
            note = row.note,
            occurredAt = row.occurredAt.toInstant(),
            businessDate = storedDate(row.businessDate),
            createdAt = row.createdAt.toInstant(),
        ),
        voiding = storedVoiding(row.status, row.voidedAt, row.voidReason, row.voidNote),
    )

    private fun toLine(row: ProductionBatchLineEntity): ProductionBatchLine = ProductionBatchLine(
        id = row.id,
        productionBatchId = row.productionBatchId,
        content = NewProductionBatchLine(
            componentProductId = row.componentProductId,
            quantity = QuantityMilli(row.quantityMilli),
            unitCost = Money(row.unitCost),
            lineCost = Money(row.lineCost),
        ),
    )
}
