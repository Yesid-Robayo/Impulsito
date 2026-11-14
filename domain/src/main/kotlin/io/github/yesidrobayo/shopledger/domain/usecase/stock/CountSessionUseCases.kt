package io.github.yesidrobayo.shopledger.domain.usecase.stock

import io.github.yesidrobayo.shopledger.domain.model.ArchiveFilter
import io.github.yesidrobayo.shopledger.domain.model.Checked
import io.github.yesidrobayo.shopledger.domain.model.Limit
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.catalog.Product
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductStockMode
import io.github.yesidrobayo.shopledger.domain.model.region.RecordTime
import io.github.yesidrobayo.shopledger.domain.model.stock.CostState
import io.github.yesidrobayo.shopledger.domain.model.stock.CountSessionLine
import io.github.yesidrobayo.shopledger.domain.model.stock.CountSessionStatus
import io.github.yesidrobayo.shopledger.domain.model.stock.NewCountSession
import io.github.yesidrobayo.shopledger.domain.model.stock.NewStockMovement
import io.github.yesidrobayo.shopledger.domain.model.stock.OpenCountRequest
import io.github.yesidrobayo.shopledger.domain.model.stock.StockAdjustmentReason
import io.github.yesidrobayo.shopledger.domain.model.stock.StockKey
import io.github.yesidrobayo.shopledger.domain.model.stock.StockMovementDraft
import io.github.yesidrobayo.shopledger.domain.model.stock.StockMovementSource
import io.github.yesidrobayo.shopledger.domain.model.stock.StockMovementType
import io.github.yesidrobayo.shopledger.domain.port.CategoryRepository
import io.github.yesidrobayo.shopledger.domain.port.Clock
import io.github.yesidrobayo.shopledger.domain.port.CountSessionRepository
import io.github.yesidrobayo.shopledger.domain.port.LocationRepository
import io.github.yesidrobayo.shopledger.domain.port.LocationStockSource
import io.github.yesidrobayo.shopledger.domain.port.LowStockAlertRepository
import io.github.yesidrobayo.shopledger.domain.port.ProductRepository
import io.github.yesidrobayo.shopledger.domain.port.StockLedgerRepository
import io.github.yesidrobayo.shopledger.domain.port.StockLotRepository
import io.github.yesidrobayo.shopledger.domain.port.StorageFailure
import io.github.yesidrobayo.shopledger.domain.port.TransactionRunner
import io.github.yesidrobayo.shopledger.domain.port.TxResult
import io.github.yesidrobayo.shopledger.domain.port.UnitRepository
import io.github.yesidrobayo.shopledger.domain.rule.UnitQuantityCheck
import io.github.yesidrobayo.shopledger.domain.rule.stock.CountLotRules
import io.github.yesidrobayo.shopledger.domain.rule.stock.StockAdjustmentRules
import io.github.yesidrobayo.shopledger.domain.rule.stock.StockMovementCheck
import io.github.yesidrobayo.shopledger.domain.rule.stock.StockMovementRules
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.lock
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.stamp
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.storageSafe
import io.github.yesidrobayo.shopledger.domain.usecase.region.BusinessCalendarSource
import io.github.yesidrobayo.shopledger.domain.usecase.region.CurrencyLock
import kotlinx.coroutines.flow.first

/** Cómo termina abrir un conteo. Salvo [Opened], no se escribió nada. */
sealed interface OpenCountResult {
    data class Opened(val sessionId: Long) : OpenCountResult

    /** La ubicación ya tiene un conteo abierto: se sigue ese, o se cancela antes. */
    data class AlreadyOpen(val sessionId: Long) : OpenCountResult

    /** La ubicación ya no existe o se archivó. */
    data object LocationUnavailable : OpenCountResult

    /** La categoría elegida ya no existe. */
    data object CategoryNotFound : OpenCountResult

    /** No hay ningún producto con stock propio que contar en ese alcance. */
    data object NothingToCount : OpenCountResult

