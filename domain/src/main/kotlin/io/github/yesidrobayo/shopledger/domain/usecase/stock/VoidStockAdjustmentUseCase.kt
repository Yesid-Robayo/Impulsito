package io.github.yesidrobayo.shopledger.domain.usecase.stock

import io.github.yesidrobayo.shopledger.domain.model.stock.CostMovement
import io.github.yesidrobayo.shopledger.domain.model.stock.NewStockMovement
import io.github.yesidrobayo.shopledger.domain.model.stock.StockMovementSource
import io.github.yesidrobayo.shopledger.domain.port.Clock
import io.github.yesidrobayo.shopledger.domain.port.LowStockAlertRepository
import io.github.yesidrobayo.shopledger.domain.port.ProductRepository
import io.github.yesidrobayo.shopledger.domain.port.StockLedgerRepository
import io.github.yesidrobayo.shopledger.domain.port.TransactionRunner
import io.github.yesidrobayo.shopledger.domain.port.TxResult
import io.github.yesidrobayo.shopledger.domain.rule.CostReplayResult
import io.github.yesidrobayo.shopledger.domain.rule.WeightedAverageCost
import io.github.yesidrobayo.shopledger.domain.rule.stock.StockAdjustmentRules
import io.github.yesidrobayo.shopledger.domain.rule.stock.StockCompensation
import io.github.yesidrobayo.shopledger.domain.rule.stock.StockMovementRules
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.stamp
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.storageSafe
import io.github.yesidrobayo.shopledger.domain.usecase.region.BusinessCalendarSource

/**
 * Anula un ajuste o un inventario inicial mal escrito, en una sola transacción.
 *
 * Es la vía correcta ante un error de digitación: corregir con otro ajuste crearía una merma que
 * nunca existió. La fila original no se toca. Se agrega su compensatorio, con la cantidad y el
 * valor opuestos, que apunta a ella; los dos dejan de contar como merma, también en la fecha del
 * original.
 *
 * El costo promedio y el valor del stock salen de reproducir el libro efectivo del producto desde
 * el principio, como si la fila anulada no hubiera existido: una resta arrastraría el redondeo y
 * dependería del orden de las anulaciones. Nada ya guardado se recalcula. El compensatorio lleva la
 * fecha de hoy, porque el libro es cronológico.
 *
 * En la misma transacción evalúa la alerta de stock bajo, como cualquier cambio de stock.
 */
class VoidStockAdjustmentUseCase(
    private val transactions: TransactionRunner,
    private val products: ProductRepository,
    private val ledger: StockLedgerRepository,
    private val alerts: LowStockAlertRepository,
    private val calendar: BusinessCalendarSource,
    private val clock: Clock,
) {
    suspend operator fun invoke(movementId: Long): VoidAdjustmentResult =
        storageSafe(VoidAdjustmentResult::StorageFailed) {
            transactions.write { void(movementId) }
        }

    private suspend fun void(movementId: Long): TxResult<VoidAdjustmentResult> {
        val original = ledger.findById(movementId) ?: return reject(VoidAdjustmentResult.NotFound)
        if (!StockAdjustmentRules.isVoidable(original.movement.type)) {
            return reject(VoidAdjustmentResult.NotVoidable)
        }
        val compensated = ledger.isCompensated(movementId)
        val draft = when (
            val compensation = StockMovementRules.compensationOf(original.movement, compensated)
        ) {
            is StockCompensation.Ready -> compensation.draft

            StockCompensation.AlreadyCompensated ->
                return reject(VoidAdjustmentResult.AlreadyVoided)

            // Un ajuste y un inventario inicial siempre tienen su compensatorio.
            StockCompensation.NotCompensable -> error("Un ajuste siempre se anula")
        }
        val productId = original.movement.key.productId
        val product = checkNotNull(products.findById(productId)) {
            "Una fila del libro siempre tiene su producto"
        }
        val time = calendar.stamp(clock.now())
            ?: return reject(VoidAdjustmentResult.UnknownTimeZone)
        val totalCost = -original.totalCost
        // El id definitivo lo da la base; para reproducir basta uno mayor que todos, y el
        // compensatorio queda fuera del libro efectivo junto con la fila que anula.
        val provisional = CostMovement(
            id = Long.MAX_VALUE,
            type = draft.type,
            quantity = draft.quantity,
            unitCost = original.unitCost,
            totalCost = totalCost,
            reversesMovementId = original.id,
        )
        val book = ledger.findByProduct(productId).map { it.toCostMovement() }
        val replay = WeightedAverageCost.replayCostState(product.initialCost, book + provisional)
        val state = when (replay) {
            is CostReplayResult.Replayed -> replay.state

            is CostReplayResult.InvalidMovement, is CostReplayResult.Overflow ->
                return reject(VoidAdjustmentResult.LedgerDamaged)
        }
        val id = ledger.append(
            NewStockMovement(
                movement = draft,
                reason = null,
                unitCost = original.unitCost,
                totalCost = totalCost,
                balanceAfter = product.onHand + draft.quantity,
                averageCostAfter = state.averageCost,
                stockValueAfter = state.stockValue,
                // Un compensatorio de ajuste no sale de ningún documento, ni siquiera de un conteo.
                source = StockMovementSource.NONE,
                note = null,
                occurredAt = time.occurredAt,
                businessDate = time.businessDate,
                createdAt = time.occurredAt,
            ),
        )
        alerts.applyAfterMovement(product, listOf(draft), time)
        return TxResult.Commit(VoidAdjustmentResult.Voided(id))
    }

    private fun reject(result: VoidAdjustmentResult): TxResult<VoidAdjustmentResult> =
        TxResult.Rollback(result)
}
