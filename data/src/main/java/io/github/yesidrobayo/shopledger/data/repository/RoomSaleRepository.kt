package io.github.yesidrobayo.shopledger.data.repository

import io.github.yesidrobayo.shopledger.data.local.AppDatabase
import io.github.yesidrobayo.shopledger.data.local.sale.SaleEntity
import io.github.yesidrobayo.shopledger.data.local.sale.SaleLineEntity
import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.DocumentStatus
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.ReportPeriod
import io.github.yesidrobayo.shopledger.domain.model.Voiding
import io.github.yesidrobayo.shopledger.domain.model.sale.LineStockEffect
import io.github.yesidrobayo.shopledger.domain.model.sale.NewSale
import io.github.yesidrobayo.shopledger.domain.model.sale.NewSaleLine
import io.github.yesidrobayo.shopledger.domain.model.sale.Sale
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleLine
import io.github.yesidrobayo.shopledger.domain.port.SaleRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Las ventas y sus líneas sobre `sales` y `sale_lines`. Una venta nace activa y lo único que cambia
 * después es su anulación, una sola vez.
 */
internal class RoomSaleRepository(database: AppDatabase) : SaleRepository {
    private val sales = database.saleDao()
    private val lines = database.saleLineDao()
    private val writes = RepositoryWrites(database)

    override fun observeIn(period: ReportPeriod): Flow<List<Sale>> = sales
        .observeIn(period.storedStart, period.storedEnd)
        .map { rows -> rows.map(::toSale) }
        .distinctUntilChanged()

    override fun observeById(id: Long): Flow<Sale?> =
        sales.observeById(id).map { it?.let(::toSale) }.distinctUntilChanged()

    override fun observeLines(saleId: Long): Flow<List<SaleLine>> = lines
        .observeBySale(saleId)
        .map { rows -> rows.map(::toLine) }
        .distinctUntilChanged()

    override suspend fun findById(id: Long): Sale? = sales.findById(id)?.let(::toSale)

    override suspend fun findLines(saleId: Long): List<SaleLine> =
        lines.findBySale(saleId).map(::toLine)

    override suspend fun insert(sale: NewSale): Long = writes.inTransaction {
        sales.insert(
            SaleEntity(
                status = DocumentStatus.ACTIVE.name,
                netTotal = sale.netTotal.minor,
                taxTotal = sale.taxTotal.minor,
                total = sale.total.minor,
                totalCost = sale.totalCost.minor,
                totalReplacementCost = sale.totalReplacementCost.minor,
                orderAdjustment = sale.orderAdjustment.minor,
                tipAmount = sale.tipAmount.minor,
                cashRoundingAmount = sale.cashRoundingAmount.minor,
                amountDue = sale.amountDue.minor,
                customerId = sale.customerId,
                priceListId = sale.priceListId,
                locationId = sale.locationId,
                cashSessionId = sale.cashSessionId,
                quoteId = sale.quoteId,
                orderId = sale.orderId,
                note = sale.note,
                occurredAt = sale.occurredAt.toStoredMillis(),
                createdAt = sale.createdAt.toStoredMillis(),
                businessDate = sale.businessDate.toStored(),
                voidedAt = null,
                voidReason = null,
                voidNote = null,
            ),
        )
    }

    override suspend fun insertLine(saleId: Long, line: NewSaleLine): Long = writes.inTransaction {
        lines.insert(
            SaleLineEntity(
                saleId = saleId,
                productId = line.productId,
                unitId = line.unitId,
                unitFactorMilli = line.unitFactor.milli,
                enteredQuantityMilli = line.enteredQuantity.milli,
                quantityMilli = line.quantity.milli,
                unitPrice = line.unitPrice.minor,
                unitCost = line.unitCost.minor,
                lineDiscount = line.lineDiscount.minor,
                orderAdjustmentShare = line.orderAdjustmentShare.minor,
                netAmount = line.netAmount.minor,
                taxRateBp = line.taxRate.bp,
                taxNameSnapshot = line.taxNameSnapshot,
                taxAmount = line.taxAmount.minor,
                lineTotal = line.lineTotal.minor,
                lineCost = line.lineCost.minor,
                unitReplacementCost = line.unitReplacementCost.minor,
                lineReplacementCost = line.lineReplacementCost.minor,
                productNameSnapshot = line.productNameSnapshot,
                stockEffect = line.stockEffect.name,
                parentLineId = line.parentLineId,
                priceListId = line.priceListId,
            ),
        )
    }

    /**
     * Una venta que no existe o ya estaba anulada no cambia, y la transacción se deshace como regla
     * rota: anularla dos veces duplicaría sus compensatorios en el libro.
     */
    override suspend fun markVoided(id: Long, voiding: Voiding) {
        writes.inTransaction {
            requireOneRow(
                sales.markVoided(
                    id,
                    voiding.voidedAt.toStoredMillis(),
                    voiding.reason.name,
                    voiding.note,
                ),
            )
        }
    }

    private fun toSale(row: SaleEntity): Sale = Sale(
        id = row.id,
        content = NewSale(
            netTotal = Money(row.netTotal),
            taxTotal = Money(row.taxTotal),
            total = Money(row.total),
            totalCost = Money(row.totalCost),
            totalReplacementCost = Money(row.totalReplacementCost),
            orderAdjustment = Money(row.orderAdjustment),
            tipAmount = Money(row.tipAmount),
            cashRoundingAmount = Money(row.cashRoundingAmount),
            amountDue = Money(row.amountDue),
            customerId = row.customerId,
            priceListId = row.priceListId,
            locationId = row.locationId,
            cashSessionId = row.cashSessionId,
            quoteId = row.quoteId,
            orderId = row.orderId,
            note = row.note,
            occurredAt = row.occurredAt.toInstant(),
            businessDate = storedDate(row.businessDate),
            createdAt = row.createdAt.toInstant(),
        ),
        voiding = storedVoiding(row.status, row.voidedAt, row.voidReason, row.voidNote),
    )

    private fun toLine(row: SaleLineEntity): SaleLine = SaleLine(
        id = row.id,
        saleId = row.saleId,
        content = NewSaleLine(
            productId = row.productId,
            unitId = row.unitId,
            unitFactor = QuantityMilli(row.unitFactorMilli),
            enteredQuantity = QuantityMilli(row.enteredQuantityMilli),
            quantity = QuantityMilli(row.quantityMilli),
            unitPrice = Money(row.unitPrice),
            unitCost = Money(row.unitCost),
            lineDiscount = Money(row.lineDiscount),
            orderAdjustmentShare = Money(row.orderAdjustmentShare),
            netAmount = Money(row.netAmount),
            taxRate = BasisPoints(row.taxRateBp),
            taxNameSnapshot = row.taxNameSnapshot,
            taxAmount = Money(row.taxAmount),
            lineTotal = Money(row.lineTotal),
            lineCost = Money(row.lineCost),
            unitReplacementCost = Money(row.unitReplacementCost),
            lineReplacementCost = Money(row.lineReplacementCost),
            productNameSnapshot = row.productNameSnapshot,
            stockEffect = storedEnum<LineStockEffect>(row.stockEffect),
            parentLineId = row.parentLineId,
            priceListId = row.priceListId,
        ),
    )
}
