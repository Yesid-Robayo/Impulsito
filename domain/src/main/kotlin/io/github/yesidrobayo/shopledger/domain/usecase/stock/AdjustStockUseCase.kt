package io.github.yesidrobayo.shopledger.domain.usecase.stock

import io.github.yesidrobayo.shopledger.domain.model.Checked
import io.github.yesidrobayo.shopledger.domain.model.Limit
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.catalog.Product
import io.github.yesidrobayo.shopledger.domain.model.catalog.SystemRow
import io.github.yesidrobayo.shopledger.domain.model.region.RecordTime
import io.github.yesidrobayo.shopledger.domain.model.stock.CostState
import io.github.yesidrobayo.shopledger.domain.model.stock.NewStockMovement
import io.github.yesidrobayo.shopledger.domain.model.stock.StockAdjustmentRequest
import io.github.yesidrobayo.shopledger.domain.model.stock.StockKey
import io.github.yesidrobayo.shopledger.domain.model.stock.StockMovementDraft
import io.github.yesidrobayo.shopledger.domain.model.stock.StockMovementSource
import io.github.yesidrobayo.shopledger.domain.model.stock.StockMovementType
import io.github.yesidrobayo.shopledger.domain.port.Clock
import io.github.yesidrobayo.shopledger.domain.port.LowStockAlertRepository
import io.github.yesidrobayo.shopledger.domain.port.ProductRepository
import io.github.yesidrobayo.shopledger.domain.port.StockLedgerRepository
import io.github.yesidrobayo.shopledger.domain.port.SystemRowRepository
import io.github.yesidrobayo.shopledger.domain.port.TransactionRunner
import io.github.yesidrobayo.shopledger.domain.port.TxResult
import io.github.yesidrobayo.shopledger.domain.port.UnitRepository
import io.github.yesidrobayo.shopledger.domain.rule.UnitQuantityCheck
import io.github.yesidrobayo.shopledger.domain.rule.UnitQuantityRule
import io.github.yesidrobayo.shopledger.domain.rule.catalog.StockModeRules
import io.github.yesidrobayo.shopledger.domain.rule.stock.AdjustmentValue
import io.github.yesidrobayo.shopledger.domain.rule.stock.StockAdjustmentRules
import io.github.yesidrobayo.shopledger.domain.rule.stock.StockMovementCheck
import io.github.yesidrobayo.shopledger.domain.rule.stock.StockMovementRules
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.lock
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.requireId
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.stamp
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.storageSafe
import io.github.yesidrobayo.shopledger.domain.usecase.region.BusinessCalendarSource
import io.github.yesidrobayo.shopledger.domain.usecase.region.CurrencyLock

/**
 * Corrige el stock de un producto cuando la realidad y el libro difieren, en una sola transacción.
 *
 * Nunca edita una fila: agrega un ajuste con su motivo, valorado al costo vigente, y deja al
 * producto con el saldo, el costo y el valor que dice esa fila. Un conteo igual al libro no crea
 * nada. Puede dejar el stock negativo: es decisión de la persona, y la pantalla avisa sin
 * bloquear. La fecha es siempre la del día, porque un ajuste no se registra atrasado.
 *
 * En la misma transacción evalúa la alerta de stock bajo: la crea si el ajuste cruza el mínimo
 * hacia abajo, y la resuelve si deja el producto por encima.
 */
