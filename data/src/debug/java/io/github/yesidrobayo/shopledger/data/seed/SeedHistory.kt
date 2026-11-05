package io.github.yesidrobayo.shopledger.data.seed

import io.github.yesidrobayo.shopledger.data.DataPorts
import io.github.yesidrobayo.shopledger.data.local.AppDatabase
import io.github.yesidrobayo.shopledger.data.local.payment.SalePaymentEntity
import io.github.yesidrobayo.shopledger.data.local.sale.SaleLineComponentEntity
import io.github.yesidrobayo.shopledger.data.repository.toStoredMillis
import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.BusinessTimeZone
import io.github.yesidrobayo.shopledger.domain.model.Limit
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.VoidReason
import io.github.yesidrobayo.shopledger.domain.model.Voiding
import io.github.yesidrobayo.shopledger.domain.model.catalog.NewPriceChange
import io.github.yesidrobayo.shopledger.domain.model.catalog.PriceChangeSource
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductStockMode
import io.github.yesidrobayo.shopledger.domain.model.expense.NewExpense
import io.github.yesidrobayo.shopledger.domain.model.purchase.NewPurchase
import io.github.yesidrobayo.shopledger.domain.model.purchase.NewPurchaseLine
import io.github.yesidrobayo.shopledger.domain.model.sale.NewSale
import io.github.yesidrobayo.shopledger.domain.model.sale.NewSaleLine
import io.github.yesidrobayo.shopledger.domain.model.stock.CostState
import io.github.yesidrobayo.shopledger.domain.model.stock.LowStockNotificationStatus
import io.github.yesidrobayo.shopledger.domain.model.stock.NewLowStockAlert
import io.github.yesidrobayo.shopledger.domain.model.stock.StockAdjustmentReason
import io.github.yesidrobayo.shopledger.domain.model.stock.StockMovementSource
import io.github.yesidrobayo.shopledger.domain.model.stock.StockMovementType
import io.github.yesidrobayo.shopledger.domain.port.TxResult
import io.github.yesidrobayo.shopledger.domain.rule.catalog.ComponentLeaf
import io.github.yesidrobayo.shopledger.domain.rule.catalog.LeafStockPlan
import io.github.yesidrobayo.shopledger.domain.rule.catalog.LineStockPlan
import io.github.yesidrobayo.shopledger.domain.rule.catalog.LineStockSubject
import io.github.yesidrobayo.shopledger.domain.rule.catalog.StockModeRules
import io.github.yesidrobayo.shopledger.domain.rule.mulDivRound
import io.github.yesidrobayo.shopledger.domain.rule.stock.LowStockEvaluator
import io.github.yesidrobayo.shopledger.domain.rule.timesQuantity
import java.util.PriorityQueue
import kotlin.time.Instant
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant

/**
 * La historia del negocio inventado: día por día, ventas, anulaciones, compras, gastos, cambios
 * de precio, ajustes y pagos de clientes y a proveedores.
 *
 * **Una transacción por día.** Pasar cada venta por su propia transacción tardaría demasiado con
 * 100.000 ventas, y lo que importa aquí son las filas, no la espera de cada confirmación. Por eso
 * cada día es un lote: una sola transacción del runner, con las mismas reglas que la app. Cada
 * venta lee su costo con [StockModeRules], cada fila de stock pasa por el repositorio del libro y
 * cada documento lleva totales que cuadran con sus líneas. Si algo falla, el día entero se deshace
 * y el generador se detiene.
 *
 * Lo que cada día hace sale del azar con la semilla, y se ejecuta en orden de hora. Más ventas los
 * fines de semana y al cerrar el mes, y algunas cerca de medianoche en la zona del negocio.
 */
