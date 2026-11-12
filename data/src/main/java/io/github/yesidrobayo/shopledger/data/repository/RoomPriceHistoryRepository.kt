package io.github.yesidrobayo.shopledger.data.repository

import io.github.yesidrobayo.shopledger.data.local.AppDatabase
import io.github.yesidrobayo.shopledger.data.local.catalog.PriceHistoryEntity
import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.catalog.BulkPriceAdjustment
import io.github.yesidrobayo.shopledger.domain.model.catalog.BulkPriceMode
import io.github.yesidrobayo.shopledger.domain.model.catalog.BulkPriceRule
import io.github.yesidrobayo.shopledger.domain.model.catalog.NewPriceChange
import io.github.yesidrobayo.shopledger.domain.model.catalog.PriceChange
import io.github.yesidrobayo.shopledger.domain.model.catalog.PriceChangeSource
import io.github.yesidrobayo.shopledger.domain.port.PriceHistoryRepository
import kotlin.uuid.Uuid
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * El historial de precios sobre `product_price_history`: agrega y lee, nada más.
 *
 * El cálculo de una actualización masiva va en columnas: el modo, el único dato que pide ese modo y
 * el paso de redondeo. Las demás columnas del cálculo quedan nulas, como exige su guarda.
 */
internal class RoomPriceHistoryRepository(database: AppDatabase) : PriceHistoryRepository {
    private val history = database.priceHistoryDao()
    private val writes = RepositoryWrites(database)

    override fun observeByProduct(productId: Long): Flow<List<PriceChange>> = history
        .observeByProduct(productId)
        .map { rows -> rows.map(::toPriceChange) }
        .distinctUntilChanged()

    override suspend fun findByProduct(productId: Long): List<PriceChange> =
        history.findByProduct(productId).map(::toPriceChange)

    // El UUID se guarda en su texto canónico de 36 caracteres, en minúsculas.
    override suspend fun findByBatch(batchId: Uuid): List<PriceChange> =
        history.findByBatch(batchId.toString()).map(::toPriceChange)

    override fun observeUndoableBatch(): Flow<Uuid?> = history
        .observeUndoableBatch()
        .map { it?.let(::storedUuid) }
        .distinctUntilChanged()

    override suspend fun findUndoableBatch(): Uuid? = history.findUndoableBatch()?.let(::storedUuid)

    override suspend fun append(change: NewPriceChange): Long = writes.inTransaction {
        val adjustment = change.bulkRule?.adjustment
        history.insert(
            PriceHistoryEntity(
                productId = change.productId,
                priceListId = change.priceListId,
                oldPrice = change.oldPrice?.minor,
                newPrice = change.newPrice.minor,
                source = change.source.name,
                batchId = change.batchId?.toString(),
                bulkMode = adjustment?.mode?.name,
                percentBp = (adjustment as? BulkPriceAdjustment.Percent)?.percent?.bp,
                amountDelta = (adjustment as? BulkPriceAdjustment.Amount)?.delta?.minor,
                targetMarginBp = (adjustment as? BulkPriceAdjustment.TargetMargin)?.margin?.bp,
                fxRateMicro = (adjustment as? BulkPriceAdjustment.ReferenceRate)?.fxRateMicro,
                roundingStep = change.bulkRule?.roundingStep?.minor,
                occurredAt = change.occurredAt.toStoredMillis(),
                businessDate = change.businessDate.toStored(),
            ),
        )
    }

    private fun toPriceChange(row: PriceHistoryEntity): PriceChange {
        val content =
            try {
                NewPriceChange(
                    productId = row.productId,
                    priceListId = row.priceListId,
                    oldPrice = row.oldPrice?.let(::Money),
                    newPrice = Money(row.newPrice),
                    source = storedEnum<PriceChangeSource>(row.source),
                    batchId = row.batchId?.let(::storedUuid),
                    bulkRule = row.bulkMode?.let { bulkRuleOf(row, storedEnum<BulkPriceMode>(it)) },
                    occurredAt = row.occurredAt.toInstant(),
                    businessDate = storedDate(row.businessDate),
                )
            } catch (_: IllegalArgumentException) {
                // El modelo rechaza una fila cuyo origen no cuadra con su lote o su cálculo.
                brokenRow()
            }
        return content.recordedAs(row.id)
    }

    /** El cálculo guardado de una actualización masiva: el dato de [mode] y el redondeo. */
    private fun bulkRuleOf(row: PriceHistoryEntity, mode: BulkPriceMode): BulkPriceRule {
        val adjustment =
            when (mode) {
                BulkPriceMode.PERCENT -> BulkPriceAdjustment.Percent(
                    BasisPoints(row.percentBp ?: brokenRow()),
                )

                BulkPriceMode.AMOUNT -> BulkPriceAdjustment.Amount(
                    Money(row.amountDelta ?: brokenRow()),
                )

                BulkPriceMode.TARGET_MARGIN ->
                    BulkPriceAdjustment.TargetMargin(BasisPoints(row.targetMarginBp ?: brokenRow()))

                BulkPriceMode.REFERENCE_RATE ->
                    BulkPriceAdjustment.ReferenceRate(row.fxRateMicro ?: brokenRow())
            }
        return BulkPriceRule(adjustment, Money(row.roundingStep ?: brokenRow()))
    }

    private fun storedUuid(text: String): Uuid = Uuid.parseOrNull(text) ?: brokenRow()
}