    /** La nota pasa de 200 caracteres. */
    data object NoteTooLong : OpenCountResult

    /** Este teléfono no reconoce la zona del negocio: no hay fecha que darle. */
    data object UnknownTimeZone : OpenCountResult

    data class StorageFailed(val failure: StorageFailure) : OpenCountResult
}

/**
 * Abre una sesión de conteo de una ubicación, y de una categoría si se elige, en una transacción:
 * una línea por producto vigente con stock propio, con lo que el libro dice de esa ubicación
 * congelado como esperado. A ciegas por defecto.
 *
 * Una ubicación tiene a lo sumo un conteo abierto: dos a la vez contarían lo mismo dos veces. El
 * principal de unas variantes, que no tiene stock propio, no entra.
 *
 * Un producto con lotes se cuenta por lote: una línea por cada lote con saldo en la ubicación, con
 * su esperado congelado, y otra para el lote sin asignar si tiene saldo o si no hay ningún lote
 * ([CountLotRules.linesOf]). Sin [lots], esos productos no entran.
 */
class OpenCountSessionUseCase(
    private val transactions: TransactionRunner,
    private val sessions: CountSessionRepository,
    private val products: ProductRepository,
    private val categories: CategoryRepository,
    private val locations: LocationRepository,
    private val stock: LocationStockSource,
    private val calendar: BusinessCalendarSource,
    private val clock: Clock,
    private val lots: StockLotRepository? = null,
) {
    suspend operator fun invoke(request: OpenCountRequest): OpenCountResult =
        storageSafe(OpenCountResult::StorageFailed) {
            transactions.write { open(request) }
        }

    private suspend fun open(request: OpenCountRequest): TxResult<OpenCountResult> {
        val note = request.note?.trim()?.takeIf { it.isNotEmpty() }
        if (note != null && note.length > NOTE_MAX) return reject(OpenCountResult.NoteTooLong)
        if (locations.findById(request.locationId)?.isArchived != false) {
            return reject(OpenCountResult.LocationUnavailable)
        }
        sessions.findOpenAt(request.locationId)?.let {
            return reject(OpenCountResult.AlreadyOpen(it.id))
        }
        val categoryId = request.categoryId
        if (categoryId != null && categories.findById(categoryId) == null) {
            return reject(OpenCountResult.CategoryNotFound)
        }
        val active = products.observe(ArchiveFilter.ACTIVE).first()
        val parents = active.mapNotNullTo(mutableSetOf()) { it.details.parentProductId }
        val counted = active.filter {
            it.details.stockMode == ProductStockMode.TRACKED &&
                (!it.details.tracksLots || lots != null) &&
                it.id !in parents &&
                (categoryId == null || it.details.categoryId == categoryId)
        }.sortedWith(compareBy({ it.details.normalizedName }, { it.id }))
        if (counted.isEmpty()) return reject(OpenCountResult.NothingToCount)
        val time = calendar.stamp(clock.now()) ?: return reject(OpenCountResult.UnknownTimeZone)
        val sessionId = sessions.insert(
            NewCountSession(
                isBlind = request.isBlind,
                locationId = request.locationId,
                categoryId = categoryId,
                note = note,
                startedAt = time.occurredAt,
                businessDate = time.businessDate,
            ),
        )
        for (product in counted) {
            if (product.details.tracksLots) {
                val repository = checkNotNull(lots) { "Solo entran con lotes si se leen" }
                val stocks = repository.findStock(product.id, request.locationId)
                for (line in CountLotRules.linesOf(stocks)) {
                    sessions.insertLine(sessionId, product.id, line.lotId, line.expected)
                }
            } else {
                val expected = stock.onHandAt(product.id, request.locationId)
                sessions.insertLine(sessionId, product.id, lotId = null, expected = expected)
            }
        }
        return TxResult.Commit(OpenCountResult.Opened(sessionId))
    }

    private fun reject(result: OpenCountResult): TxResult<OpenCountResult> =
        TxResult.Rollback(result)

    private companion object {
        const val NOTE_MAX = 200
    }
}

