package io.github.yesidrobayo.shopledger.domain.usecase.sale

import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.Checked
import io.github.yesidrobayo.shopledger.domain.model.Limit
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.catalog.Product
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductStockMode
import io.github.yesidrobayo.shopledger.domain.model.catalog.SystemRow
import io.github.yesidrobayo.shopledger.domain.model.customer.CustomerEntryDraft
import io.github.yesidrobayo.shopledger.domain.model.customer.CustomerEntrySource
import io.github.yesidrobayo.shopledger.domain.model.customer.CustomerLedgerEntryType
import io.github.yesidrobayo.shopledger.domain.model.customer.LoyaltyEntryDraft
import io.github.yesidrobayo.shopledger.domain.model.customer.LoyaltyEntrySource
import io.github.yesidrobayo.shopledger.domain.model.customer.LoyaltyEntryType
import io.github.yesidrobayo.shopledger.domain.model.money.AccountCurrency
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyMovementDraft
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyMovementType
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyOrigin
import io.github.yesidrobayo.shopledger.domain.model.payment.PaymentMethodKind
import io.github.yesidrobayo.shopledger.domain.model.region.RecordTime
import io.github.yesidrobayo.shopledger.domain.model.sale.NewSale
import io.github.yesidrobayo.shopledger.domain.model.sale.NewSaleLine
import io.github.yesidrobayo.shopledger.domain.model.sale.NewSalePayment
import io.github.yesidrobayo.shopledger.domain.model.sale.PaymentRejection
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleLineRequest
import io.github.yesidrobayo.shopledger.domain.model.sale.SalePaymentMethod
import io.github.yesidrobayo.shopledger.domain.model.sale.SalePaymentRequest
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleRequest
import io.github.yesidrobayo.shopledger.domain.model.settings.BusinessCurrency
import io.github.yesidrobayo.shopledger.domain.model.settings.LoyaltySettings
import io.github.yesidrobayo.shopledger.domain.model.settings.TaxMode
import io.github.yesidrobayo.shopledger.domain.model.settings.TaxSettings
import io.github.yesidrobayo.shopledger.domain.model.stock.CostState
import io.github.yesidrobayo.shopledger.domain.model.stock.NewStockMovement
import io.github.yesidrobayo.shopledger.domain.model.stock.StockKey
import io.github.yesidrobayo.shopledger.domain.model.stock.StockMovementSource
import io.github.yesidrobayo.shopledger.domain.model.sumOrNull
import io.github.yesidrobayo.shopledger.domain.model.valueOrNull
import io.github.yesidrobayo.shopledger.domain.port.AppSettingsRepository
import io.github.yesidrobayo.shopledger.domain.port.Clock
import io.github.yesidrobayo.shopledger.domain.port.CustomerLedgerRepository
import io.github.yesidrobayo.shopledger.domain.port.CustomerRepository
import io.github.yesidrobayo.shopledger.domain.port.HeldSaleRepository
import io.github.yesidrobayo.shopledger.domain.port.LowStockAlertRepository
import io.github.yesidrobayo.shopledger.domain.port.LoyaltyLedgerRepository
import io.github.yesidrobayo.shopledger.domain.port.MoneyAccountRepository
import io.github.yesidrobayo.shopledger.domain.port.MoneyLedgerRepository
import io.github.yesidrobayo.shopledger.domain.port.PaymentMethodNames
import io.github.yesidrobayo.shopledger.domain.port.PriceListRepository
import io.github.yesidrobayo.shopledger.domain.port.ProductPriceRepository
import io.github.yesidrobayo.shopledger.domain.port.ProductRepository
import io.github.yesidrobayo.shopledger.domain.port.ProductUnitRepository
import io.github.yesidrobayo.shopledger.domain.port.SalePaymentRepository
import io.github.yesidrobayo.shopledger.domain.port.SaleRepository
import io.github.yesidrobayo.shopledger.domain.port.StockLedgerRepository
import io.github.yesidrobayo.shopledger.domain.port.StorageFailure
import io.github.yesidrobayo.shopledger.domain.port.StorageFailureException
import io.github.yesidrobayo.shopledger.domain.port.SystemRowRepository
import io.github.yesidrobayo.shopledger.domain.port.TaxRateRepository
import io.github.yesidrobayo.shopledger.domain.port.TransactionRunner
import io.github.yesidrobayo.shopledger.domain.port.TxResult
import io.github.yesidrobayo.shopledger.domain.port.UnitRepository
import io.github.yesidrobayo.shopledger.domain.rule.CashRounding
import io.github.yesidrobayo.shopledger.domain.rule.LineTax
import io.github.yesidrobayo.shopledger.domain.rule.TaxCalculator
import io.github.yesidrobayo.shopledger.domain.rule.UnitQuantityCheck
import io.github.yesidrobayo.shopledger.domain.rule.UnitQuantityRule
import io.github.yesidrobayo.shopledger.domain.rule.catalog.LineStockPlan
import io.github.yesidrobayo.shopledger.domain.rule.catalog.LineStockSubject
import io.github.yesidrobayo.shopledger.domain.rule.catalog.StockModeRules
import io.github.yesidrobayo.shopledger.domain.rule.customer.CustomerEntryIssue
import io.github.yesidrobayo.shopledger.domain.rule.customer.LoyaltyLedgerRules
import io.github.yesidrobayo.shopledger.domain.rule.customer.LoyaltyProgramRules
import io.github.yesidrobayo.shopledger.domain.rule.customer.RedemptionCheck
import io.github.yesidrobayo.shopledger.domain.rule.pricing.LinePriceList
import io.github.yesidrobayo.shopledger.domain.rule.pricing.SalePriceList
import io.github.yesidrobayo.shopledger.domain.rule.region.BusinessCalendar
import io.github.yesidrobayo.shopledger.domain.rule.region.BusinessCalendarResult
import io.github.yesidrobayo.shopledger.domain.rule.sale.AdjustmentSplit
import io.github.yesidrobayo.shopledger.domain.rule.sale.OrderAdjustmentShares
import io.github.yesidrobayo.shopledger.domain.rule.sale.PaymentFees
import io.github.yesidrobayo.shopledger.domain.rule.sale.PaymentSplit
import io.github.yesidrobayo.shopledger.domain.rule.sale.PaymentSplitCheck
import io.github.yesidrobayo.shopledger.domain.rule.sale.PaymentSurcharges
import io.github.yesidrobayo.shopledger.domain.rule.sale.PaymentTips
import io.github.yesidrobayo.shopledger.domain.rule.sale.SaleLineRules
import io.github.yesidrobayo.shopledger.domain.rule.sale.SaleUnit
import io.github.yesidrobayo.shopledger.domain.rule.sale.SaleUnitResult
import io.github.yesidrobayo.shopledger.domain.rule.sale.SaleUnits
import io.github.yesidrobayo.shopledger.domain.rule.sale.SurchargeSettlement
import io.github.yesidrobayo.shopledger.domain.usecase.customer.CustomerEntryOutcome
import io.github.yesidrobayo.shopledger.domain.usecase.customer.CustomerLedgerWriter
import io.github.yesidrobayo.shopledger.domain.usecase.customer.LoyaltyEntryOutcome
import io.github.yesidrobayo.shopledger.domain.usecase.customer.LoyaltyLedgerWriter
import io.github.yesidrobayo.shopledger.domain.usecase.money.MoneyAppendResult
import io.github.yesidrobayo.shopledger.domain.usecase.money.MoneyLedgerWriter
import io.github.yesidrobayo.shopledger.domain.usecase.region.CurrencyLock
import io.github.yesidrobayo.shopledger.domain.usecase.region.CurrencyLockResult
import io.github.yesidrobayo.shopledger.domain.usecase.stock.StockChangeRecorder
import kotlin.time.Instant

