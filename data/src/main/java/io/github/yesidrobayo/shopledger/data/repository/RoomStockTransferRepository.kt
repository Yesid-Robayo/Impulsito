package io.github.yesidrobayo.shopledger.data.repository

import io.github.yesidrobayo.shopledger.data.local.AppDatabase
import io.github.yesidrobayo.shopledger.data.local.stock.StockTransferDao
import io.github.yesidrobayo.shopledger.data.local.stock.StockTransferEntity
import io.github.yesidrobayo.shopledger.data.local.stock.StockTransferLineEntity
import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.DocumentStatus
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
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
 * Las transferencias sobre `stock_transfers` y `stock_transfer_lines`. Las filas del libro de cada
 * línea las agrega el libro, en la misma transacción; aquí solo se leen.
 */
internal class RoomStockTransferRepository(
    private val transfers: StockTransferDao,
    private val ledger: RoomStockLedgerRepository,
    private val writes: RepositoryWrites,
) : StockTransferRepository {
    constructor(database: AppDatabase) : this(
        database.stockTransferDao(),
        RoomStockLedgerRepository(database),
        RepositoryWrites(database),
    )

    override fun observeIn(start: BusinessDate, end: BusinessDate): Flow<List<StockTransfer>> =
        transfers.observeIn(start.toStored(), end.toStored())
            .map { rows -> rows.map(::toTransfer) }
            .distinctUntilChanged()

    override suspend fun findById(id: Long): StockTransfer? =
        transfers.findById(id)?.let(::toTransfer)

    override suspend fun findLines(transferId: Long): List<StockTransferLine> =
        transfers.findLines(transferId).map { row ->
            StockTransferLine(
                id = row.id,
                transferId = row.transferId,
                line = NewStockTransferLine(
                    productId = row.productId,
                    lotId = row.lotId,
                    quantity = QuantityMilli(row.quantityMilli),
                ),
            )
        }

    override suspend fun findMovements(transferId: Long): List<StockMovementRecord> =
        transfers.findMovements(transferId).map(ledger::toRecord)

    override suspend fun insert(transfer: NewStockTransfer): Long = writes.inTransaction {
        transfers.insert(
            StockTransferEntity(
                fromLocationId = transfer.fromLocationId,
                toLocationId = transfer.toLocationId,
                note = transfer.note,
                occurredAt = transfer.occurredAt.toStoredMillis(),
                businessDate = transfer.businessDate.toStored(),
                createdAt = transfer.createdAt.toStoredMillis(),
                status = DocumentStatus.ACTIVE.name,
                voidedAt = null,
                voidReason = null,
                voidNote = null,
            ),
        )
    }

    override suspend fun insertLine(transferId: Long, line: NewStockTransferLine): Long =
        writes.inTransaction {
            transfers.insertLine(
                StockTransferLineEntity(
                    transferId = transferId,
                    productId = line.productId,
                    lotId = line.lotId,
                    quantityMilli = line.quantity.milli,
                ),
            )
        }

    /**
     * Una transferencia que no existe o ya estaba anulada no cambia, y la transacción se deshace
     * como regla rota: anularla dos veces devolvería el stock dos veces.
     */
    override suspend fun markVoided(id: Long, voiding: Voiding) {
        writes.inTransaction {
            requireOneRow(
                transfers.markVoided(
                    id,
                    voiding.voidedAt.toStoredMillis(),
                    voiding.reason.name,
                    voiding.note,
                ),
            )
        }
    }

    private fun toTransfer(row: StockTransferEntity): StockTransfer = StockTransfer(
        id = row.id,
        content = NewStockTransfer(
            fromLocationId = row.fromLocationId,
            toLocationId = row.toLocationId,
            note = row.note,
            occurredAt = row.occurredAt.toInstant(),
            businessDate = storedDate(row.businessDate),
            createdAt = row.createdAt.toInstant(),
        ),
        voiding = storedVoiding(row.status, row.voidedAt, row.voidReason, row.voidNote),
    )
}
