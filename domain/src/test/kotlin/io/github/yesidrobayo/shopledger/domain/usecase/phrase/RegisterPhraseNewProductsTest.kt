package io.github.yesidrobayo.shopledger.domain.usecase.phrase

import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.catalog.ConversionDraft
import io.github.yesidrobayo.shopledger.domain.model.catalog.ConversionInputError
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductForm
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductStockMode
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductUsage
import io.github.yesidrobayo.shopledger.domain.model.catalog.RecipeComponent
import io.github.yesidrobayo.shopledger.domain.model.catalog.RecipeLine
import io.github.yesidrobayo.shopledger.domain.model.payment.PaymentMethodKind
import io.github.yesidrobayo.shopledger.domain.model.payment.PaymentMethodRecord
import io.github.yesidrobayo.shopledger.domain.model.phrase.DraftLine
import io.github.yesidrobayo.shopledger.domain.model.phrase.DraftTarget
import io.github.yesidrobayo.shopledger.domain.model.phrase.NewProductTraits
import io.github.yesidrobayo.shopledger.domain.model.phrase.PhraseDraft
import io.github.yesidrobayo.shopledger.domain.model.phrase.PhraseIntent
import io.github.yesidrobayo.shopledger.domain.model.phrase.SaidConversion
import io.github.yesidrobayo.shopledger.domain.model.purchase.PurchaseRequest
import io.github.yesidrobayo.shopledger.domain.model.sale.LineDiscount
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleRequest
import io.github.yesidrobayo.shopledger.domain.model.stock.StockAdjustmentReason
import io.github.yesidrobayo.shopledger.domain.model.stock.StockAdjustmentRequest
import io.github.yesidrobayo.shopledger.domain.rule.production.ProductionInput
import io.github.yesidrobayo.shopledger.domain.testing.FakePaymentMethodRepository
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.SaveConversionResult
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.SaveProductResult
import io.github.yesidrobayo.shopledger.domain.usecase.phrase.PhraseWorld.Companion.GRAM
import io.github.yesidrobayo.shopledger.domain.usecase.phrase.PhraseWorld.Companion.KILOGRAM
import io.github.yesidrobayo.shopledger.domain.usecase.phrase.PhraseWorld.Companion.POUND
import io.github.yesidrobayo.shopledger.domain.usecase.production.ProductionStep
import io.github.yesidrobayo.shopledger.domain.usecase.sale.RegisterSaleResult
import io.github.yesidrobayo.shopledger.domain.usecase.sale.product
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Lo que no existía se crea con la operación: los productos nuevos de una tarjeta y la medida que
 * se dijo en ella van en la misma escritura que la venta, la compra, el conteo, la producción o la
 * receta, y si algo se rechaza no queda nada. Además, un combo de la receta se cuenta una sola vez
 * al producir con lo dicho. Los productos y montos son inventados.
 */
class RegisterPhraseNewProductsTest {
    private val world = PhraseWorld().apply {
        addProducts(
            product(FLOUR, "Harina", unitId = KILOGRAM, usage = ProductUsage.INPUT),
            product(EGG, "Huevo"),
            product(SUGAR, "Azúcar", unitId = KILOGRAM),
            product(FILLING, "Relleno", stockMode = ProductStockMode.COMPONENTS),
            product(PIE, "Pastel"),
        )
        addRecipe(
            RecipeComponent(1L, FILLING, SUGAR, QuantityMilli(100)),
            RecipeComponent(2L, FILLING, EGG, QuantityMilli(2_000)),
            RecipeComponent(3L, PIE, FILLING, QuantityMilli(1_000)),
            RecipeComponent(4L, PIE, FLOUR, QuantityMilli(200)),
        )
        addMethods(
            PaymentMethodRecord(
                id = CASH,
                systemKey = "CASH",
                details = FakePaymentMethodRepository.initialDetails(PaymentMethodKind.CASH),
                sortOrder = CASH,
                archivedAt = null,
                createdAt = PhraseWorld.EPOCH,
                updatedAt = PhraseWorld.EPOCH,
            ),
        )
    }
    private val writers get() = world.writers
    private val register = world.register()

    private fun fresh(name: String, traits: NewProductTraits = NewProductTraits.NONE) =
        DraftTarget.New(name, traits)

    private fun line(
        product: DraftTarget?,
        milli: Long = 1_000L,
        unitId: Long? = null,
        unitPrice: Long? = null,
        lineTotal: Long? = null,
    ) = DraftLine(
        product,
        QuantityMilli(milli),
        unitId,
        unitPrice?.let(::Money),
        lineTotal?.let(::Money),
    )

    private fun sale(vararg lines: DraftLine) =
        PhraseDraft(PhraseIntent.SALE, lines = lines.toList(), paymentMethodId = CASH)

