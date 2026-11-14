package io.github.yesidrobayo.shopledger.domain.port

import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.Voiding
import io.github.yesidrobayo.shopledger.domain.model.stock.NewStockTransfer
import io.github.yesidrobayo.shopledger.domain.model.stock.NewStockTransferLine
import io.github.yesidrobayo.shopledger.domain.model.stock.StockMovementRecord
import io.github.yesidrobayo.shopledger.domain.model.stock.StockTransfer
import io.github.yesidrobayo.shopledger.domain.model.stock.StockTransferLine
import kotlinx.coroutines.flow.Flow

/**
 * Las transferencias de stock entre ubicaciones y sus líneas. Las filas del libro de cada línea no
 * se escriben aquí: las agrega [StockLedgerRepository.append], en la misma transacción.
 *
 * Una transferencia no se edita ni se borra: la única escritura después de guardarla es su
 * anulación. Las funciones que escriben solo se llaman dentro de [TransactionRunner.write].
 */
interface StockTransferRepository {
    /**
     * Las transferencias con fecha de negocio entre [start] y [end], las dos incluidas, de la más
     * nueva a la más antigua. Vuelve a emitir con cada cambio confirmado.
     */
    fun observeIn(start: BusinessDate, end: BusinessDate): Flow<List<StockTransfer>>

    suspend fun findById(id: Long): StockTransfer?

    /** Las líneas de la transferencia, en el orden en que se registraron. */
    suspend fun findLines(transferId: Long): List<StockTransferLine>

    /** Las filas del libro que salieron de sus líneas, en el orden del libro. */
    suspend fun findMovements(transferId: Long): List<StockMovementRecord>

    /** Guarda la transferencia, activa, y devuelve su id. */
    suspend fun insert(transfer: NewStockTransfer): Long

    /** Guarda una línea de [transferId] y devuelve su id. */
    suspend fun insertLine(transferId: Long, line: NewStockTransferLine): Long

    /** La anula con [voiding]. Una transferencia se anula una sola vez. */
    suspend fun markVoided(id: Long, voiding: Voiding)
}
