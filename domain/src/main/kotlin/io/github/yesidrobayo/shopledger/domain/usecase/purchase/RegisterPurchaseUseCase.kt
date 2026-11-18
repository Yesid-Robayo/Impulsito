package io.github.yesidrobayo.shopledger.domain.usecase.purchase

import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.Limit
import io.github.yesidrobayo.shopledger.domain.model.Margin
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.catalog.Supplier
import io.github.yesidrobayo.shopledger.domain.model.catalog.SystemRow
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyMovementDraft
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyMovementType
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyOrigin
import io.github.yesidrobayo.shopledger.domain.model.payable.NewSupplierLedgerEntry
import io.github.yesidrobayo.shopledger.domain.model.payable.SupplierLedgerEntryType
import io.github.yesidrobayo.shopledger.domain.model.purchase.NewPurchase
import io.github.yesidrobayo.shopledger.domain.model.purchase.NewPurchaseLine
import io.github.yesidrobayo.shopledger.domain.model.purchase.PurchaseCostChange
import io.github.yesidrobayo.shopledger.domain.model.purchase.PurchaseLineLot
import io.github.yesidrobayo.shopledger.domain.model.purchase.PurchaseLineRejection
import io.github.yesidrobayo.shopledger.domain.model.purchase.PurchaseLineRequest
import io.github.yesidrobayo.shopledger.domain.model.purchase.PurchaseRequest
import io.github.yesidrobayo.shopledger.domain.model.region.RecordTime
import io.github.yesidrobayo.shopledger.domain.model.settings.TaxSettings
import io.github.yesidrobayo.shopledger.domain.model.stock.NewStockLot
import io.github.yesidrobayo.shopledger.domain.model.stock.NewStockMovement
import io.github.yesidrobayo.shopledger.domain.model.stock.StockKey
import io.github.yesidrobayo.shopledger.domain.model.stock.StockMovementDraft
import io.github.yesidrobayo.shopledger.domain.model.stock.StockMovementSource
import io.github.yesidrobayo.shopledger.domain.model.stock.StockMovementType
import io.github.yesidrobayo.shopledger.domain.model.valueOrNull
import io.github.yesidrobayo.shopledger.domain.port.AppSettingsRepository
import io.github.yesidrobayo.shopledger.domain.port.CategoryRepository
import io.github.yesidrobayo.shopledger.domain.port.Clock
import io.github.yesidrobayo.shopledger.domain.port.LocationRepository
import io.github.yesidrobayo.shopledger.domain.port.LowStockAlertRepository
import io.github.yesidrobayo.shopledger.domain.port.ProductRepository
import io.github.yesidrobayo.shopledger.domain.port.ProductUnitRepository
import io.github.yesidrobayo.shopledger.domain.port.PurchaseRepository
import io.github.yesidrobayo.shopledger.domain.port.StockLedgerRepository
import io.github.yesidrobayo.shopledger.domain.port.StockLotRepository
import io.github.yesidrobayo.shopledger.domain.port.StorageFailure
import io.github.yesidrobayo.shopledger.domain.port.StorageFailureException
import io.github.yesidrobayo.shopledger.domain.port.SupplierLedgerRepository
import io.github.yesidrobayo.shopledger.domain.port.SupplierRepository
import io.github.yesidrobayo.shopledger.domain.port.SystemRowRepository
import io.github.yesidrobayo.shopledger.domain.port.TaxRateRepository
import io.github.yesidrobayo.shopledger.domain.port.TransactionRunner
import io.github.yesidrobayo.shopledger.domain.port.TxResult
import io.github.yesidrobayo.shopledger.domain.port.UnitRepository
import io.github.yesidrobayo.shopledger.domain.rule.MarginCalculator
import io.github.yesidrobayo.shopledger.domain.rule.TaxCalculator
import io.github.yesidrobayo.shopledger.domain.rule.catalog.ReplacementCostRules
import io.github.yesidrobayo.shopledger.domain.rule.payable.PurchaseCredit
import io.github.yesidrobayo.shopledger.domain.rule.payable.SupplierLedgerRules
import io.github.yesidrobayo.shopledger.domain.rule.purchase.PricedPurchaseLine
import io.github.yesidrobayo.shopledger.domain.rule.purchase.PurchasePricing
import io.github.yesidrobayo.shopledger.domain.rule.purchase.PurchasePricingResult
import io.github.yesidrobayo.shopledger.domain.rule.purchase.PurchaseSubject
import io.github.yesidrobayo.shopledger.domain.rule.purchase.PurchaseTotals
import io.github.yesidrobayo.shopledger.domain.rule.purchase.PurchaseUnit
import io.github.yesidrobayo.shopledger.domain.rule.region.BusinessCalendar
import io.github.yesidrobayo.shopledger.domain.rule.region.BusinessCalendarResult
import io.github.yesidrobayo.shopledger.domain.rule.stock.LotCodeCheck
import io.github.yesidrobayo.shopledger.domain.rule.stock.LotRules
import io.github.yesidrobayo.shopledger.domain.usecase.money.AccountIssue
import io.github.yesidrobayo.shopledger.domain.usecase.money.DocumentMoney
import io.github.yesidrobayo.shopledger.domain.usecase.money.MoneyAppendResult
import io.github.yesidrobayo.shopledger.domain.usecase.payable.appendChained
import io.github.yesidrobayo.shopledger.domain.usecase.region.CurrencyLock
import io.github.yesidrobayo.shopledger.domain.usecase.region.CurrencyLockResult
import io.github.yesidrobayo.shopledger.domain.usecase.stock.StockChangeRecorder
import kotlin.time.Instant