    private fun form(
        name: String,
        unitId: Long? = null,
        salePrice: Long = 0L,
        usage: ProductUsage? = null,
    ) = ProductForm(name = name, baseUnitId = unitId, salePrice = Money(salePrice), usage = usage)

    @Test
    fun `a sale of something new creates it with its said price in the same write`() = runTest {
        val result = register(sale(line(fresh("Torta"), 2_000, unitPrice = 5_000)))

        assertEquals(RegisterPhraseResult.Registered(PhraseIntent.SALE, 11L), result)
        assertEquals(listOf(true), writers.committed)
        val request = writers.requests[1] as SaleRequest
        assertEquals(form("Torta", salePrice = 5_000), writers.requests[0])
        assertEquals(40L, request.lines.single().productId)
        assertEquals(Money(5_000), request.lines.single().unitPrice)
    }

    @Test
    fun `a said line total gives the price, and a repeated name is one product`() = runTest {
        register(
            sale(
                line(fresh("Torta"), 3_000, lineTotal = 1_000),
                line(fresh("torta "), 1_000, unitPrice = 400),
            ),
        )

        // 3 tortas por 1000: a 334 cada una.
        assertEquals(form("Torta", salePrice = 334), writers.requests[0])
        val request = writers.requests[1] as SaleRequest
        assertEquals(listOf(40L, 40L), request.lines.map { it.productId })
        assertEquals(LineDiscount.Amount(Money(2)), request.lines[0].discount)
    }

    @Test
    fun `a purchase and a count create what is new in the said unit`() = runTest {
        val bought = PhraseDraft(
            PhraseIntent.PURCHASE,
            lines = listOf(line(fresh("Avena"), 2_000, KILOGRAM, unitPrice = 3_000)),
        )
        register(bought)
        assertEquals(form("Avena", unitId = KILOGRAM), writers.requests[0])
        val purchase = writers.requests[1] as PurchaseRequest
        assertEquals(40L, purchase.lines.single().productId)
        assertEquals(null, purchase.lines.single().unitId)

        val counted = PhraseDraft(
            PhraseIntent.STOCK_ADJUSTMENT,
            reason = StockAdjustmentReason.COUNT_CORRECTION,
            lines = listOf(line(fresh("Canela"), 5_000)),
        )
        val result = register(counted)
        assertEquals(RegisterPhraseResult.Registered(PhraseIntent.STOCK_ADJUSTMENT, 15L), result)
        assertEquals(
            StockAdjustmentRequest.Count(41L, QuantityMilli(5_000)),
            writers.requests.last(),
        )
        assertEquals(listOf(true, true), writers.committed)
    }

    @Test
    fun `something new is produced with what it takes, which becomes its recipe`() = runTest {
        val input = NewProductTraits(usage = ProductUsage.INPUT)
        val made = PhraseDraft(
            PhraseIntent.PRODUCTION,
            lines = listOf(line(fresh("Galleta"), 2_000)),
            inputs = listOf(
                line(fresh("Avena", input), 400_000, GRAM),
                line(DraftTarget.Existing(EGG), 3_000),
            ),
        )

        val result = register(made)

        assertEquals(RegisterPhraseResult.Registered(PhraseIntent.PRODUCTION, 14L), result)
        assertEquals(form("Galleta"), writers.requests[0])
        assertEquals(form("Avena", unitId = GRAM, usage = ProductUsage.INPUT), writers.requests[1])
        val step = writers.requests[2] as ProductionStep
        assertEquals(
            listOf(
                ProductionInput(41L, QuantityMilli(400_000)),
                ProductionInput(EGG, QuantityMilli(3_000)),
            ),
            step.request.inputs,
        )
        // Por galleta: 200 g de avena y 1,5 huevos.
        assertEquals(
            listOf(RecipeLine(41L, QuantityMilli(200_000)), RecipeLine(EGG, QuantityMilli(1_500))),
            step.recipe,
        )
    }

    @Test
    fun `a recipe of something new creates it with its recipe`() = runTest {
        val recipe = PhraseDraft(
            PhraseIntent.RECIPE,
            target = fresh("Brownie", NewProductTraits(newCategory = "Postres")),
            lines = listOf(line(DraftTarget.Existing(FLOUR), 500)),
        )

        val result = register(recipe)

        assertEquals(RegisterPhraseResult.Registered(PhraseIntent.RECIPE, 40L), result)
        assertEquals(listOf<String?>("Postres"), writers.newCategories)
        assertEquals(
            RecipeCall(40L, listOf(RecipeLine(FLOUR, QuantityMilli(500)))),
            writers.requests[1],
        )
    }

