package io.github.yesidrobayo.shopledger.domain.usecase

import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.BusinessTimeZone
import io.github.yesidrobayo.shopledger.domain.model.Limit
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.purchase.NewPurchase
import io.github.yesidrobayo.shopledger.domain.model.purchase.NewPurchaseLine
import io.github.yesidrobayo.shopledger.domain.model.stock.NewStockMovement
import io.github.yesidrobayo.shopledger.domain.model.stock.StockKey
import io.github.yesidrobayo.shopledger.domain.model.stock.StockMovementDraft
import io.github.yesidrobayo.shopledger.domain.model.stock.StockMovementSource
import io.github.yesidrobayo.shopledger.domain.model.stock.StockMovementType
import io.github.yesidrobayo.shopledger.domain.model.sum
import io.github.yesidrobayo.shopledger.domain.port.Clock
import io.github.yesidrobayo.shopledger.domain.port.PurchaseRepository
import io.github.yesidrobayo.shopledger.domain.port.StockLedgerRepository
import io.github.yesidrobayo.shopledger.domain.port.StorageFailure
import io.github.yesidrobayo.shopledger.domain.port.StorageFailureException
import io.github.yesidrobayo.shopledger.domain.port.TransactionRunner
import io.github.yesidrobayo.shopledger.domain.port.TxResult

/** Una línea que la persona escribe en la compra falsa: producto, cantidad y lo que pagó. */
internal data class RequestedLine(
    val productId: Long,
    val quantity: QuantityMilli,
    val total: Money,
)

/** Resultado de [FakeRegisterPurchaseUseCase]. */
internal sealed interface FakePurchaseResult {
    data class Success(val purchaseId: Long) : FakePurchaseResult

    data object EmptyPurchase : FakePurchaseResult

    data object TotalExceedsLimit : FakePurchaseResult

    data class StorageFailed(val failure: StorageFailure) : FakePurchaseResult
}

/**
 * Un caso de uso de mentira con la forma de los reales: escribe en varias tablas y por varios
 * puertos, y todo va dentro de una sola transacción. Una regla de negocio rechaza con `Rollback`, y
 * una falla de la base se vuelve un resultado.
 *
 * No calcula el costo como la app: solo suma lo pagado al valor del stock. Aquí se prueba la
 * transacción, no el costo.
 */
internal class FakeRegisterPurchaseUseCase(
    private val transactions: TransactionRunner,
    private val purchases: PurchaseRepository,
    private val ledger: StockLedgerRepository,
    private val clock: Clock,
    private val zone: BusinessTimeZone,
) {
    suspend operator fun invoke(lines: List<RequestedLine>): FakePurchaseResult = try {
        transactions.write { register(lines) }
    } catch (failure: StorageFailureException) {
        FakePurchaseResult.StorageFailed(failure.failure)
    }

    private suspend fun register(lines: List<RequestedLine>): TxResult<FakePurchaseResult> {
        if (lines.isEmpty()) return TxResult.Rollback(FakePurchaseResult.EmptyPurchase)
        val total = lines.map { it.total }.sum()
        if (!Limit.DOCUMENT_TOTAL.allows(total.minor)) {
            return TxResult.Rollback(FakePurchaseResult.TotalExceedsLimit)
        }
        val now = clock.now()
        val date = BusinessDate.of(now, zone)
        val purchaseId = purchases.insert(
            NewPurchase(
                supplierId = null,
                invoiceReference = null,
                netTotal = total,
                taxTotal = Money.ZERO,
                total = total,
                costTotal = total,
                amountPaid = total,
                moneyAccountId = null,
                dueDate = null,
                locationId = MAIN_LOCATION,
                foreignCurrency = null,
                occurredAt = now,
                businessDate = date,
                createdAt = now,
            ),
        )
        for (line in lines) {
            val lineId = purchases.insertLine(purchaseId, line.toPurchaseLine())
            // Lee dentro de la transacción: ve las filas que esta misma compra ya agregó.
            val previous = ledger.findByProduct(line.productId).lastOrNull()
            ledger.append(
                NewStockMovement(
                    movement = StockMovementDraft(
                        key = StockKey(line.productId, MAIN_LOCATION, lotId = null),
                        type = StockMovementType.PURCHASE,
                        quantity = line.quantity,
                        reversesMovementId = null,
                    ),
                    reason = null,
                    unitCost = Money.ZERO,
                    totalCost = line.total,
                    balanceAfter = (previous?.balanceAfter ?: QuantityMilli.ZERO) + line.quantity,
                    averageCostAfter = Money.ZERO,
                    stockValueAfter = (previous?.stockValueAfter ?: Money.ZERO) + line.total,
                    source = StockMovementSource(purchaseLineId = lineId),
                    note = null,
                    occurredAt = now,
                    businessDate = date,
                    createdAt = now,
                ),
            )
        }
        return TxResult.Commit(FakePurchaseResult.Success(purchaseId))
    }

    private fun RequestedLine.toPurchaseLine() = NewPurchaseLine(
        productId = productId,
        unitId = BASE_UNIT,
        unitFactor = QuantityMilli.ofUnits(1L),
        enteredQuantity = quantity,
        quantity = quantity,
        unitCost = Money.ZERO,
        lineTotal = total,
        taxRate = BasisPoints.ZERO,
        taxNameSnapshot = null,
        taxAmount = Money.ZERO,
        costAmount = total,
        lotId = null,
        referenceUnitCost = null,
    )

    private companion object {
        const val MAIN_LOCATION = 1L
        const val BASE_UNIT = 1L
    }
}
