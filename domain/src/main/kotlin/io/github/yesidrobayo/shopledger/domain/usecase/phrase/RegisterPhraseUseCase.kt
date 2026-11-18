package io.github.yesidrobayo.shopledger.domain.usecase.phrase

import io.github.yesidrobayo.shopledger.domain.model.Limit
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.Scale
import io.github.yesidrobayo.shopledger.domain.model.catalog.ConversionDetails
import io.github.yesidrobayo.shopledger.domain.model.catalog.Product
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductForm
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductStockMode
import io.github.yesidrobayo.shopledger.domain.model.catalog.RecipeLine
import io.github.yesidrobayo.shopledger.domain.model.expense.ExpenseRequest
import io.github.yesidrobayo.shopledger.domain.model.phrase.DraftLine
import io.github.yesidrobayo.shopledger.domain.model.phrase.DraftTarget
import io.github.yesidrobayo.shopledger.domain.model.phrase.PhraseDraft
import io.github.yesidrobayo.shopledger.domain.model.phrase.PhraseIntent
import io.github.yesidrobayo.shopledger.domain.model.purchase.PurchaseLineAmount
import io.github.yesidrobayo.shopledger.domain.model.purchase.PurchaseLineRequest
import io.github.yesidrobayo.shopledger.domain.model.purchase.PurchaseRequest
import io.github.yesidrobayo.shopledger.domain.model.sale.LineDiscount
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleLineRequest
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleRequest
import io.github.yesidrobayo.shopledger.domain.model.stock.StockAdjustmentReason
import io.github.yesidrobayo.shopledger.domain.model.stock.StockAdjustmentRequest
import io.github.yesidrobayo.shopledger.domain.model.sumOrNull
import io.github.yesidrobayo.shopledger.domain.model.valueOrNull
import io.github.yesidrobayo.shopledger.domain.port.PaymentMethodRepository
import io.github.yesidrobayo.shopledger.domain.port.ProductComponentRepository
import io.github.yesidrobayo.shopledger.domain.port.ProductRepository
import io.github.yesidrobayo.shopledger.domain.port.ProductUnitRepository
import io.github.yesidrobayo.shopledger.domain.port.StorageFailure
import io.github.yesidrobayo.shopledger.domain.port.StorageFailureException
import io.github.yesidrobayo.shopledger.domain.port.UnitRepository
import io.github.yesidrobayo.shopledger.domain.rule.mulDivRound
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseAmounts
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseMeasures
import io.github.yesidrobayo.shopledger.domain.rule.phrase.gaps
import io.github.yesidrobayo.shopledger.domain.rule.production.ProductionRules
import io.github.yesidrobayo.shopledger.domain.rule.production.RecipeInputsResult
import io.github.yesidrobayo.shopledger.domain.rule.sale.SaleUnitResult
import io.github.yesidrobayo.shopledger.domain.rule.sale.SaleUnits
import io.github.yesidrobayo.shopledger.domain.rule.timesQuantity
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.ChangePriceResult
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.SaveProductResult
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.SaveRecipeResult
import io.github.yesidrobayo.shopledger.domain.usecase.customer.CustomerPaymentRequest
import io.github.yesidrobayo.shopledger.domain.usecase.customer.RecordCustomerPaymentResult
import io.github.yesidrobayo.shopledger.domain.usecase.expense.RegisterExpenseResult
import io.github.yesidrobayo.shopledger.domain.usecase.payable.PaySupplierResult
import io.github.yesidrobayo.shopledger.domain.usecase.payable.SupplierPaymentRequest
import io.github.yesidrobayo.shopledger.domain.usecase.production.ProductionRequest
import io.github.yesidrobayo.shopledger.domain.usecase.production.RegisterProductionResult
import io.github.yesidrobayo.shopledger.domain.usecase.purchase.RegisterPurchaseResult
import io.github.yesidrobayo.shopledger.domain.usecase.sale.RegisterSaleResult
import io.github.yesidrobayo.shopledger.domain.usecase.stock.CorrectCostResult
import io.github.yesidrobayo.shopledger.domain.usecase.stock.StockAdjustmentResult
import io.github.yesidrobayo.shopledger.domain.usecase.stock.StockAdjustmentsResult