/**
 * Confirma una compra: cabecera, líneas y una entrada del libro de stock por línea, con el saldo,
 * el costo promedio y el valor del stock que deja cada una, todo en una sola transacción. Si
 * cualquier paso falla o una regla rechaza, no queda nada: una compra a medias dejaría el stock o
 * el costo diciendo algo que no pasó.
 *
 * - **El costo promedio sale del total**, no del costo unitario redondeado. Cada línea suma al
 *   valor del stock lo que costó, sin el impuesto si quien compra lo recupera, y el promedio se
 *   deduce de ese valor. Con stock en cero, negativo o sin valor, el costo anterior no pesa.
 * - **Costo unitario o total, a elección.** La persona escribe uno de los dos y el otro se deduce.
 *   Los dos se guardan en la línea. Una bonificación vale cero, y baja el promedio.
 * - **Un producto puede repetirse**, por ejemplo con dos costos distintos. Las líneas se aplican
 * en   orden, cada una con su fila del libro, y la segunda parte del costo que dejó la primera.
 * - **Las ventas pasadas no cambian.** Cada venta congeló su costo, y una compra solo mueve el
 *   costo de lo que venga después. Tampoco cambia el precio de venta de ningún producto.
 * - **El impuesto va línea por línea**, con la tasa del producto o la del negocio, y cada línea
 *   copia la tasa y su nombre. Sin impuesto en el negocio, la línea no lleva.
 * - **La fecha puede ser pasada**, nunca futura. Las filas del libro llevan la fecha de la compra,
 *   pero toman su lugar en el orden del libro al registrarse: una compra atrasada cambia el costo
 *   desde hoy, no desde su fecha.
 * - **La moneda se bloquea con la primera compra**, en su misma transacción.
 * - **Se compra en otra unidad** con la conversión de compra del producto, como la caja: la línea
 *   congela su factor, su costo unitario va por caja, y el libro suma la cantidad llevada a la
 *   unidad base con el total de la línea. Una caja de 24 por 10000 deja el valor en 10000 y el
 *   promedio en 417 por unidad. Una unidad que el producto no compra se rechaza con su motivo.
 *
 * - **Se puede comprar a plazo.** Lo pagado al confirmar queda en la compra y no cambia nunca; lo
 *   que falta entra al libro del proveedor como un cargo de la compra, con su vencimiento, y sube
 *   lo que se le debe. Una compra a plazo exige proveedor. El vencimiento se propone con el plazo
 *   habitual del proveedor, se puede cambiar y no puede ser anterior a la compra. Comprar a plazo
 *   no toca ganancias: la compra pesa al vender. Sin decir lo pagado, la compra va pagada completa
 *   y no escribe nada en el libro del proveedor, como siempre.
 *
 * Por ahora la compra va sin cuenta de dinero: sus movimientos llegan con el libro de dinero.
 * Un producto que se controla por lotes entra al lote de su línea: uno que ya tiene, o uno nuevo
 * que se crea aquí con su código y su vencimiento, y que recuerda la línea con que entró. Sin
 * lote, entra al lote sin asignar. [lots] solo hace falta para comprar con lote.
 *
 * Con [alerts], evalúa en la misma transacción la alerta de stock bajo de cada producto que mueve,
 * una sola vez por producto. Sin él no la evalúa: solo lo omiten las pruebas que no miran alertas.
 * [supplierLedger] solo hace falta para comprar a plazo.
 *
 * Cada producto comprado toma como costo de reposición lo que costó una unidad base de su última
 * línea con costo, fechado con la compra, en esta misma transacción. Una compra con fecha anterior
 * a la reposición que ya tiene no la cambia, y una bonificación tampoco. El costo promedio no se
 * mueve por eso: la reposición es solo una vista adicional.
 *
 * Con una cuenta elegida, lo pagado al confirmar sale de ella como un `PURCHASE_PAYMENT`, en esta
 * misma transacción y con la fecha de la compra. La cuenta tiene que estar vigente y llevar la
 * moneda del negocio. Sin cuenta, o sin nada pagado, no se escribe dinero ni se lee ninguna
 * cuenta. [money] solo hace falta para pagar desde una cuenta.
 */
