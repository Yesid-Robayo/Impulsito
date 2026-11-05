package io.github.yesidrobayo.shopledger.data.seed

import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.Checked
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.stock.CostMovement
import io.github.yesidrobayo.shopledger.domain.model.stock.CostState
import io.github.yesidrobayo.shopledger.domain.model.stock.NewStockMovement
import io.github.yesidrobayo.shopledger.domain.model.stock.StockAdjustmentReason
import io.github.yesidrobayo.shopledger.domain.model.stock.StockKey
import io.github.yesidrobayo.shopledger.domain.model.stock.StockMovement
import io.github.yesidrobayo.shopledger.domain.model.stock.StockMovementDraft
import io.github.yesidrobayo.shopledger.domain.model.stock.StockMovementSource
import io.github.yesidrobayo.shopledger.domain.model.stock.StockMovementType
import io.github.yesidrobayo.shopledger.domain.port.StockLedgerRepository
import io.github.yesidrobayo.shopledger.domain.rule.CostReplayResult
import io.github.yesidrobayo.shopledger.domain.rule.WeightedAverageCost
import io.github.yesidrobayo.shopledger.domain.rule.stock.StockCompensation
import io.github.yesidrobayo.shopledger.domain.rule.stock.StockMovementRules
import kotlin.time.Instant

/**
 * El libro de stock del negocio inventado, escrito con las mismas reglas que la app.
 *
 * Cada fila pasa por [StockLedgerRepository.append], que comprueba el saldo contra la base y deja
 * el saldo, el costo y el valor en el producto y en su parte del stock. El costo sale de
 * [WeightedAverageCost], y una anulación, de reproducir el libro sin la fila anulada, como pide la
 * regla del costo: nada se resta a mano.
 *
 * Lleva en memoria el estado de costo y las filas de cada producto con stock, para no releer el
 * libro entero en cada venta. Las filas en memoria son las mismas que la base guardó, con sus ids:
 * al final, las consultas de invariantes comprueban que la base y estas cuentas coinciden.
 */
internal class SeedStock(private val ledger: StockLedgerRepository) {
    private val books = HashMap<Long, Book>()

    /** Empieza el libro de un producto con stock, sin filas y con [initialCost]. */
    fun register(productId: Long, initialCost: Money) {
        books[productId] = Book(initialCost)
    }

    /** El estado de costo actual del producto. */
    fun state(productId: Long): CostState = book(productId).state

    /** Cuántas filas tiene el libro del producto. */
    fun movementCount(productId: Long): Int = book(productId).movements.size

    /**
     * Una entrada con factura o inventario inicial: [quantity] milésimas que valen [total]. El
     * costo promedio sale del valor, con `entrada`.
     */
    suspend fun entry(
        productId: Long,
        locationId: Long,
        type: StockMovementType,
        quantity: QuantityMilli,
        total: Money,
        unitCost: Money,
        source: StockMovementSource,
        at: Instant,
        date: BusinessDate,
    ): RecordedMovement {
        val after = checked(WeightedAverageCost.entry(state(productId), quantity, total))
        return append(
            productId = productId,
            locationId = locationId,
            type = type,
            quantity = quantity,
            unitCost = unitCost,
            totalCost = total,
            after = after,
            reason = null,
            reverses = null,
            source = source,
            at = at,
            date = date,
        )
    }

    /**
     * Una salida ya calculada por la regla de la línea de venta: [cost] es lo que se lleva del
     * valor, y [after], el estado que deja.
     */
    suspend fun saleExit(
        productId: Long,
        locationId: Long,
        quantity: QuantityMilli,
        unitCost: Money,
        cost: Money,
        after: CostState,
        source: StockMovementSource,
        at: Instant,
        date: BusinessDate,
    ): RecordedMovement = append(
        productId = productId,
        locationId = locationId,
        type = StockMovementType.SALE,
        quantity = -quantity,
        unitCost = unitCost,
        totalCost = -cost,
        after = after,
        reason = null,
        reverses = null,
        source = source,
        at = at,
        date = date,
    )

    /**
     * Un ajuste sin factura. Una pérdida sale con `costoDeSalida`; una corrección que suma entra al
     * costo vigente, con `ajustePositivo`, sin cambiar el promedio.
     */
    suspend fun adjust(
        productId: Long,
        locationId: Long,
        quantity: QuantityMilli,
        reason: StockAdjustmentReason,
        at: Instant,
        date: BusinessDate,
    ): RecordedMovement {
        val before = state(productId)
        val (total, after) =
            if (quantity.milli < 0L) {
                val out = -quantity
                val cost = checked(WeightedAverageCost.exitCost(before, out))
                -cost to WeightedAverageCost.exit(before, out, cost)
            } else {
                val cost = checked(WeightedAverageCost.positiveAdjustmentCost(before, quantity))
                cost to checked(WeightedAverageCost.positiveAdjustment(before, quantity, cost))
            }
        return append(
            productId = productId,
            locationId = locationId,
            type = StockMovementType.ADJUSTMENT,
            quantity = quantity,
            unitCost = before.averageCost,
            totalCost = total,
            after = after,
            reason = reason,
            reverses = null,
            source = StockMovementSource.NONE,
            at = at,
            date = date,
        )
    }

