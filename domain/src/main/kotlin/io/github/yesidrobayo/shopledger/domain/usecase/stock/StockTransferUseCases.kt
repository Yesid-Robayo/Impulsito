package io.github.yesidrobayo.shopledger.domain.usecase.stock

import io.github.yesidrobayo.shopledger.domain.model.Limit
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.VoidReason
import io.github.yesidrobayo.shopledger.domain.model.VoidableDocument
import io.github.yesidrobayo.shopledger.domain.model.Voiding
import io.github.yesidrobayo.shopledger.domain.model.catalog.Product
import io.github.yesidrobayo.shopledger.domain.model.region.RecordTime
import io.github.yesidrobayo.shopledger.domain.model.stock.LotPart
import io.github.yesidrobayo.shopledger.domain.model.stock.NewStockMovement
import io.github.yesidrobayo.shopledger.domain.model.stock.NewStockTransfer
import io.github.yesidrobayo.shopledger.domain.model.stock.NewStockTransferLine
import io.github.yesidrobayo.shopledger.domain.model.stock.StockKey
import io.github.yesidrobayo.shopledger.domain.model.stock.StockMovementDraft
import io.github.yesidrobayo.shopledger.domain.model.stock.StockMovementSource
import io.github.yesidrobayo.shopledger.domain.model.stock.StockMovementType
import io.github.yesidrobayo.shopledger.domain.model.stock.StockTransferLineRejection
import io.github.yesidrobayo.shopledger.domain.model.stock.StockTransferLineRequest
import io.github.yesidrobayo.shopledger.domain.model.stock.StockTransferRequest
import io.github.yesidrobayo.shopledger.domain.port.Clock
import io.github.yesidrobayo.shopledger.domain.port.LocationRepository
import io.github.yesidrobayo.shopledger.domain.port.ProductRepository
import io.github.yesidrobayo.shopledger.domain.port.StockLedgerRepository
import io.github.yesidrobayo.shopledger.domain.port.StockLotRepository
import io.github.yesidrobayo.shopledger.domain.port.StockTransferRepository
import io.github.yesidrobayo.shopledger.domain.port.StorageFailure
import io.github.yesidrobayo.shopledger.domain.port.TransactionRunner
import io.github.yesidrobayo.shopledger.domain.port.TxResult
import io.github.yesidrobayo.shopledger.domain.port.UnitRepository
import io.github.yesidrobayo.shopledger.domain.rule.stock.LotRules
import io.github.yesidrobayo.shopledger.domain.rule.stock.StockCompensation
import io.github.yesidrobayo.shopledger.domain.rule.stock.StockMovementRules
import io.github.yesidrobayo.shopledger.domain.rule.stock.StockTransferRules
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.stamp
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.storageSafe
import io.github.yesidrobayo.shopledger.domain.usecase.region.BusinessCalendarSource

/** Cómo termina registrar una transferencia. Salvo [Registered], no se escribió nada. */
sealed interface RegisterStockTransferResult {
    /** Quedó guardada como [transferId], con una salida y una entrada por línea. */
    data class Registered(val transferId: Long) : RegisterStockTransferResult

    /** No tiene ninguna línea. */
    data object EmptyTransfer : RegisterStockTransferResult

    /** Pasa del tope de líneas de un documento. */
    data object TooManyLines : RegisterStockTransferResult

    /** La nota pasa de 200 caracteres. */
    data object NoteTooLong : RegisterStockTransferResult

    /** El origen y el destino son la misma ubicación: no hay nada que mover. */
    data object SameLocation : RegisterStockTransferResult

    /** El origen o el destino ya no existe o se archivó: hay que elegir otro. */
    data object LocationUnavailable : RegisterStockTransferResult

    /** La línea número [index], contada desde cero, no se puede transferir, por [reason]. */
    data class LineRejected(val index: Int, val reason: StockTransferLineRejection) :
        RegisterStockTransferResult

    /** Este teléfono no reconoce la zona del negocio: no hay fecha que darle. */
    data object UnknownTimeZone : RegisterStockTransferResult

    /** La base no pudo escribir. No quedó nada a medias, y lo escrito se conserva. */
    data class StorageFailed(val failure: StorageFailure) : RegisterStockTransferResult
}