/** Cómo termina anotar lo contado en una línea. Salvo [Recorded], no se escribió nada. */
sealed interface RecordCountResult {
    data object Recorded : RecordCountResult

    data object NotFound : RecordCountResult

    /** La sesión ya se aplicó o se canceló: sus líneas no cambian. */
    data object SessionClosed : RecordCountResult

    /** La cantidad no vale para la unidad del producto, por [check]. */
    data class InvalidQuantity(val check: UnitQuantityCheck) : RecordCountResult

    /** Pasa del tope del stock de un producto. */
    data object AboveLimit : RecordCountResult

    data class StorageFailed(val failure: StorageFailure) : RecordCountResult
}

/**
 * Anota lo contado en una línea de una sesión abierta, o lo borra con `null`. Se guarda al
 * escribirlo: cerrar la app no pierde el conteo. No toca el libro ni cuenta un cambio del negocio
 * sin respaldar hasta aplicar.
 */
class RecordCountUseCase(
    private val transactions: TransactionRunner,
    private val sessions: CountSessionRepository,
    private val products: ProductRepository,
    private val units: UnitRepository,
) {
    suspend operator fun invoke(
        sessionId: Long,
        lineId: Long,
        counted: QuantityMilli?,
    ): RecordCountResult = storageSafe(RecordCountResult::StorageFailed) {
        transactions.write { record(sessionId, lineId, counted) }
    }

    private suspend fun record(
        sessionId: Long,
        lineId: Long,
        counted: QuantityMilli?,
    ): TxResult<RecordCountResult> {
        val session = sessions.findById(sessionId) ?: return reject(RecordCountResult.NotFound)
        if (session.status != CountSessionStatus.OPEN) {
            return reject(RecordCountResult.SessionClosed)
        }
        val line = sessions.findLines(sessionId).firstOrNull { it.id == lineId }
            ?: return reject(RecordCountResult.NotFound)
        if (counted != null) {
            val product = checkNotNull(products.findById(line.productId)) {
                "Una línea siempre tiene su producto"
            }
            val unit = checkNotNull(units.findById(product.details.baseUnitId)) {
                "Falta la unidad base"
            }
            val check = StockAdjustmentRules.checkCount(counted, unit.allowsFraction)
            if (check != UnitQuantityCheck.Valid) {
                return reject(RecordCountResult.InvalidQuantity(check))
            }
            if (!Limit.ON_HAND_MILLI.allows(counted.milli)) {
                return reject(RecordCountResult.AboveLimit)
            }
        }
        if (line.counted == counted) return TxResult.Rollback(RecordCountResult.Recorded)
        sessions.setCounted(lineId, counted)
        // Cuenta como cambio sin respaldar: lo contado a mano no se debe perder.
        return TxResult.Commit(RecordCountResult.Recorded)
    }

    private fun reject(result: RecordCountResult): TxResult<RecordCountResult> =
        TxResult.Rollback(result)
}

/** Cómo termina aplicar o cancelar un conteo. Salvo [Applied] y [Cancelled], no se escribió nada. */
sealed interface FinishCountResult {
    /** Quedó aplicada, con [adjustments] ajustes en el libro, uno por diferencia. */
    data class Applied(val adjustments: Int) : FinishCountResult

    data object Cancelled : FinishCountResult

    data object NotFound : FinishCountResult

    /** Ya estaba aplicada o cancelada: no se aplica dos veces. */
    data object AlreadyFinished : FinishCountResult

    /** Un ajuste dejaría el stock o su valor por encima de [limit]. */
    data class AboveLimit(val limit: Limit) : FinishCountResult

    /** Este teléfono no reconoce la zona del negocio: no hay fecha que darle a los ajustes. */
    data object UnknownTimeZone : FinishCountResult

    /** Algún ajuste tiene valor, y la moneda del negocio sigue sin confirmar. */
    data object CurrencyUnconfirmed : FinishCountResult