class RegisterPurchaseUseCase(
    private val transactions: TransactionRunner,
    private val settings: AppSettingsRepository,
    private val products: ProductRepository,
    private val purchases: PurchaseRepository,
    private val suppliers: SupplierRepository,
    private val units: UnitRepository,
    private val systemRows: SystemRowRepository,
    ledger: StockLedgerRepository,
    private val taxRates: TaxRateRepository,
    private val clock: Clock,
    private val productUnits: ProductUnitRepository? = null,
    private val alerts: LowStockAlertRepository? = null,
    private val categories: CategoryRepository? = null,
    private val supplierLedger: SupplierLedgerRepository? = null,
    private val money: DocumentMoney? = null,
    private val locations: LocationRepository? = null,
    private val lots: StockLotRepository? = null,
) {
    private val currencyLock = CurrencyLock(settings)
    private val ledger = StockChangeRecorder(ledger)

    suspend operator fun invoke(request: PurchaseRequest): RegisterPurchaseResult = try {
        transactions.write { register(request) }
    } catch (failure: StorageFailureException) {
        RegisterPurchaseResult.StorageFailed(failure.failure)
    }

    /**
     * Registra [request] dentro de una transacción que ya abrió quien llama, como la compra de un
     * producto nuevo dicho con una frase, que se crea en la misma escritura. Un rechazo llega como
     * [TxResult.Rollback], y quien llama deshace todo.
     */
    internal suspend fun registerWithin(
        request: PurchaseRequest,
    ): TxResult<RegisterPurchaseResult> = register(request)

    private suspend fun register(request: PurchaseRequest): TxResult<RegisterPurchaseResult> {
        ledger.start()
        val reference = request.invoiceReference?.trim()?.takeIf { it.isNotEmpty() }
        shapeRejection(request, reference)?.let { return TxResult.Rollback(it) }
        val current = settings.get()
        val calendar = when (val result = BusinessCalendar.of(current.regional)) {
            is BusinessCalendarResult.Ready -> result.calendar

            is BusinessCalendarResult.UnknownZone ->
                return reject(RegisterPurchaseResult.UnknownTimeZone(result.storedZoneId))
        }
        val now = clock.now()
        val time = recordTime(calendar, request.date, now)
            ?: return reject(RegisterPurchaseResult.FutureDate)
        val supplier = request.supplierId?.let { id ->
            val found = suppliers.findById(id)
                ?: return reject(RegisterPurchaseResult.SupplierNotFound)
            if (found.isArchived) return reject(RegisterPurchaseResult.SupplierArchived)
            found
        }
        val location = locationOf(request)
            ?: return reject(RegisterPurchaseResult.LocationUnavailable)
        val subjects = subjectsOf(request.lines, current.taxes)
        val priced = when (
            val result = PurchasePricing.price(request.lines, subjects, current.taxes)
        ) {
            is PurchasePricingResult.Priced -> result

            is PurchasePricingResult.LineRejected ->
                return reject(RegisterPurchaseResult.LineRejected(result.index, result.reason))

            PurchasePricingResult.TotalTooLarge ->
                return reject(RegisterPurchaseResult.PurchaseTotalTooLarge)
        }
        storedLotRejection(request.lines)?.let { return reject(it) }
        val settlement = when (
            val result = settlementOf(request, priced.totals.total, supplier, time)
        ) {
            is SettlementResult.Ready -> result.settlement
            is SettlementResult.Rejected -> return reject(result.reason)
        }

        // Justo antes de escribir: la primera compra bloquea la moneda en esta misma transacción,
        // y si la compra se deshace, el bloqueo también.
        val currency = when (val lock = currencyLock.lockForMoneyRecord(now)) {
            is CurrencyLockResult.Locked -> lock.currency

            CurrencyLockResult.Unconfirmed ->
                return reject(RegisterPurchaseResult.CurrencyNotConfirmed)
        }
        // Sin nada pagado, la cuenta no mueve nada: la compra no la guarda.
        val accountId = request.moneyAccountId?.takeIf { settlement.amountPaid > Money.ZERO }
        val payer = accountId?.let {
            val documentMoney = checkNotNull(money) { "Pagar desde una cuenta necesita el dinero" }
            documentMoney.issueOf(it, currency)?.let { issue ->
                return reject(RegisterPurchaseResult.AccountUnavailable(issue))
            }
            documentMoney
        }
        val purchaseId = write(
            location,
            request.supplierId,
            reference,
            priced.lines.zip(request.lines.map { it.lot }),
            priced.totals,
            settlement.copy(moneyAccountId = accountId),
            time,
            now,
        )
        refreshReplacementCosts(priced.lines, time, now)
        if (payer != null) {
            val draft = MoneyMovementDraft(
                accountId = accountId,
                type = MoneyMovementType.PURCHASE_PAYMENT,
                amount = -settlement.amountPaid,
                origin = MoneyOrigin.Purchase(purchaseId),
            )
            when (payer.append(draft, time, now)) {
                is MoneyAppendResult.Appended -> Unit

                // Se revisó en esta misma transacción: no puede haber desaparecido.
                MoneyAppendResult.AccountNotFound ->
                    return reject(RegisterPurchaseResult.AccountUnavailable(AccountIssue.NOT_FOUND))

                MoneyAppendResult.Overflow -> return reject(RegisterPurchaseResult.AccountOverflow)
            }
        }
        if (settlement.credit != null && supplier != null) {
            val charged = charge(supplier.id, purchaseId, settlement.credit, settlement, time, now)
            if (!charged) return reject(RegisterPurchaseResult.SupplierBalanceTooLarge)
        }
        alerts?.let { ledger.applyAlerts(it, products, calendar.stamp(now)) }
        val changes =
            reviewPrices(priced, subjects, current.taxes, current.profile.defaultTargetMargin, now)
        return TxResult.Commit(RegisterPurchaseResult.Registered(purchaseId, changes))
    }

    /**
     * Los cambios de costo, con lo que pide revisar el precio. Un costo conocido que sube marca el
     * producto para revisar su precio, en esta misma transacción, salvo que ya estuviera marcado:
     * la cola de revisión ordena por la marca más antigua. Además, se dice si el margen del precio
     * de venta quedó por debajo de su objetivo, el del producto, el de su categoría o el del
     * negocio.
     */
    private suspend fun reviewPrices(
        priced: PurchasePricingResult.Priced,
        subjects: Map<Long, PurchaseSubject>,
        taxes: TaxSettings,
        defaultTarget: BasisPoints,
        now: Instant,
    ): List<PurchaseCostChange> {
        val after = priced.lines.associate { it.subject.product.id to it.after }
        return priced.costChanges.map { change ->
            val subject = subjects.getValue(change.productId)
            val product = subject.product
            // Un insumo no se vende: no hay precio que revisar.
            if (change.costRose && product.priceReviewSince == null &&
                product.details.usage.isSold
            ) {
                products.setPriceReviewSince(product.id, now, now)
            }
            val target = product.details.targetMargin
                ?: product.details.categoryId?.let {
                    categories?.findById(it)?.details?.targetMargin
                }
                ?: defaultTarget
            val net = TaxCalculator.catalogNetPrice(product.salePrice, taxes.mode, subject.taxRate)
                .valueOrNull()
            val cost = after.getValue(change.productId).averageCost
            val below = net != null && when (val margin = MarginCalculator.margin(net, cost)) {
                is Margin.Defined -> margin.value < target

                // Sin precio no hay margen que comparar; uno absurdo se revisa igual.
                Margin.NoData -> false

                Margin.OutOfRange -> true
            }
            change.copy(marginBelowTarget = below)
        }
    }

    /**
     * Pone como costo de reposición de cada producto lo que costó una unidad base en la compra, si
     * la compra no es anterior a la reposición que ya conoce. El producto es el que se leyó al
     * empezar esta misma transacción, así que su reposición no pudo cambiar por debajo.
     */
    private suspend fun refreshReplacementCosts(
        lines: List<PricedPurchaseLine>,
        time: RecordTime,
        now: Instant,
    ) {
        val costs = ReplacementCostRules.fromPurchase(
            lines.map { it.subject.product.id to it.movementCost },
        )
        for ((productId, unitCost) in costs) {
            val product = lines.first { it.subject.product.id == productId }.subject.product
            if (ReplacementCostRules.purchaseReplaces(product.replacementCostAt, time.occurredAt)) {
                products.updateReplacementCost(productId, unitCost, time.occurredAt, now)
            }
        }
    }

    /**
     * Cuánto se paga al confirmar, cuánto queda debiendo y cuándo vence, o por qué no se puede
     * registrar así. Una compra pagada completa no lleva vencimiento, aunque se haya escrito.
     */
    private fun settlementOf(
        request: PurchaseRequest,
        total: Money,
        supplier: Supplier?,
        time: RecordTime,
    ): SettlementResult {
        val paid = request.amountPaid ?: total
        val credit = when (val result = SupplierLedgerRules.creditOf(total, paid)) {
            PurchaseCredit.PaidInFull -> return SettlementResult.Ready(Settlement(paid, null, null))

            is PurchaseCredit.OnCredit -> result.amount

            PurchaseCredit.OutOfRange ->
                return SettlementResult.Rejected(RegisterPurchaseResult.AmountPaidOutOfRange)
        }
        if (supplier == null) {
            return SettlementResult.Rejected(RegisterPurchaseResult.SupplierRequiredForCredit)
        }
        val due = request.dueDate
            ?: SupplierLedgerRules.proposedDueDate(
                time.businessDate,
                supplier.details.paymentTermsDays,
            )
        if (due != null && due < time.businessDate) {
            return SettlementResult.Rejected(RegisterPurchaseResult.DueDateBeforePurchase)
        }
        return SettlementResult.Ready(Settlement(paid, credit, due))
    }

    /**
     * Agrega al libro del proveedor el cargo de [credit], lo que la compra deja debiendo, con su
     * vencimiento y la fecha de la compra, y sube su saldo. Devuelve `false` si ese saldo no cabe.
     */
    private suspend fun charge(
        supplierId: Long,
        purchaseId: Long,
        credit: Money,
        settlement: Settlement,
        time: RecordTime,
        now: Instant,
    ): Boolean {
        val ledger =
            requireNotNull(supplierLedger) { "Comprar a plazo pide el libro del proveedor" }
        val id = ledger.appendChained(supplierId, credit) { balanceAfter ->
            NewSupplierLedgerEntry(
                supplierId = supplierId,
                type = SupplierLedgerEntryType.CREDIT_PURCHASE,
                amount = credit,
                balanceAfter = balanceAfter,
                purchaseId = purchaseId,
                dueDate = settlement.dueDate,
                occurredAt = time.occurredAt,
                businessDate = time.businessDate,
                createdAt = now,
            )
        }
        return id != null
    }

    /** Lo que se rechaza sin leer la base: la forma de la compra. */
    private fun shapeRejection(
        request: PurchaseRequest,
        reference: String?,
    ): RegisterPurchaseResult? = when {
        request.lines.isEmpty() -> RegisterPurchaseResult.EmptyPurchase

        request.lines.size > Limit.DOCUMENT_LINES.max -> RegisterPurchaseResult.TooManyLines

        (reference?.length ?: 0) > MAX_REFERENCE_LENGTH ->
            RegisterPurchaseResult.InvoiceReferenceTooLong

        else -> null
    }

    /**
     * El instante y la fecha de la compra: ahora, si la fecha es hoy o no se eligió; las 12:00 de
     * ese día, si es pasada; o `null` si es futura.
     */
    private fun recordTime(
        calendar: BusinessCalendar,
        chosen: BusinessDate?,
        now: Instant,
    ): RecordTime? {
        val today = calendar.stamp(now)
        return when {
            chosen == null || chosen == today.businessDate -> today
            chosen > today.businessDate -> null
            else -> calendar.stampBackdated(chosen)
        }
    }

    /**
     * Lo que el cálculo necesita de cada producto de la compra, leído una vez dentro de la
     * transacción: el producto con su costo, si su unidad admite fracciones y la tasa que paga. Un
     * producto que no existe queda fuera, y el cálculo rechaza su línea.
     */
    private suspend fun subjectsOf(
        lines: List<PurchaseLineRequest>,
        taxes: TaxSettings,
    ): Map<Long, PurchaseSubject> {
        val fractionByUnit = HashMap<Long, Boolean>()
        val taxByRate = HashMap<Long, AppliedTax>()
        return products.findByIds(lines.map { it.productId }.toSet()).associate { product ->
            val allowsFraction = fractionByUnit.getOrPut(product.details.baseUnitId) {
                // La unidad base de un producto siempre existe: su llave no deja borrarla.
                (units.findById(product.details.baseUnitId) ?: damaged()).allowsFraction
            }
            val tax = PurchasePricing.taxRateIdOf(product, taxes)?.let { rateId ->
                taxByRate.getOrPut(rateId) {
                    // Su llave deja sin tasa al producto y al negocio si se elimina: nunca falta.
                    val rate = taxRates.findById(rateId) ?: damaged()
                    AppliedTax(rate.details.rate, rate.details.name.take(MAX_TAX_NAME_LENGTH))
                }
            } ?: AppliedTax.NONE
            product.id to PurchaseSubject(
                product = product,
                allowsFraction = allowsFraction,
                taxRate = tax.rate,
                taxName = tax.name,
                purchaseUnits = purchaseUnitsOf(product.id, product.details.baseUnitId, lines),
            )
        }
    }

    /**
     * Las conversiones de compra de [productId] que piden sus líneas, leídas dentro de la
     * transacción para congelar el factor que hay al confirmar. Una que no existe o no es de
     * compra queda fuera, y el cálculo rechaza su línea.
     */
    private suspend fun purchaseUnitsOf(
        productId: Long,
        baseUnitId: Long,
        lines: List<PurchaseLineRequest>,
    ): Map<Long, PurchaseUnit> {
        val asked = lines.filter { it.productId == productId }
            .mapNotNull { it.unitId }
            .filter { it != baseUnitId }
            .toSet()
        if (asked.isEmpty()) return emptyMap()
        val repository =
            requireNotNull(productUnits) { "Comprar en otra unidad pide las conversiones" }
        val found = HashMap<Long, PurchaseUnit>()
        for (unitId in asked) {
            val conversion = repository.findByProductAndUnit(productId, unitId) ?: continue
            if (!conversion.details.forPurchase) continue
            // La unidad de una conversión siempre existe: su llave no deja borrarla.
            val unit = units.findById(unitId) ?: damaged()
            found[unitId] = PurchaseUnit(conversion.details.factorMilli, unit.allowsFraction)
        }
        return found
    }

    /**
     * La ubicación donde entra lo comprado: la elegida, si sigue vigente, o la inicial sin elegir.
     * `null` si la elegida ya no existe o se archivó. Sin ubicaciones conectadas solo se acepta la
     * inicial.
     */
    private suspend fun locationOf(request: PurchaseRequest): Long? {
        val initial = checkNotNull(systemRows.findId(SystemRow.MAIN_LOCATION)) {
            "Falta una fila inicial de la base"
        }
        val chosen = request.locationId ?: return initial
        if (chosen == initial) return initial
        return locations?.findById(chosen)?.takeUnless { it.isArchived }?.id
    }

    private suspend fun write(
        location: Long,
        supplierId: Long?,
        reference: String?,
        lines: List<Pair<PricedPurchaseLine, PurchaseLineLot?>>,
        totals: PurchaseTotals,
        settlement: Settlement,
        time: RecordTime,
        now: Instant,
    ): Long {
        val purchaseId = purchases.insert(
            NewPurchase(
                supplierId = supplierId,
                invoiceReference = reference,
                netTotal = totals.net,
                taxTotal = totals.tax,
                total = totals.total,
                costTotal = totals.cost,
                amountPaid = settlement.amountPaid,
                moneyAccountId = settlement.moneyAccountId,
                dueDate = settlement.dueDate,
                locationId = location,
                foreignCurrency = null,
                occurredAt = time.occurredAt,
                businessDate = time.businessDate,
                createdAt = now,
            ),
        )
        for ((line, lot) in lines) {
            val product = line.subject.product
            val lotId = lotOf(product.id, product.details.tracksLots, lot, now)
            val lineId = purchases.insertLine(purchaseId, line.toNewLine(lotId))
            // Un lote nuevo recuerda la línea con que entró, que recién ahora tiene id.
            if (lot is PurchaseLineLot.New && lotId != null) {
                requireNotNull(lots).attachPurchaseLine(lotId, lineId)
            }
            ledger.append(
                NewStockMovement(
                    movement = StockMovementDraft(
                        key = StockKey(product.id, location, lotId),
                        type = StockMovementType.PURCHASE,
                        quantity = line.quantity,
                        reversesMovementId = null,
                    ),
                    reason = null,
                    unitCost = line.movementCost,
                    totalCost = line.split.costAmount,
                    balanceAfter = line.after.onHand,
                    averageCostAfter = line.after.averageCost,
                    stockValueAfter = line.after.stockValue,
                    source = StockMovementSource(purchaseLineId = lineId),
                    note = null,
                    occurredAt = time.occurredAt,
                    businessDate = time.businessDate,
                    createdAt = now,
                ),
            )
        }
        return purchaseId
    }

    /**
     * El motivo por el que un lote no sirve, leído en la base: un lote elegido que no existe o es
     * de otro producto, o un código nuevo que el producto ya tiene. `null` si todos sirven.
     */
    private suspend fun storedLotRejection(
        lines: List<PurchaseLineRequest>,
    ): RegisterPurchaseResult? {
        for ((index, line) in lines.withIndex()) {
            val reason = when (val lot = line.lot) {
                null -> null

                is PurchaseLineLot.Existing -> {
                    val found = requireNotNull(lots) { "Comprar con lote pide los lotes" }
                        .findById(lot.lotId)
                    if (found?.productId == line.productId) {
                        null
                    } else {
                        PurchaseLineRejection.LOT_NOT_FOUND
                    }
                }

                is PurchaseLineLot.New -> {
                    val code = codeOf(lot)
                    val repository = requireNotNull(lots) { "Comprar con lote pide los lotes" }
                    repository.findByCode(line.productId, code)
                        ?.let { PurchaseLineRejection.LOT_CODE_REPEATED }
                }
            }
            if (reason != null) return RegisterPurchaseResult.LineRejected(index, reason)
        }
        return null
    }

    /**
     * El lote de la fila de una línea: ninguno en un producto sin lotes, el elegido, uno nuevo que
     * se crea aquí, o el lote sin asignar si la línea no trae lote.
     */
    private suspend fun lotOf(
        productId: Long,
        tracksLots: Boolean,
        lot: PurchaseLineLot?,
        now: Instant,
    ): Long? = when (lot) {
        null -> if (tracksLots) ledger.unassignedLot(productId, now) else null

        is PurchaseLineLot.Existing -> lot.lotId

        is PurchaseLineLot.New ->
            requireNotNull(lots).insert(
                NewStockLot(
                    productId = productId,
                    code = codeOf(lot),
                    expiryDate = lot.expiry,
                    purchaseLineId = null,
                    createdAt = now,
                ),
            )
    }

    /** El código recortado de un lote nuevo, que el cálculo de la compra ya revisó. */
    private fun codeOf(lot: PurchaseLineLot.New): String = when (
        val check = LotRules.checkCode(lot.code)
    ) {
        is LotCodeCheck.Valid -> check.code
        LotCodeCheck.Blank, is LotCodeCheck.TooLong -> error("El cálculo ya rechazó ese código")
    }

    private fun PricedPurchaseLine.toNewLine(lotId: Long?): NewPurchaseLine = NewPurchaseLine(
        productId = subject.product.id,
        unitId = unitId,
        unitFactor = unitFactor,
        enteredQuantity = enteredQuantity,
        quantity = quantity,
        unitCost = amounts.unitCost,
        lineTotal = split.lineTotal,
        taxRate = subject.taxRate,
        taxNameSnapshot = subject.taxName,
        taxAmount = split.taxAmount,
        costAmount = split.costAmount,
        lotId = lotId,
        referenceUnitCost = null,
    )

    private fun reject(result: RegisterPurchaseResult): TxResult<RegisterPurchaseResult> =
        TxResult.Rollback(result)

    /** Una fila que la base garantiza y no está: la base está dañada, y no se escribe nada. */
    private fun damaged(): Nothing =
        throw StorageFailureException(StorageFailure.INVARIANT_VIOLATION)

    /**
     * Cómo se paga la compra: [amountPaid] al confirmar, [credit] lo que queda debiendo, o `null`
     * si se pagó completa, y [dueDate] el vencimiento de esa deuda, si tiene. [moneyAccountId] es
     * la cuenta de la que sale lo pagado, si la hay.
     */
    private data class Settlement(
        val amountPaid: Money,
        val credit: Money?,
        val dueDate: BusinessDate?,
        val moneyAccountId: Long? = null,
    )

    private sealed interface SettlementResult {
        data class Ready(val settlement: Settlement) : SettlementResult

        data class Rejected(val reason: RegisterPurchaseResult) : SettlementResult
    }

    /** La tasa que copia una línea: los puntos básicos y el nombre del momento. */
    private class AppliedTax(val rate: BasisPoints, val name: String?) {
        companion object {
            val NONE = AppliedTax(BasisPoints.ZERO, name = null)
        }
    }

    private companion object {
        /** Largo máximo de la referencia del documento del proveedor. */
        const val MAX_REFERENCE_LENGTH = 40

        /** Largo máximo del nombre de impuesto que copia una línea. */
        const val MAX_TAX_NAME_LENGTH = 40
    }
}