internal class SeedHistory(
    private val database: AppDatabase,
    private val ports: DataPorts,
    private val stock: SeedStock,
    private val books: SeedBooks,
    private val random: SeedRandom,
    private val profile: SeedProfile,
    private val catalog: SeedCatalog,
    private val clock: SeedClock,
    private val zone: BusinessTimeZone,
) {
    private val products = catalog.products
    private val mainSales = sampler(products.filter { it.id in catalog.saleWeights })
    private val secondSales = sampler(products.filter { it.dual && it.id in catalog.saleWeights })
    private val mainPurchases =
        sampler(products.filter { it.tracked && it.stocked && it.restocked })
    private val secondPurchases =
        sampler(products.filter { it.dual && it.stocked && it.restocked })
    private val customers = WeightedSampler(catalog.customers.map { it to it.weight })
    private val pending = HashMap<Long, LongArray>()
    private val customerPayments = HashMap<LocalDate, MutableList<Long>>()
    private val customersWithPayment = HashSet<Long>()
    private val supplierPayments = HashMap<LocalDate, MutableList<SupplierPayment>>()
    private val linesPerPurchase = maxOf(1, profile.purchaseLines / profile.purchases)
    private var sequence = 0L
    private var invoices = 0L
    private lateinit var firstDay: LocalDate

    /** Escribe la historia de [start] a [end], los dos incluidos. */
    suspend fun run(start: LocalDate, end: LocalDate) {
        firstDay = start
        val days = generateSequence(start) { it.plus(1, DateTimeUnit.DAY) }
            .takeWhile { it <= end }
            .toList()
        val sales = distribute(profile.sales, days.map(::saleWeight))
        val flat = days.map { 1L }
        val purchases = distribute(profile.purchases, flat)
        val expenses = distribute(profile.expenses, flat)
        val priceChanges = distribute(profile.priceChanges, flat)
        val adjustments = distribute(profile.adjustments, flat)
        days.forEachIndexed { index, day ->
            val queue = PriorityQueue(compareBy<Event>({ it.at }, { it.sequence }))
            repeat(sales[index]) { queue += event(day, saleSecond(), EventKind.SALE) }
            repeat(purchases[index]) {
                queue +=
                    event(day, between(PURCHASE_HOURS), EventKind.PURCHASE)
            }
            repeat(expenses[index]) {
                queue += event(day, between(EXPENSE_HOURS), EventKind.EXPENSE)
            }
            repeat(priceChanges[index]) {
                queue += event(day, between(PRICE_HOURS), EventKind.PRICE)
            }
            repeat(adjustments[index]) {
                queue +=
                    event(day, between(CLOSING_HOURS), EventKind.ADJUSTMENT)
            }
            customerPayments.remove(day)?.forEach { customer ->
                queue += event(day, between(PAYMENT_HOURS), EventKind.CUSTOMER_PAYMENT, customer)
            }
            supplierPayments.remove(day)?.forEach { payment ->
                queue +=
                    event(
                        day,
                        between(PAYMENT_HOURS),
                        EventKind.SUPPLIER_PAYMENT,
                        payment = payment,
                    )
            }
            if (queue.isNotEmpty()) writeDay(queue)
        }
    }

    private suspend fun writeDay(queue: PriorityQueue<Event>) {
        ports.transactions.write {
            while (queue.isNotEmpty()) {
                val event = queue.poll() ?: break
                clock.current = event.at
                val date = BusinessDate.of(event.at, zone)
                when (event.kind) {
                    EventKind.SALE -> sale(event.at, date)?.let { queue += it }

                    EventKind.VOID -> voidSale(requireNotNull(event.sale), event.at, date)

                    EventKind.PURCHASE -> purchase(event.at, date)

                    EventKind.EXPENSE -> expense(event.at, date)

                    EventKind.PRICE -> priceChange(event.at, date)

                    EventKind.ADJUSTMENT -> adjustment(event.at, date)

                    EventKind.CUSTOMER_PAYMENT -> customerPayment(event.customerId, event.at, date)

                    EventKind.SUPPLIER_PAYMENT ->
                        supplierPayment(requireNotNull(event.payment), event.at, date)
                }
            }
            books.flush(clock.current)
            TxResult.Commit(Unit)
        }
    }

    /**
     * Una venta con sus líneas, sus filas de stock, sus pagos, su dinero, su deuda y sus puntos.
     * Devuelve su anulación, si esta venta es de las que se anulan.
     */
    private suspend fun sale(at: Instant, date: BusinessDate): Event? {
        val second = !secondSales.isEmpty && random.chance(SECOND_LOCATION_PER_MILLE)
        val location = if (second) catalog.secondLocationId else catalog.mainLocationId
        val sampler = if (second) secondSales else mainSales
        val customer = if (random.chance(CUSTOMER_PER_MILLE)) customers.pick(random) else null
        val chosen = LinkedHashSet<SeedProduct>()
        val wanted = lineCount()
        var tries = 0
        while (chosen.size < wanted &&
            tries++ < wanted * TRIES_PER_LINE
        ) {
            chosen += sampler.pick(random)
        }

        val states = HashMap<Long, CostState>()
        val lines = chosen.map { product -> planLine(product, states) }
        applyOrderAdjustment(lines)
        val netTotal = lines.sumOf { it.net }
        val sale =
            NewSale(
                netTotal = Money(netTotal),
                taxTotal = Money.ZERO,
                total = Money(netTotal),
                totalCost = Money(lines.sumOf { it.plan.lineCost.minor }),
                totalReplacementCost = Money(lines.sumOf { it.replacementCost }),
                orderAdjustment = Money(lines.sumOf { it.share }),
                tipAmount = Money.ZERO,
                cashRoundingAmount = Money.ZERO,
                amountDue = Money(netTotal),
                customerId = customer?.id,
                priceListId = catalog.priceListId,
                locationId = location,
                cashSessionId = null,
                quoteId = null,
                orderId = null,
                note = null,
                occurredAt = at,
                businessDate = date,
                createdAt = at,
            )
        val saleId = ports.sales.insert(sale)
        val movements = ArrayList<RecordedMovement>()
        for (line in lines) movements += writeLine(saleId, line, location, at, date)
        val record = SaleRecord(saleId, customer?.id, movements)
        pay(record, netTotal, at, date)
        if (customer != null) {
            val points = netTotal / AMOUNT_PER_POINT
            if (points > 0L) {
                record.points =
                    books.points(customer.id, EARN, points, saleId, reverses = null, at, date)
            }
        }
        if (!random.chance(VOID_PER_MILLE)) return null
        val voidAt = Instant.fromEpochSeconds(
            at.epochSeconds + random.between(MIN_VOID_SECONDS, MAX_VOID_SECONDS),
        )
        return Event(voidAt, nextSequence(), EventKind.VOID, sale = record)
    }

    /** Planea una línea con la regla de su modo de stock, sobre los estados de esta venta. */
    private fun planLine(product: SeedProduct, states: HashMap<Long, CostState>): PlannedLine {
        val quantity = QuantityMilli(product.unit.saleQuantity(random))
        fun stateOf(id: Long): CostState = states[id] ?: stock.state(id)
        val subject =
            when (product.mode) {
                ProductStockMode.TRACKED -> LineStockSubject.Tracked(stateOf(product.id), quantity)

                ProductStockMode.UNTRACKED ->
                    LineStockSubject.Untracked(Money(product.fixedUnitCost), quantity)

                ProductStockMode.COMPONENTS -> {
                    val leaves =
                        product.components.map { component ->
                            val leafQuantity =
                                QuantityMilli(
                                    checked(
                                        mulDivRound(component.quantityMilli, quantity.milli, MILLI),
                                    ),
                                )
                            if (component.product.tracked) {
                                ComponentLeaf.Tracked(component.product.id, leafQuantity)
                            } else {
                                ComponentLeaf.Untracked(
                                    component.product.id,
                                    leafQuantity,
                                    Money(component.product.fixedUnitCost),
                                )
                            }
                        }
                    val trackedIds = leaves.filterIsInstance<ComponentLeaf.Tracked>().map {
                        it.productId
                    }
                    LineStockSubject.Components(leaves, trackedIds.associateWith(::stateOf))
                }
            }
        val plan = checked(StockModeRules.planLine(subject))
        when (plan) {
            is LineStockPlan.Direct -> states[product.id] = plan.exit.stateAfter
            is LineStockPlan.NoStock -> Unit
            is LineStockPlan.Components -> states.putAll(plan.costStatesAfter)
        }
        val gross = checked(
            Money(product.salePrice).timesQuantity(quantity, Limit.DOCUMENT_TOTAL),
        ).minor
        val discount =
            if (random.chance(
                    LINE_DISCOUNT_PER_MILLE,
                )
            ) {
                checked(mulDivRound(gross, DISCOUNT_BP, BP))
            } else {
                0L
            }
        val unitCost =
            when (plan) {
                is LineStockPlan.Direct -> plan.exit.unitCost.minor

                is LineStockPlan.NoStock -> plan.unitCost.minor

                is LineStockPlan.Components ->
                    minOf(
                        Limit.UNIT_MONEY.max,
                        checked(mulDivRound(plan.lineCost.minor, MILLI, quantity.milli)),
                    )
            }
        val replacement = if (product.tracked) product.replacementCost else 0L
        return PlannedLine(
            product = product,
            quantity = quantity,
            gross = gross,
            discount = discount,
            plan = plan,
            unitCost = unitCost,
            unitReplacementCost = replacement,
            replacementCost = checked(mulDivRound(replacement, quantity.milli, MILLI)),
        )
    }

    /** Un descuento de toda la venta en algunas, repartido entre las líneas por su monto. */
    private fun applyOrderAdjustment(lines: List<PlannedLine>) {
        val after = lines.sumOf { it.gross - it.discount }
        if (after <= 0L || !random.chance(ORDER_ADJUSTMENT_PER_MILLE)) return
        val adjustment = checked(mulDivRound(after, ORDER_DISCOUNT_BP, BP))
        var left = adjustment
        lines.forEachIndexed { index, line ->
            val base = line.gross - line.discount
            val part =
                if (index ==
                    lines.lastIndex
                ) {
                    left
                } else {
                    checked(mulDivRound(adjustment, base, after))
                }
            val share = minOf(part, base, left)
            line.share = -share
            left -= share
        }
    }

    private suspend fun writeLine(
        saleId: Long,
        line: PlannedLine,
        location: Long,
        at: Instant,
        date: BusinessDate,
    ): List<RecordedMovement> {
        val product = line.product
        val lineId =
            ports.sales.insertLine(
                saleId,
                NewSaleLine(
                    productId = product.id,
                    unitId = product.unitId,
                    unitFactor = QuantityMilli(MILLI),
                    enteredQuantity = line.quantity,
                    quantity = line.quantity,
                    unitPrice = Money(product.salePrice),
                    unitCost = Money(line.unitCost),
                    lineDiscount = Money(line.discount),
                    orderAdjustmentShare = Money(line.share),
                    netAmount = Money(line.net),
                    taxRate = BasisPoints.ZERO,
                    taxNameSnapshot = null,
                    taxAmount = Money.ZERO,
                    lineTotal = Money(line.net),
                    lineCost = line.plan.lineCost,
                    unitReplacementCost = Money(line.unitReplacementCost),
                    lineReplacementCost = Money(line.replacementCost),
                    productNameSnapshot = product.name,
                    stockEffect = line.plan.stockEffect,
                    parentLineId = null,
                    priceListId = catalog.priceListId,
                ),
            )
        return when (val plan = line.plan) {
            is LineStockPlan.Direct -> {
                addPending(product.id, location, line.quantity.milli)
                listOf(
                    stock.saleExit(
                        productId = product.id,
                        locationId = location,
                        quantity = plan.exit.quantity,
                        unitCost = plan.exit.unitCost,
                        cost = plan.exit.cost,
                        after = plan.exit.stateAfter,
                        source = StockMovementSource(saleLineId = lineId),
                        at = at,
                        date = date,
                    ),
                )
            }

            is LineStockPlan.NoStock -> emptyList()

            is LineStockPlan.Components -> plan.leaves.mapNotNull { leaf ->
                val componentId =
                    database.saleLineComponentDao().insert(
                        SaleLineComponentEntity(
                            saleLineId = lineId,
                            componentProductId = leaf.productId,
                            quantityMilli = leaf.quantity.milli,
                            tracked = if (leaf.tracked) 1L else 0L,
                            unitCost = leaf.unitCost.minor,
                            lineCost = leaf.lineCost.minor,
                        ),
                    )
                when (leaf) {
                    is LeafStockPlan.Tracked -> {
                        addPending(leaf.productId, location, leaf.quantity.milli)
                        stock.saleExit(
                            productId = leaf.productId,
                            locationId = location,
                            quantity = leaf.exit.quantity,
                            unitCost = leaf.exit.unitCost,
                            cost = leaf.exit.cost,
                            after = leaf.exit.stateAfter,
                            source =
                                StockMovementSource(
                                    saleLineId = lineId,
                                    saleLineComponentId = componentId,
                                ),
                            at = at,
                            date = date,
                        )
                    }

                    is LeafStockPlan.Untracked -> null
                }
            }
        }
    }

    /**
     * Los pagos de la venta, que suman lo que se cobra: a cuenta para casi todo cliente con
     * crédito, a veces dos métodos, y si no, uno solo. Con cuenta de dinero, el cobro entra en ella
     * con su fecha de acreditación, y la comisión sale aparte.
     */
    private suspend fun pay(record: SaleRecord, amountDue: Long, at: Instant, date: BusinessDate) {
        val parts =
            when {
                record.customerId != null && random.chance(ON_ACCOUNT_PER_MILLE) ->
                    listOf(method(ON_ACCOUNT) to amountDue)

                amountDue >= 2L && random.chance(SPLIT_PER_MILLE) -> {
                    val first =
                        maxOf(1L, amountDue * random.between(SPLIT_MIN, SPLIT_MAX) / PERCENT)
                    val other = if (random.chance(HALF)) CARD else TRANSFER
                    listOf(method(CASH) to first, method(other) to amountDue - first)
                }

                else -> listOf(singleMethod() to amountDue)
            }
        for ((method, amount) in parts) {
            val fee = if (method.feeBp > 0L &&
                amount > 0L
            ) {
                checked(mulDivRound(amount, method.feeBp, BP))
            } else {
                0L
            }
            val settlement = date.plusDays(method.settlementDays)
            val tendered =
                if (method.kind == CASH && random.chance(TENDERED_PER_MILLE)) {
                    val extra = random.between(0L, amount / 2)
                    (amount + extra + CASH_STEP - 1) / CASH_STEP * CASH_STEP
                } else {
                    null
                }
            val paymentId =
                database.salePaymentDao().insert(
                    SalePaymentEntity(
                        saleId = record.saleId,
                        paymentMethodId = method.id,
                        methodNameSnapshot = method.name,
                        methodKindSnapshot = method.kind,
                        amount = amount,
                        feeAmount = fee,
                        settlementDate = settlement.toString(),
                        moneyAccountId = method.accountId,
                        tenderedAmount = tendered,
                        changeAmount = tendered?.let { it - amount },
                        tenderCurrencyCode = null,
                        tenderCurrencyDecimals = null,
                        tenderAmount = null,
                        fxRateMicro = null,
                        createdAt = at.toStoredMillis(),
                    ),
                )
            val account = method.accountId
            if (account != null && amount > 0L) {
                record.money +=
                    books.money(
                        MoneyEntry(
                            account,
                            SALE_RECEIPT,
                            amount,
                            settlement,
                            salePaymentId = paymentId,
                        ),
                        at,
                        date,
                    )
                if (fee > 0L) {
                    record.money +=
                        books.money(
                            MoneyEntry(account, FEE, -fee, salePaymentId = paymentId),
                            at,
                            date,
                        )
                }
            }
            val customer = record.customerId
            if (method.kind == ON_ACCOUNT && customer != null && amount > 0L) {
                record.credit =
                    books.customer(
                        customer,
                        CREDIT_SALE,
                        amount,
                        record.saleId,
                        reverses = null,
                        dueDate = date.plusDays(CREDIT_DAYS),
                        at = at,
                        date = date,
                    )
                if (customersWithPayment.add(customer)) {
                    val payDay = date.date.plus(
                        random.between(MIN_PAY_DAYS, MAX_PAY_DAYS),
                        DateTimeUnit.DAY,
                    )
                    customerPayments.getOrPut(payDay) { ArrayList() } += customer
                }
            }
        }
    }

    /**
     * Anula una venta: la marca, compensa cada fila de stock reproduciendo el costo, y compensa su
     * dinero, su deuda y sus puntos. Nada se borra.
     */
    private suspend fun voidSale(record: SaleRecord, at: Instant, date: BusinessDate) {
        val reason = if (random.chance(HALF)) VoidReason.UNDO else VoidReason.ENTRY_ERROR
        ports.sales.markVoided(record.saleId, Voiding(at, reason, note = null))
        for (movement in record.movements) {
            stock.compensate(movement, at, date)
            addPending(movement.productId, movement.locationId, movement.quantity.milli)
        }
        for (money in record.money) books.voidMoney(money, at, date)
        val customer = record.customerId
        record.credit?.let { credit ->
            books.customer(
                requireNotNull(customer),
                ENTRY_VOID,
                -credit.amount,
                saleId = null,
                reverses = credit.id,
                dueDate = null,
                at = at,
                date = date,
            )
        }
        record.points?.let { points ->
            books.points(
                requireNotNull(customer),
                ENTRY_VOID,
                -points.amount,
                null,
                points.id,
                at,
                date,
            )
        }
    }

    /**
     * Una compra que repone lo vendido: sus líneas son los productos que más se venden, en la
     * cantidad que salió desde la última compra, más un margen. A crédito si el proveedor da plazo.
     */
    private suspend fun purchase(at: Instant, date: BusinessDate) {
        val second = !secondPurchases.isEmpty && random.chance(SECOND_PURCHASE_PER_MILLE)
        val location = if (second) catalog.secondLocationId else catalog.mainLocationId
        val sampler = if (second) secondPurchases else mainPurchases
        val wanted = random.between(1, 2 * linesPerPurchase - 1)
        val chosen = LinkedHashSet<SeedProduct>()
        var tries = 0
        while (chosen.size < wanted &&
            tries++ < wanted * TRIES_PER_LINE
        ) {
            chosen += sampler.pick(random)
        }
        val supplier = if (random.chance(
                SUPPLIER_PER_MILLE,
            )
        ) {
            random.pick(catalog.suppliers)
        } else {
            null
        }
        val lines =
            chosen.map { product ->
                val owed = pendingOf(product.id, location)
                val quantity =
                    if (owed > 0L) {
                        product.unit.roundUp(
                            owed * random.between(PERCENT, RESTOCK_MAX_PERCENT) / PERCENT,
                        )
                    } else {
                        product.unit.roundUp(product.unit.averageSale * IDLE_RESTOCK_SALES)
                    }
                val unitCost = costAt(product, at)
                val total =
                    checked(
                        Money(
                            unitCost,
                        ).timesQuantity(QuantityMilli(quantity), Limit.DOCUMENT_TOTAL),
                    )
                PurchasePlan(product, quantity, unitCost, total.minor)
            }
        val total = lines.sumOf { it.total }
        val credit = supplier != null && random.chance(CREDIT_PURCHASE_PER_MILLE)
        val account =
            if (credit) {
                null
            } else if (random.chance(HALF)) {
                catalog.cashAccountId
            } else {
                catalog.bankAccountId
            }
        val dueDate = if (credit) date.plusDays(requireNotNull(supplier).termsDays) else null
        val purchaseId =
            ports.purchases.insert(
                NewPurchase(
                    supplierId = supplier?.id,
                    invoiceReference = if (random.chance(HALF)) "A-${++invoices}" else null,
                    netTotal = Money(total),
                    taxTotal = Money.ZERO,
                    total = Money(total),
                    costTotal = Money(total),
                    amountPaid = Money(if (credit) 0L else total),
                    moneyAccountId = account,
                    dueDate = dueDate,
                    locationId = location,
                    foreignCurrency = null,
                    occurredAt = at,
                    businessDate = date,
                    createdAt = at,
                ),
            )
        for (line in lines) {
            val lineId =
                ports.purchases.insertLine(
                    purchaseId,
                    NewPurchaseLine(
                        productId = line.product.id,
                        unitId = line.product.unitId,
                        unitFactor = QuantityMilli(MILLI),
                        enteredQuantity = QuantityMilli(line.quantity),
                        quantity = QuantityMilli(line.quantity),
                        unitCost = Money(line.unitCost),
                        lineTotal = Money(line.total),
                        taxRate = BasisPoints.ZERO,
                        taxNameSnapshot = null,
                        taxAmount = Money.ZERO,
                        costAmount = Money(line.total),
                        lotId = null,
                        referenceUnitCost = null,
                    ),
                )
            stock.entry(
                productId = line.product.id,
                locationId = location,
                type = StockMovementType.PURCHASE,
                quantity = QuantityMilli(line.quantity),
                total = Money(line.total),
                unitCost = Money(line.unitCost),
                source = StockMovementSource(purchaseLineId = lineId),
                at = at,
                date = date,
            )
            ports.products.updateReplacementCost(line.product.id, Money(line.unitCost), at, at)
            line.product.replacementCost = line.unitCost
            pending[line.product.id]?.set(slot(location), 0L)
        }
        if (total == 0L) return
        if (account != null) {
            books.money(
                MoneyEntry(account, PURCHASE_PAYMENT, -total, purchaseId = purchaseId),
                at,
                date,
            )
        } else {
            val owner = requireNotNull(supplier)
            val due = requireNotNull(dueDate)
            books.supplier(owner.id, CREDIT_PURCHASE, total, purchaseId, due, at, date)
            val first = if (total >= 2L && random.chance(HALF)) total / 2 else total
            schedule(due.date, SupplierPayment(owner.id, first))
            if (first < total) {
                schedule(
                    due.date.plus(SECOND_INSTALLMENT_DAYS, DateTimeUnit.DAY),
                    SupplierPayment(
                        owner.id,
                        total - first,
                    ),
                )
            }
        }
    }

    private suspend fun supplierPayment(payment: SupplierPayment, at: Instant, date: BusinessDate) {
        val entry = books.supplier(
            payment.supplierId,
            PAYMENT,
            -payment.amount,
            null,
            null,
            at,
            date,
        )
        books.money(
            MoneyEntry(
                catalog.bankAccountId,
                SUPPLIER_PAYMENT,
                -payment.amount,
                supplierLedgerEntryId = entry.id,
            ),
            at,
            date,
        )
    }

    /** El cliente paga todo lo que debe ese día, en efectivo. */
    private suspend fun customerPayment(customerId: Long, at: Instant, date: BusinessDate) {
        customersWithPayment -= customerId
        val owed = books.customerBalance(customerId)
        if (owed <= 0L) return
        val entry =
            books.customer(
                customerId,
                PAYMENT,
                -owed,
                saleId = null,
                reverses = null,
                dueDate = null,
                at,
                date,
            )
        books.money(
            MoneyEntry(
                catalog.cashAccountId,
                CUSTOMER_PAYMENT,
                owed,
                customerLedgerEntryId = entry.id,
            ),
            at,
            date,
        )
    }

    private suspend fun expense(at: Instant, date: BusinessDate) {
        val amount = random.between(MIN_EXPENSE, MAX_EXPENSE) / PRICE_STEP * PRICE_STEP
        val account =
            when {
                !random.chance(EXPENSE_PAID_PER_MILLE) -> null
                random.chance(HALF) -> catalog.cashAccountId
                else -> catalog.bankAccountId
            }
        val notes = words().expenseNotes
        val id =
            ports.expenses.insert(
                NewExpense(
                    expenseCategoryId = random.pick(catalog.expenseCategoryIds),
                    amount = Money(amount),
                    description = if (random.chance(HALF)) random.pick(notes) else null,
                    moneyAccountId = account,
                    recurringExpenseId = null,
                    occurredAt = at,
                    businessDate = date,
                    createdAt = at,
                ),
            )
        if (account != null) {
            books.money(MoneyEntry(account, EXPENSE_PAYMENT, -amount, expenseId = id), at, date)
        }
    }

    /** Un aumento de precio de un producto cualquiera, con su fila en el historial. */
    private suspend fun priceChange(at: Instant, date: BusinessDate) {
        val product = random.pick(products)
        val old = product.salePrice
        val raised = old * random.between(PRICE_RAISE_MIN, PRICE_RAISE_MAX) / PERCENT
        val new = minOf(
            Limit.UNIT_MONEY.max,
            maxOf(
                old + PRICE_STEP,
                raised / PRICE_STEP * PRICE_STEP,
            ),
        )
        ports.products.updateSalePrice(product.id, Money(new), at)
        ports.priceHistory.append(
            NewPriceChange(
                productId = product.id,
                priceListId = catalog.priceListId,
                oldPrice = Money(old),
                newPrice = Money(new),
                source = PriceChangeSource.MANUAL,
                batchId = null,
                bulkRule = null,
                occurredAt = at,
                businessDate = date,
            ),
        )
        product.salePrice = new
    }

    /** Una pérdida o una corrección de conteo, sin factura. */
    private suspend fun adjustment(at: Instant, date: BusinessDate) {
        val product = mainPurchases.pick(random)
        val location =
            if (product.dual &&
                random.chance(HALF)
            ) {
                catalog.secondLocationId
            } else {
                catalog.mainLocationId
            }
        val step = product.unit.step * random.between(1L, MAX_ADJUSTMENT_STEPS)
        val (quantity, reason) =
            if (random.chance(LOSS_PER_MILLE)) {
                -step to random.pick(LOSS_REASONS)
            } else {
                step to StockAdjustmentReason.COUNT_CORRECTION
            }
        stock.adjust(product.id, location, QuantityMilli(quantity), reason, at, date)
        addPending(product.id, location, -quantity)
    }

    /** Archiva una parte del catálogo y deja las alertas de lo que quedó bajo. */
    suspend fun close(at: Instant, date: BusinessDate) {
        clock.current = at
        ports.transactions.write {
            val candidates = products.filter { it.id != catalog.heavyProduct?.id }.toMutableList()
            val archived = HashSet<Long>()
            repeat(minOf(profile.archivedProducts, candidates.size)) {
                val product = candidates.removeAt(random.nextInt(candidates.size))
                ports.products.setArchivedAt(product.id, at, at)
                archived += product.id
            }
            for (product in products) {
                if (!product.tracked || product.id in archived) continue
                val onHand = stock.state(product.id).onHand
                val minStock = QuantityMilli(product.minStockMilli)
                val low =
                    LowStockEvaluator.isLow(
                        product.mode,
                        isArchived = false,
                        onHand = onHand,
                        minStock = minStock,
                    )
                if (low) {
                    ports.lowStockAlerts.insert(
                        NewLowStockAlert(
                            productId = product.id,
                            onHandAtTrigger = onHand,
                            minStockAtTrigger = minStock,
                            notificationStatus = LowStockNotificationStatus.SHOWN,
                            occurredAt = at,
                            businessDate = date,
                        ),
                    )
                }
            }
            TxResult.Commit(Unit)
        }
    }

    /** Las filas de stock que tiene el producto pesado, si el perfil lo pide. */
    fun heavyMovements(): Int = catalog.heavyProduct?.let { stock.movementCount(it.id) } ?: 0

    /** El costo de comprarlo ese día: sube un 6 % al año, con un poco de ruido. */
    private fun costAt(product: SeedProduct, at: Instant): Long {
        val day = BusinessDate.of(at, zone).date
        val elapsed = firstDay.daysUntilSafe(day)
        val inflated =
            product.costBasis * (BP + INFLATION_BP_PER_YEAR * elapsed / DAYS_PER_YEAR) / BP
        return maxOf(1L, inflated * random.between(NOISE_MIN, NOISE_MAX) / PERCENT)
    }

    private fun lineCount(): Int {
        val roll = random.nextInt(PER_MILLE)
        return LINE_COUNTS.indexOfFirst { roll < it } + 1
    }

    private fun singleMethod(): SeedPaymentMethod {
        val roll = random.nextInt(PER_MILLE)
        return method(METHOD_ROLLS.first { roll < it.first }.second)
    }

    private fun method(kind: String): SeedPaymentMethod =
        requireNotNull(catalog.methods[kind]) { "Falta un método de pago" }

    private fun words(): SeedVocabulary = catalog.vocabulary

    private fun schedule(day: LocalDate, payment: SupplierPayment) {
        supplierPayments.getOrPut(day) { ArrayList() } += payment
    }

    private fun slot(location: Long): Int = if (location == catalog.mainLocationId) 0 else 1

    private fun pendingOf(productId: Long, location: Long): Long =
        pending[productId]?.get(slot(location)) ?: 0L

    /** Suma lo que salió, o resta lo que volvió, a lo que la próxima compra debe reponer. */
    private fun addPending(productId: Long, location: Long, quantity: Long) {
        val slots = pending.getOrPut(productId) { LongArray(2) }
        val index = slot(location)
        slots[index] = maxOf(0L, slots[index] + quantity)
    }

    private fun sampler(items: List<SeedProduct>): WeightedSampler<SeedProduct> =
        WeightedSampler(items.map { it to (catalog.saleWeights[it.id] ?: 1L) })

    /** Peso de ventas del día: más los fines de semana y en los últimos días del mes. */
    private fun saleWeight(day: LocalDate): Long {
        var weight = BASE_DAY_WEIGHT
        if (day.dayOfWeek == DayOfWeek.SATURDAY || day.dayOfWeek == DayOfWeek.SUNDAY) {
            weight = weight * WEEKEND_PERCENT / PERCENT
        }
        if (day.plus(MONTH_END_DAYS, DateTimeUnit.DAY).month != day.month) {
            weight = weight * MONTH_END_PERCENT / PERCENT
        }
        return weight
    }

    /**
     * La hora de una venta: casi todas en horario de atención, y algunas cerca de medianoche, a un
     * lado y al otro, para probar la fecha de negocio.
     */
    private fun saleSecond(): Int = when {
        !random.chance(MIDNIGHT_PER_MILLE) -> between(SALE_HOURS)
        random.chance(HALF) -> random.between(LATE_NIGHT_START, SECONDS_PER_DAY - 1)
        else -> random.between(0, EARLY_MORNING_END)
    }

    private fun between(hours: IntRange): Int =
        random.between(hours.first * SECONDS_PER_HOUR, (hours.last + 1) * SECONDS_PER_HOUR - 1)

    private fun event(
        day: LocalDate,
        second: Int,
        kind: EventKind,
        customerId: Long = 0L,
        payment: SupplierPayment? = null,
    ): Event {
        val at = LocalDateTime(day, LocalTime.fromSecondOfDay(second)).toInstant(zone.timeZone)
        return Event(at, nextSequence(), kind, customerId = customerId, payment = payment)
    }

    private fun nextSequence(): Long = sequence++

    private enum class EventKind {
        SALE,
        VOID,
        PURCHASE,
        EXPENSE,
        PRICE,
        ADJUSTMENT,
        CUSTOMER_PAYMENT,
        SUPPLIER_PAYMENT,
    }

    private class Event(
        val at: Instant,
        val sequence: Long,
        val kind: EventKind,
        val sale: SaleRecord? = null,
        val customerId: Long = 0L,
        val payment: SupplierPayment? = null,
    )

    /** Lo que una venta escribió y su anulación debe compensar. */
    private class SaleRecord(
        val saleId: Long,
        val customerId: Long?,
        val movements: List<RecordedMovement>,
    ) {
        val money = ArrayList<BookEntry>()
        var credit: BookEntry? = null
        var points: BookEntry? = null
    }

    private class PlannedLine(
        val product: SeedProduct,
        val quantity: QuantityMilli,
        val gross: Long,
        val discount: Long,
        val plan: LineStockPlan,
        val unitCost: Long,
        val unitReplacementCost: Long,
        val replacementCost: Long,
    ) {
        /** La parte de la línea en el descuento de toda la venta, negativa. */
        var share: Long = 0L
        val net: Long get() = gross - discount + share
    }

    private class PurchasePlan(
        val product: SeedProduct,
        val quantity: Long,
        val unitCost: Long,
        val total: Long,
    )

    private class SupplierPayment(val supplierId: Long, val amount: Long)

    private companion object {
        const val MILLI = 1_000L
        const val BP = 10_000L
        const val PERCENT = 100L
        const val PER_MILLE = 1_000
        const val HALF = 500
        const val SECONDS_PER_HOUR = 3_600
        const val SECONDS_PER_DAY = 86_400
        const val LATE_NIGHT_START = 23 * SECONDS_PER_HOUR + 30 * 60
        const val EARLY_MORNING_END = 30 * 60 - 1
        val SALE_HOURS = 8..20
        val PURCHASE_HOURS = 7..17
        val EXPENSE_HOURS = 9..19
        val PRICE_HOURS = 7..7
        val CLOSING_HOURS = 19..20
        val PAYMENT_HOURS = 10..17
        const val MIDNIGHT_PER_MILLE = 15
        const val BASE_DAY_WEIGHT = 100L
        const val WEEKEND_PERCENT = 150L
        const val MONTH_END_PERCENT = 130L
        const val MONTH_END_DAYS = 4
        const val SECOND_LOCATION_PER_MILLE = 150
        const val SECOND_PURCHASE_PER_MILLE = 200
        const val CUSTOMER_PER_MILLE = 350
        const val ON_ACCOUNT_PER_MILLE = 700
        const val SPLIT_PER_MILLE = 100
        const val SPLIT_MIN = 30L
        const val SPLIT_MAX = 70L
        const val TENDERED_PER_MILLE = 400
        const val CASH_STEP = 100L
        const val VOID_PER_MILLE = 20
        const val MIN_VOID_SECONDS = 60L
        const val MAX_VOID_SECONDS = 2_400L
        const val TRIES_PER_LINE = 4
        const val LINE_DISCOUNT_PER_MILLE = 50
        const val DISCOUNT_BP = 1_000L
        const val ORDER_ADJUSTMENT_PER_MILLE = 30
        const val ORDER_DISCOUNT_BP = 500L
        const val AMOUNT_PER_POINT = 1_000L
        const val CREDIT_DAYS = 30
        const val MIN_PAY_DAYS = 7
        const val MAX_PAY_DAYS = 30
        const val SUPPLIER_PER_MILLE = 900
        const val CREDIT_PURCHASE_PER_MILLE = 600
        const val SECOND_INSTALLMENT_DAYS = 10
        const val RESTOCK_MAX_PERCENT = 130L
        const val IDLE_RESTOCK_SALES = 5L
        const val MIN_EXPENSE = 1_000L
        const val MAX_EXPENSE = 300_000L
        const val EXPENSE_PAID_PER_MILLE = 800
        const val PRICE_STEP = 10L
        const val PRICE_RAISE_MIN = 102L
        const val PRICE_RAISE_MAX = 108L
        const val MAX_ADJUSTMENT_STEPS = 3L
        const val LOSS_PER_MILLE = 750
        const val INFLATION_BP_PER_YEAR = 600L
        const val DAYS_PER_YEAR = 365L
        const val NOISE_MIN = 97L
        const val NOISE_MAX = 103L
        const val CASH = "CASH"
        const val CARD = "CARD"
        const val TRANSFER = "TRANSFER"
        const val ON_ACCOUNT = "ON_ACCOUNT"
        const val SALE_RECEIPT = "SALE_RECEIPT"
        const val FEE = "FEE"
        const val PURCHASE_PAYMENT = "PURCHASE_PAYMENT"
        const val SUPPLIER_PAYMENT = "SUPPLIER_PAYMENT"
        const val CUSTOMER_PAYMENT = "CUSTOMER_PAYMENT"
        const val EXPENSE_PAYMENT = "EXPENSE_PAYMENT"
        const val CREDIT_SALE = "CREDIT_SALE"
        const val CREDIT_PURCHASE = "CREDIT_PURCHASE"
        const val PAYMENT = "PAYMENT"
        const val ENTRY_VOID = "ENTRY_VOID"
        const val EARN = "EARN"

        /** Tope acumulado por mil de cada cantidad de líneas, de una a ocho: unas 2,5 de media. */
        val LINE_COUNTS = intArrayOf(330, 590, 780, 890, 950, 980, 990, 1_000)

        /** Tope acumulado por mil de cada método cuando la venta se paga con uno solo. */
        val METHOD_ROLLS =
            listOf(
                550 to CASH,
                770 to CARD,
                890 to TRANSFER,
                970 to "WALLET",
                1_000 to "OTHER",
            )

        val LOSS_REASONS =
            listOf(
                StockAdjustmentReason.DAMAGED,
                StockAdjustmentReason.EXPIRED,
                StockAdjustmentReason.LOST_OR_STOLEN,
                StockAdjustmentReason.OWN_USE,
                StockAdjustmentReason.GIFT_OR_SAMPLE,
                StockAdjustmentReason.COUNT_CORRECTION,
            )
    }
}

