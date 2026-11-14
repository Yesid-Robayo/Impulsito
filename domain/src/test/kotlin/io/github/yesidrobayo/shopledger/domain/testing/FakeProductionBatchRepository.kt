package io.github.yesidrobayo.shopledger.domain.testing

import io.github.yesidrobayo.shopledger.domain.model.production.NewProductionBatch
import io.github.yesidrobayo.shopledger.domain.model.production.NewProductionBatchLine
import io.github.yesidrobayo.shopledger.domain.model.production.ProductionBatch
import io.github.yesidrobayo.shopledger.domain.model.production.ProductionBatchLine
import io.github.yesidrobayo.shopledger.domain.port.ProductionBatchRepository

/**
 * Las producciones sobre [InMemoryDatabase], con lo que exige la guarda de la tabla: una cabecera
 * nace activa, y un insumo apunta a una producción que existe.
 */
internal class FakeProductionBatchRepository(private val database: InMemoryDatabase) :
    ProductionBatchRepository {
    override suspend fun findById(id: Long): ProductionBatch? =
        database.read().productionBatches[id]

    override suspend fun findLines(productionBatchId: Long): List<ProductionBatchLine> =
        database.read().productionLines.values
            .filter { it.productionBatchId == productionBatchId }
            .sortedBy { it.id }

    override suspend fun insert(batch: NewProductionBatch): Long {
        check(batch.quantity.milli in 1L..1_000_000_000L) { "guard:production_batches" }
        val id = database.nextId("production_batches")
        val row = ProductionBatch(id, batch, voiding = null)
        database.write { it.copy(productionBatches = it.productionBatches + (id to row)) }
        return id
    }

    override suspend fun insertLine(productionBatchId: Long, line: NewProductionBatchLine): Long {
        checkNotNull(database.read().productionBatches[productionBatchId]) { "FOREIGN KEY" }
        check(line.quantity.milli in 1L..1_000_000_000L) { "guard:production_batch_lines" }
        val id = database.nextId("production_batch_lines")
        val row = ProductionBatchLine(id, productionBatchId, line)
        database.write { it.copy(productionLines = it.productionLines + (id to row)) }
        return id
    }
}