    data class StorageFailed(val failure: StorageFailure) : FinishCountResult
}

/**
 * Aplica un conteo abierto, en una sola transacción: por cada línea contada con diferencia, un
 * `ADJUSTMENT` de corrección por conteo con la sesión como origen, en la ubicación de la sesión,
 * por lo contado menos lo esperado al abrir. Esperado 12 y contado 10 dan −2000. Las líneas sin
 * contar no cambian. Luego la marca aplicada, y evalúa las alertas de stock bajo una vez por producto.
 *
 * Lo esperado es lo que había al abrir, aunque algo se haya movido después: la pantalla lo avisa
 * antes de aplicar, con [CountSessionRepository.productsMovedSinceOpen].
 *
 * Un producto con lotes escribe un ajuste por lote contado con diferencia, en ese lote. Lo que
 * sobra entra al lote; lo que falta sale de él hasta dejarlo en cero, y el resto del lote sin
 * asignar ([CountLotRules.splitCorrection]), que se crea si no existe: un lote con código nunca
 * queda en negativo.
 */
class ApplyCountSessionUseCase(
    private val transactions: TransactionRunner,
    private val sessions: CountSessionRepository,
    private val products: ProductRepository,
    ledger: StockLedgerRepository,
    private val alerts: LowStockAlertRepository,
    private val calendar: BusinessCalendarSource,
    private val currencyLock: CurrencyLock,
    private val clock: Clock,
    private val lots: StockLotRepository? = null,
) {
    private val ledger = StockChangeRecorder(ledger)

    suspend operator fun invoke(sessionId: Long): FinishCountResult =
        storageSafe(FinishCountResult::StorageFailed) {
            transactions.write {
                ledger.start()
                apply(sessionId)
            }
        }

    private suspend fun apply(sessionId: Long): TxResult<FinishCountResult> {
        val session = sessions.findById(sessionId) ?: return reject(FinishCountResult.NotFound)
        if (session.status != CountSessionStatus.OPEN) {
            return reject(FinishCountResult.AlreadyFinished)
        }
        val time = calendar.stamp(clock.now()) ?: return reject(FinishCountResult.UnknownTimeZone)
        var written = 0
        val currency = CurrencyOnce()
        for (line in sessions.findLines(sessionId)) {
            val change = line.difference?.takeIf { it != QuantityMilli.ZERO } ?: continue
            val product = checkNotNull(products.findById(line.productId)) {
                "Una línea siempre tiene su producto"
            }
            for (part in partsOf(product, line, session.locationId, change, time)) {
                val key = StockKey(product.id, session.locationId, part.lotId)
                append(sessionId, key, part.quantity, currency, time)?.let { return reject(it) }
                written++
            }
        }
        sessions.finish(sessionId, CountSessionStatus.APPLIED, time.occurredAt)
        ledger.applyAlerts(alerts, products, time)
        return TxResult.Commit(FinishCountResult.Applied(written))
    }

    /**
     * Las filas que corrigen [line] por [change]: una sin lote en un producto sin lotes. Con
     * lotes, en el lote de la línea, o en el sin asignar si la línea es de él o de ninguno; un
     * lote con código no da más de lo que tiene, y el resto sale del sin asignar.
     */
    private suspend fun partsOf(
        product: Product,
        line: CountSessionLine,
        locationId: Long,
        change: QuantityMilli,
        time: RecordTime,
    ): List<CorrectionPart> {
        if (!product.details.tracksLots) return listOf(CorrectionPart(lotId = null, change))
        val repository = checkNotNull(lots) { "Aplicar un conteo con lotes pide los lotes" }
        val lot = line.lotId?.let { repository.findById(it) }
        if (lot == null || lot.isUnassigned) {
            val unassigned = lot?.id ?: ledger.unassignedLot(product.id, time.occurredAt)
            return listOf(CorrectionPart(unassigned, change))
        }
        val available = ledger.onHandAt(StockKey(product.id, locationId, lot.id))
        val split = CountLotRules.splitCorrection(change, available)
        val parts = ArrayList<CorrectionPart>(2)
        if (split.lot != QuantityMilli.ZERO) parts += CorrectionPart(lot.id, split.lot)
        if (split.unassigned != QuantityMilli.ZERO) {
            val unassigned = ledger.unassignedLot(product.id, time.occurredAt)
            parts += CorrectionPart(unassigned, split.unassigned)
        }
        return parts
    }

    /**
     * Agrega el ajuste de [change] en [key], valorado al costo del producto de ahora, o devuelve
     * por qué no se puede. La primera fila con valor fija la moneda, una sola vez.
     */
    private suspend fun append(
        sessionId: Long,
        key: StockKey,
        change: QuantityMilli,
        currency: CurrencyOnce,
        time: RecordTime,
    ): FinishCountResult? {
        // Se lee otra vez: dos filas del mismo producto ven el estado que dejó la anterior.
        val product = checkNotNull(products.findById(key.productId)) {
            "Una línea siempre tiene su producto"
        }
        val draft = StockMovementDraft(
            key = key,
            type = StockMovementType.ADJUSTMENT,
            quantity = change,
            reversesMovementId = null,
        )
        if (StockMovementRules.check(draft, product.onHand, null, false) ==
            StockMovementCheck.AboveOnHandLimit
        ) {
            return FinishCountResult.AboveLimit(Limit.ON_HAND_MILLI)
        }
        val before = CostState(product.onHand, product.averageCost, product.stockValue)
        val value = when (val checked = StockAdjustmentRules.value(before, change)) {
            is Checked.Value -> checked.value
            Checked.Overflow -> return FinishCountResult.AboveLimit(Limit.STOCK_VALUE)
            is Checked.AboveLimit -> return FinishCountResult.AboveLimit(checked.limit)
        }
        // Un ajuste sin valor no es dinero: no fija ninguna moneda.
        if (!currency.locked && value.totalCost != Money.ZERO) {
            if (!currencyLock.lock(time.occurredAt)) return FinishCountResult.CurrencyUnconfirmed
            currency.locked = true
        }
        ledger.append(
            NewStockMovement(
                movement = draft,
                reason = StockAdjustmentReason.COUNT_CORRECTION,
                unitCost = product.averageCost,
                totalCost = value.totalCost,
                balanceAfter = value.after.onHand,
                averageCostAfter = value.after.averageCost,
                stockValueAfter = value.after.stockValue,
                source = StockMovementSource(countSessionId = sessionId),
                note = null,
                occurredAt = time.occurredAt,
                businessDate = time.businessDate,
                createdAt = time.occurredAt,
            ),
        )
        return null
    }

    private fun reject(result: FinishCountResult): TxResult<FinishCountResult> =
        TxResult.Rollback(result)
}

/** Una fila de la corrección de una línea: [quantity] en el lote [lotId], o sin lote. */
private data class CorrectionPart(val lotId: Long?, val quantity: QuantityMilli)

/** Si la moneda ya quedó fija en esta aplicación: se fija una sola vez. */
private class CurrencyOnce {
    var locked: Boolean = false
}

/** Cancela un conteo abierto: lo contado se conserva en sus líneas, y el libro no cambia. */
class CancelCountSessionUseCase(
    private val transactions: TransactionRunner,
    private val sessions: CountSessionRepository,
    private val clock: Clock,
) {
    suspend operator fun invoke(sessionId: Long): FinishCountResult =
        storageSafe(FinishCountResult::StorageFailed) {
            transactions.write {
                val session = sessions.findById(sessionId)
                when {
                    session == null -> TxResult.Rollback(FinishCountResult.NotFound)

                    session.status != CountSessionStatus.OPEN ->
                        TxResult.Rollback(FinishCountResult.AlreadyFinished)

                    else -> {
                        sessions.finish(sessionId, CountSessionStatus.CANCELLED, clock.now())
                        TxResult.Commit(FinishCountResult.Cancelled)
                    }
                }
            }
        }
}
