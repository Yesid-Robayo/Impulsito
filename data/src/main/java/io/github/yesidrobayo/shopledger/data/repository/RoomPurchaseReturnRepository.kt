package io.github.yesidrobayo.shopledger.data.repository

import io.github.yesidrobayo.shopledger.data.local.AppDatabase
import io.github.yesidrobayo.shopledger.data.local.returns.PurchaseReturnEntity
import io.github.yesidrobayo.shopledger.data.local.returns.PurchaseReturnLineEntity
import io.github.yesidrobayo.shopledger.data.local.returns.TakenFromPurchaseLine
import io.github.yesidrobayo.shopledger.domain.model.DocumentStatus
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.Voiding
import io.github.yesidrobayo.shopledger.domain.model.returns.LineAmounts
import io.github.yesidrobayo.shopledger.domain.model.returns.NewPurchaseReturn
import io.github.yesidrobayo.shopledger.domain.model.returns.NewPurchaseReturnLine
import io.github.yesidrobayo.shopledger.domain.model.returns.PurchaseReturn
import io.github.yesidrobayo.shopledger.domain.model.returns.PurchaseReturnLine
import io.github.yesidrobayo.shopledger.domain.model.returns.PurchaseReturnResolution
import io.github.yesidrobayo.shopledger.domain.port.PurchaseReturnRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Las devoluciones a proveedor y sus líneas sobre `purchase_returns` y `purchase_return_lines`. Una
 * devolución nace activa y lo único que cambia después es su anulación, una sola vez.
 */
internal class RoomPurchaseReturnRepository(database: AppDatabase) : PurchaseReturnRepository {
    private val returns = database.purchaseReturnDao()
    private val lines = database.purchaseReturnLineDao()
    private val writes = RepositoryWrites(database)

    override fun observeById(id: Long): Flow<PurchaseReturn?> =
        returns.observeById(id).map { it?.let(::toReturn) }.distinctUntilChanged()

    override fun observeLines(purchaseReturnId: Long): Flow<List<PurchaseReturnLine>> = lines
        .observeByReturn(purchaseReturnId)
        .map { rows -> rows.map(::toLine) }
        .distinctUntilChanged()

    override fun observeByPurchase(purchaseId: Long): Flow<List<PurchaseReturn>> = returns
        .observeByPurchase(purchaseId)
        .map { rows -> rows.map(::toReturn) }
        .distinctUntilChanged()

    override suspend fun findById(id: Long): PurchaseReturn? =
        returns.findById(id)?.let { toReturn(it) }

    override suspend fun findLines(purchaseReturnId: Long): List<PurchaseReturnLine> =
        lines.findByReturn(purchaseReturnId).map(::toLine)

    override suspend fun takenByPurchase(purchaseId: Long): Map<Long, LineAmounts> =
        lines.takenByPurchase(purchaseId).associate { it.purchaseLineId to toTaken(it) }

    override suspend fun insert(purchaseReturn: NewPurchaseReturn): Long = writes.inTransaction {
        returns.insert(
            PurchaseReturnEntity(
                purchaseId = purchaseReturn.purchaseId,
                supplierId = purchaseReturn.supplierId,
                resolution = purchaseReturn.resolution.name,
                total = purchaseReturn.total.minor,
                moneyAccountId = purchaseReturn.moneyAccountId,
                note = purchaseReturn.note,
                occurredAt = purchaseReturn.occurredAt.toStoredMillis(),
                businessDate = purchaseReturn.businessDate.toStored(),
                createdAt = purchaseReturn.createdAt.toStoredMillis(),
                status = DocumentStatus.ACTIVE.name,
                voidedAt = null,
                voidReason = null,
                voidNote = null,
            ),
        )
    }

    override suspend fun insertLine(purchaseReturnId: Long, line: NewPurchaseReturnLine): Long =
        writes.inTransaction {
            lines.insert(
                PurchaseReturnLineEntity(
                    purchaseReturnId = purchaseReturnId,
                    purchaseLineId = line.purchaseLineId,
                    productId = line.productId,
                    lotId = line.lotId,
                    quantityMilli = line.quantity.milli,
                    unitCost = line.unitCost.minor,
                    lineTotal = line.lineTotal.minor,
                ),
            )
        }

    /**
     * Una devolución que no existe o ya estaba anulada no cambia, y la transacción se deshace como
     * regla rota: anularla dos veces duplicaría sus compensatorios.
     */
    override suspend fun markVoided(id: Long, voiding: Voiding) {
        writes.inTransaction {
            requireOneRow(
                returns.markVoided(
                    id,
                    voiding.voidedAt.toStoredMillis(),
                    voiding.reason.name,
                    voiding.note,
                ),
            )
        }
    }

    /** Lo pagado va entero como neto: la devolución a proveedor no separa el impuesto. */
    private fun toTaken(row: TakenFromPurchaseLine): LineAmounts = LineAmounts(
        quantity = QuantityMilli(row.quantityMilli),
        net = Money(row.lineTotal),
        tax = Money.ZERO,
        cost = Money(row.cost),
    )

    private fun toReturn(row: PurchaseReturnEntity): PurchaseReturn = PurchaseReturn(
        id = row.id,
        content = NewPurchaseReturn(
            purchaseId = row.purchaseId,
            supplierId = row.supplierId,
            resolution = storedEnum<PurchaseReturnResolution>(row.resolution),
            total = Money(row.total),
            moneyAccountId = row.moneyAccountId,
            note = row.note,
            occurredAt = row.occurredAt.toInstant(),
            businessDate = storedDate(row.businessDate),
            createdAt = row.createdAt.toInstant(),
        ),
        voiding = storedVoiding(row.status, row.voidedAt, row.voidReason, row.voidNote),
    )

    private fun toLine(row: PurchaseReturnLineEntity): PurchaseReturnLine = PurchaseReturnLine(
        id = row.id,
        purchaseReturnId = row.purchaseReturnId,
        content = NewPurchaseReturnLine(
            purchaseLineId = row.purchaseLineId,
            productId = row.productId,
            lotId = row.lotId,
            quantity = QuantityMilli(row.quantityMilli),
            unitCost = Money(row.unitCost),
            lineTotal = Money(row.lineTotal),
        ),
    )
}