    /**
     * Anula [original] con su compensatorio: la cantidad opuesta en la misma parte del stock, el
     * mismo origen y el costo que deja reproducir el libro como si la fila nunca hubiera existido.
     */
    suspend fun compensate(original: RecordedMovement, at: Instant, date: BusinessDate): Long {
        val draft =
            when (
                val compensation =
                    StockMovementRules.compensationOf(
                        original.asMovement(),
                        isAlreadyCompensated = false,
                    )
            ) {
                is StockCompensation.Ready -> compensation.draft

                StockCompensation.NotCompensable,
                StockCompensation.AlreadyCompensated,
                -> error("La fila no se puede anular")
            }
        val book = book(original.productId)
        val pending =
            CostMovement(
                id = Long.MAX_VALUE,
                type = draft.type,
                quantity = draft.quantity,
                unitCost = original.unitCost,
                totalCost = -original.totalCost,
                reversesMovementId = original.id,
            )
        val after =
            when (
                val replay =
                    WeightedAverageCost.replayCostState(book.initialCost, book.movements + pending)
            ) {
                is CostReplayResult.Replayed -> replay.state

                is CostReplayResult.Overflow,
                is CostReplayResult.InvalidMovement,
                -> error("El libro del producto no se pudo reproducir")
            }
        return append(
            productId = original.productId,
            locationId = original.locationId,
            type = draft.type,
            quantity = draft.quantity,
            unitCost = original.unitCost,
            totalCost = -original.totalCost,
            after = after,
            reason = null,
            reverses = original.id,
            source = original.source,
            at = at,
            date = date,
        ).id
    }

    private suspend fun append(
        productId: Long,
        locationId: Long,
        type: StockMovementType,
        quantity: QuantityMilli,
        unitCost: Money,
        totalCost: Money,
        after: CostState,
        reason: StockAdjustmentReason?,
        reverses: Long?,
        source: StockMovementSource,
        at: Instant,
        date: BusinessDate,
    ): RecordedMovement {
        val book = book(productId)
        val draft =
            StockMovementDraft(
                key = StockKey(productId, locationId, lotId = null),
                type = type,
                quantity = quantity,
                reversesMovementId = reverses,
            )
        val id =
            ledger.append(
                NewStockMovement(
                    movement = draft,
                    reason = reason,
                    unitCost = unitCost,
                    totalCost = totalCost,
                    balanceAfter = after.onHand,
                    averageCostAfter = after.averageCost,
                    stockValueAfter = after.stockValue,
                    source = source,
                    note = null,
                    occurredAt = at,
                    businessDate = date,
                    createdAt = at,
                ),
            )
        book.movements += CostMovement(id, type, quantity, unitCost, totalCost, reverses)
        book.state = after
        return RecordedMovement(
            id,
            productId,
            locationId,
            type,
            quantity,
            unitCost,
            totalCost,
            source,
        )
    }

    private fun book(productId: Long): Book =
        requireNotNull(books[productId]) { "El producto no lleva libro de stock" }

    private class Book(val initialCost: Money) {
        var state: CostState = CostState.initial(initialCost)
        val movements = ArrayList<CostMovement>()
    }
}

/** Una fila ya escrita en el libro, con lo que hace falta para anularla después. */
internal class RecordedMovement(
    val id: Long,
    val productId: Long,
    val locationId: Long,
    val type: StockMovementType,
    val quantity: QuantityMilli,
    val unitCost: Money,
    val totalCost: Money,
    val source: StockMovementSource,
) {
    fun asMovement(): StockMovement = StockMovement(
        id = id,
        key = StockKey(productId, locationId, lotId = null),
        type = type,
        quantity = quantity,
        reversesMovementId = null,
    )
}

/**
 * El valor de una cuenta que no debía desbordar ni pasar de su tope. Con los rangos del generador
 * no pasa: si pasa, el generador está mal escrito y se detiene, sin guardar la transacción.
 */
internal fun <T> checked(result: Checked<T>): T = when (result) {
    is Checked.Value -> result.value
    Checked.Overflow -> error("Una cuenta del generador desbordó")
    is Checked.AboveLimit -> error("Una cifra del generador pasó de su tope")
}
