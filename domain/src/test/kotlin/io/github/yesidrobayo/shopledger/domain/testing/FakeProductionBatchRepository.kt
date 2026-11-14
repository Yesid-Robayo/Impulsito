package io.github.yesidrobayo.shopledger.domain.testing

import io.github.yesidrobayo.shopledger.domain.model.Voiding
import io.github.yesidrobayo.shopledger.domain.model.production.NewProductionBatch
import io.github.yesidrobayo.shopledger.domain.model.production.NewProductionBatchLine
import io.github.yesidrobayo.shopledger.domain.model.production.ProductionBatch
import io.github.yesidrobayo.shopledger.domain.model.production.ProductionBatchLine
import io.github.yesidrobayo.shopledger.domain.port.ProductionBatchRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Las producciones sobre [InMemoryDatabase], con lo que exige la guarda de la tabla: una cabecera
 * nace activa, y un insumo apunta a una producción que existe.
 */
internal class FakeProductionBatchRepository(private val database: InMemoryDatabase) :
    ProductionBatchRepository {
    override fun observeRecent(limit: Int): Flow<List<ProductionBatch>> =
        database.tables.map { tables ->
            tables.productionBatches.values
                .sortedWith(
                    compareByDescending<ProductionBatch> { it.content.businessDate }
                        .thenByDescending { it.id },
                )
                .take(limit)
        }

    override fun observeById(id: Long): Flow<ProductionBatch?> =
        database.tables.map { it.productionBatches[id] }

    override fun observeLines(productionBatchId: Long): Flow<List<ProductionBatchLine>> =
        database.tables.map { tables ->
            tables.productionLines.values
                .filter { it.productionBatchId == productionBatchId }
                .sortedBy { it.id }
        }

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

    override suspend fun markVoided(id: Long, voiding: Voiding) {
        val batch = checkNotNull(database.read().productionBatches[id]) { "La fila no existe" }
        check(batch.voiding == null) { "guard:production_batches_void_only" }
        val voided = batch.copy(voiding = voiding)
        database.write { it.copy(productionBatches = it.productionBatches + (id to voided)) }
    }
}
