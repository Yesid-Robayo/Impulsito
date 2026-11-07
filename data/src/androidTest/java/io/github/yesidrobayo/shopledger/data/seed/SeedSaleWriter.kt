package io.github.yesidrobayo.shopledger.data.seed

import androidx.room3.useReaderConnection
import io.github.yesidrobayo.shopledger.data.DataPorts
import io.github.yesidrobayo.shopledger.data.local.AppDatabase
import io.github.yesidrobayo.shopledger.data.local.money.MoneyMovementEntity
import io.github.yesidrobayo.shopledger.data.local.payment.SalePaymentEntity
import io.github.yesidrobayo.shopledger.data.local.texts
import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.sale.LineStockEffect
import io.github.yesidrobayo.shopledger.domain.model.sale.NewSale
import io.github.yesidrobayo.shopledger.domain.model.sale.NewSaleLine
import io.github.yesidrobayo.shopledger.domain.model.stock.CostState
import io.github.yesidrobayo.shopledger.domain.model.stock.NewStockMovement
import io.github.yesidrobayo.shopledger.domain.model.stock.StockKey
import io.github.yesidrobayo.shopledger.domain.model.stock.StockMovementSource
import io.github.yesidrobayo.shopledger.domain.port.SystemClock
import io.github.yesidrobayo.shopledger.domain.port.TxResult
import io.github.yesidrobayo.shopledger.domain.rule.catalog.LineStockPlan
import io.github.yesidrobayo.shopledger.domain.rule.catalog.LineStockSubject
import io.github.yesidrobayo.shopledger.domain.rule.catalog.StockModeRules
import io.github.yesidrobayo.shopledger.domain.rule.timesQuantity

/**
 * Escribe ventas de diez líneas sobre una base generada, con todo lo que escribe una venta real en
 * una sola transacción: cabecera, líneas, filas del libro con su costo, pago y dinero. Lee los
 * productos dentro de la transacción, como el caso de uso, así que cada venta deja los invariantes
 * de la base intactos. Todo es inventado.
 *
 * Sirve para medir una venta sobre el volumen de referencia y para escribir mientras corre una
 * copia de la base.
 */
