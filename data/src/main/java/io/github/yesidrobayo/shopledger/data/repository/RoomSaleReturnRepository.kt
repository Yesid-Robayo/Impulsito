package io.github.yesidrobayo.shopledger.data.repository

import io.github.yesidrobayo.shopledger.data.local.AppDatabase
import io.github.yesidrobayo.shopledger.data.local.returns.ReturnedLineTotals
import io.github.yesidrobayo.shopledger.data.local.returns.SaleReturnEntity
import io.github.yesidrobayo.shopledger.data.local.returns.SaleReturnLineEntity
import io.github.yesidrobayo.shopledger.domain.model.DocumentStatus
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.returns.LineAmounts
import io.github.yesidrobayo.shopledger.domain.model.returns.NewSaleReturn
import io.github.yesidrobayo.shopledger.domain.model.returns.NewSaleReturnLine
import io.github.yesidrobayo.shopledger.domain.model.returns.ReturnResolution
import io.github.yesidrobayo.shopledger.domain.model.returns.SaleReturn
import io.github.yesidrobayo.shopledger.domain.model.returns.SaleReturnLine
import io.github.yesidrobayo.shopledger.domain.port.SaleReturnRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Las devoluciones de venta y sus líneas sobre `sale_returns` y `sale_return_lines`. Una devolución
 * nace activa, sus líneas no cambian nunca, y las guardas de la base rechazan una devolución de una
 * venta anulada y una línea que pase de lo vendido.
 */
internal class RoomSaleReturnRepository(database: AppDatabase) : SaleReturnRepository {
    private val returns = database.saleReturnDao()
    private val lines = database.saleReturnLineDao()
    private val writes = RepositoryWrites(database)

    override fun observeBySale(saleId: Long): Flow<List<SaleReturn>> = returns
        .observeBySale(saleId)
        .map { rows -> rows.map(::toReturn) }
        .distinctUntilChanged()

    override fun observeTakenBySale(saleId: Long): Flow<Map<Long, LineAmounts>> = lines
        .observeTakenBySale(saleId)
        .map(::toTaken)
        .distinctUntilChanged()

    override suspend fun findById(id: Long): SaleReturn? = returns.findById(id)?.let(::toReturn)

    override suspend fun findLines(saleReturnId: Long): List<SaleReturnLine> =
        lines.findByReturn(saleReturnId).map(::toLine)

    override suspend fun takenBySale(saleId: Long): Map<Long, LineAmounts> =
        toTaken(lines.takenBySale(saleId))

    override suspend fun insert(saleReturn: NewSaleReturn): Long = writes.inTransaction {
        returns.insert(
            SaleReturnEntity(
                saleId = saleReturn.saleId,
                resolution = saleReturn.resolution.name,
                netRefund = saleReturn.netRefund.minor,
                taxRefund = saleReturn.taxRefund.minor,
                totalRefund = saleReturn.totalRefund.minor,
                costReturned = saleReturn.costReturned.minor,
                moneyAccountId = saleReturn.moneyAccountId,
                replacementSaleId = saleReturn.replacementSaleId,
                note = saleReturn.note,
                occurredAt = saleReturn.occurredAt.toStoredMillis(),
                businessDate = saleReturn.businessDate.toStored(),
                createdAt = saleReturn.createdAt.toStoredMillis(),
                status = DocumentStatus.ACTIVE.name,
                voidedAt = null,
                voidReason = null,
                voidNote = null,
            ),
        )
    }

    override suspend fun insertLine(saleReturnId: Long, line: NewSaleReturnLine): Long =
        writes.inTransaction {
            lines.insert(
                SaleReturnLineEntity(
                    saleReturnId = saleReturnId,
                    saleLineId = line.saleLineId,
                    quantityMilli = line.quantity.milli,
                    netAmount = line.netAmount.minor,
                    taxAmount = line.taxAmount.minor,
                    lineTotal = line.lineTotal.minor,
                    lineCost = line.lineCost.minor,
                    restock = line.restock.toStoredFlag(),
                ),
            )
        }

    private fun toReturn(row: SaleReturnEntity): SaleReturn = SaleReturn(
        id = row.id,
        content = NewSaleReturn(
            saleId = row.saleId,
            resolution = storedEnum<ReturnResolution>(row.resolution),
            netRefund = Money(row.netRefund),
            taxRefund = Money(row.taxRefund),
            totalRefund = Money(row.totalRefund),
            costReturned = Money(row.costReturned),
            moneyAccountId = row.moneyAccountId,
            replacementSaleId = row.replacementSaleId,
            note = row.note,
            occurredAt = row.occurredAt.toInstant(),
            businessDate = storedDate(row.businessDate),
            createdAt = row.createdAt.toInstant(),
        ),
        voiding = storedVoiding(row.status, row.voidedAt, row.voidReason, row.voidNote),
    )

    private fun toLine(row: SaleReturnLineEntity): SaleReturnLine = SaleReturnLine(
        id = row.id,
        saleReturnId = row.saleReturnId,
        content = NewSaleReturnLine(
            saleLineId = row.saleLineId,
            quantity = QuantityMilli(row.quantityMilli),
            netAmount = Money(row.netAmount),
            taxAmount = Money(row.taxAmount),
            lineTotal = Money(row.lineTotal),
            lineCost = Money(row.lineCost),
            restock = row.restock.toFlag(),
        ),
    )

    private fun toTaken(rows: List<ReturnedLineTotals>): Map<Long, LineAmounts> =
        rows.associate { row ->
            row.saleLineId to LineAmounts(
                quantity = QuantityMilli(row.quantityMilli),
                net = Money(row.netAmount),
                tax = Money(row.taxAmount),
                cost = Money(row.lineCost),
            )
        }
}
