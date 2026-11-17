package io.github.yesidrobayo.shopledger.domain.usecase.phrase

import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.catalog.Product
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductForm
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductUsage
import io.github.yesidrobayo.shopledger.domain.model.catalog.RecipeComponent
import io.github.yesidrobayo.shopledger.domain.model.catalog.RecipeLine
import io.github.yesidrobayo.shopledger.domain.model.expense.ExpenseRequest
import io.github.yesidrobayo.shopledger.domain.model.payment.PaymentMethodKind
import io.github.yesidrobayo.shopledger.domain.model.payment.PaymentMethodRecord
import io.github.yesidrobayo.shopledger.domain.model.phrase.DraftGap
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
import io.github.yesidrobayo.shopledger.domain.port.ProductRepository
import io.github.yesidrobayo.shopledger.domain.port.StorageFailure
import io.github.yesidrobayo.shopledger.domain.port.StorageFailureException
import io.github.yesidrobayo.shopledger.domain.rule.production.ProductionInput
import io.github.yesidrobayo.shopledger.domain.rule.production.RecipeInputsResult
import io.github.yesidrobayo.shopledger.domain.testing.FakePaymentMethodRepository
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.ChangePriceResult
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.SaveProductResult
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.SaveRecipeResult
import io.github.yesidrobayo.shopledger.domain.usecase.customer.CustomerPaymentRequest
import io.github.yesidrobayo.shopledger.domain.usecase.customer.RecordCustomerPaymentResult
import io.github.yesidrobayo.shopledger.domain.usecase.expense.RegisterExpenseResult
import io.github.yesidrobayo.shopledger.domain.usecase.payable.PaySupplierResult
import io.github.yesidrobayo.shopledger.domain.usecase.payable.SupplierPaymentRequest
import io.github.yesidrobayo.shopledger.domain.usecase.phrase.PhraseWorld.Companion.BOX
import io.github.yesidrobayo.shopledger.domain.usecase.phrase.PhraseWorld.Companion.DOZEN
import io.github.yesidrobayo.shopledger.domain.usecase.phrase.PhraseWorld.Companion.GRAM
import io.github.yesidrobayo.shopledger.domain.usecase.phrase.PhraseWorld.Companion.KILOGRAM
import io.github.yesidrobayo.shopledger.domain.usecase.phrase.PhraseWorld.Companion.LITER
import io.github.yesidrobayo.shopledger.domain.usecase.phrase.PhraseWorld.Companion.POUND
import io.github.yesidrobayo.shopledger.domain.usecase.phrase.PhraseWorld.Companion.SACK
import io.github.yesidrobayo.shopledger.domain.usecase.production.ProductionRequest
import io.github.yesidrobayo.shopledger.domain.usecase.production.RegisterProductionResult
import io.github.yesidrobayo.shopledger.domain.usecase.purchase.RegisterPurchaseResult
import io.github.yesidrobayo.shopledger.domain.usecase.sale.RegisterSaleResult
import io.github.yesidrobayo.shopledger.domain.usecase.sale.product
import io.github.yesidrobayo.shopledger.domain.usecase.stock.CorrectCostResult
import io.github.yesidrobayo.shopledger.domain.usecase.stock.StockAdjustmentResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RegisterPhraseUseCaseTest {
    private val world = PhraseWorld().apply {
        addProducts(
            product(BREAD, "Pan", salePrice = 500),
            product(SODA, "Gaseosa", salePrice = 2_000),
            product(CHEESE, "Queso", salePrice = 18_500, unitId = KILOGRAM),
            product(FLOUR, "Harina", unitId = KILOGRAM, usage = ProductUsage.INPUT),
            product(EGG, "Huevo"),
            product(EMPANADA, "Empanada", salePrice = 2_500),
            product(ARCHIVED, "Vieja", archived = true),
            product(HUGE, "Silo", salePrice = 1_000_000_000_000),
            product(LOST_BASE, "Sin base", unitId = 999L),
        )
        addConversion(1L, SODA, BOX, 12_000, salePrice = 22_000)
        addConversion(2L, FLOUR, SACK, 50_000, forSale = false)
        addConversion(3L, EGG, DOZEN, 12_000, forSale = false, forPurchase = false)
        addConversion(4L, HUGE, BOX, 2_000)
        addConversion(5L, EMPANADA, DOZEN, 12_000, forSale = false, forPurchase = false)
        addRecipe(
            RecipeComponent(1L, EMPANADA, FLOUR, QuantityMilli(100)),
            RecipeComponent(2L, EMPANADA, EGG, QuantityMilli(1_000)),
        )
        addMethods(
            method(CASH_WITH_ACCOUNT, accountId = ACCOUNT),
            method(CASH_WITHOUT_ACCOUNT, accountId = null),
            method(ARCHIVED_METHOD, accountId = ACCOUNT, archived = true),
        )
    }
    private val writers get() = world.writers
    private val register = world.register()

    private fun method(id: Long, accountId: Long?, archived: Boolean = false) = PaymentMethodRecord(
        id = id,
        systemKey = "CASH",
        details = FakePaymentMethodRepository.initialDetails(PaymentMethodKind.CASH)
            .copy(moneyAccountId = accountId),
        sortOrder = id,
        archivedAt = if (archived) PhraseWorld.EPOCH else null,
        createdAt = PhraseWorld.EPOCH,
        updatedAt = PhraseWorld.EPOCH,
    )

    private fun line(
        productId: Long?,
        quantity: Long? = 1_000L,
        unitId: Long? = null,
        unitPrice: Long? = null,
        lineTotal: Long? = null,
    ) = DraftLine(
        product = productId?.let { DraftTarget.Existing(it) },
        quantity = quantity?.let(::QuantityMilli),
        unitId = unitId,
        unitPrice = unitPrice?.let(::Money),
        lineTotal = lineTotal?.let(::Money),
    )

    private fun sale(vararg lines: DraftLine, total: Long? = null) = PhraseDraft(
        intent = PhraseIntent.SALE,
        lines = lines.toList(),
        paymentMethodId = CASH_WITH_ACCOUNT,
        total = total?.let(::Money),
    )

    private fun purchase(vararg lines: DraftLine) =
        PhraseDraft(intent = PhraseIntent.PURCHASE, lines = lines.toList(), partyId = SUPPLIER)

    private fun single(intent: PhraseIntent, vararg lines: DraftLine) = PhraseDraft(
        intent = intent,
        reason = StockAdjustmentReason.DAMAGED.takeIf { intent == PhraseIntent.STOCK_ADJUSTMENT },
        lines = lines.toList(),
    )

    private fun saleLine(
        productId: Long,
        quantity: Long,
        unitPrice: Long? = null,
        discount: LineDiscount = LineDiscount.None,
        unitId: Long? = null,
    ) = SaleLineRequest(
        productId = productId,
        quantity = QuantityMilli(quantity),
        unitPrice = unitPrice?.let(::Money),
        discount = discount,
        unitId = unitId,
    )

    private fun saleRequest(vararg lines: SaleLineRequest, adjustment: Long = 0L) = SaleRequest(
        lines = lines.toList(),
        paymentMethodId = CASH_WITH_ACCOUNT,
        orderAdjustment = Money(adjustment),
    )

    private fun rejected(reason: PhraseRejection) = RegisterPhraseResult.Rejected(reason)

    // Lo que falta.

    @Test
    fun `a card with gaps writes nothing and says what is missing`() = runTest {
        val result = register(PhraseDraft(intent = PhraseIntent.SALE, lines = listOf(line(BREAD))))
        assertEquals(RegisterPhraseResult.Incomplete(setOf(DraftGap.PAYMENT)), result)
        assertTrue(writers.requests.isEmpty())
    }

    // Venta.

    @Test
    fun `a sale goes with the price of the product, the payment and the customer`() = runTest {
        val result = register(sale(line(BREAD, 3_000)).copy(partyId = CUSTOMER))
        assertEquals(RegisterPhraseResult.Registered(PhraseIntent.SALE, 11L), result)
        assertEquals(
            listOf(saleRequest(saleLine(BREAD, 3_000)).copy(customerId = CUSTOMER)),
            writers.requests,
        )
    }

    @Test
    fun `a said price goes as it is, and a said line total is charged exactly`() = runTest {
        register(sale(line(BREAD, 2_000, unitPrice = 450), line(BREAD, 3_000, lineTotal = 1_000)))
        assertEquals(
            listOf(
                saleRequest(
                    saleLine(BREAD, 2_000, unitPrice = 450),
                    // 3 panes por 1000: a 334 con 2 de descuento.
                    saleLine(
                        BREAD,
                        3_000,
                        unitPrice = 334,
                        discount = LineDiscount.Amount(Money(2)),
                    ),
                ),
            ),
            writers.requests,
        )
    }

    @Test
    fun `a sale presentation is kept, and other units go to the base unit`() = runTest {
        register(
            sale(
                line(SODA, 2_000, unitId = BOX),
                // Dos docenas a 6000 la docena, sin presentación de venta: 24 a 500.
                line(EGG, 2_000, unitId = DOZEN, unitPrice = 6_000),
                // 500 gramos de queso por kilo: medio kilo al precio del producto.
                line(CHEESE, 500_000, unitId = GRAM),
            ),
        )
        assertEquals(
            listOf(
                saleRequest(
                    saleLine(SODA, 2_000, unitId = BOX),
                    saleLine(EGG, 24_000, unitPrice = 500),
                    saleLine(CHEESE, 500),
                ),
            ),
            writers.requests,
        )
    }

    @Test
    fun `a sale total is the adjustment that takes the lines to it`() = runTest {
        // 2 panes a 500 y una caja a 22000 son 23000; todo por 22500 es un descuento de 500.
        register(sale(line(BREAD, 2_000), line(SODA, 1_000, unitId = BOX), total = 22_500))
        assertEquals(
            listOf(
                saleRequest(
                    saleLine(BREAD, 2_000),
                    saleLine(SODA, 1_000, unitId = BOX),
                    adjustment = -500,
                ),
            ),
            writers.requests,
        )
    }

    @Test
    fun `a sale total that cannot be computed is refused`() = runTest {
        val result = register(sale(line(HUGE, 1_000, unitId = BOX), total = 100))
        assertEquals(rejected(PhraseRejection.AmountNotApplicable), result)
        assertTrue(writers.requests.isEmpty())
    }

    @Test
    fun `without a total, a price the card cannot compute is left to the sale`() = runTest {
        register(sale(line(HUGE, 1_000, unitId = BOX)))
        assertEquals(listOf(saleRequest(saleLine(HUGE, 1_000, unitId = BOX))), writers.requests)
    }

    @Test
    fun `a sale amount beyond the limits is refused`() = runTest {
        assertEquals(
            rejected(PhraseRejection.AmountNotApplicable),
            register(sale(line(BREAD, 1, lineTotal = 1_000_000_000_000))),
        )
        assertEquals(
            rejected(PhraseRejection.AmountNotApplicable),
            register(sale(line(EGG, 2_000, unitId = DOZEN, unitPrice = 1_000_000_000_000_000_000))),
        )
        assertTrue(writers.requests.isEmpty())
    }

    @Test
    fun `a sale refusal and a storage failure come back as they are`() = runTest {
        writers.sale = RegisterSaleResult.PaymentMethodNotFound
        assertEquals(
            rejected(PhraseRejection.Sale(RegisterSaleResult.PaymentMethodNotFound)),
            register(sale(line(BREAD))),
        )
        writers.sale = RegisterSaleResult.StorageFailed(StorageFailure.DISK_FULL)
        assertEquals(
            RegisterPhraseResult.StorageFailed(StorageFailure.DISK_FULL),
            register(sale(line(BREAD))),
        )
    }

    @Test
    fun `a product that is gone or archived is refused`() = runTest {
        assertEquals(
            rejected(PhraseRejection.ProductUnavailable(ARCHIVED)),
            register(sale(line(ARCHIVED))),
        )
        assertEquals(
            rejected(PhraseRejection.ProductUnavailable(999L)),
            register(sale(line(999L))),
        )
        assertTrue(writers.requests.isEmpty())
    }

    @Test
    fun `a unit that does not convert to the product is refused with its line`() = runTest {
        // Una libra no tiene tamaño fijo, un litro no es un peso, y un gramo no es una unidad.
        assertEquals(
            rejected(PhraseRejection.UnitNotConvertible(1)),
            register(sale(line(BREAD), line(CHEESE, unitId = POUND))),
        )
        assertEquals(
            rejected(PhraseRejection.UnitNotConvertible(0)),
            register(sale(line(CHEESE, unitId = LITER))),
        )
        assertEquals(
            rejected(PhraseRejection.UnitNotConvertible(0)),
            register(sale(line(BREAD, unitId = GRAM))),
        )
        assertEquals(
            rejected(PhraseRejection.UnitNotConvertible(0)),
            register(sale(line(BREAD, unitId = 999L))),
        )
        assertEquals(
            rejected(PhraseRejection.UnitNotConvertible(0)),
            register(sale(line(LOST_BASE, unitId = GRAM))),
        )
        // Lo que redondea a cero tampoco entra.
        assertEquals(
            rejected(PhraseRejection.UnitNotConvertible(0)),
            register(sale(line(CHEESE, 1, unitId = GRAM))),
        )
        assertTrue(writers.requests.isEmpty())
    }

    // Compra.

    @Test
    fun `a purchase writes each cost as it was said`() = runTest {
        val result = register(
            purchase(
                line(FLOUR, 2_000, unitId = SACK, unitPrice = 90_000),
                line(BREAD, 3_000, lineTotal = 1_200),
                // 500 gramos a 20 el gramo, por kilo: medio kilo por 10000.
                line(CHEESE, 500_000, unitId = GRAM, unitPrice = 20),
                // Dos docenas a 3000, sin presentación de compra: 24 por 6000.
                line(EGG, 2_000, unitId = DOZEN, unitPrice = 3_000),
            ),
        )
        assertEquals(RegisterPhraseResult.Registered(PhraseIntent.PURCHASE, 12L), result)
        assertEquals(
            listOf(
                PurchaseRequest(
                    lines = listOf(
                        PurchaseLineRequest(
                            FLOUR,
                            QuantityMilli(2_000),
                            PurchaseLineAmount.UnitCost(Money(90_000)),
                            SACK,
                        ),
                        PurchaseLineRequest(
                            BREAD,
                            QuantityMilli(3_000),
                            PurchaseLineAmount.LineTotal(Money(1_200)),
                        ),
                        PurchaseLineRequest(
                            CHEESE,
                            QuantityMilli(500),
                            PurchaseLineAmount.LineTotal(Money(10_000)),
                        ),
                        PurchaseLineRequest(
                            EGG,
                            QuantityMilli(24_000),
                            PurchaseLineAmount.LineTotal(Money(6_000)),
                        ),
                    ),
                    supplierId = SUPPLIER,
                ),
            ),
            writers.requests,
        )
    }

    @Test
    fun `a paid purchase takes the money from the account of its method`() = runTest {
        register(
            purchase(line(BREAD, unitPrice = 300)).copy(paymentMethodId = CASH_WITH_ACCOUNT),
        )
        val request = writers.requests.single() as PurchaseRequest
        assertEquals(ACCOUNT, request.moneyAccountId)
        assertEquals(null, request.amountPaid)
    }

    @Test
    fun `a credit purchase pays nothing now and moves no account`() = runTest {
        register(
            purchase(line(BREAD, unitPrice = 300))
                .copy(paymentMethodId = CASH_WITH_ACCOUNT, credit = true),
        )
        val request = writers.requests.single() as PurchaseRequest
        assertEquals(null, request.moneyAccountId)
        assertEquals(Money.ZERO, request.amountPaid)
    }

    @Test
    fun `a purchase with a method that is gone is refused`() = runTest {
        for (methodId in listOf(ARCHIVED_METHOD, 999L)) {
            assertEquals(
                rejected(PhraseRejection.PaymentMethodUnavailable),
                register(purchase(line(BREAD, unitPrice = 300)).copy(paymentMethodId = methodId)),
            )
        }
        assertTrue(writers.requests.isEmpty())
    }

    @Test
    fun `a purchase that cannot be placed or priced is refused`() = runTest {
        assertEquals(
            rejected(PhraseRejection.ProductUnavailable(ARCHIVED)),
            register(purchase(line(ARCHIVED, unitPrice = 300))),
        )
        assertEquals(
            rejected(PhraseRejection.UnitNotConvertible(0)),
            register(purchase(line(CHEESE, unitId = POUND, unitPrice = 300))),
        )
        assertEquals(
            rejected(PhraseRejection.AmountNotApplicable),
            register(
                purchase(
                    line(CHEESE, 500_000, unitId = GRAM, unitPrice = 1_000_000_000_000_000_000),
                ),
            ),
        )
        assertTrue(writers.requests.isEmpty())
    }

    @Test
    fun `a purchase refusal and a storage failure come back as they are`() = runTest {
        writers.purchase = RegisterPurchaseResult.SupplierNotFound
        assertEquals(
            rejected(PhraseRejection.Purchase(RegisterPurchaseResult.SupplierNotFound)),
            register(purchase(line(BREAD, unitPrice = 300))),
        )
        writers.purchase = RegisterPurchaseResult.StorageFailed(StorageFailure.IO_ERROR)
        assertEquals(
            RegisterPhraseResult.StorageFailed(StorageFailure.IO_ERROR),
            register(purchase(line(BREAD, unitPrice = 300))),
        )
    }

    // Gasto, abono y pago.

    private val expense = PhraseDraft(
        intent = PhraseIntent.EXPENSE,
        categoryId = CATEGORY,
        total = Money(5_000),
        paymentMethodId = CASH_WITH_ACCOUNT,
    )

    @Test
    fun `an expense leaves from the account of its method`() = runTest {
        assertEquals(
            RegisterPhraseResult.Registered(PhraseIntent.EXPENSE, 13L),
            register(expense),
        )
        assertEquals(
            listOf(ExpenseRequest(Money(5_000), CATEGORY, moneyAccountId = ACCOUNT)),
            writers.requests,
        )
    }

    @Test
    fun `an expense without a method moves no account, and a gone method is refused`() = runTest {
        register(expense.copy(paymentMethodId = CASH_WITHOUT_ACCOUNT))
        register(expense.copy(paymentMethodId = null))
        assertEquals(
            listOf(
                ExpenseRequest(Money(5_000), CATEGORY),
                ExpenseRequest(Money(5_000), CATEGORY),
            ),
            writers.requests,
        )
        assertEquals(
            rejected(PhraseRejection.PaymentMethodUnavailable),
            register(expense.copy(paymentMethodId = ARCHIVED_METHOD)),
        )
    }

    @Test
    fun `an expense refusal and a storage failure come back as they are`() = runTest {
        writers.expense = RegisterExpenseResult.CategoryArchived
        assertEquals(
            rejected(PhraseRejection.Expense(RegisterExpenseResult.CategoryArchived)),
            register(expense),
        )
        writers.expense = RegisterExpenseResult.StorageFailed(StorageFailure.CORRUPT)
        assertEquals(RegisterPhraseResult.StorageFailed(StorageFailure.CORRUPT), register(expense))
    }

    private val customerPayment = PhraseDraft(
        intent = PhraseIntent.CUSTOMER_PAYMENT,
        partyId = CUSTOMER,
        total = Money(20_000),
        paymentMethodId = CASH_WITH_ACCOUNT,
    )

    @Test
    fun `a customer payment enters the account of its method`() = runTest {
        assertEquals(
            RegisterPhraseResult.Registered(PhraseIntent.CUSTOMER_PAYMENT, 16L),
            register(customerPayment),
        )
        assertEquals(
            listOf(CustomerPaymentRequest(CUSTOMER, Money(20_000), ACCOUNT, null, null)),
            writers.requests,
        )
        assertEquals(
            rejected(PhraseRejection.PaymentMethodUnavailable),
            register(customerPayment.copy(paymentMethodId = ARCHIVED_METHOD)),
        )
    }

    @Test
    fun `a customer payment refusal and a storage failure come back as they are`() = runTest {
        writers.customerPayment = RecordCustomerPaymentResult.CustomerNotFound
        assertEquals(
            rejected(PhraseRejection.CustomerPayment(RecordCustomerPaymentResult.CustomerNotFound)),
            register(customerPayment),
        )
        writers.customerPayment = RecordCustomerPaymentResult.StorageFailed(StorageFailure.BUSY)
        assertEquals(
            RegisterPhraseResult.StorageFailed(StorageFailure.BUSY),
            register(customerPayment),
        )
    }

    private val supplierPayment = PhraseDraft(
        intent = PhraseIntent.SUPPLIER_PAYMENT,
        partyId = SUPPLIER,
        total = Money(50_000),
    )

    @Test
    fun `a supplier payment without a method moves no account`() = runTest {
        assertEquals(
            RegisterPhraseResult.Registered(PhraseIntent.SUPPLIER_PAYMENT, 17L),
            register(supplierPayment),
        )
        assertEquals(
            listOf(SupplierPaymentRequest(SUPPLIER, Money(50_000))),
            writers.requests,
        )
        assertEquals(
            rejected(PhraseRejection.PaymentMethodUnavailable),
            register(supplierPayment.copy(paymentMethodId = 999L)),
        )
    }

    @Test
    fun `a supplier payment refusal and a storage failure come back as they are`() = runTest {
        writers.supplierPayment = PaySupplierResult.SupplierNotFound
        assertEquals(
            rejected(PhraseRejection.SupplierPayment(PaySupplierResult.SupplierNotFound)),
            register(supplierPayment),
        )
        writers.supplierPayment = PaySupplierResult.StorageFailed(StorageFailure.DISK_FULL)
        assertEquals(
            RegisterPhraseResult.StorageFailed(StorageFailure.DISK_FULL),
            register(supplierPayment),
        )
    }

    // Producción.

    @Test
    fun `a production takes its inputs from the recipe`() = runTest {
        val result = register(single(PhraseIntent.PRODUCTION, line(EMPANADA, 20_000)))
        assertEquals(RegisterPhraseResult.Registered(PhraseIntent.PRODUCTION, 14L), result)
        assertEquals(
            listOf(
                ProductionRequest(
                    productId = EMPANADA,
                    quantity = QuantityMilli(20_000),
                    inputs = listOf(
                        ProductionInput(FLOUR, QuantityMilli(2_000)),
                        ProductionInput(EGG, QuantityMilli(20_000)),
                    ),
                    note = null,
                ),
            ),
            writers.requests,
        )
    }

    @Test
    fun `a production said in another unit goes in the base unit`() = runTest {
        register(single(PhraseIntent.PRODUCTION, line(EMPANADA, 2_000, unitId = DOZEN)))
        val request = writers.requests.single() as ProductionRequest
        assertEquals(QuantityMilli(24_000), request.quantity)
        assertEquals(
            listOf(
                ProductionInput(FLOUR, QuantityMilli(2_400)),
                ProductionInput(EGG, QuantityMilli(24_000)),
            ),
            request.inputs,
        )
    }

    @Test
    fun `a production whose recipe cannot be scaled is refused`() = runTest {
        assertEquals(
            rejected(
                PhraseRejection.ProductionInputs(RecipeInputsResult.InputRoundsToZero(FLOUR)),
            ),
            register(single(PhraseIntent.PRODUCTION, line(EMPANADA, 1))),
        )
        assertTrue(writers.requests.isEmpty())
    }

    @Test
    fun `a production goes one product at a time and with a product that exists`() = runTest {
        assertEquals(
            rejected(PhraseRejection.OneProductAtATime),
            register(single(PhraseIntent.PRODUCTION, line(EMPANADA), line(BREAD))),
        )
        assertEquals(
            rejected(PhraseRejection.ProductUnavailable(ARCHIVED)),
            register(single(PhraseIntent.PRODUCTION, line(ARCHIVED))),
        )
        assertEquals(
            rejected(PhraseRejection.UnitNotConvertible(0)),
            register(single(PhraseIntent.PRODUCTION, line(EMPANADA, unitId = GRAM))),
        )
    }

    @Test
    fun `a production refusal and a storage failure come back as they are`() = runTest {
        writers.production = RegisterProductionResult.NoRecipe
        assertEquals(
            rejected(PhraseRejection.Production(RegisterProductionResult.NoRecipe)),
            register(single(PhraseIntent.PRODUCTION, line(BREAD))),
        )
        // Sin receta no hay insumos: lo dice la producción de siempre.
        val request = writers.requests.single() as ProductionRequest
        assertEquals(emptyList<ProductionInput>(), request.inputs)
        writers.production = RegisterProductionResult.StorageFailed(StorageFailure.IO_ERROR)
        assertEquals(
            RegisterPhraseResult.StorageFailed(StorageFailure.IO_ERROR),
            register(single(PhraseIntent.PRODUCTION, line(EMPANADA))),
        )
    }

    // Ajuste.

    @Test
    fun `a count is the real stock, and another reason is what goes out`() = runTest {
        val count = PhraseDraft(
            intent = PhraseIntent.STOCK_ADJUSTMENT,
            reason = StockAdjustmentReason.COUNT_CORRECTION,
            lines = listOf(line(BREAD, 40_000)),
        )
        assertEquals(
            RegisterPhraseResult.Registered(PhraseIntent.STOCK_ADJUSTMENT, 15L),
            register(count),
        )
        register(single(PhraseIntent.STOCK_ADJUSTMENT, line(CHEESE, 500_000, unitId = GRAM)))
        assertEquals(
            listOf(
                StockAdjustmentRequest.Count(BREAD, QuantityMilli(40_000)),
                StockAdjustmentRequest.Change(
                    CHEESE,
                    StockAdjustmentReason.DAMAGED,
                    QuantityMilli(500),
                ),
            ),
            writers.requests,
        )
    }

    @Test
    fun `an adjustment goes one product at a time and with a unit that converts`() = runTest {
        assertEquals(
            rejected(PhraseRejection.OneProductAtATime),
            register(single(PhraseIntent.STOCK_ADJUSTMENT, line(BREAD), line(SODA))),
        )
        assertEquals(
            rejected(PhraseRejection.ProductUnavailable(ARCHIVED)),
            register(single(PhraseIntent.STOCK_ADJUSTMENT, line(ARCHIVED))),
        )
        assertEquals(
            rejected(PhraseRejection.UnitNotConvertible(0)),
            register(single(PhraseIntent.STOCK_ADJUSTMENT, line(BREAD, unitId = POUND))),
        )
    }

    @Test
    fun `an adjustment refusal and a storage failure come back as they are`() = runTest {
        writers.adjustment = StockAdjustmentResult.NoDifference
        assertEquals(
            rejected(PhraseRejection.Adjustment(StockAdjustmentResult.NoDifference)),
            register(single(PhraseIntent.STOCK_ADJUSTMENT, line(BREAD))),
        )
        writers.adjustment = StockAdjustmentResult.StorageFailed(StorageFailure.DISK_FULL)
        assertEquals(
            RegisterPhraseResult.StorageFailed(StorageFailure.DISK_FULL),
            register(single(PhraseIntent.STOCK_ADJUSTMENT, line(BREAD))),
        )
    }

    // Receta.

    private fun recipe(vararg lines: DraftLine, yields: Long? = null) = PhraseDraft(
        intent = PhraseIntent.RECIPE,
        target = DraftTarget.Existing(EMPANADA),
        targetQuantity = yields?.let(::QuantityMilli),
        lines = lines.toList(),
    )

    @Test
    fun `a recipe for ten keeps what one unit takes, in the base unit of each input`() = runTest {
        val result = register(
            recipe(
                line(FLOUR, 1_000, unitId = KILOGRAM),
                line(EGG, 2_000),
                line(CHEESE, 500_000, unitId = GRAM),
                yields = 10_000,
            ),
        )
        assertEquals(RegisterPhraseResult.Registered(PhraseIntent.RECIPE, EMPANADA), result)
        assertEquals(
            listOf(
                RecipeCall(
                    EMPANADA,
                    listOf(
                        RecipeLine(FLOUR, QuantityMilli(100)),
                        RecipeLine(EGG, QuantityMilli(200)),
                        RecipeLine(CHEESE, QuantityMilli(50)),
                    ),
                ),
            ),
            writers.requests,
        )
    }

    @Test
    fun `a recipe that does not say its yield is for one unit`() = runTest {
        register(recipe(line(EGG, 2_000)))
        assertEquals(
            listOf(RecipeCall(EMPANADA, listOf(RecipeLine(EGG, QuantityMilli(2_000))))),
            writers.requests,
        )
    }

    @Test
    fun `a recipe with an input that is gone or does not convert is refused`() = runTest {
        assertEquals(
            rejected(PhraseRejection.ProductUnavailable(ARCHIVED)),
            register(recipe(line(ARCHIVED))),
        )
        assertEquals(
            rejected(PhraseRejection.UnitNotConvertible(1)),
            register(recipe(line(EGG), line(FLOUR, unitId = LITER))),
        )
    }

    @Test
    fun `a recipe refusal and a storage failure come back as they are`() = runTest {
        writers.recipe = SaveRecipeResult.NotCombo
        assertEquals(
            rejected(PhraseRejection.Recipe(SaveRecipeResult.NotCombo)),
            register(recipe(line(EGG))),
        )
        writers.recipe = SaveRecipeResult.StorageFailed(StorageFailure.IO_ERROR)
        assertEquals(
            RegisterPhraseResult.StorageFailed(StorageFailure.IO_ERROR),
            register(recipe(line(EGG))),
        )
    }

    // Producto nuevo.

    @Test
    fun `a new product is created with its price, cost and unit`() = runTest {
        val result = register(
            PhraseDraft(
                intent = PhraseIntent.NEW_PRODUCT,
                target = DraftTarget.New("Zanahoria"),
                price = Money(1_500),
                cost = Money(600),
                unitId = KILOGRAM,
            ),
        )
        assertEquals(RegisterPhraseResult.Registered(PhraseIntent.NEW_PRODUCT, 20L), result)
        register(PhraseDraft(intent = PhraseIntent.NEW_PRODUCT, target = DraftTarget.New("Ajo")))
        assertEquals(
            listOf(
                ProductForm(
                    name = "Zanahoria",
                    baseUnitId = KILOGRAM,
                    salePrice = Money(1_500),
                    cost = Money(600),
                ),
                ProductForm(name = "Ajo"),
            ),
            writers.requests,
        )
    }

    @Test
    fun `a new product refusal and a storage failure come back as they are`() = runTest {
        val draft = PhraseDraft(intent = PhraseIntent.NEW_PRODUCT, target = DraftTarget.New("Pan"))
        writers.product = SaveProductResult.DuplicateName("Pan")
        assertEquals(
            rejected(PhraseRejection.Product(SaveProductResult.DuplicateName("Pan"))),
            register(draft),
        )
        writers.product = SaveProductResult.StorageFailed(StorageFailure.CORRUPT)
        assertEquals(RegisterPhraseResult.StorageFailed(StorageFailure.CORRUPT), register(draft))
    }

    // Costo y precio.

    private fun change(intent: PhraseIntent, vararg lines: DraftLine) =
        PhraseDraft(intent = intent, lines = lines.toList())

    @Test
    fun `a cost said by another unit goes per base unit`() = runTest {
        val intent = PhraseIntent.COST_CORRECTION
        assertEquals(
            RegisterPhraseResult.Registered(intent, 18L),
            register(change(intent, line(BREAD, null, unitPrice = 400))),
        )
        // Una caja de 12 a 12000 es 1000 la unidad, y 20 el gramo son 20000 el kilo.
        register(change(intent, line(SODA, null, unitId = BOX, unitPrice = 12_000)))
        register(change(intent, line(CHEESE, null, unitId = GRAM, unitPrice = 20)))
        assertEquals(
            listOf(
                AmountCall(BREAD, Money(400)),
                AmountCall(SODA, Money(1_000)),
                AmountCall(CHEESE, Money(20_000)),
            ),
            writers.requests,
        )
    }

    @Test
    fun `a cost correction that cannot be placed is refused`() = runTest {
        val intent = PhraseIntent.COST_CORRECTION
        assertEquals(
            rejected(PhraseRejection.OneProductAtATime),
            register(change(intent, line(BREAD, unitPrice = 1), line(SODA, unitPrice = 1))),
        )
        assertEquals(
            rejected(PhraseRejection.ProductUnavailable(ARCHIVED)),
            register(change(intent, line(ARCHIVED, unitPrice = 1))),
        )
        assertEquals(
            rejected(PhraseRejection.UnitNotConvertible(0)),
            register(change(intent, line(CHEESE, unitId = POUND, unitPrice = 1))),
        )
        assertEquals(
            rejected(PhraseRejection.AmountNotApplicable),
            register(change(intent, line(CHEESE, unitId = GRAM, unitPrice = 1_000_000_000_000))),
        )
        assertTrue(writers.requests.isEmpty())
    }

    @Test
    fun `a cost refusal and a storage failure come back as they are`() = runTest {
        val draft = change(PhraseIntent.COST_CORRECTION, line(BREAD, unitPrice = 400))
        writers.cost = CorrectCostResult.Unchanged
        assertEquals(rejected(PhraseRejection.Cost(CorrectCostResult.Unchanged)), register(draft))
        writers.cost = CorrectCostResult.StorageFailed(StorageFailure.BUSY)
        assertEquals(RegisterPhraseResult.StorageFailed(StorageFailure.BUSY), register(draft))
    }

    @Test
    fun `a new price goes per base unit, but never from a presentation`() = runTest {
        val intent = PhraseIntent.PRICE_CHANGE
        assertEquals(
            RegisterPhraseResult.Registered(intent, 19L),
            register(change(intent, line(BREAD, null, unitPrice = 600))),
        )
        register(change(intent, line(CHEESE, null, unitId = GRAM, unitPrice = 20)))
        assertEquals(
            listOf(AmountCall(BREAD, Money(600)), AmountCall(CHEESE, Money(20_000))),
            writers.requests,
        )
        assertEquals(
            rejected(PhraseRejection.PriceNotInBaseUnit),
            register(change(intent, line(SODA, null, unitId = BOX, unitPrice = 22_000))),
        )
    }

    @Test
    fun `a price change that cannot be placed is refused`() = runTest {
        val intent = PhraseIntent.PRICE_CHANGE
        assertEquals(
            rejected(PhraseRejection.OneProductAtATime),
            register(change(intent, line(BREAD, unitPrice = 1), line(SODA, unitPrice = 1))),
        )
        assertEquals(
            rejected(PhraseRejection.ProductUnavailable(999L)),
            register(change(intent, line(999L, unitPrice = 1))),
        )
        assertEquals(
            rejected(PhraseRejection.UnitNotConvertible(0)),
            register(change(intent, line(CHEESE, unitId = LITER, unitPrice = 1))),
        )
        assertEquals(
            rejected(PhraseRejection.AmountNotApplicable),
            register(change(intent, line(CHEESE, unitId = GRAM, unitPrice = 1_000_000_000_000))),
        )
        assertTrue(writers.requests.isEmpty())
    }

    @Test
    fun `a price refusal and a storage failure come back as they are`() = runTest {
        val draft = change(PhraseIntent.PRICE_CHANGE, line(BREAD, unitPrice = 600))
        writers.price = ChangePriceResult.ProductArchived
        assertEquals(
            rejected(PhraseRejection.Price(ChangePriceResult.ProductArchived)),
            register(draft),
        )
        writers.price = ChangePriceResult.StorageFailed(StorageFailure.IO_ERROR)
        assertEquals(RegisterPhraseResult.StorageFailed(StorageFailure.IO_ERROR), register(draft))
    }

    // La base.

    @Test
    fun `a storage failure while reading the catalog writes nothing`() = runTest {
        val broken = object : ProductRepository by world.products {
            override suspend fun findById(id: Long): Product? =
                throw StorageFailureException(StorageFailure.IO_ERROR)
        }
        val register = RegisterPhraseUseCase(
            writers = writers.writers(),
            products = broken,
            productUnits = world.productUnits,
            units = world.units,
            components = world.components,
            paymentMethods = world.paymentMethods,
        )
        assertEquals(
            RegisterPhraseResult.StorageFailed(StorageFailure.IO_ERROR),
            register(sale(line(BREAD))),
        )
        assertTrue(writers.requests.isEmpty())
    }

    private companion object {
        const val BREAD = 100L
        const val SODA = 101L
        const val CHEESE = 102L
        const val FLOUR = 103L
        const val EGG = 104L
        const val EMPANADA = 105L
        const val ARCHIVED = 106L
        const val HUGE = 107L
        const val LOST_BASE = 108L

        const val CASH_WITH_ACCOUNT = 400L
        const val CASH_WITHOUT_ACCOUNT = 401L
        const val ARCHIVED_METHOD = 402L
        const val ACCOUNT = 77L

        const val CUSTOMER = 200L
        const val SUPPLIER = 300L
        const val CATEGORY = 500L
    }
}
