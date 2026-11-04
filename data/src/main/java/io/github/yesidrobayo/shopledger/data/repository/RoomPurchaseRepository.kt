package io.github.yesidrobayo.shopledger.data.repository

import io.github.yesidrobayo.shopledger.data.local.AppDatabase
import io.github.yesidrobayo.shopledger.data.local.purchase.PurchaseEntity
import io.github.yesidrobayo.shopledger.data.local.purchase.PurchaseLineEntity
import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.DocumentStatus
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.ReportPeriod
import io.github.yesidrobayo.shopledger.domain.model.Voiding
import io.github.yesidrobayo.shopledger.domain.model.purchase.NewPurchase
import io.github.yesidrobayo.shopledger.domain.model.purchase.NewPurchaseLine
import io.github.yesidrobayo.shopledger.domain.model.purchase.Purchase
import io.github.yesidrobayo.shopledger.domain.model.purchase.PurchaseForeignCurrency
import io.github.yesidrobayo.shopledger.domain.model.purchase.PurchaseLine
import io.github.yesidrobayo.shopledger.domain.port.PurchaseRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Las compras y sus líneas sobre `purchases` y `purchase_lines`. Una compra nace activa y lo único
 * que cambia después es su anulación, una sola vez.
 */
internal class RoomPurchaseRepository(database: AppDatabase) : PurchaseRepository {
    private val purchases = database.purchaseDao()
    private val lines = database.purchaseLineDao()
    private val writes = RepositoryWrites(database)

    override fun observeIn(period: ReportPeriod): Flow<List<Purchase>> = purchases
        .observeIn(period.storedStart, period.storedEnd)
        .map { rows -> rows.map(::toPurchase) }
        .distinctUntilChanged()

    override fun observeById(id: Long): Flow<Purchase?> =
        purchases.observeById(id).map { it?.let(::toPurchase) }.distinctUntilChanged()

    override fun observeLines(purchaseId: Long): Flow<List<PurchaseLine>> = lines
        .observeByPurchase(purchaseId)
        .map { rows -> rows.map(::toLine) }
        .distinctUntilChanged()

    override suspend fun findById(id: Long): Purchase? = purchases.findById(id)?.let(::toPurchase)

    override suspend fun findLines(purchaseId: Long): List<PurchaseLine> =
        lines.findByPurchase(purchaseId).map(::toLine)

    override suspend fun insert(purchase: NewPurchase): Long = writes.inTransaction {
        purchases.insert(
            PurchaseEntity(
                supplierId = purchase.supplierId,
                status = DocumentStatus.ACTIVE.name,
                invoiceReference = purchase.invoiceReference,
                netTotal = purchase.netTotal.minor,
                taxTotal = purchase.taxTotal.minor,
                total = purchase.total.minor,
                costTotal = purchase.costTotal.minor,
                amountPaid = purchase.amountPaid.minor,
                moneyAccountId = purchase.moneyAccountId,
                dueDate = purchase.dueDate?.toStored(),
                locationId = purchase.locationId,
                fxCurrencyCode = purchase.foreignCurrency?.currencyCode,
                fxRateMicro = purchase.foreignCurrency?.rateMicro,
                occurredAt = purchase.occurredAt.toStoredMillis(),
                createdAt = purchase.createdAt.toStoredMillis(),
                businessDate = purchase.businessDate.toStored(),
                voidedAt = null,
                voidReason = null,
                voidNote = null,
            ),
        )
    }

    override suspend fun insertLine(purchaseId: Long, line: NewPurchaseLine): Long =
        writes.inTransaction {
            lines.insert(
                PurchaseLineEntity(
                    purchaseId = purchaseId,
                    productId = line.productId,
                    unitId = line.unitId,
                    unitFactorMilli = line.unitFactor.milli,
                    enteredQuantityMilli = line.enteredQuantity.milli,
                    quantityMilli = line.quantity.milli,
                    unitCost = line.unitCost.minor,
                    lineTotal = line.lineTotal.minor,
                    taxRateBp = line.taxRate.bp,
                    taxNameSnapshot = line.taxNameSnapshot,
                    taxAmount = line.taxAmount.minor,
                    costAmount = line.costAmount.minor,
                    lotId = line.lotId,
                    referenceUnitCost = line.referenceUnitCost?.minor,
                ),
            )
        }

    /**
     * Una compra que no existe o ya estaba anulada no cambia, y la transacción se deshace como regla
     * rota: anularla dos veces duplicaría sus compensatorios en el libro.
     */
    override suspend fun markVoided(id: Long, voiding: Voiding) {
        writes.inTransaction {
            requireOneRow(
                purchases.markVoided(
                    id,
                    voiding.voidedAt.toStoredMillis(),
                    voiding.reason.name,
                    voiding.note,
                ),
            )
        }
    }

    private fun toPurchase(row: PurchaseEntity): Purchase = Purchase(
        id = row.id,
        content = NewPurchase(
            supplierId = row.supplierId,
            invoiceReference = row.invoiceReference,
            netTotal = Money(row.netTotal),
            taxTotal = Money(row.taxTotal),
            total = Money(row.total),
            costTotal = Money(row.costTotal),
            amountPaid = Money(row.amountPaid),
            moneyAccountId = row.moneyAccountId,
            dueDate = row.dueDate?.let(::storedDate),
            locationId = row.locationId,
            foreignCurrency = foreignCurrencyOf(row.fxCurrencyCode, row.fxRateMicro),
            occurredAt = row.occurredAt.toInstant(),
            businessDate = storedDate(row.businessDate),
            createdAt = row.createdAt.toInstant(),
        ),
        voiding = storedVoiding(row.status, row.voidedAt, row.voidReason, row.voidNote),
    )

    /** La moneda de la compra, si no fue la del libro: el código y la cotización van juntos. */
    private fun foreignCurrencyOf(code: String?, rateMicro: Long?): PurchaseForeignCurrency? =
        when {
            code == null && rateMicro == null -> null

            code == null || rateMicro == null -> brokenRow()

            else ->
                try {
                    PurchaseForeignCurrency(code, rateMicro)
                } catch (_: IllegalArgumentException) {
                    brokenRow()
                }
        }

    private fun toLine(row: PurchaseLineEntity): PurchaseLine = PurchaseLine(
        id = row.id,
        purchaseId = row.purchaseId,
        content = NewPurchaseLine(
            productId = row.productId,
            unitId = row.unitId,
            unitFactor = QuantityMilli(row.unitFactorMilli),
            enteredQuantity = QuantityMilli(row.enteredQuantityMilli),
            quantity = QuantityMilli(row.quantityMilli),
            unitCost = Money(row.unitCost),
            lineTotal = Money(row.lineTotal),
            taxRate = BasisPoints(row.taxRateBp),
            taxNameSnapshot = row.taxNameSnapshot,
            taxAmount = Money(row.taxAmount),
            costAmount = Money(row.costAmount),
            lotId = row.lotId,
            referenceUnitCost = row.referenceUnitCost?.let(::Money),
        ),
    )
}