/**
 * Mueve stock entre dos ubicaciones, en una sola transacción: la transferencia, sus líneas y, por
 * cada línea, una salida del origen y una entrada al destino en el libro.
 *
 * - **No toca la cantidad, el costo ni el valor.** Las dos filas llevan costo cero, y el producto
 *   queda con el mismo saldo, el mismo costo promedio y el mismo valor: solo cambia dónde está. La
 *   salida deja el saldo del producto más bajo y la entrada lo devuelve, para que cada fila diga
 *   cómo dejó al producto.
 * - **Nunca se bloquea por falta de stock en el origen:** puede quedar en negativo, como al vender.
 *   La pantalla lo avisa antes de confirmar.
 * - **La fecha es la de hoy**, como un ajuste: lo que se movió atrasado se ve en el orden del libro.
 * - **Un producto con lotes sale por lote**, como en la venta: primero el lote elegido, luego el
 *   que vence antes, y lo que falte, del lote sin asignar, que se crea si no existe. Ningún lote
 *   con código da más de lo que tiene en el origen. Cada lote del que sale es una línea guardada
 *   con su salida y su entrada, y llega al destino con su mismo código y vencimiento.
 *
 * No toca las alertas de stock bajo: el saldo de cada producto no cambia.
 */
class RegisterStockTransferUseCase(
    private val transactions: TransactionRunner,
    private val products: ProductRepository,
    private val units: UnitRepository,
    private val locations: LocationRepository,
    private val transfers: StockTransferRepository,
    private val ledger: StockLedgerRepository,
    private val calendar: BusinessCalendarSource,
    private val clock: Clock,
    private val lots: StockLotRepository,
) {
    suspend operator fun invoke(request: StockTransferRequest): RegisterStockTransferResult =
        storageSafe(RegisterStockTransferResult::StorageFailed) {
            transactions.write { register(request) }
        }

    private suspend fun register(
        request: StockTransferRequest,
    ): TxResult<RegisterStockTransferResult> {
        if (request.lines.isEmpty()) return reject(RegisterStockTransferResult.EmptyTransfer)
        if (!Limit.DOCUMENT_LINES.allows(request.lines.size.toLong())) {
            return reject(RegisterStockTransferResult.TooManyLines)
        }
        val note = request.note?.trim()?.takeIf { it.isNotEmpty() }
        if (note != null && note.length > StockTransferRules.NOTE_MAX) {
            return reject(RegisterStockTransferResult.NoteTooLong)
        }
        if (request.fromLocationId == request.toLocationId) {
            return reject(RegisterStockTransferResult.SameLocation)
        }
        val ends = listOf(request.fromLocationId, request.toLocationId)
        if (ends.any { id -> locations.findById(id)?.isArchived != false }) {
            return reject(RegisterStockTransferResult.LocationUnavailable)
        }
        val byId = products.findByIds(request.lines.map { it.productId }.toSet())
            .associateBy { it.id }
        request.lines.forEachIndexed { index, line ->
            val product = byId[line.productId]
            val allowsFraction = product
                ?.let { units.findById(it.details.baseUnitId)?.allowsFraction }
                ?: false
            val chosenLot = line.lotId?.let { lots.findById(it) }
            StockTransferRules.lineRejection(
                product,
                allowsFraction,
                line.quantity,
                line.lotId,
                chosenLot,
            )?.let {
                return reject(RegisterStockTransferResult.LineRejected(index, it))
            }
        }
        val time = calendar.stamp(clock.now())
            ?: return reject(RegisterStockTransferResult.UnknownTimeZone)
        val transferId = transfers.insert(
            NewStockTransfer(
                fromLocationId = request.fromLocationId,
                toLocationId = request.toLocationId,
                note = note,
                occurredAt = time.occurredAt,
                businessDate = time.businessDate,
                createdAt = time.occurredAt,
            ),
        )
        for (line in request.lines) {
            // Se lee otra vez: dos líneas del mismo producto ven el saldo que dejó la anterior.
            val product = checkNotNull(products.findById(line.productId)) {
                "El producto de la línea se acaba de leer"
            }
            for (part in partsOf(product, line, request.fromLocationId, time)) {
                val lineId = transfers.insertLine(
                    transferId,
                    NewStockTransferLine(product.id, lotId = part.lotId, quantity = part.quantity),
                )
                val source = StockMovementSource(transferLineId = lineId)
                val exit = StockMovementDraft(
                    key = StockKey(product.id, request.fromLocationId, part.lotId),
                    type = StockMovementType.TRANSFER_OUT,
                    quantity = -part.quantity,
                    reversesMovementId = null,
                )
                val entry = StockMovementDraft(
                    key = StockKey(product.id, request.toLocationId, part.lotId),
                    type = StockMovementType.TRANSFER_IN,
                    quantity = part.quantity,
                    reversesMovementId = null,
                )
                val low = StockTransferRules.balanceAfterExit(product.onHand, part.quantity)
                ledger.append(transferRow(exit, product, low, source, time))
                ledger.append(transferRow(entry, product, product.onHand, source, time))
            }
        }
        return TxResult.Commit(RegisterStockTransferResult.Registered(transferId))
    }

    /**
     * De qué lotes sale [line]: sin lotes, una sola parte sin lote. Con lotes, el reparto de la
     * venta sobre lo que tiene cada lote en el origen, leído otra vez para cada línea: dos líneas
     * del mismo producto ven lo que dejó la anterior. La parte sin lote va al sin asignar.
     */
    private suspend fun partsOf(
        product: Product,
        line: StockTransferLineRequest,
        fromLocationId: Long,
        time: RecordTime,
    ): List<LotPart> {
        if (!product.details.tracksLots) return listOf(LotPart(lotId = null, line.quantity))
        val stocks = lots.findStock(product.id, fromLocationId)
        return LotRules.splitOutflow(line.quantity, stocks, line.lotId).map { part ->
            part.copy(lotId = part.lotId ?: ledger.unassignedLot(product.id, time.occurredAt))
        }
    }

    private fun reject(
        result: RegisterStockTransferResult,
    ): TxResult<RegisterStockTransferResult> = TxResult.Rollback(result)
}

