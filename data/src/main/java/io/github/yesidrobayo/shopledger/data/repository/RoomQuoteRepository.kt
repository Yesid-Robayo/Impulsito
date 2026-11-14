package io.github.yesidrobayo.shopledger.data.repository

import io.github.yesidrobayo.shopledger.data.local.AppDatabase
import io.github.yesidrobayo.shopledger.data.local.document.QuoteEntity
import io.github.yesidrobayo.shopledger.data.local.document.QuoteLineEntity
import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.document.DocumentLine
import io.github.yesidrobayo.shopledger.domain.model.document.DocumentTotals
import io.github.yesidrobayo.shopledger.domain.model.document.NewDocumentLine
import io.github.yesidrobayo.shopledger.domain.model.document.NewQuote
import io.github.yesidrobayo.shopledger.domain.model.document.Quote
import io.github.yesidrobayo.shopledger.domain.model.document.QuoteStatus
import io.github.yesidrobayo.shopledger.domain.port.QuoteRepository
import kotlin.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Las cotizaciones sobre `quotes` y `quote_lines`.
 *
 * Editar una abierta borra sus líneas y escribe las de ahora, en el orden en que se armaron: sus
 * líneas no son historia mientras está abierta. Cerrada, sus guardas no dejan cambiar nada más que
 * `updated_at`, así que convertir y cancelar comprueban el estado en la misma sentencia.
 */
internal class RoomQuoteRepository(database: AppDatabase) : QuoteRepository {
    private val quotes = database.quoteDao()
    private val writes = RepositoryWrites(database)

    override fun observeByStatus(status: QuoteStatus): Flow<List<Quote>> = quotes
        .observeByStatus(status.name)
        .map { rows -> rows.map(::toQuote) }
        .distinctUntilChanged()

    override fun observeById(id: Long): Flow<Quote?> =
        quotes.observeById(id).map { it?.let(::toQuote) }.distinctUntilChanged()

    override suspend fun findById(id: Long): Quote? = quotes.findById(id)?.let(::toQuote)

    override suspend fun findLines(quoteId: Long): List<DocumentLine> =
        quotes.findLines(quoteId).map(::toLine)

    override suspend fun insert(quote: NewQuote): Long = writes.inTransaction {
        val at = quote.at.toStoredMillis()
        val id = quotes.insert(
            QuoteEntity(
                customerId = quote.customerId,
                status = QuoteStatus.OPEN.name,
                validUntil = quote.validUntil?.toStored(),
                netTotal = quote.totals.net.minor,
                taxTotal = quote.totals.tax.minor,
                total = quote.totals.total.minor,
                convertedSaleId = null,
                note = quote.note,
                occurredAt = quote.occurredAt.toStoredMillis(),
                businessDate = quote.businessDate.toStored(),
                createdAt = at,
                updatedAt = at,
            ),
        )
        insertLines(id, quote.lines)
        id
    }

    override suspend fun update(id: Long, quote: NewQuote): Boolean = writes.inTransaction {
        val current = quotes.findById(id)
        if (current == null || current.status != QuoteStatus.OPEN.name) return@inTransaction false
        quotes.deleteLinesOf(id)
        insertLines(id, quote.lines)
        requireOneRow(
            quotes.update(
                id = id,
                customerId = quote.customerId,
                validUntil = quote.validUntil?.toStored(),
                netTotal = quote.totals.net.minor,
                taxTotal = quote.totals.tax.minor,
                total = quote.totals.total.minor,
                note = quote.note,
                updatedAt = quote.at.toStoredMillis(),
            ),
        )
        true
    }

    override suspend fun markConverted(id: Long, saleId: Long, at: Instant): Boolean =
        writes.inTransaction { quotes.markConverted(id, saleId, at.toStoredMillis()) == 1 }

    override suspend fun markCancelled(id: Long, at: Instant): Boolean =
        writes.inTransaction { quotes.markCancelled(id, at.toStoredMillis()) == 1 }

    private suspend fun insertLines(quoteId: Long, lines: List<NewDocumentLine>) {
        for (line in lines) {
            quotes.insertLine(
                QuoteLineEntity(
                    quoteId = quoteId,
                    productId = line.productId,
                    unitId = line.unitId,
                    unitFactorMilli = line.unitFactor.milli,
                    enteredQuantityMilli = line.enteredQuantity.milli,
                    quantityMilli = line.quantity.milli,
                    unitPrice = line.unitPrice.minor,
                    lineDiscount = line.lineDiscount.minor,
                    netAmount = line.netAmount.minor,
                    taxRateBp = line.taxRate.bp,
                    taxNameSnapshot = line.taxNameSnapshot,
                    taxAmount = line.taxAmount.minor,
                    lineTotal = line.lineTotal.minor,
                    productNameSnapshot = line.productNameSnapshot,
                ),
            )
        }
    }

    private fun toQuote(row: QuoteEntity): Quote {
        val status = storedEnum<QuoteStatus>(row.status)
        // La guarda exige la venta solo con la cotización convertida: otra cosa es una fila rota.
        if ((status == QuoteStatus.CONVERTED) != (row.convertedSaleId != null)) brokenRow()
        return Quote(
            id = row.id,
            customerId = row.customerId,
            status = status,
            validUntil = row.validUntil?.let(::storedDate),
            totals = DocumentTotals(Money(row.netTotal), Money(row.taxTotal), Money(row.total)),
            convertedSaleId = row.convertedSaleId,
            note = row.note,
            occurredAt = row.occurredAt.toInstant(),
            businessDate = storedDate(row.businessDate),
            createdAt = row.createdAt.toInstant(),
            updatedAt = row.updatedAt.toInstant(),
        )
    }

    private fun toLine(row: QuoteLineEntity): DocumentLine = DocumentLine(
        id = row.id,
        documentId = row.quoteId,
        content = NewDocumentLine(
            productId = row.productId,
            unitId = row.unitId,
            unitFactor = QuantityMilli(row.unitFactorMilli),
            enteredQuantity = QuantityMilli(row.enteredQuantityMilli),
            quantity = QuantityMilli(row.quantityMilli),
            unitPrice = Money(row.unitPrice),
            lineDiscount = Money(row.lineDiscount),
            netAmount = Money(row.netAmount),
            taxRate = BasisPoints(row.taxRateBp),
            taxNameSnapshot = row.taxNameSnapshot,
            taxAmount = Money(row.taxAmount),
            lineTotal = Money(row.lineTotal),
            productNameSnapshot = row.productNameSnapshot,
        ),
    )
}