/**
 * Elige al azar con pesos: la probabilidad de cada elemento es su peso sobre el total. Busca en
 * las sumas acumuladas, así que elegir no recorre la lista.
 */
internal class WeightedSampler<T>(entries: List<Pair<T, Long>>) {
    private val items = entries.filter { it.second > 0L }.map { it.first }
    private val cumulative = LongArray(items.size)

    init {
        var sum = 0L
        entries.filter { it.second > 0L }.forEachIndexed { index, entry ->
            sum += entry.second
            cumulative[index] = sum
        }
    }

    val isEmpty: Boolean get() = items.isEmpty()

    fun pick(random: SeedRandom): T {
        check(items.isNotEmpty()) { "No hay nada que elegir" }
        val target = random.between(0L, cumulative.last() - 1)
        var low = 0
        var high = cumulative.lastIndex
        while (low < high) {
            val middle = (low + high) / 2
            if (cumulative[middle] > target) high = middle else low = middle + 1
        }
        return items[low]
    }
}

/**
 * Reparte [total] entre los días según [weights], con aritmética entera: la suma da exactamente
 * [total], y el reparto es siempre el mismo.
 */
internal fun distribute(total: Int, weights: List<Long>): IntArray {
    val sum = weights.sum()
    var accumulated = 0L
    var assigned = 0L
    return IntArray(weights.size) { index ->
        accumulated += weights[index]
        val upTo = total.toLong() * accumulated / sum
        (upTo - assigned).toInt().also { assigned = upTo }
    }
}

/** Días de [this] a [other], sin depender de la zona: las dos son fechas de calendario. */
private fun LocalDate.daysUntilSafe(other: LocalDate): Long =
    other.toEpochDays().toLong() - toEpochDays().toLong()