    @Test
    fun `when something is refused nothing stays, not even the new product`() = runTest {
        writers.refusedProduct = SaveProductResult.DuplicateName("Torta")
        val refused = register(sale(line(fresh("Torta"), unitPrice = 100)))
        assertEquals(
            RegisterPhraseResult.Rejected(
                PhraseRejection.Product(SaveProductResult.DuplicateName("Torta")),
            ),
            refused,
        )
        assertEquals(listOf(false), writers.committed)

        writers.refusedProduct = null
        writers.sale = RegisterSaleResult.EmptySale
        val result = register(sale(line(fresh("Torta"), unitPrice = 100)))
        assertEquals(
            RegisterPhraseResult.Rejected(PhraseRejection.Sale(RegisterSaleResult.EmptySale)),
            result,
        )
        assertEquals(listOf(false, false), writers.committed)
    }

    @Test
    fun `a said measure is saved as a conversion of the product with the operation`() = runTest {
        val said = SaidConversion(FLOUR, POUND, 454L)
        val measured = sale(line(DraftTarget.Existing(EGG))).copy(conversions = listOf(said))

        assertEquals(RegisterPhraseResult.Registered(PhraseIntent.SALE, 11L), register(measured))
        assertEquals(
            ConversionDraft(FLOUR, POUND, 454L, null, null, forSale = false, forPurchase = false),
            writers.requests[0],
        )
        // Una medida que el producto ya tiene guardada no detiene la operación.
        writers.conversion = SaveConversionResult.UnitTaken
        assertEquals(RegisterPhraseResult.Registered(PhraseIntent.SALE, 11L), register(measured))
        val invalid = SaveConversionResult.Invalid(setOf(ConversionInputError.FACTOR_TOO_HIGH))
        writers.conversion = invalid
        assertEquals(
            RegisterPhraseResult.Rejected(PhraseRejection.Conversion(invalid)),
            register(measured),
        )
        assertEquals(listOf(true, true, false), writers.committed)
    }

    // Un combo de la receta se cuenta una sola vez.

    @Test
    fun `an input that the recipe brings inside a combo is not counted twice`() = runTest {
        // El pastel lleva un relleno (0,100 kg de azúcar y 2 huevos) y 0,200 kg de harina; se
        // dijo que esta tanda llevó 3 huevos, y se actualiza la receta.
        val made = PhraseDraft(
            PhraseIntent.PRODUCTION,
            lines = listOf(line(DraftTarget.Existing(PIE))),
            inputs = listOf(line(DraftTarget.Existing(EGG), 3_000)),
            updatesRecipe = true,
        )

        register(made)

        val step = writers.requests.single() as ProductionStep
        assertEquals(
            listOf(
                ProductionInput(SUGAR, QuantityMilli(100)),
                ProductionInput(EGG, QuantityMilli(3_000)),
                ProductionInput(FLOUR, QuantityMilli(200)),
            ),
            step.request.inputs,
        )
        // El relleno se abre: el huevo queda una vez, y no suelto y dentro del combo.
        assertEquals(
            listOf(
                RecipeLine(SUGAR, QuantityMilli(100)),
                RecipeLine(EGG, QuantityMilli(3_000)),
                RecipeLine(FLOUR, QuantityMilli(200)),
            ),
            step.recipe,
        )
    }

    @Test
    fun `a said combo is consumed by its components in place of the recipe's`() = runTest {
        val made = PhraseDraft(
            PhraseIntent.PRODUCTION,
            lines = listOf(line(DraftTarget.Existing(PIE))),
            inputs = listOf(line(DraftTarget.Existing(FILLING), 2_000)),
        )

        register(made)

        val step = writers.requests.single() as ProductionStep
        // Dos rellenos: 0,200 kg de azúcar y 4 huevos, más la harina de la receta.
        assertEquals(
            listOf(
                ProductionInput(FLOUR, QuantityMilli(200)),
                ProductionInput(SUGAR, QuantityMilli(200)),
                ProductionInput(EGG, QuantityMilli(4_000)),
            ),
            step.request.inputs,
        )
        assertEquals(null, step.recipe)
    }

    @Test
    fun `a combo that cannot be counted stops the production`() = runTest {
        val made = PhraseDraft(
            PhraseIntent.PRODUCTION,
            lines = listOf(line(DraftTarget.Existing(PIE))),
            inputs = listOf(line(DraftTarget.Existing(FILLING), 1)),
        )

        val result = register(made)

        assertEquals(
            PhraseRejection.ProductionInputs::class,
            ((result as RegisterPhraseResult.Rejected).reason)::class,
        )
    }

    private companion object {
        const val FLOUR = 600L
        const val EGG = 601L
        const val SUGAR = 602L
        const val FILLING = 603L
        const val PIE = 604L
        const val CASH = 700L
    }
}
