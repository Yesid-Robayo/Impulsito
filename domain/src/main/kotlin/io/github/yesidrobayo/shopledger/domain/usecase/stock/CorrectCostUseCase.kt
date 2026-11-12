package io.github.yesidrobayo.shopledger.domain.usecase.stock

import io.github.yesidrobayo.shopledger.domain.model.Checked
import io.github.yesidrobayo.shopledger.domain.model.Limit
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.catalog.SystemRow
import io.github.yesidrobayo.shopledger.domain.model.stock.CostState
import io.github.yesidrobayo.shopledger.domain.model.stock.NewStockMovement
import io.github.yesidrobayo.shopledger.domain.model.stock.StockKey
import io.github.yesidrobayo.shopledger.domain.model.stock.StockMovementDraft
import io.github.yesidrobayo.shopledger.domain.model.stock.StockMovementSource
import io.github.yesidrobayo.shopledger.domain.model.stock.StockMovementType
import io.github.yesidrobayo.shopledger.domain.port.Clock
import io.github.yesidrobayo.shopledger.domain.port.ProductRepository
import io.github.yesidrobayo.shopledger.domain.port.StockLedgerRepository
import io.github.yesidrobayo.shopledger.domain.port.StorageFailure
import io.github.yesidrobayo.shopledger.domain.port.SystemRowRepository
import io.github.yesidrobayo.shopledger.domain.port.TransactionRunner
import io.github.yesidrobayo.shopledger.domain.port.TxResult
import io.github.yesidrobayo.shopledger.domain.rule.WeightedAverageCost
import io.github.yesidrobayo.shopledger.domain.rule.catalog.CatalogText
import io.github.yesidrobayo.shopledger.domain.rule.catalog.StockModeRules
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.lock
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.requireId
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.stamp
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.storageSafe
import io.github.yesidrobayo.shopledger.domain.usecase.region.BusinessCalendarSource
import io.github.yesidrobayo.shopledger.domain.usecase.region.CurrencyLock

/** Cómo termina corregir el costo de un producto. Salvo [Corrected], no se escribió nada. */
sealed interface CorrectCostResult {
    /** Quedó la fila [movementId] en el libro, y el producto con su costo y su valor nuevos. */
    data class Corrected(val movementId: Long) : CorrectCostResult

    /** El costo nuevo es el mismo de ahora: una fila no diría nada. */
    data object Unchanged : CorrectCostResult

    data object NotFound : CorrectCostResult

    /** Archivado: hay que reactivarlo antes de corregir nada en su libro. */
    data object ProductArchived : CorrectCostResult

    /** No lleva libro de stock, como un servicio: su costo es fijo y se edita en el producto. */
    data object NotTracked : CorrectCostResult

    /** El costo es negativo o pasa del tope de dinero por unidad. */
    data object CostOutOfRange : CorrectCostResult

    /** La nota pasa de [max] caracteres. */
    data class NoteTooLong(val max: Int) : CorrectCostResult

    /** El valor del stock a ese costo pasaría del tope que admite la base. */
    data object StockValueTooLarge : CorrectCostResult

    /** La zona del negocio no se reconoce: la fila no tendría fecha. */
    data object UnknownTimeZone : CorrectCostResult

    /** La moneda del negocio no está confirmada, y un costo es dinero. */
    data object CurrencyUnconfirmed : CorrectCostResult

    data class StorageFailed(val failure: StorageFailure) : CorrectCostResult
}

/**
 * Corrige a mano el costo de un producto, sin una compra: fija el costo promedio en el nuevo y
 * valora a ese costo las unidades que hay, con una fila `COST_CORRECTION` de cantidad cero que
 * guarda el costo de antes y el de después. Sirve para dar costo a un stock que no lo tenía o
 * corregir uno errado.
 *
 * Nunca edita una fila del libro ni toca ventas pasadas: cada venta congeló su costo al
 * registrarse. Sí cambia el valor del inventario. Todo va en una sola transacción.
 */
class CorrectCostUseCase(
    private val transactions: TransactionRunner,
    private val products: ProductRepository,
    private val systemRows: SystemRowRepository,
    private val ledger: StockLedgerRepository,
    private val calendar: BusinessCalendarSource,
    private val currencyLock: CurrencyLock,
    private val clock: Clock,
) {
    /** Fija el costo de [productId] en [newCost], con [note] opcional que explica por qué. */
    suspend operator fun invoke(
        productId: Long,
        newCost: Money,
        note: String? = null,
    ): CorrectCostResult = storageSafe(CorrectCostResult::StorageFailed) {
        transactions.write { correct(productId, newCost, note) }
    }

    private suspend fun correct(
        productId: Long,
        newCost: Money,
        rawNote: String?,
    ): TxResult<CorrectCostResult> {
        if (newCost < Money.ZERO || !Limit.UNIT_MONEY.allows(newCost.minor)) {
            return TxResult.Rollback(CorrectCostResult.CostOutOfRange)
        }
        val note = rawNote?.trim()?.takeIf { it.isNotEmpty() }
        if (note != null && CatalogText.length(note) > MAX_NOTE_LENGTH) {
            return TxResult.Rollback(CorrectCostResult.NoteTooLong(MAX_NOTE_LENGTH))
        }
        val product = products.findById(productId)
            ?: return TxResult.Rollback(CorrectCostResult.NotFound)
        if (product.isArchived) return TxResult.Rollback(CorrectCostResult.ProductArchived)
        if (!StockModeRules.hasLedger(product.details.stockMode)) {
            return TxResult.Rollback(CorrectCostResult.NotTracked)
        }
        if (newCost == product.averageCost) return TxResult.Rollback(CorrectCostResult.Unchanged)
        val before = CostState(product.onHand, product.averageCost, product.stockValue)
        val after = when (val checked = WeightedAverageCost.correctCost(before, newCost)) {
            is Checked.Value -> checked.value

            Checked.Overflow, is Checked.AboveLimit ->
                return TxResult.Rollback(CorrectCostResult.StockValueTooLarge)
        }
        if (!Limit.STOCK_VALUE.allows(after.stockValue.minor)) {
            return TxResult.Rollback(CorrectCostResult.StockValueTooLarge)
        }
        val time = calendar.stamp(clock.now())
            ?: return TxResult.Rollback(CorrectCostResult.UnknownTimeZone)
        if (!currencyLock.lock(time.occurredAt)) {
            return TxResult.Rollback(CorrectCostResult.CurrencyUnconfirmed)
        }
        val location = systemRows.requireId(SystemRow.MAIN_LOCATION)
        val movementId = ledger.append(
            NewStockMovement(
                movement = StockMovementDraft(
                    key = StockKey(product.id, location, lotId = null),
                    type = StockMovementType.COST_CORRECTION,
                    quantity = QuantityMilli.ZERO,
                    reversesMovementId = null,
                ),
                reason = null,
                // El costo nuevo queda en la fila, y el de antes es el promedio de la fila anterior.
                unitCost = newCost,
                totalCost = Money.ZERO,
                balanceAfter = after.onHand,
                averageCostAfter = after.averageCost,
                stockValueAfter = after.stockValue,
                source = StockMovementSource.NONE,
                note = note,
                occurredAt = time.occurredAt,
                businessDate = time.businessDate,
                createdAt = time.occurredAt,
            ),
        )
        return TxResult.Commit(CorrectCostResult.Corrected(movementId))
    }

    companion object {
        /** Largo máximo de la nota, el mismo que admite la base para una fila del libro. */
        const val MAX_NOTE_LENGTH: Int = 200
    }
}