/**
 * Confirma una venta: cabecera, líneas, salidas del libro de stock con sus saldos, y su pago, todo
 * en una sola transacción. Si cualquier paso falla o una regla rechaza, no queda nada: una venta a
 * medias es un dato falso guardado como bueno.
 *
 * - **Nunca se bloquea por stock.** Vender más de lo que hay deja el saldo negativo, y ese negativo
 *   avisa que falta registrar una compra o un conteo.
 * - **El costo se congela.** Cada línea guarda el costo promedio del momento para mostrarlo, y su
 *   costo real sale del valor del stock. Nada posterior lo reescribe.
 * - **Todo se lee dentro de la transacción**, que toma el bloqueo de escritura al empezar: el saldo
 *   y el costo con que se calcula cada salida son los que hay al confirmar. Dos líneas del mismo
 *   producto encadenan su costo.
 * - **La moneda se bloquea con la primera venta**, en su misma transacción: sin moneda confirmada
 *   no se vende, porque cada monto se guarda en su unidad menor.
 * - **Un pago por método**, al menos uno, también con total cero. La venta común tiene un solo
 *   pago que cubre todo lo que se cobra; cobrada con varios métodos, sus pagos deben sumar
 *   exactamente lo que se cobra, o no se confirma. Cada pago lleva la comisión y la acreditación
 *   que la persona escribió en su método, y copia el nombre y el tipo del método, y su cuenta.
 * - **El ajuste de toda la venta** es el descuento o recargo que escribe la persona, con signo.
 *   Se reparte entre las líneas en proporción a lo que cada una cobra tras su descuento, y un
 *   descuento mayor que eso se rechaza. Los pagos escritos cubren lo que se cobra con él.
 * - **El recargo o descuento de cada método** se calcula sobre lo que cubre su pago antes del
 *   recargo, se suma al ajuste de la venta y se reparte entre las líneas junto con el ajuste
 *   escrito, en un solo reparto. Cada pago cubre además lo que su recargo sube el total.
 * - **El impuesto se calcula línea por línea**, con el modo del negocio y la tasa del producto, o
 *   la tasa por defecto si el producto no tiene. Cada línea copia la tasa y su nombre, así que
 *   cambiar o archivar la tasa después no toca esta venta. El impuesto sale de lo que la línea
 *   cobra después de su descuento y de su parte del ajuste, así que un recargo también paga
 *   impuesto. Sumar los impuestos de las líneas, y nunca calcularlo sobre el total, hace que el
 *   total sea siempre lo neto más el impuesto, y los pagos cobran ese total.
 *
 * - **Se vende en otra unidad** con la conversión de venta del producto: la línea congela su
 *   factor, cobra por unidad entera de esa unidad y descuenta del libro la cantidad llevada a la
 *   unidad base. Una unidad que el producto no vende se rechaza con su motivo.
 * - **Una venta retomada de la espera** sale de la espera en la misma transacción en que se
 *   confirma: si la venta se deshace, la espera sigue ahí, y nunca quedan las dos.
 * - **El efectivo se redondea** al paso que escribió la persona, pago por pago y ya con el recargo
 *   de su método: cada pago en efectivo se entrega aparte. Los demás métodos cobran el monto exacto.
 *   Los pagos escritos cubren lo que se cobra antes del redondeo, y cada pago guarda su monto ya
 *   redondeado; la suma de los redondeos es el de la venta, y lo que se cobra es el total más ese
 *   redondeo.
 * - **Lo recibido y el cambio** van solo en efectivo: el cambio es lo recibido menos el pago ya
 *   redondeado, y lo recibido que no alcanza se rechaza.
 *
 * - **La propina** va aparte de la venta: los pagos la cubren, paga la comisión de su método, pero
 *   no su recargo, y nunca entra en lo que cobran las líneas ni en ninguna ganancia. Se reparte
 *   entre los pagos en proporción a lo que cubre cada uno. Solo se acepta con las propinas
 *   encendidas.
 *
 * - **A crédito** se vende con un cliente: los pagos a crédito, sumados, entran como un solo cargo
 *   `CREDIT_SALE` en su libro, con el vencimiento elegido y ligado a la venta, en esta misma
 *   transacción. Sin cliente, un pago a crédito se rechaza. El cliente debe estar vigente: uno
 *   archivado o anonimizado ya no se ofrece al vender.
 *
 * - **Los puntos no son dinero.** Con el programa de puntos activo y un cliente, la venta gana lo
 *   neto que queda después del canje dividido por el monto de un punto, truncado, y lo escribe
 *   como un `EARN` en el libro de puntos, en esta misma transacción. El canje descuenta los puntos
 *   pedidos, nunca más que el saldo del libro, y su descuento entra en el ajuste de toda la venta,
 *   nunca como un pago: se reparte entre las líneas junto con el ajuste escrito. Se escribe como un
 *   `REDEEM`, antes del `EARN`. Lo que pasaría del tope de puntos no se gana: ganar nunca deshace
 *   una venta.
 * - **El dinero entra en su cuenta** si el método del pago tiene una: un `SALE_RECEIPT` por lo
 *   cobrado y un `FEE` por la comisión, ligados al pago, disponibles en su fecha de acreditación y
 *   en esta misma transacción. Sin cuenta no se escribe nada, ni se lee ninguna cuenta: quien no
 *   usa cuentas no paga nada por ellas. Una cuenta archivada o en otra moneda no recibe el pago:
 *   mezclar monedas daría un saldo sin sentido, y bloquear la venta por eso sería peor. La venta se
 *   guarda igual, el pago queda sin cuenta y el resultado lo avisa. Con una caja abierta en la
 *   cuenta, la venta queda en esa sesión.
 *
 * Por ahora no vende combos ni productos con lotes: los rechaza con su motivo.
 *
 * Con [alerts], evalúa en la misma transacción la alerta de stock bajo de cada producto que mueve,
 * una sola vez por producto. Sin él no la evalúa: solo lo omiten las pruebas que no miran alertas.
 */
