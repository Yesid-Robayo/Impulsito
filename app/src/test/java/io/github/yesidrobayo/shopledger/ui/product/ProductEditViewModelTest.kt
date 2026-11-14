package io.github.yesidrobayo.shopledger.ui.product

import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.Margin
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.catalog.CatalogChoices
import io.github.yesidrobayo.shopledger.domain.model.catalog.Category
import io.github.yesidrobayo.shopledger.domain.model.catalog.CategoryDetails
import io.github.yesidrobayo.shopledger.domain.model.catalog.ConversionDetails
import io.github.yesidrobayo.shopledger.domain.model.catalog.ConversionDraft
import io.github.yesidrobayo.shopledger.domain.model.catalog.CustomField
import io.github.yesidrobayo.shopledger.domain.model.catalog.CustomFieldType
import io.github.yesidrobayo.shopledger.domain.model.catalog.CustomValueIssue
import io.github.yesidrobayo.shopledger.domain.model.catalog.HistoryLockedField
import io.github.yesidrobayo.shopledger.domain.model.catalog.ImageImport
import io.github.yesidrobayo.shopledger.domain.model.catalog.MeasureUnit
import io.github.yesidrobayo.shopledger.domain.model.catalog.PricingContext
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductConversion
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductExtras
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductForEdit
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductForm
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductFormIssue
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductPhotoChange
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductStockMode
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductTag
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductUsage
import io.github.yesidrobayo.shopledger.domain.model.catalog.StoredImage
import io.github.yesidrobayo.shopledger.domain.model.catalog.UnitDraft
import io.github.yesidrobayo.shopledger.domain.model.catalog.UnitKind
import io.github.yesidrobayo.shopledger.domain.model.pricing.PriceList
import io.github.yesidrobayo.shopledger.domain.model.pricing.PriceListPricing
import io.github.yesidrobayo.shopledger.domain.model.region.NumberSymbols
import io.github.yesidrobayo.shopledger.domain.model.settings.CurrencyCode
import io.github.yesidrobayo.shopledger.domain.model.settings.ReferenceCurrency
import io.github.yesidrobayo.shopledger.domain.model.settings.TaxMode
import io.github.yesidrobayo.shopledger.domain.port.StorageFailure
import io.github.yesidrobayo.shopledger.domain.rule.pricing.ListPriceOrigin
import io.github.yesidrobayo.shopledger.domain.rule.pricing.ListPriceResult
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.DeleteConversionResult
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.GenerateOwnBarcodesResult
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.OwnBarcodeClash
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.SaveCategoryResult
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.SaveConversionResult
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.SaveProductExtrasResult
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.SaveProductResult
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.SaveUnitResult
import io.github.yesidrobayo.shopledger.domain.usecase.pricing.ProductListPrice
import io.github.yesidrobayo.shopledger.domain.usecase.pricing.SetListPriceResult
import io.github.yesidrobayo.shopledger.ui.payment.ImageProblem
import io.github.yesidrobayo.shopledger.ui.pricing.ListPriceEditorAction
import io.github.yesidrobayo.shopledger.ui.unit.ConversionEditorAction
import io.github.yesidrobayo.shopledger.ui.unit.UnitEditorAction
import io.github.yesidrobayo.shopledger.ui.unit.UnitNames
import kotlin.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** El formulario de un producto, con respuestas fijas del dominio. Los datos son inventados. */
@OptIn(ExperimentalCoroutinesApi::class)
class ProductEditViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val operations = FakeOperations()

    // Coma decimal y sin grupos: lo que limpia el campo antes de llegar aquí.
    private val numbers =
        ProductNumberFormat(SYMBOLS, currencyDecimals = 2, quantitySymbols = SYMBOLS)

    @Before
    fun useTestMain() = Dispatchers.setMain(dispatcher)

    @After
    fun resetTestMain() = Dispatchers.resetMain()

    private fun newProduct(prefill: String? = null) =
        ProductEditViewModel(operations, numbers, null, prefill, NAMES)

    @Test
    fun `a new product starts with the prefilled name and the counting unit`() =
        runTest(dispatcher) {
            val model = newProduct(prefill = "acite")
            advanceUntilIdle()

            val state = model.uiState.value
            assertTrue(state.isNew)
            assertEquals("acite", state.form.name)
            assertEquals(COUNT.id, state.form.unitId)
            assertEquals(listOf(GRAINS), state.categories)
            assertFalse(state.dirty)
        }

    @Test
    fun `the profit card follows price and cost on every keystroke`() = runTest(dispatcher) {
        val model = newProduct()
        advanceUntilIdle()

        model.onAction(ProductEditAction.SalePriceChange("50"))
        assertEquals(ProfitPreview.NoCost, model.uiState.value.profit)
        model.onAction(ProductEditAction.CostChange("38"))
        // 5000 y 3800 en unidad menor: 1200 de ganancia y 24,0 % de margen.
        assertEquals(
            ProfitPreview.Profit(Money(1_200L), Margin.Defined(BasisPoints(2_400L))),
            model.uiState.value.profit,
        )
        model.onAction(ProductEditAction.CostChange("52"))
        assertEquals(ProfitPreview.Loss(Money(200L)), model.uiState.value.profit)
        model.onAction(ProductEditAction.SalePriceChange(""))
        assertEquals(ProfitPreview.NoPrice, model.uiState.value.profit)
        assertTrue(model.uiState.value.dirty)
    }

    @Test
    fun `the card suggests a price from the target margin and uses it`() = runTest(dispatcher) {
        val model = newProduct()
        advanceUntilIdle()
        assertNull(model.uiState.value.suggestTarget)

        model.onAction(ProductEditAction.CostChange("13,55"))
        assertEquals(BasisPoints(3_000L), model.uiState.value.suggestTarget)

        model.onAction(ProductEditAction.OpenSuggestion)
        // 1355 con 30 % pide 1936, que deja 30,01 %.
        assertEquals(
            PriceSuggestionDraft(
                "30",
                SuggestionOutcome.Suggested(Money(1_936L), Margin.Defined(BasisPoints(3_001L))),
            ),
            model.uiState.value.suggestion,
        )

        model.onAction(ProductEditAction.SuggestionTarget("35"))
        assertEquals(Money(2_085L), model.uiState.value.suggestion?.price)

        model.onAction(ProductEditAction.UseSuggestedPrice)
        val state = model.uiState.value
        assertNull(state.suggestion)
        assertEquals("20,85", state.form.salePrice)
        assertEquals(
            ProfitPreview.Profit(Money(730L), Margin.Defined(BasisPoints(3_501L))),
            state.profit,
        )
        assertTrue(state.dirty)
    }

    @Test
    fun `the target follows the chosen category and is not offered without a cost`() =
        runTest(dispatcher) {
            operations.pricing.value =
                operations.pricing.value.copy(
                    categoryTargets = mapOf(GRAINS.id to BasisPoints(4_000L)),
                )
            val model = newProduct()
            advanceUntilIdle()
            model.onAction(ProductEditAction.SalePriceChange("50"))
            assertNull(model.uiState.value.suggestTarget)
            model.onAction(ProductEditAction.OpenSuggestion)
            assertNull(model.uiState.value.suggestion)

            model.onAction(ProductEditAction.CostChange("10"))
            model.onAction(ProductEditAction.CategoryChange(GRAINS.id))
            assertEquals(BasisPoints(4_000L), model.uiState.value.suggestTarget)

            model.onAction(ProductEditAction.OpenSuggestion)
            model.onAction(ProductEditAction.DismissSuggestion)
            assertNull(model.uiState.value.suggestion)

            model.onAction(ProductEditAction.StockModeChange(ProductStockMode.COMPONENTS))
            assertNull(model.uiState.value.suggestTarget)
        }

    @Test
    fun `with the tax inside the price the card measures without it`() = runTest(dispatcher) {
        operations.pricing.value =
            operations.pricing.value.copy(
                taxMode = TaxMode.INCLUDED,
                defaultTaxRateId = 9L,
                taxRates = mapOf(9L to BasisPoints(2_100L)),
            )
        val model = newProduct()
        advanceUntilIdle()

        model.onAction(ProductEditAction.SalePriceChange("17,30"))
        model.onAction(ProductEditAction.CostChange("10"))

        // 1730 sin el 21 % son 1430: deja 430, el 30,07 %.
        assertEquals(
            ProfitPreview.Profit(Money(430L), Margin.Defined(BasisPoints(3_007L))),
            model.uiState.value.profit,
        )
        model.onAction(ProductEditAction.OpenSuggestion)
        assertEquals(Money(1_730L), model.uiState.value.suggestion?.price)
    }

    @Test
    fun `saving reads the numbers in minor units and thousandths`() = runTest(dispatcher) {
        val model = newProduct()
        advanceUntilIdle()
        model.onAction(ProductEditAction.NameChange("Jabón Líquido"))
        model.onAction(ProductEditAction.CategoryChange(GRAINS.id))
        model.onAction(ProductEditAction.SalePriceChange("35"))
        model.onAction(ProductEditAction.CostChange("12"))
        model.onAction(ProductEditAction.InitialStockChange("24"))
        model.onAction(ProductEditAction.MinStockChange("5"))
        model.onAction(ProductEditAction.BarcodeChange(" 7701234 "))

        model.onAction(ProductEditAction.Save)
        advanceUntilIdle()

        val (form, stock) = operations.created.single()
        assertEquals("Jabón Líquido", form.name)
        assertEquals(GRAINS.id, form.categoryId)
        assertEquals(COUNT.id, form.baseUnitId)
        assertEquals(Money(3_500L), form.salePrice)
        assertEquals(Money(1_200L), form.cost)
        assertEquals(QuantityMilli(5_000L), form.minStock)
        assertEquals("7701234", form.barcode)
        assertEquals(QuantityMilli(24_000L), stock)
        assertEquals(7L, model.uiState.value.savedProductId)
        assertFalse(model.uiState.value.dirty)
    }

    @Test
    fun `a new product is sold, and choosing input saves it as an input`() = runTest(dispatcher) {
        val model = newProduct()
        advanceUntilIdle()
        assertEquals(ProductUsage.SALE, model.uiState.value.form.usage)
        // Un producto nuevo no avisa nada: no tiene cotizaciones ni pedidos.
        model.onAction(ProductEditAction.UsageChange(ProductUsage.INPUT))
        assertFalse(model.uiState.value.warnsInputChange)
        assertTrue(model.uiState.value.dirty)
        model.onAction(ProductEditAction.NameChange("Harina"))

        model.onAction(ProductEditAction.Save)
        advanceUntilIdle()

        assertEquals(ProductUsage.INPUT, operations.created.single().first.usage)
    }

    @Test
    fun `a sold product turned into an input warns about its open quotes and orders`() =
        runTest(dispatcher) {
            val stored = product(5L, "Azúcar", salePrice = 2_000L)
            operations.editing = ProductForEdit(
                product = stored.copy(details = stored.details.copy(usage = ProductUsage.BOTH)),
                lockedFields = emptySet(),
                costLocked = false,
            )
            val model = ProductEditViewModel(
                operations,
                numbers,
                productId = 5L,
                prefillName = null,
                unitNames = NAMES,
            )
            advanceUntilIdle()
            assertEquals(ProductUsage.BOTH, model.uiState.value.form.usage)
            assertFalse(model.uiState.value.warnsInputChange)

            model.onAction(ProductEditAction.UsageChange(ProductUsage.INPUT))
            assertTrue(model.uiState.value.warnsInputChange)
            model.onAction(ProductEditAction.UsageChange(ProductUsage.SALE))
            assertFalse(model.uiState.value.warnsInputChange)
            model.onAction(ProductEditAction.UsageChange(ProductUsage.INPUT))

            model.onAction(ProductEditAction.Save)
            advanceUntilIdle()
            assertEquals(ProductUsage.INPUT, operations.edited.single().second.usage)
        }

    @Test
    fun `fields that cannot be read are marked and nothing is sent`() = runTest(dispatcher) {
        val model = newProduct()
        advanceUntilIdle()
        model.onAction(ProductEditAction.SalePriceChange("1,234"))
        model.onAction(ProductEditAction.InitialStockChange("2,5"))
        model.onAction(ProductEditAction.MinStockChange("999999999999"))

        model.onAction(ProductEditAction.Save)
        advanceUntilIdle()

        assertEquals(
            mapOf(
                ProductEditField.NAME to FieldProblem.Empty,
                ProductEditField.SALE_PRICE to FieldProblem.TooManyDecimals(2),
                ProductEditField.INITIAL_STOCK to FieldProblem.FractionNotAllowed,
                ProductEditField.MIN_STOCK to FieldProblem.TooHigh,
            ),
            model.uiState.value.errors,
        )
        assertTrue(operations.created.isEmpty())
        // Escribir en un campo borra su error, y solo el suyo.
        model.onAction(ProductEditAction.NameChange("Arroz"))
        assertNull(model.uiState.value.errors[ProductEditField.NAME])
        assertEquals(3, model.uiState.value.errors.size)
    }

    @Test
    fun `leaving the name marks a duplicate, and a rejected save keeps everything`() =
        runTest(dispatcher) {
            operations.clash = "Jabón Líquido"
            val model = newProduct()
            advanceUntilIdle()
            model.onAction(ProductEditAction.NameChange("jabon liquido"))
            model.onAction(ProductEditAction.NameLeft)
            advanceUntilIdle()
            assertEquals(
                FieldProblem.Duplicate("Jabón Líquido"),
                model.uiState.value.errors[ProductEditField.NAME],
            )

            operations.clash = null
            operations.result = SaveProductResult.Invalid(setOf(ProductFormIssue.BARCODE_TOO_LONG))
            model.onAction(ProductEditAction.NameChange("Arroz"))
            model.onAction(ProductEditAction.Save)
            advanceUntilIdle()
            assertEquals(
                mapOf(ProductEditField.BARCODE to FieldProblem.TooLong(64)),
                model.uiState.value.errors,
            )
            assertEquals("Arroz", model.uiState.value.form.name)
            assertNull(model.uiState.value.savedProductId)
        }

    @Test
    fun `a storage failure and an unconfirmed currency become notices`() = runTest(dispatcher) {
        val model = newProduct()
        advanceUntilIdle()
        model.onAction(ProductEditAction.NameChange("Arroz"))

        operations.result = SaveProductResult.StorageFailed(StorageFailure.DISK_FULL)
        model.onAction(ProductEditAction.Save)
        advanceUntilIdle()
        assertEquals(ProductEditNotice.SaveFailed(outOfSpace = true), model.uiState.value.notice)

        operations.result = SaveProductResult.CurrencyUnconfirmed
        model.onAction(ProductEditAction.Save)
        advanceUntilIdle()
        assertEquals(ProductEditNotice.CurrencyUnconfirmed, model.uiState.value.notice)
        assertFalse(model.uiState.value.saving)
    }

    @Test
    fun `editing loads the product, locks what its history fixes and keeps the cost`() =
        runTest(dispatcher) {
            operations.editing = ProductForEdit(
                product = product(
                    5L,
                    "Arroz",
                    salePrice = 420_000L,
                    averageCost = 300_000L,
                    onHand = 6_000L,
                ),
                lockedFields = HistoryLockedField.entries.toSet(),
                costLocked = true,
            )
            val model =
                ProductEditViewModel(
                    operations,
                    numbers,
                    productId = 5L,
                    prefillName = null,
                    unitNames = NAMES,
                )
            assertTrue(model.uiState.value.loading)
            advanceUntilIdle()

            val state = model.uiState.value
            assertFalse(state.loading)
            assertEquals("Arroz", state.form.name)
            assertEquals("4200", state.form.salePrice)
            assertEquals("3000", state.form.cost)
            assertTrue(state.unitLocked)
            assertTrue(state.costLocked)
            assertEquals(Money(300_000L), state.averageCost)

            model.onAction(ProductEditAction.SalePriceChange("4500,5"))
            model.onAction(ProductEditAction.Save)
            advanceUntilIdle()
            val (id, form) = operations.edited.single()
            assertEquals(5L, id)
            assertEquals(Money(450_050L), form.salePrice)
            assertEquals(Money(300_000L), form.cost)
        }

    @Test
    fun `the reference price shows in its currency and only a change is sent`() =
        runTest(dispatcher) {
            val stored = product(5L, "Arroz", salePrice = 420_000L, averageCost = 300_000L)
            operations.editing = ProductForEdit(
                product = stored.copy(
                    details = stored.details.copy(referencePrice = Money(1_250L)),
                ),
                lockedFields = emptySet(),
                costLocked = false,
            )
            val reference = MutableStateFlow<ReferenceCurrency?>(null)
            val model = ProductEditViewModel(
                operations,
                numbers,
                productId = 5L,
                prefillName = null,
                unitNames = NAMES,
                referenceCurrency = { reference },
            )
            advanceUntilIdle()
            // Sin moneda de referencia no se pide, y el guardado no toca lo que había.
            assertNull(model.uiState.value.reference)
            model.onAction(ProductEditAction.Save)
            advanceUntilIdle()
            assertNull(operations.edited.last().second.referencePrice)

            // Con una moneda de 3 decimales, 1250 se lee 1,25, sin ceros de más, como los demás
            // montos.
            reference.value = ReferenceCurrency(requireNotNull(CurrencyCode.of("XTS")), 3)
            advanceUntilIdle()
            assertEquals("1,25", model.uiState.value.form.referencePrice)
            model.onAction(ProductEditAction.Save)
            advanceUntilIdle()
            assertNull(operations.edited.last().second.referencePrice)

            model.onAction(ProductEditAction.ReferencePriceChange("2,5"))
            model.onAction(ProductEditAction.Save)
            advanceUntilIdle()
            assertEquals(Money(2_500L), operations.edited.last().second.referencePrice)

            model.onAction(ProductEditAction.ReferencePriceChange("1,23456"))
            model.onAction(ProductEditAction.Save)
            advanceUntilIdle()
            assertEquals(
                FieldProblem.TooManyDecimals(3),
                model.uiState.value.errors[ProductEditField.REFERENCE_PRICE],
            )
            model.onAction(ProductEditAction.ReferencePriceChange("abc"))
            model.onAction(ProductEditAction.Save)
            advanceUntilIdle()
            assertEquals(
                FieldProblem.NotANumber,
                model.uiState.value.errors[ProductEditField.REFERENCE_PRICE],
            )

            // En blanco es sin dato: se manda cero.
            model.onAction(ProductEditAction.ReferencePriceChange(""))
            model.onAction(ProductEditAction.Save)
            advanceUntilIdle()
            assertEquals(Money.ZERO, operations.edited.last().second.referencePrice)
        }

    @Test
    fun `the other lists show their price, and a fixed one opens its sheet`() =
        runTest(dispatcher) {
            operations.editing = ProductForEdit(
                product = product(5L, "Arroz", salePrice = 420_000L, averageCost = 300_000L),
                lockedFields = emptySet(),
                costLocked = false,
            )
            val wholesale = PriceList(2L, null, "Mayorista", PriceListPricing.Fixed, null)
            val special =
                PriceList(
                    3L,
                    null,
                    "Especial",
                    PriceListPricing.Relative(1L, BasisPoints(-1_000L)),
                    null,
                )
            val prices = listOf(
                ProductListPrice(
                    wholesale,
                    ListPriceResult.Priced(Money(400_000L), ListPriceOrigin.OWN),
                ),
                ProductListPrice(
                    special,
                    ListPriceResult.Priced(Money(378_000L), ListPriceOrigin.CALCULATED),
                ),
            )
            val sent = mutableListOf<Triple<Long, Long, Money?>>()
            val model =
                ProductEditViewModel(
                    operations,
                    numbers,
                    productId = 5L,
                    prefillName = null,
                    unitNames = NAMES,
                    listPrices = { MutableStateFlow(prices) },
                    setListPrice = { list, product, price ->
                        sent += Triple(list, product, price)
                        SetListPriceResult.Changed(1L)
                    },
                )
            advanceUntilIdle()
            assertEquals(prices, model.uiState.value.listPrices)

            // Una relativa se calcula: no abre nada.
            model.onAction(ProductEditAction.EditListPrice(special.id))
            assertNull(model.listPriceEditor.editor.value)

            model.onAction(ProductEditAction.EditListPrice(wholesale.id))
            val editor = requireNotNull(model.listPriceEditor.editor.value)
            assertEquals("4000", editor.priceText)
            assertTrue(editor.hasOwnPrice)
            model.listPriceEditor.onAction(ListPriceEditorAction.Price("4100"))
            model.listPriceEditor.onAction(ListPriceEditorAction.Save)
            advanceUntilIdle()
            assertEquals(Triple(wholesale.id, 5L, Money(410_000L)), sent.single())
            assertNull(model.listPriceEditor.editor.value)
        }

    @Test
    fun `the replacement cost loads as written, empty stays empty, and only a change is sent`() =
        runTest(dispatcher) {
            operations.editing = ProductForEdit(
                product = product(5L, "Arroz", salePrice = 420_000L, averageCost = 300_000L)
                    .copy(replacementCost = Money(310_050L)),
                lockedFields = emptySet(),
                costLocked = false,
            )
            val model = ProductEditViewModel(operations, numbers, 5L, null, NAMES)
            advanceUntilIdle()
            assertEquals("3100,5", model.uiState.value.form.replacementCost)

            // Sin tocar el campo no se manda: lo que ponga una compra mientras tanto no se pisa.
            model.onAction(ProductEditAction.SalePriceChange("4300"))
            model.onAction(ProductEditAction.Save)
            advanceUntilIdle()
            assertNull(operations.edited.last().second.replacementCost)

            model.onAction(ProductEditAction.ReplacementCostChange("3200"))
            model.onAction(ProductEditAction.Save)
            advanceUntilIdle()
            assertEquals(Money(320_000L), operations.edited.last().second.replacementCost)

            model.onAction(ProductEditAction.ReplacementCostChange(""))
            model.onAction(ProductEditAction.Save)
            advanceUntilIdle()
            assertEquals(Money.ZERO, operations.edited.last().second.replacementCost)
        }

    @Test
    fun `without a replacement cost the field is empty, never the average cost`() =
        runTest(dispatcher) {
            operations.editing = ProductForEdit(
                product = product(5L, "Arroz", salePrice = 420_000L, averageCost = 300_000L),
                lockedFields = emptySet(),
                costLocked = true,
            )
            val model = ProductEditViewModel(operations, numbers, 5L, null, NAMES)
            advanceUntilIdle()

            assertEquals("", model.uiState.value.form.replacementCost)
        }

    @Test
    fun `a replacement cost that cannot be read marks its field and saves nothing`() =
        runTest(dispatcher) {
            val model = newProduct()
            advanceUntilIdle()
            model.onAction(ProductEditAction.NameChange("Arroz"))
            model.onAction(ProductEditAction.ReplacementCostChange("1,234"))
            model.onAction(ProductEditAction.Save)
            advanceUntilIdle()

            assertEquals(
                FieldProblem.TooManyDecimals(2),
                model.uiState.value.errors[ProductEditField.REPLACEMENT_COST],
            )
            assertTrue(operations.created.isEmpty())

            model.onAction(ProductEditAction.ReplacementCostChange("12,5"))
            assertNull(model.uiState.value.errors[ProductEditField.REPLACEMENT_COST])
            model.onAction(ProductEditAction.Save)
            advanceUntilIdle()
            assertEquals(Money(1_250L), operations.created.single().first.replacementCost)
        }

    @Test
    fun `a rejected replacement cost is shown on its field`() = runTest(dispatcher) {
        val model = newProduct()
        advanceUntilIdle()
        model.onAction(ProductEditAction.NameChange("Arroz"))
        operations.result = SaveProductResult.Invalid(
            setOf(ProductFormIssue.REPLACEMENT_COST_OUT_OF_RANGE),
        )

        model.onAction(ProductEditAction.Save)
        advanceUntilIdle()

        assertEquals(
            FieldProblem.TooHigh,
            model.uiState.value.errors[ProductEditField.REPLACEMENT_COST],
        )
    }

    @Test
    fun `a service sends no replacement cost`() = runTest(dispatcher) {
        val model = newProduct()
        advanceUntilIdle()
        model.onAction(ProductEditAction.NameChange("Corte"))
        model.onAction(ProductEditAction.ReplacementCostChange("10"))
        model.onAction(ProductEditAction.StockModeChange(ProductStockMode.UNTRACKED))

        model.onAction(ProductEditAction.Save)
        advanceUntilIdle()

        assertEquals(Money.ZERO, operations.created.single().first.replacementCost)
    }

    @Test
    fun `a service has no stock to count and its cost is the fixed cost of each sale`() =
        runTest(dispatcher) {
            val model = newProduct()
            advanceUntilIdle()
            model.onAction(ProductEditAction.NameChange("Corte de cabello"))
            model.onAction(ProductEditAction.InitialStockChange("1,5"))
            model.onAction(ProductEditAction.MinStockChange("abc"))
            model.onAction(ProductEditAction.CostChange("2500"))

            model.onAction(ProductEditAction.StockModeChange(ProductStockMode.UNTRACKED))
            assertFalse(model.uiState.value.tracksStock)
            model.onAction(ProductEditAction.Save)
            advanceUntilIdle()

            // Lo escrito en el stock no cuenta ni da error: un servicio no tiene stock.
            val (form, initial) = operations.created.single()
            assertEquals(ProductStockMode.UNTRACKED, form.stockMode)
            assertEquals(QuantityMilli.ZERO, form.minStock)
            assertEquals(QuantityMilli.ZERO, initial)
            assertEquals(Money(250_000L), form.cost)
        }

    @Test
    fun `with history the base unit and the stock mode stay as they are`() = runTest(dispatcher) {
        operations.editing = ProductForEdit(
            product = product(5L, "Arroz", onHand = 6_000L),
            lockedFields = setOf(HistoryLockedField.BASE_UNIT, HistoryLockedField.STOCK_MODE),
            costLocked = false,
        )
        val model =
            ProductEditViewModel(
                operations,
                numbers,
                productId = 5L,
                prefillName = null,
                unitNames = NAMES,
            )
        advanceUntilIdle()

        assertTrue(model.uiState.value.unitLocked)
        assertTrue(model.uiState.value.stockModeLocked)
        model.onAction(ProductEditAction.StockModeChange(ProductStockMode.UNTRACKED))
        assertEquals(ProductStockMode.TRACKED, model.uiState.value.form.stockMode)
    }

    @Test
    fun `only the stock mode locked by lots leaves the unit free`() = runTest(dispatcher) {
        operations.editing = ProductForEdit(
            product = product(5L, "Arroz"),
            lockedFields = setOf(HistoryLockedField.TRACKS_LOTS),
            costLocked = false,
        )
        val model =
            ProductEditViewModel(
                operations,
                numbers,
                productId = 5L,
                prefillName = null,
                unitNames = NAMES,
            )
        advanceUntilIdle()

        assertFalse(model.uiState.value.unitLocked)
        assertFalse(model.uiState.value.stockModeLocked)
    }

    @Test
    fun `a saved product shows its conversions and adds one with itself already chosen`() =
        runTest(dispatcher) {
            operations.editing =
                ProductForEdit(product(5L, "Arroz"), lockedFields = emptySet(), costLocked = false)
            val model =
                ProductEditViewModel(
                    operations,
                    numbers,
                    productId = 5L,
                    prefillName = null,
                    unitNames = NAMES,
                )
            advanceUntilIdle()
            val box = ProductConversion(
                30L,
                5L,
                ConversionDetails(KILO.id, 24_000L, null, null, forSale = true, forPurchase = true),
            )
            operations.conversions.value = listOf(box)
            advanceUntilIdle()
            assertEquals(listOf(box), model.uiState.value.conversions)

            model.onAction(ProductEditAction.EditConversion(30L))
            assertEquals(30L, model.conversionEditor.editor.value?.editingId)
            model.conversionEditor.onAction(ConversionEditorAction.Dismiss)

            model.onAction(ProductEditAction.NewConversion)
            val editor = requireNotNull(model.conversionEditor.editor.value)
            assertEquals(5L, editor.productId)
            assertTrue(editor.productLocked)
            model.conversionEditor.onAction(ConversionEditorAction.AlternateUnit(KILO.id))
            model.conversionEditor.onAction(ConversionEditorAction.Factor("12"))
            model.conversionEditor.onAction(ConversionEditorAction.Save)
            advanceUntilIdle()
            assertEquals(12_000L, operations.conversionsSaved.single().second.factorMilli)
            assertNull(model.conversionEditor.editor.value)
        }

    @Test
    fun `a new product has no conversions to add yet`() = runTest(dispatcher) {
        val model = newProduct()
        advanceUntilIdle()

        model.onAction(ProductEditAction.NewConversion)
        model.onAction(ProductEditAction.ManageUnits)

        assertNull(model.conversionEditor.editor.value)
    }

    @Test
    fun `a unit created from the picker is chosen for the product`() = runTest(dispatcher) {
        val model = newProduct()
        advanceUntilIdle()

        model.onAction(ProductEditAction.NewUnit)
        model.unitEditor.onAction(UnitEditorAction.Name("Bulto"))
        model.unitEditor.onAction(UnitEditorAction.Save)
        advanceUntilIdle()

        assertEquals("Bulto", operations.unitsSaved.single().name)
        assertEquals(77L, model.uiState.value.form.unitId)
        assertNull(model.unitEditor.editor.value)
    }

    @Test
    fun `a missing product says so`() = runTest(dispatcher) {
        val model =
            ProductEditViewModel(
                operations,
                numbers,
                productId = 9L,
                prefillName = null,
                unitNames = NAMES,
            )
        advanceUntilIdle()

        assertTrue(model.uiState.value.notFound)
    }

    @Test
    fun `closing with changes asks first, and without changes closes`() = runTest(dispatcher) {
        val model = newProduct()
        advanceUntilIdle()
        model.onAction(ProductEditAction.Close)
        assertTrue(model.uiState.value.closed)

        val edited = newProduct()
        advanceUntilIdle()
        edited.onAction(ProductEditAction.NameChange("Arroz"))
        edited.onAction(ProductEditAction.Close)
        assertTrue(edited.uiState.value.confirmingDiscard)
        edited.onAction(ProductEditAction.KeepEditing)
        assertFalse(edited.uiState.value.confirmingDiscard)
        assertFalse(edited.uiState.value.closed)
        edited.onAction(ProductEditAction.Close)
        edited.onAction(ProductEditAction.Discard)
        assertTrue(edited.uiState.value.closed)
    }

    @Test
    fun `a category created from the sheet is chosen`() = runTest(dispatcher) {
        val model = newProduct()
        advanceUntilIdle()

        model.onAction(ProductEditAction.CreateCategory("Lácteos"))
        advanceUntilIdle()

        assertEquals(listOf("Lácteos"), operations.categoriesCreated)
        assertEquals(42L, model.uiState.value.form.categoryId)
    }

    @Test
    fun `a code read by the scanner starts a new product`() = runTest(dispatcher) {
        val model = ProductEditViewModel(
            operations,
            numbers,
            null,
            null,
            NAMES,
            prefillBarcode = "P00000042",
        )
        advanceUntilIdle()

        assertEquals("P00000042", model.uiState.value.form.barcode)
    }

    @Test
    fun `an own code is generated for a saved product without one`() = runTest(dispatcher) {
        operations.editing = ProductForEdit(product(5L, "Arroz"), emptySet(), costLocked = false)
        val asked = mutableListOf<Long>()
        val model =
            ProductEditViewModel(
                operations,
                numbers,
                5L,
                null,
                NAMES,
                generateBarcode = { id ->
                    asked += id
                    val generated = mapOf(id to "P00000005")
                    GenerateOwnBarcodesResult.Done(generated, emptyList(), emptyList())
                },
            )
        advanceUntilIdle()

        model.onAction(ProductEditAction.GenerateBarcode)
        advanceUntilIdle()

        assertEquals(listOf(5L), asked)
        assertEquals("P00000005", model.uiState.value.form.barcode)
        // Ya quedó guardado: cerrar no pregunta por cambios.
        assertFalse(model.uiState.value.dirty)
    }

    @Test
    fun `a generated code another product has is marked on the field`() = runTest(dispatcher) {
        operations.editing = ProductForEdit(product(5L, "Arroz"), emptySet(), costLocked = false)
        val model =
            ProductEditViewModel(
                operations,
                numbers,
                5L,
                null,
                NAMES,
                generateBarcode = { id ->
                    GenerateOwnBarcodesResult.Done(
                        emptyMap(),
                        listOf(OwnBarcodeClash(id, "P00000005", "Aceite")),
                        emptyList(),
                    )
                },
            )
        advanceUntilIdle()

        model.onAction(ProductEditAction.GenerateBarcode)
        advanceUntilIdle()

        assertEquals("", model.uiState.value.form.barcode)
        assertEquals(
            FieldProblem.Duplicate("Aceite"),
            model.uiState.value.errors[ProductEditField.BARCODE],
        )
    }

    @Test
    fun `a new product or one with a code does not generate`() = runTest(dispatcher) {
        var calls = 0
        val fresh =
            ProductEditViewModel(operations, numbers, null, null, NAMES, generateBarcode = {
                calls++
                GenerateOwnBarcodesResult.Done(emptyMap(), emptyList(), emptyList())
            })
        advanceUntilIdle()
        fresh.onAction(ProductEditAction.GenerateBarcode)
        advanceUntilIdle()

        assertEquals(0, calls)
    }

    @Test
    fun `custom values arrive in the regional format and are saved neutral after the product`() =
        runTest(dispatcher) {
            operations.extras.value =
                ProductExtras(
                    EXTRA_FIELDS,
                    mapOf(WARRANTY.id to "12.5"),
                    listOf(SEASON),
                    emptySet(),
                )
            val model = newProduct(prefill = "Arroz")
            advanceUntilIdle()

            val shown = model.uiState.value.extras
            assertTrue(shown.visible)
            assertEquals("12,5", shown.values[WARRANTY.id])

            model.onAction(ProductEditAction.ExtraValueChange(BRAND.id, "Del valle"))
            model.onAction(ProductEditAction.ExtraValueChange(WARRANTY.id, "-1,250"))
            model.onAction(ProductEditAction.ExtraValueChange(EXPIRY.id, "2026-12-31"))
            model.onAction(ProductEditAction.ExtraValueChange(GIFT.id, "true"))
            model.onAction(ProductEditAction.ExtraTagToggle(SEASON.id))
            model.onAction(ProductEditAction.Save)
            advanceUntilIdle()

            assertEquals(1, operations.created.size)
            assertEquals(
                listOf(
                    Triple(
                        7L,
                        mapOf(
                            BRAND.id to "Del valle",
                            WARRANTY.id to "-1.25",
                            EXPIRY.id to "2026-12-31",
                            GIFT.id to "true",
                        ),
                        setOf(SEASON.id),
                    ),
                ),
                operations.extrasSaved,
            )
            assertEquals(7L, model.uiState.value.savedProductId)
        }

    @Test
    fun `a custom value that does not fit its type stops the save before the product`() =
        runTest(dispatcher) {
            operations.extras.value =
                ProductExtras(EXTRA_FIELDS, emptyMap(), emptyList(), emptySet())
            val model = newProduct(prefill = "Arroz")
            advanceUntilIdle()

            model.onAction(ProductEditAction.ExtraValueChange(WARRANTY.id, "-"))
            model.onAction(ProductEditAction.ExtraValueChange(BRAND.id, "x".repeat(201)))
            model.onAction(ProductEditAction.Save)
            advanceUntilIdle()

            assertTrue(operations.created.isEmpty())
            assertTrue(operations.extrasSaved.isEmpty())
            assertEquals(
                mapOf(
                    WARRANTY.id to CustomValueIssue.NOT_A_NUMBER,
                    BRAND.id to CustomValueIssue.TOO_LONG,
                ),
                model.uiState.value.extras.errors,
            )
            // Escribir de nuevo borra el error de ese campo, y lo escrito sigue ahí.
            model.onAction(ProductEditAction.ExtraValueChange(WARRANTY.id, "3"))
            assertEquals(setOf(BRAND.id), model.uiState.value.extras.errors.keys)
            assertEquals("x".repeat(201), model.uiState.value.extras.values[BRAND.id])
        }

    @Test
    fun `when only the custom values fail, the form stays and retrying does not create again`() =
        runTest(dispatcher) {
            operations.extras.value =
                ProductExtras(EXTRA_FIELDS, emptyMap(), listOf(SEASON), emptySet())
            operations.extrasResult =
                SaveProductExtrasResult.StorageFailed(StorageFailure.DISK_FULL)
            val model = newProduct(prefill = "Arroz")
            advanceUntilIdle()
            model.onAction(ProductEditAction.ExtraTagToggle(SEASON.id))

            model.onAction(ProductEditAction.Save)
            advanceUntilIdle()

            val failed = model.uiState.value
            assertEquals(ProductEditNotice.SaveFailed(outOfSpace = true), failed.notice)
            assertNull(failed.savedProductId)
            assertEquals(setOf(SEASON.id), failed.extras.tagIds)
            assertEquals(1, operations.created.size)

            operations.extrasResult = SaveProductExtrasResult.Saved
            model.onAction(ProductEditAction.Save)
            advanceUntilIdle()

            // El producto ya estaba: no se crea ni se edita otra vez, solo se guarda lo propio.
            assertEquals(1, operations.created.size)
            assertTrue(operations.edited.isEmpty())
            assertEquals(listOf(7L, 7L), operations.extrasSaved.map { it.first })
            assertEquals(7L, model.uiState.value.savedProductId)
        }

    @Test
    fun `without touching the custom values only the product is saved`() = runTest(dispatcher) {
        operations.extras.value =
            ProductExtras(EXTRA_FIELDS, mapOf(BRAND.id to "Del valle"), listOf(SEASON), setOf(1L))
        val model = newProduct(prefill = "Arroz")
        advanceUntilIdle()
        assertTrue(model.uiState.value.extras.hasData)

        model.onAction(ProductEditAction.Save)
        advanceUntilIdle()

        assertEquals(1, operations.created.size)
        assertTrue(operations.extrasSaved.isEmpty())
        assertEquals(7L, model.uiState.value.savedProductId)
    }

    private class FakeOperations : ProductEditOperations {
        val choices = MutableStateFlow(CatalogChoices(listOf(GRAINS), listOf(COUNT, KILO)))
        var editing: ProductForEdit? = null
        var clash: String? = null
        var result: SaveProductResult? = null
        val created = mutableListOf<Pair<ProductForm, QuantityMilli>>()
        val edited = mutableListOf<Pair<Long, ProductForm>>()
        val categoriesCreated = mutableListOf<String>()

        override suspend fun load(productId: Long): ProductForEdit? = editing?.takeIf {
            it.product.id ==
                productId
        }

        override fun choices(): Flow<CatalogChoices> = choices

        override suspend fun nameClash(name: String, editingId: Long?): String? = clash

        override suspend fun create(
            form: ProductForm,
            initialStock: QuantityMilli,
        ): SaveProductResult {
            created += form to initialStock
            return result ?: SaveProductResult.Saved(7L)
        }

        override suspend fun edit(productId: Long, form: ProductForm): SaveProductResult {
            edited += productId to form
            return result ?: SaveProductResult.Saved(productId)
        }

        override suspend fun createCategory(name: String): SaveCategoryResult {
            categoriesCreated += name
            return SaveCategoryResult.Saved(42L)
        }

        val conversions = MutableStateFlow<List<ProductConversion>>(emptyList())
        val unitsSaved = mutableListOf<UnitDraft>()
        val conversionsSaved = mutableListOf<Pair<Long?, ConversionDraft>>()

        override fun conversions(productId: Long): Flow<List<ProductConversion>> = conversions

        override suspend fun saveConversion(
            editingId: Long?,
            draft: ConversionDraft,
        ): SaveConversionResult {
            conversionsSaved += editingId to draft
            return SaveConversionResult.Saved(editingId ?: 30L)
        }

        override suspend fun deleteConversion(id: Long): DeleteConversionResult =
            DeleteConversionResult.Deleted

        override suspend fun saveUnit(draft: UnitDraft): SaveUnitResult {
            unitsSaved += draft
            return SaveUnitResult.Saved(77L)
        }

        /** Un negocio sin impuesto, con margen objetivo de 30 % y sin paso de redondeo. */
        val pricing = MutableStateFlow(
            PricingContext(
                defaultTargetMargin = BasisPoints(3_000L),
                categoryTargets = emptyMap(),
                taxMode = TaxMode.NONE,
                defaultTaxRateId = null,
                taxRates = emptyMap(),
                roundingStep = Money.ZERO,
            ),
        )

        override fun pricing(): Flow<PricingContext> = pricing

        var nextPhoto: ImageImport = ImageImport.Imported(PHOTO)
        val photosImported = mutableListOf<String>()

        override suspend fun importPhoto(source: String): ImageImport {
            photosImported += source
            return nextPhoto
        }

        /** Sin campos propios ni etiquetas, salvo que una prueba los ponga. */
        val extras =
            MutableStateFlow(ProductExtras(emptyList(), emptyMap(), emptyList(), emptySet()))
        var extrasResult: SaveProductExtrasResult = SaveProductExtrasResult.Saved
        val extrasSaved = mutableListOf<Triple<Long, Map<Long, String>, Set<Long>>>()

        override fun extras(productId: Long?): Flow<ProductExtras> = extras

        override suspend fun saveExtras(
            productId: Long,
            values: Map<Long, String>,
            tagIds: Set<Long>,
        ): SaveProductExtrasResult {
            extrasSaved += Triple(productId, values, tagIds)
            return extrasResult
        }

        override fun calendar(): Flow<FieldCalendar?> = MutableStateFlow(null)
    }

    @Test
    fun `a chosen photo is imported and saved with the new product`() = runTest(dispatcher) {
        val model = newProduct()
        advanceUntilIdle()
        model.onAction(ProductEditAction.NameChange("Pan de queso"))

        model.onAction(ProductEditAction.PickPhoto("content://picker/7"))
        assertTrue(model.uiState.value.importingPhoto)
        advanceUntilIdle()

        assertEquals(listOf("content://picker/7"), operations.photosImported)
        assertEquals(EditPhoto.Picked(PHOTO), model.uiState.value.form.photo)
        assertFalse(model.uiState.value.importingPhoto)
        model.onAction(ProductEditAction.Save)
        advanceUntilIdle()
        assertEquals(ProductPhotoChange.Use(PHOTO), operations.created.single().first.photo)
    }

    @Test
    fun `an unreadable photo keeps the one there was and says why`() = runTest(dispatcher) {
        val model = newProduct()
        advanceUntilIdle()
        model.onAction(ProductEditAction.PickPhoto("content://picker/1"))
        advanceUntilIdle()

        operations.nextPhoto = ImageImport.Unreadable
        model.onAction(ProductEditAction.PickPhoto("content://picker/2"))
        advanceUntilIdle()
        assertEquals(EditPhoto.Picked(PHOTO), model.uiState.value.form.photo)
        assertEquals(ImageProblem.UNREADABLE, model.uiState.value.photoProblem)

        operations.nextPhoto = ImageImport.OutOfSpace
        model.onAction(ProductEditAction.PickPhoto("content://picker/3"))
        advanceUntilIdle()
        assertEquals(ImageProblem.OUT_OF_SPACE, model.uiState.value.photoProblem)
        model.onAction(ProductEditAction.RemovePhoto)
        assertEquals(EditPhoto.None, model.uiState.value.form.photo)
        assertEquals(null, model.uiState.value.photoProblem)
    }

    @Test
    fun `a new product without a photo keeps none`() = runTest(dispatcher) {
        val model = newProduct()
        advanceUntilIdle()
        model.onAction(ProductEditAction.NameChange("Pan de queso"))
        // Abrir el selector lo resuelve la ruta: el formulario no cambia.
        model.onAction(ProductEditAction.ChoosePhoto)
        assertEquals(EditPhoto.None, model.uiState.value.form.photo)

        model.onAction(ProductEditAction.Save)
        advanceUntilIdle()

        assertEquals(ProductPhotoChange.Keep, operations.created.single().first.photo)
    }

    private companion object {
        /** Una foto ya recodificada, con una suma inventada. */
        val PHOTO = StoredImage("c".repeat(64), "image/webp", 640, 480, 21_000L)

        /** Los nombres de las unidades, sin recursos: la clave hace de nombre traducido. */
        val NAMES =
            object : UnitNames {
                override fun name(unit: MeasureUnit): String = unit.name ?: unit.systemKey.orEmpty()

                override fun symbol(unit: MeasureUnit): String? = unit.symbol
            }
        val SYMBOLS = NumberSymbols(decimalSeparator = ',', minusSign = '-', grouping = null)
        val COUNT = MeasureUnit(1L, "UNIT", null, null, UnitKind.COUNT, false, null)
        val BRAND = CustomField(1L, "Marca", CustomFieldType.TEXT, 0L, false, 0)
        val WARRANTY = CustomField(2L, "Garantía", CustomFieldType.NUMBER, 1L, false, 1)
        val EXPIRY = CustomField(3L, "Vence", CustomFieldType.DATE, 2L, false, 0)
        val GIFT = CustomField(4L, "Para regalo", CustomFieldType.BOOLEAN, 3L, false, 0)
        val EXTRA_FIELDS = listOf(BRAND, WARRANTY, EXPIRY, GIFT)
        val SEASON = ProductTag(9L, "Temporada", 0)
        val KILO = MeasureUnit(2L, "KILOGRAM", null, null, UnitKind.MASS, true, null)
        val GRAINS = Category(
            id = 3L,
            details = CategoryDetails("Granos", "granos", null),
            createdAt = Instant.fromEpochMilliseconds(0L),
            updatedAt = Instant.fromEpochMilliseconds(0L),
        )
    }
}
