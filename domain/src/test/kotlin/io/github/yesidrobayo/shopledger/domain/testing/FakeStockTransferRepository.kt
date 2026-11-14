package io.github.yesidrobayo.shopledger.domain.testing

import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.Voiding
import io.github.yesidrobayo.shopledger.domain.model.stock.NewStockTransfer
import io.github.yesidrobayo.shopledger.domain.model.stock.NewStockTransferLine
import io.github.yesidrobayo.shopledger.domain.model.stock.StockMovementRecord
import io.github.yesidrobayo.shopledger.domain.model.stock.StockTransfer
import io.github.yesidrobayo.shopledger.domain.model.stock.StockTransferLine
import io.github.yesidrobayo.shopledger.domain.port.StockTransferRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Un [StockTransferRepository] sobre [InMemoryDatabase]. Repite lo que la base impide: anular dos
 * veces, y una línea de una transferencia que no existe.
 */
internal class FakeStockTransferRepository(private val database: InMemoryDatabase) :
    StockTransferRepository {
    override fun observeIn(start: BusinessDate, end: BusinessDate): Flow<List<StockTransfer>> =
        database.tables.map { tables ->
            tables.stockTransfers.values
                .filter { it.content.businessDate in start..end }
                .sortedWith(
                    compareByDescending<StockTransfer> { it.content.businessDate }
                        .thenByDescending { it.id },
                )
        }.distinctUntilChanged()

    override suspend fun findById(id: Long): StockTransfer? = database.read().stockTransfers[id]

    override suspend fun findLines(transferId: Long): List<StockTransferLine> =
        database.read().stockTransferLines.values.filter { it.transferId == transferId }
            .sortedBy { it.id }

    override suspend fun findMovements(transferId: Long): List<StockMovementRecord> {
        val tables = database.read()
        val lineIds = findLines(transferId).map { it.id }.toSet()
        return tables.movements.values.filter { it.source.transferLineId in lineIds }
            .sortedBy { it.id }
    }

    override suspend fun insert(transfer: NewStockTransfer): Long {
        val id = database.nextId(TABLE)
        val row = StockTransfer(id, transfer, voiding = null)
        database.write { it.copy(stockTransfers = it.stockTransfers + (id to row)) }
        return id
    }

    override suspend fun insertLine(transferId: Long, line: NewStockTransferLine): Long {
        checkNotNull(database.read().stockTransfers[transferId]) { "La transferencia no existe" }
        val id = database.nextId(LINES)
        val row = StockTransferLine(id, transferId, line)
        database.write { it.copy(stockTransferLines = it.stockTransferLines + (id to row)) }
        return id
    }

    override suspend fun markVoided(id: Long, voiding: Voiding) {
        val transfer = checkNotNull(database.read().stockTransfers[id]) { "No existe" }
        check(transfer.voiding == null) { "Una transferencia se anula una sola vez" }
        database.write {
            it.copy(stockTransfers = it.stockTransfers + (id to transfer.copy(voiding = voiding)))
        }
    }

    private companion object {
        const val TABLE = "stock_transfers"
        const val LINES = "stock_transfer_lines"
    }
}
