package io.github.yesidrobayo.shopledger.domain.usecase.purchase

import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.purchase.PurchaseLineAmount
import io.github.yesidrobayo.shopledger.domain.model.purchase.PurchaseLineRequest
import io.github.yesidrobayo.shopledger.domain.model.purchase.PurchaseRequest
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleLineRequest
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleRequest
import io.github.yesidrobayo.shopledger.domain.model.stock.CostState
import io.github.yesidrobayo.shopledger.domain.model.stock.NewStockMovement
import io.github.yesidrobayo.shopledger.domain.model.stock.StockKey
import io.github.yesidrobayo.shopledger.domain.model.stock.StockMovementDraft
import io.github.yesidrobayo.shopledger.domain.model.stock.StockMovementRecord
import io.github.yesidrobayo.shopledger.domain.model.stock.StockMovementSource
import io.github.yesidrobayo.shopledger.domain.model.stock.StockMovementType
import io.github.yesidrobayo.shopledger.domain.model.valueOrNull
import io.github.yesidrobayo.shopledger.domain.port.TxResult
import io.github.yesidrobayo.shopledger.domain.rule.WeightedAverageCost
import io.github.yesidrobayo.shopledger.domain.usecase.sale.RegisterSaleResult
import io.github.yesidrobayo.shopledger.domain.usecase.sale.SaleWorld
import io.github.yesidrobayo.shopledger.domain.usecase.sale.method
import kotlin.time.Instant

/** Una línea de [milli] milésimas escrita con su costo unitario. */
internal fun atUnitCost(productId: Long, milli: Long, cost: Long) =
    PurchaseLineRequest(productId, QuantityMilli(milli), PurchaseLineAmount.UnitCost(Money(cost)))

/** Una línea de [milli] milésimas escrita con el total que trae el documento. */
internal fun forTotal(productId: Long, milli: Long, total: Long) =
    PurchaseLineRequest(productId, QuantityMilli(milli), PurchaseLineAmount.LineTotal(Money(total)))

/** Compra [lines] y devuelve el id, o falla la prueba si la compra no se registró. */
internal suspend fun SaleWorld.buy(
    vararg lines: PurchaseLineRequest,
    supplierId: Long? = null,
    date: BusinessDate? = null,
): Long {
    val result = registerPurchase()(PurchaseRequest(lines.toList(), supplierId, date = date))
    return (result as RegisterPurchaseResult.Registered).purchaseId
}

/** Vende [milli] milésimas de [productId] en efectivo y devuelve el id de la venta. */
internal suspend fun SaleWorld.sell(productId: Long, milli: Long): Long {
    if (SaleWorld.CASH !in tables.methods) addMethod(method())
    val request =
        SaleRequest(listOf(SaleLineRequest(productId, QuantityMilli(milli))), SaleWorld.CASH)
    return (registerSale()(request) as RegisterSaleResult.Registered).saleId
}

/**
 * Un inventario inicial de [milli] milésimas por [total], escrito en el libro con el costo que
 * calcula la regla del costo promedio, como lo haría su caso de uso.
 */
internal suspend fun SaleWorld.open(productId: Long, milli: Long, total: Long) {
    transactions.write {
        val product = checkNotNull(products.findById(productId))
        val before = CostState(product.onHand, product.averageCost, product.stockValue)
        val after = checkNotNull(
            WeightedAverageCost.entry(before, QuantityMilli(milli), Money(total)).valueOrNull(),
        )
        ledger.append(
            NewStockMovement(
                movement = StockMovementDraft(
                    StockKey(productId, SaleWorld.MAIN_LOCATION, null),
                    StockMovementType.OPENING_BALANCE,
                    QuantityMilli(milli),
                    reversesMovementId = null,
                ),
                reason = null,
                unitCost = after.averageCost,
                totalCost = Money(total),
                balanceAfter = after.onHand,
                averageCostAfter = after.averageCost,
                stockValueAfter = after.stockValue,
                source = StockMovementSource.NONE,
                note = null,
                occurredAt = NOW,
                businessDate = TODAY,
                createdAt = NOW,
            ),
        )
        TxResult.Commit(Unit)
    }
}

/** Las filas del libro, en orden. */
internal fun SaleWorld.movements(): List<StockMovementRecord> =
    tables.movements.values.sortedBy { it.id }

/** El costo de un producto como lo guarda: saldo, costo promedio y valor del stock. */
internal fun SaleWorld.costOf(productId: Long): CostState =
    tables.products.getValue(productId).let { CostState(it.onHand, it.averageCost, it.stockValue) }

internal fun cost(milli: Long, average: Long, value: Long) =
    CostState(QuantityMilli(milli), Money(average), Money(value))

internal val NOW: Instant = Instant.fromEpochMilliseconds(SaleWorld.NOW_MILLIS)

/** Lunes 2026-11-02 en la zona de las pruebas. */
internal val TODAY: BusinessDate = BusinessDate(kotlinx.datetime.LocalDate(2026, 11, 2))