internal class SeedSaleWriter private constructor(
    private val database: AppDatabase,
    private val ports: DataPorts,
    private val ids: List<Long>,
    private val methodId: Long,
    private val account: Long,
    private val config: SeedConfig,
) {
    /** Una venta de [LINES] líneas, en su propia transacción. */
    suspend fun write() {
        ports.transactions.write {
            writeSale()
            TxResult.Commit(Unit)
        }
    }

    private suspend fun writeSale() {
        val now = SystemClock.now()
        val date = BusinessDate.of(now, config.zone)
        val quantity = QuantityMilli(UNIT)
        val products = ports.products.findByIds(ids).sortedBy { it.id }
        val lines =
            products.map { product ->
                val state = CostState(product.onHand, product.averageCost, product.stockValue)
                val plan =
                    checked(StockModeRules.planLine(LineStockSubject.Tracked(state, quantity)))
                val exit = requireNotNull(plan as? LineStockPlan.Direct).exit
                Triple(product, exit, checked(product.salePrice.timesQuantity(quantity)))
            }
        val total = lines.fold(Money.ZERO) { sum, line -> sum + line.third }
        val cost = lines.fold(Money.ZERO) { sum, line -> sum + line.second.cost }
        val saleId =
            ports.sales.insert(
                NewSale(
                    netTotal = total,
                    taxTotal = Money.ZERO,
                    total = total,
                    totalCost = cost,
                    totalReplacementCost = Money.ZERO,
                    orderAdjustment = Money.ZERO,
                    tipAmount = Money.ZERO,
                    cashRoundingAmount = Money.ZERO,
                    amountDue = total,
                    customerId = null,
                    priceListId = DEFAULT_PRICE_LIST,
                    locationId = MAIN_LOCATION,
                    cashSessionId = null,
                    quoteId = null,
                    orderId = null,
                    note = null,
                    occurredAt = now,
                    businessDate = date,
                    createdAt = now,
                ),
            )
        for ((product, exit, amount) in lines) {
            val lineId =
                ports.sales.insertLine(
                    saleId,
                    NewSaleLine(
                        productId = product.id,
                        unitId = product.details.baseUnitId,
                        unitFactor = QuantityMilli(UNIT),
                        enteredQuantity = quantity,
                        quantity = quantity,
                        unitPrice = product.salePrice,
                        unitCost = exit.unitCost,
                        lineDiscount = Money.ZERO,
                        orderAdjustmentShare = Money.ZERO,
                        netAmount = amount,
                        taxRate = BasisPoints.ZERO,
                        taxNameSnapshot = null,
                        taxAmount = Money.ZERO,
                        lineTotal = amount,
                        lineCost = exit.cost,
                        unitReplacementCost = Money.ZERO,
                        lineReplacementCost = Money.ZERO,
                        productNameSnapshot = product.details.name,
                        stockEffect = LineStockEffect.DIRECT,
                        parentLineId = null,
                        priceListId = DEFAULT_PRICE_LIST,
                    ),
                )
            ports.stockLedger.append(
                NewStockMovement(
                    movement = exit.saleMovement(StockKey(product.id, MAIN_LOCATION, lotId = null)),
                    reason = null,
                    unitCost = exit.unitCost,
                    totalCost = -exit.cost,
                    balanceAfter = exit.stateAfter.onHand,
                    averageCostAfter = exit.stateAfter.averageCost,
                    stockValueAfter = exit.stateAfter.stockValue,
                    source = StockMovementSource(saleLineId = lineId),
                    note = null,
                    occurredAt = now,
                    businessDate = date,
                    createdAt = now,
                ),
            )
        }
        val paymentId =
            database.salePaymentDao().insert(
                SalePaymentEntity(
                    saleId = saleId,
                    paymentMethodId = methodId,
                    methodNameSnapshot = "Efectivo",
                    methodKindSnapshot = "CASH",
                    amount = total.minor,
                    feeAmount = 0L,
                    settlementDate = date.toString(),
                    moneyAccountId = account,
                    tenderedAmount = null,
                    changeAmount = null,
                    tenderCurrencyCode = null,
                    tenderCurrencyDecimals = null,
                    tenderAmount = null,
                    fxRateMicro = null,
                    createdAt = now.toEpochMilliseconds(),
                ),
            )
        val balance = database.moneyMovementDao().lastBalanceAfter(account) ?: 0L
        database.moneyMovementDao().insert(
            MoneyMovementEntity(
                moneyAccountId = account,
                type = "SALE_RECEIPT",
                amount = total.minor,
                balanceAfter = balance + total.minor,
                availableDate = date.toString(),
                transferGroupId = null,
                fxRateMicro = null,
                salePaymentId = paymentId,
                customerLedgerEntryId = null,
                supplierLedgerEntryId = null,
                expenseId = null,
                purchaseId = null,
                saleReturnId = null,
                purchaseReturnId = null,
                cashSessionId = null,
                reversesMovementId = null,
                note = null,
                occurredAt = now.toEpochMilliseconds(),
                businessDate = date.toString(),
                createdAt = now.toEpochMilliseconds(),
            ),
        )
        database.moneyAccountDao().setBalance(
            account,
            balance + total.minor,
            now.toEpochMilliseconds(),
        )
    }

    companion object {
        /** Líneas de cada venta. */
        const val LINES = 10

        private const val UNIT = 1_000L
        private const val MAIN_LOCATION = 1L
        private const val DEFAULT_PRICE_LIST = 1L

        private const val TEN_PRODUCTS =
            "SELECT CAST(id AS TEXT) FROM products WHERE stock_mode = 'TRACKED' " +
                "AND archived_at IS NULL AND base_unit_id = " +
                "(SELECT id FROM units WHERE system_key = 'UNIT') ORDER BY id LIMIT 10"

        /**
         * Prepara las ventas sobre [database], abierta sobre una base generada con [config]: toma
         * diez productos con stock y la cuenta del efectivo.
         */
        suspend fun on(
            database: AppDatabase,
            ports: DataPorts,
            config: SeedConfig,
        ): SeedSaleWriter {
            val ids =
                database.useReaderConnection { connection ->
                    connection.texts(TEN_PRODUCTS).map { it.toLong() }
                }
            check(ids.size == LINES) { "La base generada no tiene diez productos con stock" }
            val cash = requireNotNull(database.paymentMethodDao().findBySystemKey("CASH"))
            val account = requireNotNull(cash.moneyAccountId)
            return SeedSaleWriter(database, ports, ids, cash.id, account, config)
        }
    }
}