/**
 * Registra la tarjeta que confirmó la persona con el caso de uso de siempre de su operación: arma
 * la misma petición que armaría la pantalla de esa operación y lo llama una sola vez. Así la
 * operación se guarda con todas sus reglas y en una sola transacción, y nada queda a medias.
 *
 * - **Primero lo que falta.** Si la tarjeta tiene huecos, devuelve cuáles y no escribe nada.
 * - **Nunca registra algo distinto de lo que muestra la tarjeta.** Un total dicho para una línea se
 *   cobra exacto, con el precio redondeado hacia arriba y un descuento por lo que sobra; un "todo
 *   por" de una venta es el ajuste que lleva la suma de las líneas a ese total. Lo que no se puede
 *   llevar sin cambiarlo se rechaza con su motivo.
 * - **Los precios que no se dijeron** son los de la venta de siempre: el del producto, o el de su
 *   presentación, en la lista inicial. Una compra, en cambio, nunca registra un costo que no se
 *   dijo: la tarjeta lo pide, como la compra de siempre, porque un costo errado daña el promedio.
 * - **Un producto nuevo se crea antes.** Ningún caso de uso crea un producto dentro de la misma
 *   escritura de una venta, una compra o una receta, así que una línea con un producto nuevo es
 *   un hueco: la tarjeta lo crea con un producto nuevo de esta misma función y lo vuelve a elegir.
 *   Se crea con lo que se dijo de él: su uso, si es un servicio y su categoría, que se crea con
 *   él si no existe.
 * - **Las unidades** se respetan donde la operación las admite: una presentación de venta en la
 *   venta y una de compra en la compra. Lo demás se lleva a la unidad base, con la conversión que
 *   guardó la persona o, entre unidades métricas, con su medida; un precio dicho por esa unidad va
 *   entonces como el total de la línea.
 * - **El dinero** de un gasto, una compra pagada, un abono o un pago sale de la cuenta del método
 *   de pago dicho, si tiene una; la venta ya lo hace sola con su método.
 * - **Un conteo o un ajuste de varios productos** se registra entero en una sola transacción: cada
 *   línea es un movimiento nuevo del libro, y si una se rechaza no queda ninguna.
 *
 * Lee el catálogo fuera de la escritura, como la pantalla de cada operación: el caso de uso vuelve
 * a leer y validar todo dentro de su propia transacción.
 */
