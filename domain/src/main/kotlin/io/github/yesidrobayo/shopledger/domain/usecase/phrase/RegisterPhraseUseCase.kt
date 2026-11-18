package io.github.yesidrobayo.shopledger.domain.usecase.phrase

import io.github.yesidrobayo.shopledger.domain.model.Limit
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.Scale
import io.github.yesidrobayo.shopledger.domain.model.catalog.ConversionDetails
import io.github.yesidrobayo.shopledger.domain.model.catalog.ConversionDraft
import io.github.yesidrobayo.shopledger.domain.model.catalog.Product
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductForm
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductStockMode
import io.github.yesidrobayo.shopledger.domain.model.catalog.RecipeComponent
import io.github.yesidrobayo.shopledger.domain.model.catalog.RecipeLine
import io.github.yesidrobayo.shopledger.domain.model.expense.ExpenseRequest
import io.github.yesidrobayo.shopledger.domain.model.phrase.DraftGap
import io.github.yesidrobayo.shopledger.domain.model.phrase.DraftLine
import io.github.yesidrobayo.shopledger.domain.model.phrase.DraftTarget
import io.github.yesidrobayo.shopledger.domain.model.phrase.PhraseDraft
import io.github.yesidrobayo.shopledger.domain.model.phrase.PhraseIntent
import io.github.yesidrobayo.shopledger.domain.model.phrase.SaidConversion
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
import io.github.yesidrobayo.shopledger.domain.port.TxResult
import io.github.yesidrobayo.shopledger.domain.port.UnitRepository
import io.github.yesidrobayo.shopledger.domain.rule.mulDivRound
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseAmounts
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseMeasures
import io.github.yesidrobayo.shopledger.domain.rule.phrase.gaps
import io.github.yesidrobayo.shopledger.domain.rule.production.ProductionInput
import io.github.yesidrobayo.shopledger.domain.rule.production.ProductionRules
import io.github.yesidrobayo.shopledger.domain.rule.production.RecipeInputsResult
import io.github.yesidrobayo.shopledger.domain.rule.production.SaidRecipes
import io.github.yesidrobayo.shopledger.domain.rule.sale.SaleUnitResult
import io.github.yesidrobayo.shopledger.domain.rule.sale.SaleUnits
import io.github.yesidrobayo.shopledger.domain.rule.timesQuantity
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.ChangePriceResult
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.SaveConversionResult
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.SaveProductResult
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.SaveRecipeResult
import io.github.yesidrobayo.shopledger.domain.usecase.customer.CustomerPaymentRequest
import io.github.yesidrobayo.shopledger.domain.usecase.customer.RecordCustomerPaymentResult
import io.github.yesidrobayo.shopledger.domain.usecase.expense.RegisterExpenseResult
import io.github.yesidrobayo.shopledger.domain.usecase.payable.PaySupplierResult
import io.github.yesidrobayo.shopledger.domain.usecase.payable.SupplierPaymentRequest
import io.github.yesidrobayo.shopledger.domain.usecase.production.ProductionRequest
import io.github.yesidrobayo.shopledger.domain.usecase.production.ProductionStep
import io.github.yesidrobayo.shopledger.domain.usecase.production.RegisterProductionsResult
import io.github.yesidrobayo.shopledger.domain.usecase.purchase.RegisterPurchaseResult
import io.github.yesidrobayo.shopledger.domain.usecase.sale.RegisterSaleResult
import io.github.yesidrobayo.shopledger.domain.usecase.stock.CorrectCostResult
import io.github.yesidrobayo.shopledger.domain.usecase.stock.StockAdjustmentResult
import io.github.yesidrobayo.shopledger.domain.usecase.stock.StockAdjustmentsResult
import kotlinx.coroutines.flow.first

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
 * - **Un producto nuevo se crea con la operación.** Un producto que no existía, en una venta,
 *   una compra, una producción, un conteo o una receta, como línea, como insumo o como lo que se
 *   produce, se crea dentro de la misma escritura que la operación: si algo se rechaza, no queda
 *   ni el producto. Se crea con lo que se dijo de él: su uso, si es un servicio y su categoría,
 *   que se crea con él si no existe; en la unidad dicha, o la de siempre si no se dijo; y, en una
 *   venta, con el precio dicho como su precio. Un nombre que se repite en la tarjeta es un solo
 *   producto.
 * - **Una medida dicha en la tarjeta se guarda.** Cuánto pesa una libra de harina, dicho en la
 *   tarjeta, queda como una conversión del producto en la misma escritura, para que la próxima vez
 *   se convierta sola. La línea ya va convertida.
 * - **Las unidades** se respetan donde la operación las admite: una presentación de venta en la
 *   venta y una de compra en la compra. Lo demás se lleva a la unidad base, con la conversión que
 *   guardó la persona o, entre unidades métricas, con su medida, también a través de la conversión
 *   guardada de una unidad métrica cuando la base no tiene medida fija; un precio dicho por esa
 *   unidad va entonces como el total de la línea.
 * - **El dinero** de un gasto, una compra pagada, un abono o un pago sale de la cuenta del método
 *   de pago dicho, si tiene una; la venta ya lo hace sola con su método.
 * - **Un conteo o un ajuste de varios productos** se registra entero en una sola transacción: cada
 *   línea es un movimiento nuevo del libro, y si una se rechaza no queda ninguna. Lo mismo con una
 *   producción de varios productos, y con la receta que se guarda al producir con lo dicho.
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
            if (writesTogether(draft)) {
                writers.together { within -> together(draft, within) }
            } else {
                register(draft, Sink.of(writers))
            }
        } catch (failure: StorageFailureException) {
            RegisterPhraseResult.StorageFailed(failure.failure)
        }
    }

    /**
     * Si la tarjeta crea algo más que su operación: un producto nuevo en una línea, un insumo o lo
     * que se produce, o una medida dicha. Un producto nuevo como operación se crea solo.
     */
    private fun writesTogether(draft: PhraseDraft): Boolean {
        if (draft.intent == PhraseIntent.NEW_PRODUCT) return false
        val products = draft.lines.map { it.product } + draft.inputs.map { it.product } +
            draft.target
        return draft.conversions.isNotEmpty() || products.any { it is DraftTarget.New }
    }

    /**
     * Dentro de una sola transacción: guarda las medidas dichas, crea los productos nuevos y
     * registra la operación con ellos. Solo confirma si la operación quedó registrada.
     */
    private suspend fun together(
        draft: PhraseDraft,
        within: PhraseWritersWithin,
    ): TxResult<RegisterPhraseResult> {
        for (said in draft.conversions) {
            val saved = within.conversion(conversionOf(said)).outcome
            // Una medida que el producto ya tiene guardada no cambia lo que muestra la tarjeta.
            val kept =
                saved is SaveConversionResult.Saved || saved == SaveConversionResult.UnitTaken
            if (!kept) return TxResult.Rollback(rejected(PhraseRejection.Conversion(saved)))
        }
        val creator = NewProducts(within)
        val resolved = when (val made = creator.resolve(draft)) {
            is Made.Ready -> made.draft

            is Made.Refused -> return TxResult.Rollback(
                rejected(PhraseRejection.Product(made.result)),
            )
        }
        val result = register(resolved, Sink.within(within))
        return if (result is RegisterPhraseResult.Registered) {
            TxResult.Commit(result)
        } else {
            TxResult.Rollback(result)
        }
    }

    private fun conversionOf(said: SaidConversion): ConversionDraft = ConversionDraft(
        productId = said.productId,
        unitId = said.unitId,
        factorMilli = said.factorMilli,
        salePrice = null,
        barcode = null,
        // Es una medida, no una presentación: no se ofrece aparte al vender ni al comprar.
        forSale = false,
        forPurchase = false,
    )

    private suspend fun register(draft: PhraseDraft, sink: Sink): RegisterPhraseResult =
        when (draft.intent) {
            PhraseIntent.SALE -> sale(draft, sink)
            PhraseIntent.PURCHASE -> purchase(draft, sink)
            PhraseIntent.EXPENSE -> expense(draft)
            PhraseIntent.PRODUCTION -> production(draft, sink)
            PhraseIntent.STOCK_ADJUSTMENT -> adjustment(draft, sink)
            PhraseIntent.CUSTOMER_PAYMENT -> customerPayment(draft)
            PhraseIntent.SUPPLIER_PAYMENT -> supplierPayment(draft)
            PhraseIntent.RECIPE -> recipe(draft, sink)
            PhraseIntent.NEW_PRODUCT -> newProduct(draft)
            PhraseIntent.COST_CORRECTION -> costCorrection(draft)
            PhraseIntent.PRICE_CHANGE -> priceChange(draft)
        }

    // Cada operación.

    private suspend fun sale(draft: PhraseDraft, sink: Sink): RegisterPhraseResult {
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
        val result = sink.sale(request)
        // Lo que no es un registro ni una falla de la base es un rechazo con su motivo.
        if (result is RegisterSaleResult.Registered) return registered(draft, result.saleId)
        if (result is RegisterSaleResult.StorageFailed) return failed(result.failure)
        return rejected(PhraseRejection.Sale(result))
    }

    private suspend fun purchase(draft: PhraseDraft, sink: Sink): RegisterPhraseResult {
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
        val result = sink.purchase(request)
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

    /**
     * Una producción de uno o de varios productos, todas en una sola transacción. Sin insumos
     * dichos, cada producto sale de su receta, y uno sin receta pide decir qué lleva. Con insumos
     * dichos, que son de un solo producto, se produce con lo dicho: ver [withSaid].
     */
    private suspend fun production(draft: PhraseDraft, sink: Sink): RegisterPhraseResult {
        val said = draft.inputs
        if (said.isNotEmpty() && draft.lines.size != 1) {
            return rejected(PhraseRejection.OneProductAtATime)
        }
        val recipes = components.findAll().groupBy { it.parentProductId }
        val componentIds = recipes.values.flatten().mapTo(HashSet()) { it.componentProductId }
        val modes = products.findByIds(componentIds).associate { it.id to it.details.stockMode }
        val book = RecipeBook(recipes, modes)
        val steps = ArrayList<ProductionStep>()
        for ((index, line) in draft.lines.withIndex()) {
            val product = productOf(line) ?: return unavailable(line)
            // Lo producido entra siempre en su unidad base, como en el formulario de producción.
            val placed = placementOf(product, line, keeps = { false })
                ?: return rejected(PhraseRejection.UnitNotConvertible(index))
            val stored = recipes[product.id].orEmpty()
            if (said.isEmpty() && stored.isEmpty()) {
                return RegisterPhraseResult.Incomplete(setOf(DraftGap.INPUTS))
            }
            steps += if (said.isEmpty()) {
                val fromRecipe = when (val read = book.inputs(product.id, placed.quantity)) {
                    is BookRead.Inputs -> read.inputs
                    is BookRead.Refused -> return rejected(read.reason)
                }
                ProductionStep(ProductionRequest(product.id, placed.quantity, fromRecipe, null))
            } else {
                when (val step = withSaid(draft, product.id, placed.quantity, stored, book)) {
                    is SaidStep.Ready -> step.step
                    is SaidStep.Refused -> return step.result
                }
            }
        }
        return when (val result = sink.productions(steps)) {
            is RegisterProductionsResult.Registered -> registered(draft, result.batchIds.first())

            is RegisterProductionsResult.RecipeRejected ->
                rejected(PhraseRejection.Recipe(result.result))

            is RegisterProductionsResult.ProductionRejected ->
                rejected(PhraseRejection.Production(result.result))

            is RegisterProductionsResult.StorageFailed -> failed(result.failure)
        }
    }

    /**
     * Producir [produced] milésimas de [productId] con lo que se dijo que llevó, cada insumo
     * llevado a su unidad base:
     *
     * - **Se produce con lo dicho**, y lo que la receta guardada, [stored], lleva y no se nombró
     *   sale de ella escalado a lo producido. Un insumo dicho que la receta no lleva entra solo en
     *   esta tanda. Un combo dicho se consume por sus componentes, en lugar de lo que la receta
     *   trae de ese combo, y un insumo dicho manda sobre todo lo que la tanda lleva de él, suelto o
     *   dentro de un combo: nada se cuenta dos veces.
     * - **Sin receta,** lo dicho se guarda como su receta, por unidad, en la misma transacción.
     * - **Con el interruptor de actualizar la receta**, lo dicho también reemplaza o se agrega a la
     *   receta guardada, por unidad; lo que no se nombró queda como estaba. Un combo de la receta
     *   que trae un insumo dicho se abre en sus componentes, para no llevarlo dos veces.
     */
    private suspend fun withSaid(
        draft: PhraseDraft,
        productId: Long,
        produced: QuantityMilli,
        stored: List<RecipeComponent>,
        book: RecipeBook,
    ): SaidStep {
        val said = ArrayList<ProductionInput>()
        val combos = HashSet<Long>()
        for ((index, input) in draft.inputs.withIndex()) {
            val component = productOf(input) ?: return SaidStep.Refused(unavailable(input))
            val placed = placementOf(component, input, keeps = { false })
                ?: return SaidStep.Refused(
                    rejected(PhraseRejection.InputUnitNotConvertible(index)),
                )
            said += ProductionInput(component.id, placed.quantity)
            if (component.details.stockMode == ProductStockMode.COMPONENTS) combos += component.id
        }
        // Lo que la receta lleva sin los combos dichos, y lo que traen los combos dichos.
        val unsaid = book.without(productId, combos)
        val parts = (
            listOf(ProductionInput(productId, produced)) +
                said.filter { it.productId in combos }
            )
        val consumed = ArrayList<ProductionInput>()
        for ((index, part) in parts.withIndex()) {
            val from = if (index == 0) unsaid else book
            when (val read = from.inputs(part.productId, part.quantity)) {
                is BookRead.Inputs -> consumed += read.inputs
                is BookRead.Refused -> return SaidStep.Refused(rejected(read.reason))
            }
        }
        val inputs = SaidRecipes.inputs(
            SaidRecipes.merged(consumed),
            said.filter { it.productId !in combos },
        )
        val request = ProductionRequest(productId, produced, inputs, note = null)
        if (stored.isNotEmpty() && !draft.updatesRecipe) {
            return SaidStep.Ready(ProductionStep(request))
        }
        val rows = stored.map { RecipeLine(it.componentProductId, it.quantity) }
        val ids = said.mapTo(HashSet()) { it.productId }
        val recipe = SaidRecipes.opened(rows, ids, book.recipes, book.modes)
            ?.let { SaidRecipes.recipe(it, said, produced) }
            ?: return SaidStep.Refused(rejected(PhraseRejection.AmountNotApplicable))
        return SaidStep.Ready(ProductionStep(request, recipe))
    }

    /**
     * Un conteo o un ajuste, de una o de varias líneas, en una sola transacción: en un conteo cada
     * línea fija lo que hay de su producto, y con otro motivo cada línea sale con ese motivo. Si
     * una línea se rechaza, no queda ninguna.
     */
    private suspend fun adjustment(draft: PhraseDraft, sink: Sink): RegisterPhraseResult {
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
        return when (val result = sink.adjustments(requests)) {
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
    private suspend fun recipe(draft: PhraseDraft, sink: Sink): RegisterPhraseResult {
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
        val result = sink.recipe(target.productId, lines)
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
        if (said.kind != base.kind) return null
        PhraseMeasures.baseAmountMilli(base.systemKey)?.let {
            return UnitFit.Scaled(saidAmount, it)
        }
        // Una base sin medida fija, como la libra: a través de la conversión guardada de una unidad
        // métrica de su clase. Con los topes de una medida y de un factor, todo cabe en Long.
        for (conversion in productUnits.observeByProduct(product.id).first()) {
            val measured = units.findById(conversion.details.unitId)?.takeIf {
                it.kind == said.kind
            }
            val amount = PhraseMeasures.baseAmountMilli(measured?.systemKey) ?: continue
            return UnitFit.Scaled(
                saidAmount * conversion.details.factorMilli,
                amount * Scale.MILLI,
            )
        }
        return null
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

    /** Una producción lista para registrar, o lo que la detuvo. */
    private sealed interface SaidStep {
        data class Ready(val step: ProductionStep) : SaidStep

        data class Refused(val result: RegisterPhraseResult) : SaidStep
    }

    /** Una línea de venta y lo que cobra, si se puede calcular. */
    private data class SaleLinePlan(val request: SaleLineRequest, val amount: Money?)

    /** La cuenta de la que sale o entra el dinero, o un método que ya no se puede usar. */
    private sealed interface Account {
        data class Of(val id: Long?) : Account

        data object Unavailable : Account
    }

    /**
     * Los casos de uso de las operaciones que pueden llevar productos nuevos: los de siempre, que
     * abren su propia transacción, o los que escriben dentro de la que abrió [together].
     */
    private class Sink(
        val sale: suspend (SaleRequest) -> RegisterSaleResult,
        val purchase: suspend (PurchaseRequest) -> RegisterPurchaseResult,
        val productions: suspend (List<ProductionStep>) -> RegisterProductionsResult,
        val adjustments: suspend (List<StockAdjustmentRequest>) -> StockAdjustmentsResult,
        val recipe: suspend (Long, List<RecipeLine>) -> SaveRecipeResult,
    ) {
        companion object {
            fun of(writers: PhraseWriters) = Sink(
                writers.sale,
                writers.purchase,
                writers.productions,
                writers.adjustments,
                writers.recipe,
            )

            fun within(writers: PhraseWritersWithin) = Sink(
                sale = { writers.sale(it).outcome },
                purchase = { writers.purchase(it).outcome },
                productions = { writers.productions(it).outcome },
                adjustments = { writers.adjustments(it).outcome },
                recipe = { parentId, lines -> writers.recipe(parentId, lines).outcome },
            )
        }
    }

    /**
     * Crea, dentro de la transacción de [within], los productos nuevos de una tarjeta: los de sus
     * líneas, sus insumos y lo que se produce o lleva la receta. Un nombre que se repite, sin
     * mayúsculas, es el mismo producto.
     */
    private inner class NewProducts(private val within: PhraseWritersWithin) {
        private val ids = HashMap<String, Long>()
        private var refused: SaveProductResult? = null

        suspend fun resolve(draft: PhraseDraft): Made {
            val lines = draft.lines.map { line ->
                val price = if (draft.intent == PhraseIntent.SALE) salePriceOf(line) else Money.ZERO
                line.copy(product = target(line.product, line.unitId, price) ?: return refusal())
            }
            val inputs = draft.inputs.map { input ->
                input.copy(
                    product =
                        target(input.product, input.unitId, Money.ZERO) ?: return refusal(),
                )
            }
            val target = draft.target?.let { target(it, null, Money.ZERO) ?: return refusal() }
            return Made.Ready(draft.copy(lines = lines, inputs = inputs, target = target))
        }

        private fun refusal(): Made = Made.Refused(checkNotNull(refused))

        /**
         * [said] ya creado, o `null` si se rechazó. Uno que existe queda como está; uno que falta
         * no llega aquí, porque es un hueco.
         */
        private suspend fun target(said: DraftTarget?, unitId: Long?, price: Money): DraftTarget? {
            val new = said as? DraftTarget.New ?: return said
            val key = new.name.trim().lowercase()
            ids[key]?.let { return DraftTarget.Existing(it) }
            val traits = new.traits
            val form = ProductForm(
                name = new.name,
                categoryId = traits.categoryId,
                baseUnitId = unitId,
                stockMode = traits.stockMode ?: ProductStockMode.TRACKED,
                salePrice = price,
                usage = traits.usage,
            )
            val category = traits.newCategory.takeIf { traits.categoryId == null }
            return when (val result = within.product(form, category).outcome) {
                is SaveProductResult.Saved -> {
                    ids[key] = result.productId
                    DraftTarget.Existing(result.productId)
                }

                else -> {
                    refused = result
                    null
                }
            }
        }
    }

    /** Lo que se dijo que cobra una unidad de una línea de venta: su precio, o su total entre la cantidad. */
    private fun salePriceOf(line: DraftLine): Money {
        line.unitPrice?.let { return it }
        val total = line.lineTotal ?: return Money.ZERO
        val quantity = line.quantity ?: return Money.ZERO
        return PhraseAmounts.exactLine(total, quantity)?.unitPrice ?: Money.ZERO
    }

    /** La tarjeta con sus productos nuevos ya creados, o por qué no se pudo crear uno. */
    private sealed interface Made {
        data class Ready(val draft: PhraseDraft) : Made

        data class Refused(val result: SaveProductResult) : Made
    }

    /**
     * Las recetas guardadas, por producto, y el modo de stock de cada componente: con ellas se
     * calcula lo que pide una receta o un combo para una cantidad.
     */
    private class RecipeBook(
        val recipes: Map<Long, List<RecipeComponent>>,
        val modes: Map<Long, ProductStockMode>,
    ) {
        /** Lo que piden [quantity] milésimas de [productId], o por qué no se puede calcular. */
        fun inputs(productId: Long, quantity: QuantityMilli): BookRead =
            when (val result = ProductionRules.recipeInputs(productId, quantity, recipes, modes)) {
                is RecipeInputsResult.Inputs -> BookRead.Inputs(result.inputs)

                is RecipeInputsResult.InputRoundsToZero,
                RecipeInputsResult.QuantityOutOfRange,
                RecipeInputsResult.TooDeep,
                -> BookRead.Refused(PhraseRejection.ProductionInputs(result))
            }

        /** Las mismas recetas, sin las filas de [productId] que son [removed]. */
        fun without(productId: Long, removed: Set<Long>): RecipeBook {
            val kept = recipes[productId].orEmpty().filter { it.componentProductId !in removed }
            return RecipeBook(recipes + (productId to kept), modes)
        }
    }

    /** Lo que pide una receta, o el motivo para no registrar. */
    private sealed interface BookRead {
        data class Inputs(val inputs: List<ProductionInput>) : BookRead

        data class Refused(val reason: PhraseRejection) : BookRead
    }

    private companion object {
        /** Una unidad entera: lo que rinde una receta que no dice cuánto. */
        val ONE = QuantityMilli(1_000L)
    }
}

/** El valor de un paso, se confirme o se deshaga: aquí todo se junta en la misma transacción. */
private val <T> TxResult<T>.outcome: T
    get() = when (this) {
        is TxResult.Commit -> value
        is TxResult.CommitUncounted -> value
        is TxResult.Rollback -> value
    }