/** Cómo termina anular una transferencia. Salvo [Voided], no se escribió nada. */
sealed interface VoidStockTransferResult {
    /** Quedó anulada, con un compensatorio por cada fila de sus líneas. */
    data object Voided : VoidStockTransferResult

    data object NotFound : VoidStockTransferResult

    /** Ya estaba anulada: anularla otra vez movería el stock dos veces. */
    data object AlreadyVoided : VoidStockTransferResult

    /** El motivo no vale para una transferencia, como una devolución de cliente. */
    data object ReasonNotAllowed : VoidStockTransferResult

    /**
     * Del lote con código [lotId] de [productId] ya salió parte de lo que llegó al destino: anular
     * dejaría ese lote en negativo, y solo el lote sin asignar puede quedar así. Faltan [missing].
     */
    data class LotAlreadyUsed(val productId: Long, val lotId: Long, val missing: QuantityMilli) :
        VoidStockTransferResult

    /** La nota pasa de 200 caracteres. */
    data object NoteTooLong : VoidStockTransferResult

    /** Este teléfono no reconoce la zona del negocio: no hay fecha que darle a la anulación. */
    data object UnknownTimeZone : VoidStockTransferResult

    /** La base no pudo escribir. No quedó nada a medias. */
    data class StorageFailed(val failure: StorageFailure) : VoidStockTransferResult
}

/**
 * Anula una transferencia errada, en una sola transacción: la marca anulada y agrega un
 * `TRANSFER_VOID` por cada salida y cada entrada de sus líneas, con la cantidad opuesta y en la misma
 * ubicación. El stock vuelve al origen, y el total, el costo y el valor siguen sin cambiar.
 *
 * Primero se compensan las entradas y después las salidas: el destino baja, y el origen recupera.
 * Puede dejar el destino en negativo si ya se vendió allí lo transferido; la pantalla lo avisa, y la
 * anulación sigue. Los compensatorios llevan la fecha de hoy.
 *
 * Cada fila se compensa en su mismo lote. Si un lote con código ya no tiene en el destino lo que
 * llegó, no se anula nada ([VoidStockTransferResult.LotAlreadyUsed]), como al anular una compra:
 * solo el lote sin asignar puede quedar en negativo.
 */