class RegisterPhraseUseCase(
    private val writers: PhraseWriters,
    private val products: ProductRepository,
    private val productUnits: ProductUnitRepository,
    private val units: UnitRepository,
    private val components: ProductComponentRepository,
    private val paymentMethods: PaymentMethodRepository,
) {
    suspend operator fun invoke(draft: PhraseDraft): RegisterPhraseResult {
        val gaps = draft.gaps()
        if (gaps.isNotEmpty()) return RegisterPhraseResult.Incomplete(gaps)
        return try {
            register(draft)
        } catch (failure: StorageFailureException) {
            RegisterPhraseResult.StorageFailed(failure.failure)
        }
    }

    private suspend fun register(draft: PhraseDraft): RegisterPhraseResult = when (draft.intent) {
        PhraseIntent.SALE -> sale(draft)
        PhraseIntent.PURCHASE -> purchase(draft)
        PhraseIntent.EXPENSE -> expense(draft)
        PhraseIntent.PRODUCTION -> production(draft)
        PhraseIntent.STOCK_ADJUSTMENT -> adjustment(draft)
        PhraseIntent.CUSTOMER_PAYMENT -> customerPayment(draft)
        PhraseIntent.SUPPLIER_PAYMENT -> supplierPayment(draft)
        PhraseIntent.RECIPE -> recipe(draft)
        PhraseIntent.NEW_PRODUCT -> newProduct(draft)
        PhraseIntent.COST_CORRECTION -> costCorrection(draft)
        PhraseIntent.PRICE_CHANGE -> priceChange(draft)
    }

    // Cada operación.

    private suspend fun sale(draft: PhraseDraft): RegisterPhraseResult {
        val methodId = requireNotNull(draft.paymentMethodId) { "Una venta completa tiene pago" }
        val lines = ArrayList<SaleLineRequest>()
        val amounts = ArrayList<Money?>()
        for ((index, line) in draft.lines.withIndex()) {
            val product = productOf(line) ?: return unavailable(line)
            val placed = placementOf(product, line, keeps = { it.forSale })
                ?: return rejected(PhraseRejection.UnitNotConvertible(index))
            val priced = saleLine(product, line, placed)
                ?: return rejected(PhraseRejection.AmountNotApplicable)
            lines += priced.request
            amounts += priced.amount
        }
        val adjustment = draft.total?.let { total ->
            adjustmentTo(total, amounts) ?: return rejected(PhraseRejection.AmountNotApplicable)
        } ?: Money.ZERO
        val request = SaleRequest(
            lines = lines,
            paymentMethodId = methodId,
            customerId = draft.partyId,
            orderAdjustment = adjustment,
        )
        val result = writers.sale(request)
        // Lo que no es un registro ni una falla de la base es un rechazo con su motivo.
        if (result is RegisterSaleResult.Registered) return registered(draft, result.saleId)
        if (result is RegisterSaleResult.StorageFailed) return failed(result.failure)
        return rejected(PhraseRejection.Sale(result))
    }

    private suspend fun purchase(draft: PhraseDraft): RegisterPhraseResult {
        val lines = ArrayList<PurchaseLineRequest>()
        for ((index, line) in draft.lines.withIndex()) {
            val product = productOf(line) ?: return unavailable(line)
            val placed = placementOf(product, line, keeps = { it.forPurchase })
                ?: return rejected(PhraseRejection.UnitNotConvertible(index))
            val amount = purchaseAmount(line, placed)
                ?: return rejected(PhraseRejection.AmountNotApplicable)
            lines += PurchaseLineRequest(product.id, placed.quantity, amount, placed.unitId)
        }
        // A crédito no se paga nada al confirmar: todo queda en el libro del proveedor.
        val account = if (draft.credit) {
            null
        } else {
            when (val found = accountOf(draft.paymentMethodId)) {
                Account.Unavailable -> return rejected(PhraseRejection.PaymentMethodUnavailable)
                is Account.Of -> found.id
            }
        }
        val request = PurchaseRequest(
            lines = lines,
            supplierId = draft.partyId,
            amountPaid = if (draft.credit) Money.ZERO else null,
            moneyAccountId = account,
        )
        val result = writers.purchase(request)
        if (result is RegisterPurchaseResult.Registered) return registered(draft, result.purchaseId)
        if (result is RegisterPurchaseResult.StorageFailed) return failed(result.failure)
        return rejected(PhraseRejection.Purchase(result))
    }

    private suspend fun expense(draft: PhraseDraft): RegisterPhraseResult {
        val account = when (val found = accountOf(draft.paymentMethodId)) {
            Account.Unavailable -> return rejected(PhraseRejection.PaymentMethodUnavailable)
            is Account.Of -> found.id
        }
        val request = ExpenseRequest(
            amount = requireNotNull(draft.total) { "Un gasto completo tiene monto" },
            categoryId = requireNotNull(draft.categoryId) { "Un gasto completo tiene categoría" },
            moneyAccountId = account,
        )
        val result = writers.expense(request)
        if (result is RegisterExpenseResult.Registered) return registered(draft, result.expenseId)
        if (result is RegisterExpenseResult.StorageFailed) return failed(result.failure)
        return rejected(PhraseRejection.Expense(result))
    }

    private suspend fun production(draft: PhraseDraft): RegisterPhraseResult {
        val line = draft.lines.singleOrNull()
            ?: return rejected(PhraseRejection.OneProductAtATime)
        val product = productOf(line) ?: return unavailable(line)
        // Lo producido entra siempre en su unidad base, como en el formulario de producción.
        val placed = placementOf(product, line, keeps = { false })
            ?: return rejected(PhraseRejection.UnitNotConvertible(0))
        val recipes = components.findAll().groupBy { it.parentProductId }
        val componentIds = recipes.values.flatten().mapTo(HashSet()) { it.componentProductId }
        val modes = products.findByIds(componentIds).associate { it.id to it.details.stockMode }
        val inputs = when (
            val result = ProductionRules.recipeInputs(product.id, placed.quantity, recipes, modes)
        ) {
            is RecipeInputsResult.Inputs -> result.inputs

            is RecipeInputsResult.InputRoundsToZero,
            RecipeInputsResult.QuantityOutOfRange,
            RecipeInputsResult.TooDeep,
            -> return rejected(PhraseRejection.ProductionInputs(result))
        }
        val request = ProductionRequest(product.id, placed.quantity, inputs, note = null)
        val result = writers.production(request)
        if (result is RegisterProductionResult.Registered) return registered(draft, result.batchId)
        if (result is RegisterProductionResult.StorageFailed) return failed(result.failure)
        return rejected(PhraseRejection.Production(result))
    }

    /**
     * Un conteo o un ajuste, de una o de varias líneas, en una sola transacción: en un conteo cada
     * línea fija lo que hay de su producto, y con otro motivo cada línea sale con ese motivo. Si
     * una línea se rechaza, no queda ninguna.
     */
    private suspend fun adjustment(draft: PhraseDraft): RegisterPhraseResult {
        val reason = requireNotNull(draft.reason) { "Un ajuste completo tiene motivo" }
        val requests = draft.lines.mapIndexed { index, line ->
            val product = productOf(line) ?: return unavailable(line)
            // El libro va en la unidad base: lo dicho en otra unidad se lleva a ella.
            val placed = placementOf(product, line, keeps = { false })
                ?: return rejected(PhraseRejection.UnitNotConvertible(index))
            if (reason == StockAdjustmentReason.COUNT_CORRECTION) {
                // Lo dicho es lo que hay de verdad, no lo que sale.
                StockAdjustmentRequest.Count(product.id, placed.quantity)
            } else {
                StockAdjustmentRequest.Change(product.id, reason, placed.quantity)
            }
        }
        return when (val result = writers.adjustments(requests)) {
            is StockAdjustmentsResult.Adjusted -> registered(draft, result.movementIds.first())

            StockAdjustmentsResult.NoDifference ->
                rejected(PhraseRejection.Adjustment(StockAdjustmentResult.NoDifference))

            is StockAdjustmentsResult.Rejected ->
                rejected(PhraseRejection.Adjustment(result.result))

            is StockAdjustmentsResult.StorageFailed -> failed(result.failure)
        }
    }

    private suspend fun customerPayment(draft: PhraseDraft): RegisterPhraseResult {
        val account = when (val found = accountOf(draft.paymentMethodId)) {
            Account.Unavailable -> return rejected(PhraseRejection.PaymentMethodUnavailable)
            is Account.Of -> found.id
        }
        val request = CustomerPaymentRequest(
            customerId = requireNotNull(draft.partyId) { "Un abono completo tiene cliente" },
            amount = requireNotNull(draft.total) { "Un abono completo tiene monto" },
            moneyAccountId = account,
            date = null,
            note = null,
        )
        val result = writers.customerPayment(request)
        if (result is RecordCustomerPaymentResult.Recorded) return registered(draft, result.entryId)
        if (result is RecordCustomerPaymentResult.StorageFailed) return failed(result.failure)
        return rejected(PhraseRejection.CustomerPayment(result))
    }

    private suspend fun supplierPayment(draft: PhraseDraft): RegisterPhraseResult {
        val account = when (val found = accountOf(draft.paymentMethodId)) {
            Account.Unavailable -> return rejected(PhraseRejection.PaymentMethodUnavailable)
            is Account.Of -> found.id
        }
        val request = SupplierPaymentRequest(
            supplierId = requireNotNull(draft.partyId) { "Un pago completo tiene proveedor" },
            amount = requireNotNull(draft.total) { "Un pago completo tiene monto" },
            moneyAccountId = account,
        )
        val result = writers.supplierPayment(request)
        if (result is PaySupplierResult.Paid) return registered(draft, result.entryId)
        if (result is PaySupplierResult.StorageFailed) return failed(result.failure)
        return rejected(PhraseRejection.SupplierPayment(result))
    }

    /**
     * La receta guarda cuánto lleva una unidad del producto, en la unidad base de cada insumo: lo
     * dicho para lo que rinde se divide entre esa cantidad, redondeado.
     */
    private suspend fun recipe(draft: PhraseDraft): RegisterPhraseResult {
        val target = requireNotNull(draft.target as? DraftTarget.Existing) {
            "Una receta completa tiene su producto"
        }
        val yields = draft.targetQuantity ?: ONE
        val lines = ArrayList<RecipeLine>()
        for ((index, line) in draft.lines.withIndex()) {
            val product = productOf(line) ?: return unavailable(line)
            val placed = placementOf(product, line, keeps = { false })
                ?: return rejected(PhraseRejection.UnitNotConvertible(index))
            val perUnit = mulDivRound(placed.quantity.milli, Scale.MILLI, yields.milli)
                .valueOrNull()
                ?: return rejected(PhraseRejection.AmountNotApplicable)
            lines += RecipeLine(product.id, QuantityMilli(perUnit))
        }
        val result = writers.recipe(target.productId, lines)
        if (result is SaveRecipeResult.Saved) return registered(draft, target.productId)
        if (result is SaveRecipeResult.StorageFailed) return failed(result.failure)
        return rejected(PhraseRejection.Recipe(result))
    }

    private suspend fun newProduct(draft: PhraseDraft): RegisterPhraseResult {
        val target = requireNotNull(draft.target as? DraftTarget.New) {
            "Un producto nuevo completo tiene su nombre"
        }
        // Lo que se dijo del producto: su uso, si es un servicio y su categoría. Lo que no se dijo
        // queda como en un producto nuevo de siempre.
        val traits = target.traits
        val form = ProductForm(
            name = target.name,
            categoryId = traits.categoryId,
            baseUnitId = draft.unitId,
            stockMode = traits.stockMode ?: ProductStockMode.TRACKED,
            salePrice = draft.price ?: Money.ZERO,
            cost = draft.cost ?: Money.ZERO,
            usage = traits.usage,
        )
        // Una categoría dicha que no está en el catálogo se crea con el producto, en su misma
        // transacción.
        val result = writers.product(form, traits.newCategory.takeIf { traits.categoryId == null })
        if (result is SaveProductResult.Saved) return registered(draft, result.productId)
        if (result is SaveProductResult.StorageFailed) return failed(result.failure)
        return rejected(PhraseRejection.Product(result))
    }

    private suspend fun costCorrection(draft: PhraseDraft): RegisterPhraseResult {
        val line = draft.lines.singleOrNull()
            ?: return rejected(PhraseRejection.OneProductAtATime)
        val product = productOf(line) ?: return unavailable(line)
        val fit = fitOf(product, line.unitId)
            ?: return rejected(PhraseRejection.UnitNotConvertible(0))
        val said = requireNotNull(line.unitPrice) { "Una corrección completa tiene costo" }
        // El costo promedio es por unidad base: lo dicho por otra unidad se lleva a ella.
        val cost = when (fit) {
            UnitFit.Base -> said

            is UnitFit.Conversion ->
                PhraseMeasures.priceIn(said, fit.details.factorMilli, Scale.MILLI)

            is UnitFit.Scaled -> PhraseMeasures.priceIn(said, fit.saidAmount, fit.baseAmount)
        } ?: return rejected(PhraseRejection.AmountNotApplicable)
        val result = writers.cost(product.id, cost)
        if (result is CorrectCostResult.Corrected) return registered(draft, result.movementId)
        if (result is CorrectCostResult.StorageFailed) return failed(result.failure)
        return rejected(PhraseRejection.Cost(result))
    }

    private suspend fun priceChange(draft: PhraseDraft): RegisterPhraseResult {
        val line = draft.lines.singleOrNull()
            ?: return rejected(PhraseRejection.OneProductAtATime)
        val product = productOf(line) ?: return unavailable(line)
        val fit = fitOf(product, line.unitId)
            ?: return rejected(PhraseRejection.UnitNotConvertible(0))
        val said = requireNotNull(line.unitPrice) { "Un cambio de precio completo tiene precio" }
        val price = when (fit) {
            UnitFit.Base -> said
            is UnitFit.Conversion -> return rejected(PhraseRejection.PriceNotInBaseUnit)
            is UnitFit.Scaled -> PhraseMeasures.priceIn(said, fit.saidAmount, fit.baseAmount)
        } ?: return rejected(PhraseRejection.AmountNotApplicable)
        val result = writers.price(product.id, price)
        if (result is ChangePriceResult.Changed) return registered(draft, result.historyId)
        if (result is ChangePriceResult.StorageFailed) return failed(result.failure)
        return rejected(PhraseRejection.Price(result))
    }

    // Piezas comunes.

    /** El producto vigente de la línea, o `null` si ya no existe o se archivó. */
    private suspend fun productOf(line: DraftLine): Product? =
        products.findById(productIdOf(line))?.takeIf { !it.isArchived }

    private fun productIdOf(line: DraftLine): Long =
        requireNotNull(line.product as? DraftTarget.Existing) {
            "Una tarjeta completa tiene sus productos"
        }.productId

    /** Cómo se relaciona la unidad dicha con la base del producto, o `null` si no se relaciona. */
    private suspend fun fitOf(product: Product, unitId: Long?): UnitFit? {
        val baseId = product.details.baseUnitId
        if (unitId == null || unitId == baseId) return UnitFit.Base
        productUnits.findByProductAndUnit(product.id, unitId)?.let {
            return UnitFit.Conversion(unitId, it.details)
        }
        val said = units.findById(unitId) ?: return null
        val base = units.findById(baseId) ?: return null
        val saidAmount = PhraseMeasures.baseAmountMilli(said.systemKey) ?: return null
        val baseAmount = PhraseMeasures.baseAmountMilli(base.systemKey) ?: return null
        return if (said.kind == base.kind) UnitFit.Scaled(saidAmount, baseAmount) else null
    }

    /**
     * La línea tal como entra en la operación: en su presentación si [keeps] la admite, o llevada
     * a la unidad base. `null` si no se puede llevar.
     */
    private suspend fun placementOf(
        product: Product,
        line: DraftLine,
        keeps: (ConversionDetails) -> Boolean,
    ): Placement? {
        val quantity = requireNotNull(line.quantity) { "Una línea completa tiene cantidad" }
        return when (val fit = fitOf(product, line.unitId) ?: return null) {
            UnitFit.Base -> Placement(null, quantity, null, scaled = false)

            is UnitFit.Conversion -> if (keeps(fit.details)) {
                Placement(fit.unitId, quantity, fit.details, scaled = false)
            } else {
                rescaled(quantity, fit.details.factorMilli, Scale.MILLI)
            }

            is UnitFit.Scaled -> rescaled(quantity, fit.saidAmount, fit.baseAmount)
        }
    }

    private fun rescaled(quantity: QuantityMilli, fromAmount: Long, toAmount: Long): Placement? =
        PhraseMeasures.convert(quantity, fromAmount, toAmount)?.let {
            Placement(null, it, null, scaled = true)
        }

    /**
     * La línea de venta y lo que cobra antes de cualquier ajuste. Un total dicho, o un precio dicho
     * en una unidad que se llevó a la base, se cobra exacto. Sin precio dicho, la línea va sin
     * precio y la venta pone el de siempre; lo que cobra se calcula igual que ella, por si hace
     * falta para un "todo por", y es `null` si no se puede calcular.
     */
    private fun saleLine(product: Product, line: DraftLine, placed: Placement): SaleLinePlan? {
        val said = requireNotNull(line.quantity) { "Una línea completa tiene cantidad" }
        val priceAsTotal = line.unitPrice?.takeIf { placed.scaled }?.let { price ->
            price.timesQuantity(said, Limit.DOCUMENT_TOTAL).valueOrNull() ?: return null
        }
        val total = line.lineTotal ?: priceAsTotal
        if (total != null) {
            val exact = PhraseAmounts.exactLine(total, placed.quantity) ?: return null
            val discount = if (exact.discount > Money.ZERO) {
                LineDiscount.Amount(exact.discount)
            } else {
                LineDiscount.None
            }
            val request = SaleLineRequest(
                productId = product.id,
                quantity = placed.quantity,
                unitPrice = exact.unitPrice,
                discount = discount,
                unitId = placed.unitId,
            )
            return SaleLinePlan(request, total)
        }
        val request = SaleLineRequest(
            productId = product.id,
            quantity = placed.quantity,
            unitPrice = line.unitPrice,
            unitId = placed.unitId,
        )
        val conversions = placed.conversion?.let { mapOf(it.unitId to it) }.orEmpty()
        val listed = when (val unit = SaleUnits.of(request, product, conversions)) {
            is SaleUnitResult.Resolved -> unit.unit.listPrice
            SaleUnitResult.Unavailable -> null
        }
        val price = line.unitPrice ?: listed
        val amount = price?.timesQuantity(placed.quantity, Limit.DOCUMENT_TOTAL)?.valueOrNull()
        return SaleLinePlan(request, amount)
    }

    /** El ajuste de toda la venta que lleva lo que cobran las líneas a [total]. */
    private fun adjustmentTo(total: Money, amounts: List<Money?>): Money? {
        val known = amounts.filterNotNull()
        if (known.size != amounts.size) return null
        val sum = known.sumOrNull() ?: return null
        return total - sum
    }

    /**
     * Cómo se escribe lo que costó una línea de compra: el total dicho, o el costo por unidad. Un
     * costo dicho en una unidad que se llevó a la base va como el total de la línea, para que el
     * valor que entra sea exactamente el dicho.
     */
    private fun purchaseAmount(line: DraftLine, placed: Placement): PurchaseLineAmount? {
        line.lineTotal?.let { return PurchaseLineAmount.LineTotal(it) }
        val cost = requireNotNull(line.unitPrice) { "Una línea de compra completa tiene costo" }
        if (!placed.scaled) return PurchaseLineAmount.UnitCost(cost)
        val said = requireNotNull(line.quantity) { "Una línea completa tiene cantidad" }
        return cost.timesQuantity(said, Limit.DOCUMENT_TOTAL).valueOrNull()
            ?.let(PurchaseLineAmount::LineTotal)
    }

    /** La cuenta del método de pago dicho, si tiene una; sin método no hay cuenta. */
    private suspend fun accountOf(methodId: Long?): Account {
        if (methodId == null) return Account.Of(null)
        val method = paymentMethods.findById(methodId)
        if (method == null || method.isArchived) return Account.Unavailable
        return Account.Of(method.details.moneyAccountId)
    }

    private fun registered(draft: PhraseDraft, documentId: Long): RegisterPhraseResult =
        RegisterPhraseResult.Registered(draft.intent, documentId)

    private fun failed(failure: StorageFailure): RegisterPhraseResult =
        RegisterPhraseResult.StorageFailed(failure)

    private fun rejected(reason: PhraseRejection): RegisterPhraseResult =
        RegisterPhraseResult.Rejected(reason)

    private fun unavailable(line: DraftLine): RegisterPhraseResult =
        rejected(PhraseRejection.ProductUnavailable(productIdOf(line)))

    /** Cómo se relaciona una unidad dicha con la base de un producto. */
    private sealed interface UnitFit {
        /** Es la base, o no se dijo unidad. */
        data object Base : UnitFit

        /** Es una presentación guardada del producto. */
        data class Conversion(val unitId: Long, val details: ConversionDetails) : UnitFit

        /** Es una medida métrica del mismo tipo que la base: [saidAmount] y [baseAmount] miden. */
        data class Scaled(val saidAmount: Long, val baseAmount: Long) : UnitFit
    }

    /**
     * Una línea lista para la operación: [quantity] milésimas de [unitId], `null` la base, con la
     * presentación [conversion] si va en una. [scaled] dice si la cantidad se llevó a la base desde
     * otra unidad.
     */
    private data class Placement(
        val unitId: Long?,
        val quantity: QuantityMilli,
        val conversion: ConversionDetails?,
        val scaled: Boolean,
    )

    /** Una línea de venta y lo que cobra, si se puede calcular. */
    private data class SaleLinePlan(val request: SaleLineRequest, val amount: Money?)

    /** La cuenta de la que sale o entra el dinero, o un método que ya no se puede usar. */
    private sealed interface Account {
        data class Of(val id: Long?) : Account

        data object Unavailable : Account
    }

    private companion object {
        /** Una unidad entera: lo que rinde una receta que no dice cuánto. */
        val ONE = QuantityMilli(1_000L)
    }
}
