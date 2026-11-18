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
import io.github.yesidrobayo.shopledger.domain.port.LocationRepository
import io.github.yesidrobayo.shopledger.domain.port.LocationStockSource
import io.github.yesidrobayo.shopledger.domain.port.LowStockAlertRepository
import io.github.yesidrobayo.shopledger.domain.port.ProductRepository
import io.github.yesidrobayo.shopledger.domain.port.StockLedgerRepository
import io.github.yesidrobayo.shopledger.domain.port.StockLotRepository
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
 *
 * En un producto que se controla por lotes, la fila lleva lote. Una salida con lote elegido sale
 * de él, y nunca más de lo que tiene. Lo demás, lo que entra, lo contado y lo que sale sin lote,
 * va al lote sin asignar, el único que puede quedar negativo.
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
    private val lots: StockLotRepository,
    private val locations: LocationRepository? = null,
    private val locationStock: LocationStockSource? = null,
) {
    suspend operator fun invoke(request: StockAdjustmentRequest): StockAdjustmentResult =
        storageSafe(StockAdjustmentResult::StorageFailed) {
            transactions.write { adjust(request) }
        }

    /**
     * Varios ajustes juntos, como un conteo dicho de varios productos, en una sola transacción:
     * cada uno agrega su propia fila al libro, con las mismas reglas que uno solo, y lo ve el
     * siguiente. Si uno se rechaza, no queda ninguno. Un conteo igual al libro no escribe nada y no
     * detiene a los demás; si todos son iguales, no hay nada que corregir.
     */
    suspend fun all(requests: List<StockAdjustmentRequest>): StockAdjustmentsResult {
        require(requests.isNotEmpty()) { "Hace falta al menos un ajuste" }
        return storageSafe(StockAdjustmentsResult::StorageFailed) {
            transactions.write { adjustAll(requests) }
        }
    }

    /**
     * Lo mismo que [all] dentro de una transacción que ya abrió quien llama, como el conteo de un
     * producto nuevo dicho con una frase, que se crea en la misma escritura.
     */
    internal suspend fun allWithin(
        requests: List<StockAdjustmentRequest>,
    ): TxResult<StockAdjustmentsResult> = adjustAll(requests)

    private suspend fun adjustAll(
        requests: List<StockAdjustmentRequest>,
    ): TxResult<StockAdjustmentsResult> {
        val movements = mutableListOf<Long>()
        for ((index, request) in requests.withIndex()) {
            // Un ajuste solo confirma lo que agregó: aquí se junta todo en la misma transacción.
            val result = when (val step = adjust(request)) {
                is TxResult.Commit -> step.value
                is TxResult.CommitUncounted -> step.value
                is TxResult.Rollback -> step.value
            }
            when (result) {
                is StockAdjustmentResult.Adjusted -> movements += result.movementId
                StockAdjustmentResult.NoDifference -> Unit
                else -> return TxResult.Rollback(StockAdjustmentsResult.Rejected(index, result))
            }
        }
        if (movements.isEmpty()) return TxResult.Rollback(StockAdjustmentsResult.NoDifference)
        return TxResult.Commit(StockAdjustmentsResult.Adjusted(movements))
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
        val location = locationOf(request)
            ?: return reject(StockAdjustmentResult.LocationUnavailable)
        // Un conteo se compara con lo que el libro dice de la ubicación contada, no con el total:
        // con una sola ubicación son lo mismo.
        val here = locationStock?.onHandAt(product.id, location) ?: product.onHand
        val change = when (val read = changeOf(request, here, unit.allowsFraction)) {
            is ChangeRead.Change -> read.quantity
            is ChangeRead.Rejected -> return reject(read.result)
        }
        if (change == QuantityMilli.ZERO) return reject(StockAdjustmentResult.NoDifference)
        // El lote se busca y se reparte en la ubicación que se ajusta, no en la inicial.
        val chosenLot = when (val read = chosenLotOf(request, product, location, change)) {
            is LotRead.Chosen -> read.lotId
            is LotRead.Rejected -> return reject(read.result)
        }
        val draft = StockMovementDraft(
            key = StockKey(product.id, location, chosenLot),
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
        // Sin lote elegido, la fila de un producto con lotes va al lote sin asignar.
        val lotted = if (chosenLot == null && product.details.tracksLots) {
            val unassigned = ledger.unassignedLot(product.id, time.occurredAt)
            draft.copy(key = draft.key.copy(lotId = unassigned))
        } else {
            draft
        }
        val movementId = ledger.append(movementOf(lotted, request, product, value, note, time))
        alerts.applyAfterMovement(product, listOf(lotted), time)
        return TxResult.Commit(StockAdjustmentResult.Adjusted(movementId, value.after.onHand))
    }

    /**
     * La ubicación que se ajusta: la elegida, si sigue vigente, o la inicial sin elegir. `null` si
     * la elegida ya no existe o se archivó. Sin ubicaciones conectadas solo se acepta la inicial.
     */
    private suspend fun locationOf(request: StockAdjustmentRequest): Long? {
        val initial = systemRows.requireId(SystemRow.MAIN_LOCATION)
        val chosen = request.locationId ?: return initial
        if (chosen == initial) return initial
        return locations?.findById(chosen)?.takeUnless { it.isArchived }?.id
    }

    /**
     * El cambio con signo que pide [request], o el motivo por el que no se puede hacer. [here] es lo
     * que el libro dice de la ubicación que se ajusta.
     */
    private fun changeOf(
        request: StockAdjustmentRequest,
        here: QuantityMilli,
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
                        StockAdjustmentRules.countDifference(here, request.counted),
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

    /**
     * El lote con código del que sale el ajuste, `null` si no eligió ninguno, o el motivo por el
     * que no se puede: el lote no es del producto, o no tiene tanto en [location].
     */
    private suspend fun chosenLotOf(
        request: StockAdjustmentRequest,
        product: Product,
        location: Long,
        change: QuantityMilli,
    ): LotRead {
        val lotId = when (request) {
            is StockAdjustmentRequest.Change -> request.lotId
            is StockAdjustmentRequest.Count -> null
        } ?: return LotRead.Chosen(null)
        val lot = lots.findById(lotId)
        if (!product.details.tracksLots || lot == null || lot.productId != product.id) {
            return LotRead.Rejected(StockAdjustmentResult.LotNotFound)
        }
        if (lot.isUnassigned) return LotRead.Chosen(lot.id)
        val available = ledger.onHandAt(StockKey(product.id, location, lot.id))
        // Una salida con lote elegido siempre resta: el lote no puede quedar negativo.
        return if (available + change < QuantityMilli.ZERO) {
            LotRead.Rejected(StockAdjustmentResult.AboveLotStock(available))
        } else {
            LotRead.Chosen(lot.id)
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

    /** El lote que eligió la solicitud, o el motivo por el que no vale. */
    private sealed interface LotRead {
        data class Chosen(val lotId: Long?) : LotRead

        data class Rejected(val result: StockAdjustmentResult) : LotRead
    }

    companion object {
        /** Largo máximo de la nota de un ajuste, el mismo que admite la base. */
        const val MAX_NOTE_LENGTH: Int = 200
    }
}