class AdjustStockUseCase(
    private val transactions: TransactionRunner,
    private val products: ProductRepository,
    private val units: UnitRepository,
    private val systemRows: SystemRowRepository,
    private val ledger: StockLedgerRepository,
    private val alerts: LowStockAlertRepository,
    private val calendar: BusinessCalendarSource,
    private val currencyLock: CurrencyLock,
    private val clock: Clock,
) {
    suspend operator fun invoke(request: StockAdjustmentRequest): StockAdjustmentResult =
        storageSafe(StockAdjustmentResult::StorageFailed) {
            transactions.write { adjust(request) }
        }

    private suspend fun adjust(request: StockAdjustmentRequest): TxResult<StockAdjustmentResult> {
        val note = request.note?.trim()?.takeIf { it.isNotEmpty() }
        if (note == null && request.reason.requiresNote) {
            return reject(StockAdjustmentResult.NoteRequired)
        }
        if (note != null && note.length > MAX_NOTE_LENGTH) {
            return reject(StockAdjustmentResult.NoteTooLong(MAX_NOTE_LENGTH))
        }
        val product = products.findById(request.productId)
            ?: return reject(StockAdjustmentResult.NotFound)
        if (product.isArchived) return reject(StockAdjustmentResult.ProductArchived)
        if (!StockModeRules.hasLedger(product.details.stockMode)) {
            return reject(StockAdjustmentResult.NotTracked)
        }
        val unit = checkNotNull(units.findById(product.details.baseUnitId)) {
            "Falta la unidad base"
        }
        val change = when (val read = changeOf(request, product, unit.allowsFraction)) {
            is ChangeRead.Change -> read.quantity
            is ChangeRead.Rejected -> return reject(read.result)
        }
        if (change == QuantityMilli.ZERO) return reject(StockAdjustmentResult.NoDifference)
        val location = systemRows.requireId(SystemRow.MAIN_LOCATION)
        val draft = StockMovementDraft(
            key = StockKey(product.id, location, lotId = null),
            type = StockMovementType.ADJUSTMENT,
            quantity = change,
            reversesMovementId = null,
        )
        // Un ajuste nuevo no anula nada, y su signo ya lo fija el motivo: solo queda el tope.
        val check = StockMovementRules.check(draft, product.onHand, null, false)
        if (check == StockMovementCheck.AboveOnHandLimit) {
            return reject(StockAdjustmentResult.AboveLimit(Limit.ON_HAND_MILLI))
        }
        val before = CostState(product.onHand, product.averageCost, product.stockValue)
        val value = when (val checked = StockAdjustmentRules.value(before, change)) {
            is Checked.Value -> checked.value

            // Con los topes ya revisados no se desborda; si pasara, es por encima de todo tope.
            Checked.Overflow -> return reject(StockAdjustmentResult.AboveLimit(Limit.STOCK_VALUE))

            is Checked.AboveLimit -> return reject(StockAdjustmentResult.AboveLimit(checked.limit))
        }
        val time = calendar.stamp(clock.now())
            ?: return reject(StockAdjustmentResult.UnknownTimeZone)
        // Un ajuste sin valor no es dinero: no fija ninguna moneda.
        if (value.totalCost != Money.ZERO && !currencyLock.lock(time.occurredAt)) {
            return reject(StockAdjustmentResult.CurrencyUnconfirmed)
        }
        val movementId = ledger.append(movementOf(draft, request, product, value, note, time))
        alerts.applyAfterMovement(product, listOf(draft), time)
        return TxResult.Commit(StockAdjustmentResult.Adjusted(movementId, value.after.onHand))
    }

    /** El cambio con signo que pide [request], o el motivo por el que no se puede hacer. */
    private fun changeOf(
        request: StockAdjustmentRequest,
        product: Product,
        allowsFraction: Boolean,
    ): ChangeRead = when (request) {
        is StockAdjustmentRequest.Count -> {
            val check = StockAdjustmentRules.checkCount(request.counted, allowsFraction)
            when {
                check != UnitQuantityCheck.Valid ->
                    ChangeRead.Rejected(StockAdjustmentResult.InvalidQuantity(check))

                !Limit.ON_HAND_MILLI.allows(request.counted.milli) ->
                    ChangeRead.Rejected(StockAdjustmentResult.AboveLimit(Limit.ON_HAND_MILLI))

                else ->
                    ChangeRead.Change(
                        StockAdjustmentRules.countDifference(product.onHand, request.counted),
                    )
            }
        }

        is StockAdjustmentRequest.Change -> {
            val check = UnitQuantityRule.check(request.quantity, allowsFraction)
            when {
                check != UnitQuantityCheck.Valid ->
                    ChangeRead.Rejected(StockAdjustmentResult.InvalidQuantity(check))

                !Limit.LINE_QUANTITY_MILLI.allows(request.quantity.milli) ->
                    ChangeRead.Rejected(StockAdjustmentResult.AboveLimit(Limit.LINE_QUANTITY_MILLI))

                request.increase && !request.reason.allowsIncrease ->
                    ChangeRead.Rejected(StockAdjustmentResult.IncreaseNotAllowed)

                request.increase -> ChangeRead.Change(request.quantity)

                else -> ChangeRead.Change(-request.quantity)
            }
        }
    }

    private fun movementOf(
        draft: StockMovementDraft,
        request: StockAdjustmentRequest,
        product: Product,
        value: AdjustmentValue,
        note: String?,
        time: RecordTime,
    ): NewStockMovement = NewStockMovement(
        movement = draft,
        reason = request.reason,
        // El costo del momento queda congelado para mostrarlo, aunque el promedio cambie después.
        unitCost = product.averageCost,
        totalCost = value.totalCost,
        balanceAfter = value.after.onHand,
        averageCostAfter = value.after.averageCost,
        stockValueAfter = value.after.stockValue,
        source = StockMovementSource.NONE,
        note = note,
        occurredAt = time.occurredAt,
        businessDate = time.businessDate,
        createdAt = time.occurredAt,
    )

    private fun reject(result: StockAdjustmentResult): TxResult<StockAdjustmentResult> =
        TxResult.Rollback(result)

    /** Lo que se lee de la solicitud antes de escribir: el cambio con signo, o un rechazo. */
    private sealed interface ChangeRead {
        data class Change(val quantity: QuantityMilli) : ChangeRead

        data class Rejected(val result: StockAdjustmentResult) : ChangeRead
    }

    companion object {
        /** Largo máximo de la nota de un ajuste, el mismo que admite la base. */
        const val MAX_NOTE_LENGTH: Int = 200
    }
}
