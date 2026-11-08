package io.github.yesidrobayo.shopledger.domain.usecase.catalog

import io.github.yesidrobayo.shopledger.domain.model.Checked
import io.github.yesidrobayo.shopledger.domain.model.Limit
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductFormIssue
import io.github.yesidrobayo.shopledger.domain.model.catalog.SystemRow
import io.github.yesidrobayo.shopledger.domain.model.stock.CostState
import io.github.yesidrobayo.shopledger.domain.port.Clock
import io.github.yesidrobayo.shopledger.domain.port.LowStockAlertRepository
import io.github.yesidrobayo.shopledger.domain.port.ProductRepository
import io.github.yesidrobayo.shopledger.domain.port.StockLedgerRepository
import io.github.yesidrobayo.shopledger.domain.port.StorageFailure
import io.github.yesidrobayo.shopledger.domain.port.SystemRowRepository
import io.github.yesidrobayo.shopledger.domain.port.TransactionRunner
import io.github.yesidrobayo.shopledger.domain.port.TxResult
import io.github.yesidrobayo.shopledger.domain.port.UnitRepository
import io.github.yesidrobayo.shopledger.domain.rule.catalog.ProductFormRules
import io.github.yesidrobayo.shopledger.domain.rule.catalog.StockModeRules
import io.github.yesidrobayo.shopledger.domain.rule.stock.LowStockEvaluator
import io.github.yesidrobayo.shopledger.domain.rule.stock.LowStockSubject
import io.github.yesidrobayo.shopledger.domain.rule.stock.LowStockTransition
import io.github.yesidrobayo.shopledger.domain.rule.stock.OpeningBalanceRule
import io.github.yesidrobayo.shopledger.domain.rule.stock.StockLedger
import io.github.yesidrobayo.shopledger.domain.usecase.region.BusinessCalendarSource
import io.github.yesidrobayo.shopledger.domain.usecase.region.CurrencyLock

/** Cómo termina registrar un inventario inicial. Salvo [Registered], no se escribió nada. */
sealed interface OpeningBalanceResult {
    /** Quedó en el libro como la fila [movementId]. */
    data class Registered(val movementId: Long) : OpeningBalanceResult

    data object NotFound : OpeningBalanceResult

    /** Un producto archivado no recibe stock: hay que reactivarlo antes. */
    data object ProductArchived : OpeningBalanceResult

    /** El producto no lleva stock propio: no tiene libro donde anotarlo. */
    data object NotTracked : OpeningBalanceResult

    /**
     * El producto ya tiene filas vigentes en su libro. Lo que entre ahora es una compra o una
     * corrección de conteo, no el stock con que empezó.
     */
    data object AlreadyHasMovements : OpeningBalanceResult

    /** La cantidad o el costo no se pueden registrar, por estos motivos. */
    data class Invalid(val issues: Set<ProductFormIssue>) : OpeningBalanceResult

    /** Dejaría el stock o su valor por encima de [limit]. */
    data class AboveLimit(val limit: Limit) : OpeningBalanceResult

    /** La zona del negocio no se reconoce, y sin ella no hay fecha de negocio que guardar. */
    data object UnknownTimeZone : OpeningBalanceResult

    /** El costo es un monto, y la moneda del negocio sigue sin confirmar. */
    data object CurrencyUnconfirmed : OpeningBalanceResult

    data class StorageFailed(val failure: StorageFailure) : OpeningBalanceResult
}

/**
 * Registra el stock con que empezó un producto que ya existía, desde el ajuste de stock.
 *
 * Solo se ofrece mientras el libro efectivo del producto está vacío: sin filas, o con todas
 * anuladas. Entra como una entrada de costo promedio, con su propia fila, y no es compra ni gasto,
 * así que no toca las ganancias. Si el producto tenía una alerta activa y queda por encima de su
 * mínimo, la resuelve en la misma transacción.
 */
class RegisterOpeningBalanceUseCase(
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
    /** Registra [quantity] milésimas a [unitCost] por unidad entera de la unidad base. */
    suspend operator fun invoke(
        productId: Long,
        quantity: QuantityMilli,
        unitCost: Money,
    ): OpeningBalanceResult = storageSafe(OpeningBalanceResult::StorageFailed) {
        transactions.write { register(productId, quantity, unitCost) }
    }

    private suspend fun register(
        productId: Long,
        quantity: QuantityMilli,
        unitCost: Money,
    ): TxResult<OpeningBalanceResult> {
        val product = products.findById(productId) ?: return reject(OpeningBalanceResult.NotFound)
        if (product.isArchived) return reject(OpeningBalanceResult.ProductArchived)
        val details = product.details
        if (!StockModeRules.hasLedger(details.stockMode)) {
            return reject(OpeningBalanceResult.NotTracked)
        }
        val movements = ledger.findByProduct(productId).map { it.movement }
        if (StockLedger.effective(movements).isNotEmpty()) {
            return reject(OpeningBalanceResult.AlreadyHasMovements)
        }
        val unit = checkNotNull(units.findById(details.baseUnitId)) { "Falta la unidad base" }
        val issues = ProductFormRules.openingIssues(quantity, unitCost, unit.allowsFraction)
        if (issues.isNotEmpty()) return reject(OpeningBalanceResult.Invalid(issues))
        val time =
            calendar.stamp(clock.now()) ?: return reject(OpeningBalanceResult.UnknownTimeZone)
        // Un costo en cero no es dinero: no fija ninguna moneda.
        if (unitCost != Money.ZERO && !currencyLock.lock(time.occurredAt)) {
            return reject(OpeningBalanceResult.CurrencyUnconfirmed)
        }
        val before = CostState(product.onHand, product.averageCost, product.stockValue)
        val entry = when (val checked = OpeningBalanceRule.entry(before, quantity, unitCost)) {
            is Checked.Value -> checked.value

            // Con los topes ya revisados no se desborda; si pasara, es por encima de todo tope.
            Checked.Overflow -> return reject(OpeningBalanceResult.AboveLimit(Limit.DOCUMENT_TOTAL))

            is Checked.AboveLimit -> return reject(OpeningBalanceResult.AboveLimit(checked.limit))
        }
        val location = systemRows.requireId(SystemRow.MAIN_LOCATION)
        val movement = openingMovement(productId, location, quantity, unitCost, entry, time)
        val movementId = ledger.append(movement)
        val activeAlert = alerts.findActiveByProducts(listOf(productId)).firstOrNull()
        val subject = LowStockSubject(
            productId = productId,
            stockMode = details.stockMode,
            isArchived = false,
            minStock = details.minStock,
            onHandBefore = product.onHand,
            hasActiveAlert = activeAlert != null,
        )
        when (
            LowStockEvaluator.evaluateTransaction(
                listOf(subject),
                listOf(movement.movement),
            )[productId]
        ) {
            LowStockTransition.Rearmed ->
                alerts.resolve(checkNotNull(activeAlert).id, time.occurredAt)

            // Una entrada nunca cruza el mínimo hacia abajo: no hay alerta que crear.
            is LowStockTransition.Crossed, LowStockTransition.Unchanged,
            LowStockTransition.NotWatched, null,
            -> Unit
        }
        return TxResult.Commit(OpeningBalanceResult.Registered(movementId))
    }

    private fun reject(result: OpeningBalanceResult): TxResult<OpeningBalanceResult> =
        TxResult.Rollback(result)
}