class RegisterSaleUseCase(
    private val transactions: TransactionRunner,
    private val settings: AppSettingsRepository,
    private val products: ProductRepository,
    private val sales: SaleRepository,
    private val payments: SalePaymentRepository,
    private val units: UnitRepository,
    private val systemRows: SystemRowRepository,
    ledger: StockLedgerRepository,
    private val methodNames: PaymentMethodNames,
    private val taxRates: TaxRateRepository,
    private val clock: Clock,
    private val heldSales: HeldSaleRepository? = null,
    private val productUnits: ProductUnitRepository? = null,
    private val alerts: LowStockAlertRepository? = null,
    private val customers: CustomerRepository? = null,
    customerLedger: CustomerLedgerRepository? = null,
    loyaltyLedger: LoyaltyLedgerRepository? = null,
    private val moneyAccounts: MoneyAccountRepository? = null,
    moneyLedger: MoneyLedgerRepository? = null,
    private val priceLists: PriceListRepository? = null,
    private val productPrices: ProductPriceRepository? = null,
) {
    private val currencyLock = CurrencyLock(settings)
    private val ledger = StockChangeRecorder(ledger)
    private val customerLedger = customerLedger?.let(::CustomerLedgerWriter)
    private val loyaltyLedger = loyaltyLedger?.let(::LoyaltyLedgerWriter)
    private val money = moneyAccounts?.let { accounts ->
        moneyLedger?.let { book -> MoneyLedgerWriter(accounts, book) }
    }

    suspend operator fun invoke(request: SaleRequest): RegisterSaleResult = try {
        transactions.write { register(request) }
    } catch (failure: StorageFailureException) {
        RegisterSaleResult.StorageFailed(failure.failure)
    }

    private suspend fun register(request: SaleRequest): TxResult<RegisterSaleResult> {
        ledger.start()
        shapeRejection(request)?.let { return TxResult.Rollback(it) }
        val note = request.note?.takeIf { it.isNotBlank() }
        val current = settings.get()
        // Una propina escrita con las propinas apagadas no se guarda: la pantalla no la ofrecía.
        if (request.tipAmount > Money.ZERO && !current.sales.tipsEnabled) {
            return TxResult.Rollback(RegisterSaleResult.TipNotAccepted)
        }

        val calendar = when (val result = BusinessCalendar.of(current.regional)) {
            is BusinessCalendarResult.Ready -> result.calendar

            is BusinessCalendarResult.UnknownZone ->
                return TxResult.Rollback(RegisterSaleResult.UnknownTimeZone(result.storedZoneId))
        }
        val now = clock.now()
        val time = recordTime(calendar, request.date, now)
            ?: return TxResult.Rollback(RegisterSaleResult.FutureDate)
        customerRejection(request, time)?.let { return TxResult.Rollback(it) }
        val redemption = when (val result = redemptionOf(request, current.loyalty)) {
            is Redemption.Points -> result
            is Redemption.Rejected -> return TxResult.Rollback(result.rejection)
        }

        val chosen = when (val result = choosePayments(request)) {
            is Chosen.Payments -> result.payments
            is Chosen.Rejected -> return TxResult.Rollback(result.rejection)
        }

        val choice = priceListOf(request)
            ?: return TxResult.Rollback(RegisterSaleResult.PriceListUnavailable)
        val priced = when (val result = priceLines(request.lines, current.taxes, choice.prices)) {
            is Priced.Lines -> result
            is Priced.Rejected -> return TxResult.Rollback(result.rejection)
        }
        val due = when (
            val result = withWrittenAdjustment(priced, request.orderAdjustment, redemption.discount)
        ) {
            is Priced.Lines -> result
            is Priced.Rejected -> return TxResult.Rollback(result.rejection)
        }
        val step = current.regional.cashRounding
        val settled = when (
            val result = settle(
                chosen,
                priced,
                due,
                request.tipAmount,
                step,
                time.businessDate,
                now,
            )
        ) {
            is Settled.Payments -> result
            is Settled.Rejected -> return TxResult.Rollback(result.rejection)
        }

        // Justo antes de escribir: la primera venta bloquea la moneda en esta misma transacción, y
        // si la venta se deshace, el bloqueo también.
        val currency = when (val lock = currencyLock.lockForMoneyRecord(now)) {
            is CurrencyLockResult.Locked -> lock.currency

            CurrencyLockResult.Unconfirmed ->
                return TxResult.Rollback(RegisterSaleResult.CurrencyNotConfirmed)
        }
        val plan = moneyPlanOf(settled.payments, currency)
        val written = write(settled, plan, request.customerId, choice.listId, note, time, now)
        val saleId = written.saleId
        chargeOnAccount(saleId, request, settled.payments, time)?.let {
            return TxResult.Rollback(it)
        }
        val earned = rewardPoints(
            saleId,
            request.customerId,
            redemption.points,
            settled.priced.totals.net,
            current.loyalty,
            time,
        )
        moveMoney(written.paymentIds, plan.payments, time, now)?.let {
            return TxResult.Rollback(it)
        }
        request.heldSaleId?.let { held ->
            // Si ya no existe, alguien la descartó en el intermedio: la venta igual se confirma.
            requireNotNull(heldSales) { "Una venta de la espera necesita sus ventas en espera" }
                .delete(held)
        }
        alerts?.let { ledger.applyAlerts(it, products, calendar.stamp(now)) }
        return TxResult.Commit(
            RegisterSaleResult.Registered(saleId, earned, moneyAccountSkipped = plan.skipped),
        )
    }

    /**
     * Qué pagos mueven su cuenta, leído dentro de la transacción. Sin ningún pago con cuenta no lee
     * nada. Un pago cuya cuenta está archivada o lleva otra moneda que el negocio, con otro código o
     * con otros decimales, se guarda sin cuenta: así ningún pago dice haber entrado en una cuenta
     * que no se movió. La venta queda en la caja abierta de la primera cuenta que la tenga.
     */
    private suspend fun moneyPlanOf(
        salePayments: List<NewSalePayment>,
        currency: BusinessCurrency,
    ): MoneyPlan {
        if (salePayments.all { it.moneyAccountId == null }) return MoneyPlan(salePayments)
        val accounts =
            checkNotNull(moneyAccounts) { "Un pago con cuenta necesita las cuentas de dinero" }
        val business = AccountCurrency.of(currency)
        var cashSessionId: Long? = null
        var skipped = false
        val planned = salePayments.map { payment ->
            val accountId = payment.moneyAccountId ?: return@map payment
            // La cuenta de un método siempre existe: su llave no deja borrarla.
            val account = accounts.findById(accountId) ?: damaged()
            if (account.isArchived || account.currency != business) {
                // Un pago en cero no iba a mover nada: no hay nada que avisar.
                skipped = skipped || payment.amount > Money.ZERO
                payment.copy(moneyAccountId = null)
            } else {
                if (cashSessionId == null) cashSessionId = accounts.openCashSessionId(accountId)
                payment
            }
        }
        return MoneyPlan(planned, cashSessionId, skipped)
    }

    /**
     * Agrega al libro de dinero lo que mueve cada pago con cuenta: lo cobrado y, si la hay, la
     * comisión, los dos con la fecha de acreditación del pago. [paymentIds] son los ids de
     * [salePayments], en el mismo orden. Devuelve el rechazo que deshace la venta, o `null`.
     */
    private suspend fun moveMoney(
        paymentIds: List<Long>,
        salePayments: List<NewSalePayment>,
        time: RecordTime,
        now: Instant,
    ): RegisterSaleResult? {
        for ((paymentId, payment) in paymentIds.zip(salePayments)) {
            val accountId = payment.moneyAccountId ?: continue
            val writer = checkNotNull(money) { "Un pago con cuenta necesita el libro de dinero" }
            for (draft in movementsOf(accountId, paymentId, payment)) {
                when (writer.append(draft, time, now)) {
                    is MoneyAppendResult.Appended -> Unit

                    // Se leyó en esta misma transacción: no puede haber desaparecido.
                    MoneyAppendResult.AccountNotFound -> damaged()

                    // Un saldo que no cabe en Long es, como un total que no cabe, una venta que no
                    // se puede guardar.
                    MoneyAppendResult.Overflow -> return RegisterSaleResult.SaleTotalTooLarge
                }
            }
        }
        return null
    }

    /**
     * Lo que el pago [paymentId] mueve en la cuenta [accountId]: lo cobrado, si es más que cero, y
     * la comisión, si la hay. Un pago en cero no mueve nada.
     */
    private fun movementsOf(
        accountId: Long,
        paymentId: Long,
        payment: NewSalePayment,
    ): List<MoneyMovementDraft> {
        val origin = MoneyOrigin.SalePayment(paymentId)
        val receipt = payment.amount.takeIf { it > Money.ZERO }?.let {
            MoneyMovementDraft(
                accountId = accountId,
                type = MoneyMovementType.SALE_RECEIPT,
                amount = it,
                origin = origin,
                availableDate = payment.settlementDate,
            )
        }
        val fee = payment.feeAmount.takeIf { it > Money.ZERO }?.let {
            MoneyMovementDraft(
                accountId = accountId,
                type = MoneyMovementType.FEE,
                amount = -it,
                origin = origin,
                availableDate = payment.settlementDate,
            )
        }
        return listOfNotNull(receipt, fee)
    }

    /** Lo que se rechaza sin leer la base: la forma de la venta. */
    private fun shapeRejection(request: SaleRequest): RegisterSaleResult? = when {
        request.lines.isEmpty() -> RegisterSaleResult.EmptySale

        request.lines.size > Limit.DOCUMENT_LINES.max -> RegisterSaleResult.TooManyLines

        (request.note?.length ?: 0) > MAX_NOTE_LENGTH -> RegisterSaleResult.NoteTooLong

        request.tipAmount < Money.ZERO || !Limit.DOCUMENT_TOTAL.allows(request.tipAmount.minor) ->
            RegisterSaleResult.TipNotAccepted

        else -> paymentRejection(request)
    }

    /** El primer pago escrito que no se puede guardar, mirado sin leer la base. */
    private fun paymentRejection(request: SaleRequest): RegisterSaleResult? =
        PaymentSplit.firstRejection(request.payments)?.let {
            RegisterSaleResult.PaymentRejected(it.index, it.value)
        }

    /**
     * El instante y la fecha de la venta: ahora, si la fecha es hoy o no se eligió; las 12:00 de
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
     * El método de cada pago, leído dentro de la transacción para congelarlo tal como está al
     * confirmar. Sin pagos escritos, el único pago es el método de la venta, y cubre todo lo que se
     * cobra.
     */
    private suspend fun choosePayments(request: SaleRequest): Chosen {
        val hasCustomer = request.customerId != null
        val written = request.payments.isNotEmpty()
        val requested = request.payments.ifEmpty {
            listOf(SalePaymentRequest(request.paymentMethodId, Money.ZERO))
        }
        val chosen = ArrayList<ChosenPayment>(requested.size)
        for ((index, payment) in requested.withIndex()) {
            val method = payments.findMethod(payment.paymentMethodId)
                ?: return Chosen.Rejected(RegisterSaleResult.PaymentMethodNotFound)
            methodRejection(method, hasCustomer)?.let { return Chosen.Rejected(it) }
            if (payment.tenderedAmount != null && method.kind != PaymentMethodKind.CASH) {
                return Chosen.Rejected(
                    RegisterSaleResult.PaymentRejected(index, PaymentRejection.TENDER_NOT_CASH),
                )
            }
            chosen += ChosenPayment(
                method,
                payment.amount.takeIf { written },
                payment.feeAmount,
                payment.tenderedAmount,
            )
        }
        return Chosen.Payments(chosen)
    }

    private fun methodRejection(
        method: SalePaymentMethod,
        hasCustomer: Boolean,
    ): RegisterSaleResult? = when {
        method.isArchived -> RegisterSaleResult.PaymentMethodArchived

        method.kind == PaymentMethodKind.ON_ACCOUNT && !hasCustomer ->
            RegisterSaleResult.PaymentNeedsCustomer

        else -> null
    }

    /**
     * Por qué el cliente o el vencimiento de la venta no sirven, leído dentro de la transacción: un
     * cliente que se archivó o se anonimizó mientras se vendía ya no se usa.
     */
    private suspend fun customerRejection(
        request: SaleRequest,
        time: RecordTime,
    ): RegisterSaleResult? {
        val customerId = request.customerId ?: return null
        val customer = checkNotNull(customers) { "Una venta con cliente necesita los clientes" }
            .findById(customerId)
        if (customer == null || customer.isArchived || customer.isAnonymized) {
            return RegisterSaleResult.CustomerUnavailable
        }
        val due = request.creditDueDate ?: return null
        return if (due < time.businessDate) RegisterSaleResult.CreditDueBeforeSale else null
    }

    /**
     * Carga en el libro del cliente lo que se pagó a crédito, como un solo `CREDIT_SALE` ligado a la
     * venta. Así los cargos de la venta suman siempre lo pagado a crédito. Devuelve el rechazo que
     * deshace la venta, o `null` si quedó escrito o no había nada a crédito.
     */
    private suspend fun chargeOnAccount(
        saleId: Long,
        request: SaleRequest,
        salePayments: List<NewSalePayment>,
        time: RecordTime,
    ): RegisterSaleResult? {
        val onAccount = salePayments.filter { it.methodKind == PaymentMethodKind.ON_ACCOUNT }
            .map { it.amount }
            .sumOrNull() ?: return RegisterSaleResult.SaleTotalTooLarge
        if (onAccount <= Money.ZERO) return null
        val customerId = checkNotNull(request.customerId) { "El pago a crédito ya exigió cliente" }
        val writer =
            checkNotNull(customerLedger) { "Una venta a crédito necesita el libro del cliente" }
        val draft = CustomerEntryDraft(
            customerId = customerId,
            type = CustomerLedgerEntryType.CREDIT_SALE,
            amount = onAccount,
            source = CustomerEntrySource.Sale(saleId),
            dueDate = request.creditDueDate,
            note = null,
        )
        return when (val outcome = writer.append(draft, time)) {
            is CustomerEntryOutcome.Appended -> null

            is CustomerEntryOutcome.Rejected -> when (outcome.issue) {
                CustomerEntryIssue.BALANCE_OVERFLOW -> RegisterSaleResult.SaleTotalTooLarge

                // El cargo se arma aquí con su tipo, su signo y su venta: otro rechazo es un error
                // de programación, no algo que la persona pueda corregir.
                CustomerEntryIssue.ZERO_AMOUNT, CustomerEntryIssue.WRONG_SIGN,
                CustomerEntryIssue.WRONG_SOURCE, CustomerEntryIssue.NOTE_REQUIRED,
                CustomerEntryIssue.NOTE_TOO_LONG,
                -> error("Cargo a crédito mal armado: " + outcome.issue)
            }

            CustomerEntryOutcome.EntryNotFound, CustomerEntryOutcome.AlreadyCompensated,
            CustomerEntryOutcome.NotReversible,
            -> error("Agregar un cargo no compensa ninguna fila")
        }
    }

    /**
     * Los puntos que se canjean y su descuento, leídos dentro de la transacción: el saldo es el de
     * su libro al confirmar. Sin canje, cero puntos y ningún descuento. El canje exige cliente y el
     * programa activo, y nunca supera el saldo.
     */
    private suspend fun redemptionOf(request: SaleRequest, loyalty: LoyaltySettings): Redemption {
        val points = request.pointsRedeemed
        if (points == 0L) return Redemption.NONE
        val customerId = request.customerId
        val pointValue = loyalty.pointValue
        if (customerId == null || !loyalty.enabled || pointValue == null || points < 0L) {
            return Redemption.Rejected(RegisterSaleResult.PointsNotAvailable)
        }
        val writer = checkNotNull(loyaltyLedger) { "Un canje necesita el libro de puntos" }
        val available = writer.balanceOf(customerId)
        return when (val check = LoyaltyLedgerRules.redemption(points, available, pointValue)) {
            is RedemptionCheck.Discount -> Redemption.Points(check.points, check.discount)

            is RedemptionCheck.NotEnough ->
                Redemption.Rejected(RegisterSaleResult.NotEnoughPoints(check.available))

            RedemptionCheck.AboveSale -> Redemption.Rejected(RegisterSaleResult.PointsAboveSale)

            // Cero o negativo ya se resolvió arriba.
            RedemptionCheck.NothingToRedeem -> error("Un canje sin puntos ya salió antes")
        }
    }

    /**
     * Escribe en el libro de puntos lo que la venta canjea y lo que gana, en ese orden, y devuelve
     * lo ganado. Sin cliente o con el programa apagado no escribe nada. Lo ganado sale de [net], lo
     * neto de la venta después del canje, y lo que pasaría del tope de puntos no se suma.
     */
    private suspend fun rewardPoints(
        saleId: Long,
        customerId: Long?,
        redeemed: Long,
        net: Money,
        loyalty: LoyaltySettings,
        time: RecordTime,
    ): Long {
        if (customerId == null || !loyalty.enabled) return 0L
        // Sin el monto de un punto no se gana nada, pero un canje ya aceptado se escribe igual.
        val earned = loyalty.amountPerPoint?.let { LoyaltyProgramRules.pointsEarned(net, it) } ?: 0L
        if (redeemed == 0L && earned == 0L) return 0L
        val writer = checkNotNull(loyaltyLedger) { "Ganar puntos necesita el libro de puntos" }
        if (redeemed > 0L) {
            appendPoints(writer, customerId, LoyaltyEntryType.REDEEM, -redeemed, saleId, time)
        }
        val added = LoyaltyLedgerRules.earnable(earned, writer.balanceOf(customerId))
        if (added > 0L) {
            appendPoints(writer, customerId, LoyaltyEntryType.EARN, added, saleId, time)
        }
        return added
    }

    /**
     * Agrega una fila de puntos de la venta. El canje ya se comprobó contra el saldo y lo ganado ya
     * se recortó al tope, así que un rechazo es un error de programación, no algo que la persona
     * pueda corregir.
     */
    private suspend fun appendPoints(
        writer: LoyaltyLedgerWriter,
        customerId: Long,
        type: LoyaltyEntryType,
        points: Long,
        saleId: Long,
        time: RecordTime,
    ) {
        val draft = LoyaltyEntryDraft(
            customerId = customerId,
            type = type,
            points = points,
            source = LoyaltyEntrySource.Sale(saleId),
            note = null,
        )
        when (val outcome = writer.append(draft, time)) {
            is LoyaltyEntryOutcome.Appended -> Unit

            is LoyaltyEntryOutcome.Rejected -> error("Fila de puntos mal armada: " + outcome.issue)

            LoyaltyEntryOutcome.EntryNotFound, LoyaltyEntryOutcome.AlreadyCompensated,
            LoyaltyEntryOutcome.NotReversible,
            -> error("Agregar puntos no compensa ninguna fila")
        }
    }

    /**
     * La lista de precios de [request], leída dentro de la transacción, o `null` si la elegida ya no
     * existe o está archivada. La inicial no necesita leer nada más: su precio es el de venta.
     */
    private suspend fun priceListOf(request: SaleRequest): ListChoice? {
        val defaultId = systemRows.findId(SystemRow.DEFAULT_PRICE_LIST) ?: damaged()
        val wanted = request.priceListId
        if (wanted == null || wanted == defaultId) return ListChoice(defaultId, prices = null)
        val lists = priceLists ?: return null
        val own = productPrices ?: return null
        val all = lists.findAll().associateBy { it.id }
        if (all[wanted]?.isArchived != false) return null
        val ownPrices = request.lines.map { it.productId }.toSet().associateWith {
            own.findByProduct(it)
        }
        return ListChoice(wanted, SalePriceList(wanted, all, ownPrices))
    }

    /** La lista de la venta, y con qué se resuelven sus precios si no es la inicial. */
    private class ListChoice(val listId: Long, val prices: SalePriceList?)

    /**
     * Calcula cada línea en orden, con los productos leídos de una vez. Un producto que se repite
     * calcula su segunda línea con el estado de costo que dejó la primera. Cada línea separa su
     * impuesto con la tasa que le toca.
     */
    private suspend fun priceLines(
        requests: List<SaleLineRequest>,
        taxes: TaxSettings,
        priceList: SalePriceList?,
    ): Priced {
        val byId = products.findByIds(requests.map { it.productId }.toSet()).associateBy { it.id }
        val costStates = HashMap<Long, CostState>()
        val fractionByUnit = HashMap<Long, Boolean>()
        val taxByRate = HashMap<Long, AppliedTax>()
        val lines = ArrayList<PricedLine>(requests.size)
        val totals = Totals()
        for ((index, request) in requests.withIndex()) {
            val product = byId[request.productId]
                ?: return rejected(index, LineRejection.PRODUCT_NOT_FOUND)
            productRejection(product)?.let { return rejected(index, it) }
            val allowsFraction = fractionByUnit.getOrPut(product.details.baseUnitId) {
                // La unidad base de un producto siempre existe: su llave no deja borrarla.
                val unit = units.findById(product.details.baseUnitId) ?: damaged()
                unit.allowsFraction
            }
            val unit = when (val result = saleUnitOf(request, product, priceList)) {
                is SaleUnitResult.Resolved -> result.unit
                SaleUnitResult.Unavailable -> return rejected(index, LineRejection.UNIT_UNAVAILABLE)
            }
            val unitAllowsFraction = if (unit.isBase) {
                allowsFraction
            } else {
                fractionByUnit.getOrPut(unit.unitId) {
                    // La conversión apunta a su unidad con una llave que no deja borrarla.
                    (units.findById(unit.unitId) ?: damaged()).allowsFraction
                }
            }
            quantityRejection(request.quantity, unitAllowsFraction)?.let {
                return rejected(index, it)
            }
            val baseQuantity = SaleUnits.baseQuantity(request.quantity, unit)
                ?: return rejected(index, LineRejection.INVALID_QUANTITY)
            val tax = appliedTax(product, taxes, taxByRate)
            val result =
                priceLine(request, product, tax, taxes.mode, costStates, unit, baseQuantity)
            val line = when (result) {
                is LineResult.Ready -> result.line
                is LineResult.Rejected -> return rejected(index, result.reason)
            }
            if (!totals.add(line)) return Priced.Rejected(RegisterSaleResult.SaleTotalTooLarge)
            line.plan.let { plan ->
                if (plan is LineStockPlan.Direct) costStates[product.id] = plan.exit.stateAfter
            }
            lines += line
        }
        return Priced.Lines(lines, totals, taxes.mode)
    }

    /**
     * La tasa que paga [product]: la suya, o la tasa por defecto del negocio si no tiene, leída
     * una sola vez por venta. Sin impuesto en el negocio, o sin ninguna tasa, la línea no lleva
     * impuesto ni nombre.
     *
     * Un producto conserva su tasa aunque esté archivada: archivarla solo deja de ofrecerla en
     * productos nuevos.
     */
    private suspend fun appliedTax(
        product: Product,
        taxes: TaxSettings,
        cache: MutableMap<Long, AppliedTax>,
    ): AppliedTax {
        if (taxes.mode == TaxMode.NONE) return AppliedTax.NONE
        val rateId = product.details.taxRateId ?: taxes.defaultTaxRateId ?: return AppliedTax.NONE
        return cache.getOrPut(rateId) {
            // Su llave deja sin tasa al producto y al negocio si se elimina: nunca falta.
            val rate = taxRates.findById(rateId) ?: damaged()
            AppliedTax(rate.details.rate, rate.details.name.take(MAX_TAX_NAME_LENGTH))
        }
    }

    /**
     * La unidad en que se vende [request]: la base, o la conversión de venta del producto, leída
     * dentro de la transacción para congelar el factor que hay al confirmar.
     */
    private suspend fun saleUnitOf(
        request: SaleLineRequest,
        product: Product,
        priceList: SalePriceList?,
    ): SaleUnitResult {
        val unitId = request.unitId ?: product.details.baseUnitId
        val conversions = if (unitId == product.details.baseUnitId) {
            emptyMap()
        } else {
            productUnits?.findByProductAndUnit(product.id, unitId)
                ?.let { mapOf(unitId to it.details) }
                .orEmpty()
        }
        val list = priceList?.priceOf(product) ?: LinePriceList.default(product)
        return SaleUnits.of(request, product, conversions, list)
    }

    private fun productRejection(product: Product): LineRejection? = when {
        product.isArchived -> LineRejection.PRODUCT_ARCHIVED

        product.details.stockMode == ProductStockMode.COMPONENTS ->
            LineRejection.COMPONENTS_NOT_SUPPORTED

        product.details.tracksLots -> LineRejection.LOTS_NOT_SUPPORTED

        else -> null
    }

    private fun quantityRejection(
        quantity: QuantityMilli,
        allowsFraction: Boolean,
    ): LineRejection? {
        if (!Limit.LINE_QUANTITY_MILLI.allows(quantity.milli)) return LineRejection.INVALID_QUANTITY
        return when (UnitQuantityRule.check(quantity, allowsFraction)) {
            UnitQuantityCheck.Valid -> null
            UnitQuantityCheck.NotPositive -> LineRejection.INVALID_QUANTITY
            UnitQuantityCheck.FractionNotAllowed -> LineRejection.FRACTION_NOT_ALLOWED
        }
    }

    private fun priceLine(
        request: SaleLineRequest,
        product: Product,
        tax: AppliedTax,
        taxMode: TaxMode,
        costStates: Map<Long, CostState>,
        unit: SaleUnit,
        baseQuantity: QuantityMilli,
    ): LineResult {
        // El precio es por unidad entera de la unidad de la línea; el stock y el costo, en la base.
        // Sin precio escrito, el de la unidad; uno calculado que no cabe es un precio inválido.
        val unitPrice = request.unitPrice ?: unit.listPrice
            ?: return LineResult.Rejected(LineRejection.INVALID_PRICE)
        if (unitPrice < Money.ZERO || !Limit.UNIT_MONEY.allows(unitPrice.minor)) {
            return LineResult.Rejected(LineRejection.INVALID_PRICE)
        }
        val gross = SaleLineRules.gross(request.quantity, unitPrice).valueOr {
            return LineResult.Rejected(LineRejection.AMOUNT_TOO_LARGE)
        }
        val discount = SaleLineRules.discountOf(gross, request.discount)
            ?: return LineResult.Rejected(LineRejection.INVALID_DISCOUNT)
        val plan = StockModeRules.planLine(stockSubject(product, baseQuantity, costStates))
            .valueOr { return LineResult.Rejected(LineRejection.AMOUNT_TOO_LARGE) }
        val unitCost = when (plan) {
            is LineStockPlan.Direct -> plan.exit.unitCost

            is LineStockPlan.NoStock -> plan.unitCost

            // Un combo se rechaza antes de llegar aquí.
            is LineStockPlan.Components -> error("Un combo no llega a calcular su línea")
        }
        val unitReplacement = SaleLineRules.unitReplacementCost(product.replacementCost, unitCost)
        val lineReplacement = SaleLineRules.lineReplacementCost(baseQuantity, unitReplacement)
            .valueOr { return LineResult.Rejected(LineRejection.AMOUNT_TOO_LARGE) }
        // Sin ajuste de la venta todavía: la línea cobra el bruto menos su descuento, y sobre eso
        // se separa el impuesto. Con el impuesto sumado, una línea al tope deja de caber.
        val base = gross - discount
        val split = TaxCalculator.line(base, taxMode, tax.rate)
            .valueOr { return LineResult.Rejected(LineRejection.AMOUNT_TOO_LARGE) }
        return LineResult.Ready(
            PricedLine(
                product = product,
                quantity = baseQuantity,
                unit = unit,
                enteredQuantity = request.quantity,
                unitPrice = unitPrice,
                unitCost = unitCost,
                discount = discount,
                base = base,
                tax = tax,
                split = split,
                plan = plan,
                unitReplacement = unitReplacement,
                lineReplacement = lineReplacement,
            ),
        )
    }

    private fun stockSubject(
        product: Product,
        quantity: QuantityMilli,
        costStates: Map<Long, CostState>,
    ): LineStockSubject = when (product.details.stockMode) {
        ProductStockMode.TRACKED -> LineStockSubject.Tracked(
            costState = costStates[product.id]
                ?: CostState(product.onHand, product.averageCost, product.stockValue),
            quantity = quantity,
        )

        ProductStockMode.UNTRACKED ->
            LineStockSubject.Untracked(product.details.fixedUnitCost, quantity)

        ProductStockMode.COMPONENTS -> error("Un combo no llega a calcular su línea")
    }

    /**
     * Las líneas con el ajuste que escribió la persona para toda la venta, más el descuento de los
     * puntos canjeados, [pointsDiscount]: es lo que se cobra antes de los recargos de los métodos.
     * Sin ajuste, las mismas líneas. Un descuento escrito mayor que lo que suman las líneas tras sus
     * descuentos se rechaza con su motivo, y uno que solo se pasa con los puntos, con el suyo; un
     * recargo que deja la venta, o una línea, sobre el tope de un documento, como cualquier total
     * demasiado grande.
     */
    private fun withWrittenAdjustment(
        priced: Priced.Lines,
        adjustment: Money,
        pointsDiscount: Money,
    ): Priced {
        if (adjustment == Money.ZERO && pointsDiscount == Money.ZERO) return priced
        // Las bases suman como mucho el tope de líneas por el tope de un documento: cabe en Long.
        val baseSum = priced.lines.fold(Money.ZERO) { sum, line -> sum + line.base }
        // Se compara sin negar el descuento: negar el menor Long no cabe.
        if (adjustment.minor < -Limit.DOCUMENT_TOTAL.max || adjustment.minor < -baseSum.minor) {
            return Priced.Rejected(RegisterSaleResult.OrderDiscountTooLarge)
        }
        // El ajuste escrito y el descuento de los puntos caben en el tope de un documento: la resta
        // cabe en Long.
        val combined = adjustment - pointsDiscount
        if (combined.minor < -baseSum.minor) {
            return Priced.Rejected(RegisterSaleResult.PointsAboveSale)
        }
        return adjusted(priced, combined)
            ?: Priced.Rejected(RegisterSaleResult.SaleTotalTooLarge)
    }

    /**
     * Los pagos que se guardan, en el orden en que se escribieron, y las líneas con todo el ajuste:
     * el escrito, que ya trae [due], más el que suman los recargos de los métodos. Los pagos
     * escritos deben sumar exactamente lo que se cobra en [due], antes de los recargos y del
     * redondeo, y ninguna comisión puede pasar del tope. Los dos ajustes se reparten juntos sobre
     * [priced], las líneas sin ajuste, para que el redondeo sea el de un solo reparto. Cada pago en
     * efectivo se redondea después a [cashStep], ya con su recargo.
     *
     * [tip] se suma a lo que deben cubrir los pagos escritos, y cada pago lleva su parte: su recargo
     * se calcula sin ella, así que la propina nunca termina en las líneas.
     */
    private fun settle(
        chosen: List<ChosenPayment>,
        priced: Priced.Lines,
        due: Priced.Lines,
        tip: Money,
        cashStep: Money,
        businessDate: BusinessDate,
        now: Instant,
    ): Settled {
        // Las dos sumas caben en Long: cada una está dentro del tope de un documento.
        val amountDue = due.totals.total + tip
        if (!Limit.DOCUMENT_TOTAL.allows(amountDue.minor)) {
            return Settled.Rejected(RegisterSaleResult.SaleTotalTooLarge)
        }
        val written = chosen.map { it.amount ?: amountDue }
        when (val check = PaymentSplit.check(amountDue, written)) {
            PaymentSplitCheck.Matches -> Unit

            is PaymentSplitCheck.Mismatch ->
                return Settled.Rejected(
                    RegisterSaleResult.PaymentsDoNotMatch(amountDue, check.paid),
                )

            PaymentSplitCheck.AboveLimit ->
                return Settled.Rejected(RegisterSaleResult.SaleTotalTooLarge)
        }
        val tips = PaymentTips.shares(tip, written)
        val surcharges = PaymentSurcharges.settle(
            amounts = written.zip(tips) { amount, share -> amount - share },
            rates = chosen.map { it.method.priceAdjustment },
            totalWith = { surcharge ->
                adjusted(priced, due.adjustment + surcharge)?.totals?.total
            },
        )
        val (adjustment, amounts) = when (surcharges) {
            is SurchargeSettlement.Settled -> surcharges.adjustment to surcharges.amounts

            SurchargeSettlement.NotAllowed ->
                return Settled.Rejected(RegisterSaleResult.SaleTotalTooLarge)

            is SurchargeSettlement.BelowZero -> return Settled.Rejected(
                RegisterSaleResult.PaymentRejected(
                    surcharges.index,
                    PaymentRejection.BELOW_ZERO_AFTER_ADJUSTMENT,
                ),
            )
        }
        // Los recargos ya se probaron con este mismo ajuste: la venta lo admite.
        val adjustedLines = checkNotNull(adjusted(priced, due.adjustment + adjustment)) {
            "El ajuste ya se probó"
        }
        var rounding = Money.ZERO
        val settled = ArrayList<NewSalePayment>(chosen.size)
        for ((index, payment) in chosen.withIndex()) {
            val cash = payment.method.kind == PaymentMethodKind.CASH
            val covered = amounts[index] + tips[index]
            val rounded = if (cash) CashRounding.amount(covered, cashStep) else Money.ZERO
            rounding += rounded
            val amount = covered + rounded
            val tendered = payment.tendered
            if (tendered != null && tendered < amount) {
                return Settled.Rejected(
                    RegisterSaleResult.PaymentRejected(
                        index,
                        PaymentRejection.TENDERED_BELOW_AMOUNT,
                    ),
                )
            }
            settled += paymentOf(payment.method, amount, payment.fee, businessDate, now)
                ?.copy(tenderedAmount = tendered, changeAmount = tendered?.let { it - amount })
                ?: return Settled.Rejected(RegisterSaleResult.SaleTotalTooLarge)
        }
        // Lo que se cobra: el total con los recargos, la propina y el redondeo del efectivo.
        val charged = adjustedLines.totals.total + tip + rounding
        if (!Limit.DOCUMENT_TOTAL.allows(charged.minor)) {
            return Settled.Rejected(RegisterSaleResult.SaleTotalTooLarge)
        }
        return Settled.Payments(adjustedLines, settled, tip, rounding, charged)
    }

    /**
     * Las líneas con [adjustment] repartido entre ellas, con su impuesto y sus totales, o `null` si
     * la venta no lo admite: un ajuste o un descuento mayor que lo vendido, o una línea o un total
     * sobre el tope de un documento. Parte siempre de las líneas sin ajuste, así que se puede
     * probar con varios ajustes. El reparto es en proporción a lo que cada línea cobra antes de
     * impuesto, y el impuesto se vuelve a separar con la parte de cada una.
     */
    private fun adjusted(priced: Priced.Lines, adjustment: Money): Priced.Lines? {
        if (adjustment.minor !in -Limit.DOCUMENT_TOTAL.max..Limit.DOCUMENT_TOTAL.max) return null
        val split = OrderAdjustmentShares.split(adjustment, priced.lines.map { it.base })
        val shares = when (split) {
            is AdjustmentSplit.Shares -> split.shares
            AdjustmentSplit.DiscountAboveBases -> return null
        }
        val totals = Totals()
        val lines = priced.lines.zip(shares) { line, share ->
            val withShare = line.withShare(share, priced.taxMode) ?: return null
            withShare.also { if (!totals.add(it)) return null }
        }
        return Priced.Lines(lines, totals, priced.taxMode, adjustment)
    }

    /**
     * Un pago de [amount], o `null` si la comisión de su método pasa del tope. La comisión es
     * [writtenFee] si la persona la escribió en la venta, o la del método. El nombre que se congela
     * es el del método, o el de su clave en el idioma de la interfaz si nadie lo renombró, recortado
     * al largo que admite el pago.
     */
    private fun paymentOf(
        method: SalePaymentMethod,
        amount: Money,
        writtenFee: Money?,
        businessDate: BusinessDate,
        now: Instant,
    ): NewSalePayment? {
        val fee = when {
            // Un pago que queda en cero, también por el descuento de su método, no paga comisión.
            amount == Money.ZERO -> Money.ZERO

            writtenFee != null -> writtenFee

            else -> PaymentFees.feeOf(amount, method.feeRate, method.feeFixed)
                .valueOr { return null }
        }
        val name = method.name ?: methodNames.systemName(
            checkNotNull(method.systemKey) { "Un método sin nombre tiene su clave inicial" },
        )
        return NewSalePayment(
            paymentMethodId = method.id,
            methodNameSnapshot = name.take(MAX_METHOD_NAME_LENGTH),
            methodKind = method.kind,
            amount = amount,
            feeAmount = fee,
            settlementDate = PaymentFees.settlementDateOf(businessDate, method.settlementDays),
            moneyAccountId = method.moneyAccountId,
            createdAt = now,
        )
    }

    /**
     * Escribe la venta ya calculada, con los pagos de [plan], y devuelve su id y los de sus pagos.
     * Lo único que hace aquí es guardar.
     */
    private suspend fun write(
        settled: Settled.Payments,
        plan: MoneyPlan,
        customerId: Long?,
        priceListId: Long,
        note: String?,
        time: RecordTime,
        now: Instant,
    ): Written {
        val priced = settled.priced
        val salePayments = plan.payments
        val locationId = systemRows.findId(SystemRow.MAIN_LOCATION) ?: damaged()
        val totals = priced.totals
        val saleId = sales.insert(
            NewSale(
                netTotal = totals.net,
                taxTotal = totals.tax,
                total = totals.total,
                totalCost = totals.cost,
                totalReplacementCost = totals.replacement,
                orderAdjustment = priced.adjustment,
                tipAmount = settled.tip,
                cashRoundingAmount = settled.cashRounding,
                amountDue = settled.amountDue,
                customerId = customerId,
                priceListId = priceListId,
                locationId = locationId,
                cashSessionId = plan.cashSessionId,
                quoteId = null,
                orderId = null,
                note = note,
                occurredAt = time.occurredAt,
                businessDate = time.businessDate,
                createdAt = now,
            ),
        )
        for (line in priced.lines) {
            val lineId = sales.insertLine(saleId, line.toNewLine(priceListId))
            val plan = line.plan
            if (plan is LineStockPlan.Direct) {
                val exit = plan.exit
                ledger.append(
                    NewStockMovement(
                        movement = exit.saleMovement(
                            StockKey(line.product.id, locationId, lotId = null),
                        ),
                        reason = null,
                        unitCost = exit.unitCost,
                        totalCost = -exit.cost,
                        balanceAfter = exit.stateAfter.onHand,
                        averageCostAfter = exit.stateAfter.averageCost,
                        stockValueAfter = exit.stateAfter.stockValue,
                        source = StockMovementSource(saleLineId = lineId),
                        note = null,
                        // El movimiento hereda el hecho de la venta: una venta atrasada lo
                        // fecha en su día, aunque tome su lugar en el libro por orden de llegada.
                        occurredAt = time.occurredAt,
                        businessDate = time.businessDate,
                        createdAt = now,
                    ),
                )
            }
        }
        val paymentIds = salePayments.map { payments.insert(saleId, it) }
        return Written(saleId, paymentIds)
    }

    /** La venta guardada y los ids de sus pagos, en el orden en que se guardaron. */
    private class Written(val saleId: Long, val paymentIds: List<Long>)

    /**
     * Los pagos tal como se guardan, con la cuenta solo si su dinero entra en ella; la caja abierta
     * en que queda la venta, si hay una; y si algún pago con algo cobrado se quedó sin su cuenta.
     */
    private class MoneyPlan(
        val payments: List<NewSalePayment>,
        val cashSessionId: Long? = null,
        val skipped: Boolean = false,
    )

    private fun PricedLine.toNewLine(priceListId: Long) = NewSaleLine(
        productId = product.id,
        unitId = unit.unitId,
        unitFactor = QuantityMilli(unit.factorMilli),
        enteredQuantity = enteredQuantity,
        quantity = quantity,
        unitPrice = unitPrice,
        unitCost = unitCost,
        lineDiscount = discount,
        orderAdjustmentShare = share,
        netAmount = split.netAmount,
        taxRate = tax.rate,
        taxNameSnapshot = tax.name,
        taxAmount = split.taxAmount,
        lineTotal = split.lineTotal,
        lineCost = plan.lineCost,
        unitReplacementCost = unitReplacement,
        lineReplacementCost = lineReplacement,
        productNameSnapshot = product.details.name,
        stockEffect = plan.stockEffect,
        parentLineId = null,
        priceListId = priceListId,
    )

    /**
     * Una fila que la base siempre tiene y no está: la base está dañada, y la venta no se escribe.
     * Sale como cualquier falla de la base, sin texto.
     */
    private fun damaged(): Nothing =
        throw StorageFailureException(StorageFailure.INVARIANT_VIOLATION)

    private fun rejected(index: Int, reason: LineRejection): Priced =
        Priced.Rejected(RegisterSaleResult.LineRejected(index, reason))

    /**
     * Una línea ya calculada, lista para guardar. [base] es el bruto menos el descuento, antes del
     * ajuste de la venta. [split] reparte lo que cobra la línea, su base más su parte [share] del
     * ajuste, entre neto e impuesto, con la tasa [tax].
     */
    private class PricedLine(
        val product: Product,
        val quantity: QuantityMilli,
        val unit: SaleUnit,
        val enteredQuantity: QuantityMilli,
        val unitPrice: Money,
        val unitCost: Money,
        val discount: Money,
        val base: Money,
        val tax: AppliedTax,
        val split: LineTax,
        val plan: LineStockPlan,
        val unitReplacement: Money,
        val lineReplacement: Money,
        val share: Money = Money.ZERO,
    ) {
        /**
         * La misma línea con su parte [share] del ajuste de la venta, y su impuesto separado de
         * nuevo sobre lo que cobra después del ajuste: así un recargo también paga impuesto. Se
         * llama sobre la línea sin ajuste. Devuelve `null` si su total pasa del tope de un
         * documento.
         */
        fun withShare(share: Money, taxMode: TaxMode): PricedLine? {
            val after = TaxCalculator.line(base + share, taxMode, tax.rate).valueOrNull()
                ?: return null
            return PricedLine(
                product = product,
                quantity = quantity,
                unit = unit,
                enteredQuantity = enteredQuantity,
                unitPrice = unitPrice,
                unitCost = unitCost,
                discount = discount,
                base = base,
                tax = tax,
                split = after,
                plan = plan,
                unitReplacement = unitReplacement,
                lineReplacement = lineReplacement,
                share = share,
            )
        }
    }

    /**
     * La tasa que copia una línea al venderse y su nombre en ese momento. Sin impuesto, la tasa es
     * cero y no hay nombre.
     */
    private class AppliedTax(val rate: BasisPoints, val name: String?) {
        companion object {
            val NONE = AppliedTax(BasisPoints.ZERO, name = null)
        }
    }

    private sealed interface LineResult {
        class Ready(val line: PricedLine) : LineResult

        class Rejected(val reason: LineRejection) : LineResult
    }

    /**
     * El método de un pago; sin [amount], el pago cubre todo lo que se cobra, y sin [fee] la
     * comisión es la del método. [tendered] es lo recibido en efectivo, si se escribió.
     */
    private class ChosenPayment(
        val method: SalePaymentMethod,
        val amount: Money?,
        val fee: Money?,
        val tendered: Money?,
    )

    private sealed interface Chosen {
        class Payments(val payments: List<ChosenPayment>) : Chosen

        class Rejected(val rejection: RegisterSaleResult) : Chosen
    }

    private sealed interface Settled {
        /**
         * [tip] es la propina, [cashRounding] la suma de los redondeos del efectivo y [amountDue] lo
         * que se cobra.
         */
        class Payments(
            val priced: Priced.Lines,
            val payments: List<NewSalePayment>,
            val tip: Money,
            val cashRounding: Money,
            val amountDue: Money,
        ) : Settled

        class Rejected(val rejection: RegisterSaleResult) : Settled
    }

    /** Los puntos que canjea la venta y su descuento, o por qué no se pueden canjear. */
    private sealed interface Redemption {
        class Points(val points: Long, val discount: Money) : Redemption

        class Rejected(val rejection: RegisterSaleResult) : Redemption

        companion object {
            val NONE = Points(0L, Money.ZERO)
        }
    }

    private sealed interface Priced {
        class Lines(
            val lines: List<PricedLine>,
            val totals: Totals,
            val taxMode: TaxMode,
            val adjustment: Money = Money.ZERO,
        ) : Priced

        class Rejected(val rejection: RegisterSaleResult) : Priced
    }

    /**
     * Los totales de la cabecera, sumados línea a línea. Cada suma se compara con el tope de un
     * documento antes de seguir: cada línea está dentro de ese tope, así que la suma cabe en
     * `Long`.
     */
    private class Totals {
        var net: Money = Money.ZERO
            private set
        var tax: Money = Money.ZERO
            private set
        var total: Money = Money.ZERO
            private set
        var cost: Money = Money.ZERO
            private set
        var replacement: Money = Money.ZERO
            private set

        /**
         * Suma [line], o devuelve `false` si algún total pasaría del tope. Como cada línea cumple
         * `total = neto + impuesto`, sus sumas también lo cumplen.
         */
        fun add(line: PricedLine): Boolean {
            val nextNet = net + line.split.netAmount
            val nextTax = tax + line.split.taxAmount
            val nextTotal = total + line.split.lineTotal
            val nextCost = cost + line.plan.lineCost
            val nextReplacement = replacement + line.lineReplacement
            val fits = listOf(nextNet, nextTax, nextTotal, nextCost, nextReplacement).all {
                Limit.DOCUMENT_TOTAL.allows(it.minor)
            }
            if (!fits) return false
            net = nextNet
            tax = nextTax
            total = nextTotal
            cost = nextCost
            replacement = nextReplacement
            return true
        }
    }

    private companion object {
        /** Largo máximo de la nota de una venta. */
        const val MAX_NOTE_LENGTH = 200

        /** Largo máximo del nombre congelado de un método de pago. */
        const val MAX_METHOD_NAME_LENGTH = 40

        /** Largo máximo del nombre congelado de una tasa de impuesto. */
        const val MAX_TAX_NAME_LENGTH = 40
    }
}

/** El valor, o lo que devuelva [rejected] si la cuenta se rechazó. */
private inline fun <T> Checked<T>.valueOr(rejected: () -> T): T = when (this) {
    is Checked.Value -> value
    Checked.Overflow, is Checked.AboveLimit -> rejected()
}