class VoidStockTransferUseCase(
    private val transactions: TransactionRunner,
    private val products: ProductRepository,
    private val transfers: StockTransferRepository,
    private val ledger: StockLedgerRepository,
    private val calendar: BusinessCalendarSource,
    private val clock: Clock,
    private val lots: StockLotRepository,
) {
    suspend operator fun invoke(
        transferId: Long,
        reason: VoidReason,
        note: String? = null,
    ): VoidStockTransferResult = storageSafe(VoidStockTransferResult::StorageFailed) {
        transactions.write { void(transferId, reason, note) }
    }

    private suspend fun void(
        transferId: Long,
        reason: VoidReason,
        rawNote: String?,
    ): TxResult<VoidStockTransferResult> {
        if (!VoidableDocument.STOCK_TRANSFER.allows(reason)) {
            return reject(VoidStockTransferResult.ReasonNotAllowed)
        }
        val note = rawNote?.trim()?.takeIf { it.isNotEmpty() }
        if (note != null && note.length > StockTransferRules.NOTE_MAX) {
            return reject(VoidStockTransferResult.NoteTooLong)
        }
        val transfer = transfers.findById(transferId)
            ?: return reject(VoidStockTransferResult.NotFound)
        if (transfer.voiding != null) return reject(VoidStockTransferResult.AlreadyVoided)
        val time = calendar.stamp(clock.now())
            ?: return reject(VoidStockTransferResult.UnknownTimeZone)
        val written = transfers.findMovements(transferId)
            .filterNot { it.movement.type.isCompensating }
        val entries = written.filter { it.movement.type == StockMovementType.TRANSFER_IN }
        ledger.lotShortfallOf(entries, lots)?.let { short ->
            val used = VoidStockTransferResult.LotAlreadyUsed(
                short.productId,
                short.lotId,
                short.missing,
            )
            return reject(used)
        }
        transfers.markVoided(transferId, Voiding(time.occurredAt, reason, note))
        // Las entradas primero: el destino baja y después el origen recupera, línea por línea.
        val rows = written
            .sortedWith(compareBy({ it.source.transferLineId }, { it.movement.type.ordinal }))
            .reversed()
        for (row in rows) {
            val draft = when (
                val compensation = StockMovementRules.compensationOf(
                    row.movement,
                    ledger.isCompensated(row.id),
                )
            ) {
                is StockCompensation.Ready -> compensation.draft

                // La transferencia estaba activa: ninguna de sus filas tiene compensatorio.
                StockCompensation.AlreadyCompensated, StockCompensation.NotCompensable ->
                    error("Una fila de una transferencia activa se compensa una vez")
            }
            val product = checkNotNull(products.findById(draft.key.productId)) {
                "Una fila del libro siempre tiene su producto"
            }
            ledger.append(
                transferRow(
                    draft,
                    product,
                    product.onHand + draft.quantity,
                    row.source,
                    time,
                ),
            )
        }
        return TxResult.Commit(VoidStockTransferResult.Voided)
    }

    private fun reject(result: VoidStockTransferResult): TxResult<VoidStockTransferResult> =
        TxResult.Rollback(result)
}

/**
 * Una fila de transferencia o de su anulación: sin costo, y con el costo promedio y el valor del
 * producto tal como estaban, porque mover stock no los cambia.
 */
private fun transferRow(
    draft: StockMovementDraft,
    product: Product,
    balanceAfter: QuantityMilli,
    source: StockMovementSource,
    time: RecordTime,
): NewStockMovement = NewStockMovement(
    movement = draft,
    reason = null,
    unitCost = Money.ZERO,
    totalCost = Money.ZERO,
    balanceAfter = balanceAfter,
    averageCostAfter = product.averageCost,
    stockValueAfter = product.stockValue,
    source = source,
    note = null,
    occurredAt = time.occurredAt,
    businessDate = time.businessDate,
    createdAt = time.occurredAt,
)
