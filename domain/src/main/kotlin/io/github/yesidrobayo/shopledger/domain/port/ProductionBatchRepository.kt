package io.github.yesidrobayo.shopledger.domain.port

import io.github.yesidrobayo.shopledger.domain.model.Voiding
import io.github.yesidrobayo.shopledger.domain.model.production.NewProductionBatch
import io.github.yesidrobayo.shopledger.domain.model.production.NewProductionBatchLine
import io.github.yesidrobayo.shopledger.domain.model.production.ProductionBatch
import io.github.yesidrobayo.shopledger.domain.model.production.ProductionBatchLine

/**
 * Las producciones y sus insumos. Una producción confirmada no se edita: solo se anula.
 *
 * Los insumos salen en el orden en que se guardaron, por id. Las funciones que escriben solo se
 * llaman dentro de [TransactionRunner.write].
 */
interface ProductionBatchRepository {
    suspend fun findById(id: Long): ProductionBatch?

    /** Los insumos de una producción, en el orden en que se guardaron. */
    suspend fun findLines(productionBatchId: Long): List<ProductionBatchLine>

    /** Guarda la cabecera, activa, y devuelve su id. */
    suspend fun insert(batch: NewProductionBatch): Long

    /** Guarda un insumo de la producción [productionBatchId] y devuelve su id. */
    suspend fun insertLine(productionBatchId: Long, line: NewProductionBatchLine): Long

    /** Anula una producción activa. Es lo único que cambia en ella después de confirmarse. */
    suspend fun markVoided(id: Long, voiding: Voiding)
}
