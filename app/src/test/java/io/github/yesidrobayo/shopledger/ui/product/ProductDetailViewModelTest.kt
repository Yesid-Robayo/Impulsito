package io.github.yesidrobayo.shopledger.ui.product

import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.Margin
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.catalog.PricingContext
import io.github.yesidrobayo.shopledger.domain.model.catalog.Product
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductDetails
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductOverview
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductStockMode
import io.github.yesidrobayo.shopledger.domain.model.region.DigitGrouping
import io.github.yesidrobayo.shopledger.domain.model.region.NumberSymbols
import io.github.yesidrobayo.shopledger.domain.model.settings.TaxMode
import io.github.yesidrobayo.shopledger.domain.port.StorageFailure
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.ArchiveProductResult
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.ChangePriceResult
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.DeleteProductResult
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.ReactivateProductResult
import io.github.yesidrobayo.shopledger.domain.usecase.stock.CorrectCostResult
import io.github.yesidrobayo.shopledger.domain.usecase.stock.VoidAdjustmentResult
import io.github.yesidrobayo.shopledger.ui.unit.SaveFailure
import kotlin.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
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

/** El detalle de un producto y su hoja de corregir el costo, con respuestas fijas del dominio. */
@OptIn(ExperimentalCoroutinesApi::class)
class ProductDetailViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val operations = FakeDetailOperations()

    @Before
    fun useTestMain() = Dispatchers.setMain(dispatcher)

    @After
    fun resetTestMain() = Dispatchers.resetMain()

    private fun TestScope.model(): ProductDetailViewModel {
        val model = ProductDetailViewModel(1L, operations, NUMBERS)
        backgroundScope.launch(dispatcher) { model.uiState.collect {} }
        advanceUntilIdle()
        return model
    }

    @Test
    fun `arriving to fix the cost opens the sheet once`() = runTest(dispatcher) {
        val model = ProductDetailViewModel(1L, operations, NUMBERS, openCostSheet = true)
        backgroundScope.launch(dispatcher) { model.uiState.collect {} }
        advanceUntilIdle()
        assertEquals("3800,5", model.uiState.value.costSheet?.cost)

        model.onAction(ProductDetailAction.DismissCostSheet)
        advanceUntilIdle()
        // Un cambio del producto no la vuelve a abrir.
        operations.product.value =
            operations.product.value?.let { it.copy(movements = emptyList()) }
        advanceUntilIdle()
        assertNull(model.uiState.value.costSheet)
    }

    @Test
    fun `arriving from the review queue opens the price sheet once`() = runTest(dispatcher) {
        val model = ProductDetailViewModel(1L, operations, NUMBERS, openPriceSheet = true)
        backgroundScope.launch(dispatcher) { model.uiState.collect {} }
        advanceUntilIdle()
        assertEquals("5000", model.uiState.value.priceSheet?.price)

        model.onAction(ProductDetailAction.DismissPriceSheet)
        advanceUntilIdle()
        operations.product.value =
            operations.product.value?.let { it.copy(movements = emptyList()) }
        advanceUntilIdle()
        assertNull(model.uiState.value.priceSheet)
    }

    @Test
    fun `the sheet opens with the current cost and saves what is written`() = runTest(dispatcher) {
        val model = model()

        model.onAction(ProductDetailAction.OpenCostSheet)
        advanceUntilIdle()
        assertEquals("3800,5", model.uiState.value.costSheet?.cost)

        model.onAction(ProductDetailAction.CostText("4050"))
        advanceUntilIdle()
        model.onAction(ProductDetailAction.CostNote("Factura"))
        advanceUntilIdle()
        model.onAction(ProductDetailAction.SaveCost)
        advanceUntilIdle()

        assertEquals(listOf(Money(405_000L) to "Factura"), operations.corrections)
        assertNull(model.uiState.value.costSheet)
        assertEquals(ProductDetailMessage.CostCorrected, model.uiState.value.message)
    }

    @Test
    fun `the price sheet opens with the price and saves the new one`() = runTest(dispatcher) {
        val model = model()

        model.onAction(ProductDetailAction.OpenPriceSheet)
        advanceUntilIdle()
        assertEquals("5000", model.uiState.value.priceSheet?.price)

        model.onAction(ProductDetailAction.PriceText("5.200,50"))
        advanceUntilIdle()
        model.onAction(ProductDetailAction.SavePrice)
        advanceUntilIdle()

        assertEquals(listOf(Money(520_050L)), operations.prices)
        assertNull(model.uiState.value.priceSheet)
        assertEquals(ProductDetailMessage.PriceChanged, model.uiState.value.message)
    }

    @Test
    fun `a price that cannot be read or saved keeps the sheet open`() = runTest(dispatcher) {
        val model = model()
        model.onAction(ProductDetailAction.OpenPriceSheet)
        advanceUntilIdle()

        model.onAction(ProductDetailAction.PriceText("abc"))
        advanceUntilIdle()
        model.onAction(ProductDetailAction.SavePrice)
        advanceUntilIdle()
        assertEquals(FieldProblem.NotANumber, model.uiState.value.priceSheet?.priceProblem)
        assertTrue(operations.prices.isEmpty())

        model.onAction(ProductDetailAction.PriceText("10"))
        advanceUntilIdle()
        operations.priceResult = ChangePriceResult.StorageFailed(StorageFailure.DISK_FULL)
        model.onAction(ProductDetailAction.SavePrice)
        advanceUntilIdle()
        assertEquals(SaveFailure(outOfSpace = true), model.uiState.value.priceSheet?.saveFailed)
        assertEquals("10", model.uiState.value.priceSheet?.price)

        operations.priceResult = ChangePriceResult.Unchanged
        model.onAction(ProductDetailAction.SavePrice)
        advanceUntilIdle()
        assertNull(model.uiState.value.priceSheet)
        assertNull(model.uiState.value.message)
    }

    @Test
    fun `a cost that is not a number stays written with its problem`() = runTest(dispatcher) {
        val model = model()
        model.onAction(ProductDetailAction.OpenCostSheet)
        advanceUntilIdle()

        for ((text, problem) in listOf(
            "abc" to FieldProblem.NotANumber,
            "1,234" to FieldProblem.TooManyDecimals(2),
            "99999999999999" to FieldProblem.TooHigh,
        )) {
            model.onAction(ProductDetailAction.CostText(text))
            advanceUntilIdle()
            model.onAction(ProductDetailAction.SaveCost)
            advanceUntilIdle()
            assertEquals(text, problem, model.uiState.value.costSheet?.costProblem)
            assertEquals(text, model.uiState.value.costSheet?.cost)
        }
        assertTrue(operations.corrections.isEmpty())
    }

    @Test
    fun `refusals keep the sheet open and say why`() = runTest(dispatcher) {
        val model = model()
        model.onAction(ProductDetailAction.OpenCostSheet)
        advanceUntilIdle()

        operations.correctResult = CorrectCostResult.NoteTooLong(200)
        model.onAction(ProductDetailAction.SaveCost)
        advanceUntilIdle()
        assertTrue(model.uiState.value.costSheet?.noteTooLong == true)

        operations.correctResult = CorrectCostResult.StorageFailed(StorageFailure.DISK_FULL)
        model.onAction(ProductDetailAction.SaveCost)
        advanceUntilIdle()
        assertEquals(SaveFailure(outOfSpace = true), model.uiState.value.costSheet?.saveFailed)

        operations.correctResult = CorrectCostResult.Unchanged
        model.onAction(ProductDetailAction.SaveCost)
        advanceUntilIdle()
        assertNull(model.uiState.value.costSheet)
        assertNull(model.uiState.value.message)
    }

    @Test
    fun `deleting asks first and closes the detail`() = runTest(dispatcher) {
        val model = model()

        model.onAction(ProductDetailAction.AskDelete)
        advanceUntilIdle()
        assertEquals(ProductDetailDialog.Delete("Arroz 1 kg"), model.uiState.value.dialog)
        model.onAction(ProductDetailAction.ConfirmDialog)
        advanceUntilIdle()

        assertTrue(model.uiState.value.closed)
        assertEquals(ProductDetailMessage.Deleted, model.uiState.value.message)
    }

    @Test
    fun `archive and reactivate say how it went`() = runTest(dispatcher) {
        val model = model()

        model.onAction(ProductDetailAction.AskArchive)
        advanceUntilIdle()
        model.onAction(ProductDetailAction.ConfirmDialog)
        advanceUntilIdle()
        assertEquals(ProductDetailMessage.Archived, model.uiState.value.message)

        operations.reactivateResult = ReactivateProductResult.NameTaken("Arroz")
        model.onAction(ProductDetailAction.Reactivate)
        advanceUntilIdle()
        assertEquals(ProductDetailMessage.NameTaken, model.uiState.value.message)
    }

    @Test
    fun `voiding an adjustment asks first and says so`() = runTest(dispatcher) {
        val model = model()

        model.onAction(ProductDetailAction.AskVoid(4L))
        advanceUntilIdle()
        assertEquals(
            ProductDetailDialog.VoidAdjustment(4L, "Arroz 1 kg"),
            model.uiState.value.dialog,
        )
        model.onAction(ProductDetailAction.ConfirmDialog)
        advanceUntilIdle()

        assertEquals(listOf(4L), operations.voided)
        assertEquals(ProductDetailMessage.AdjustmentVoided, model.uiState.value.message)
        assertNull(ProductDetailViewModel.voidMessage(VoidAdjustmentResult.AlreadyVoided))
        assertEquals(
            ProductDetailMessage.Failed(false),
            ProductDetailViewModel.voidMessage(VoidAdjustmentResult.LedgerDamaged),
        )
    }

    @Test
    fun `each lifecycle result has its message`() {
        val disk = StorageFailure.DISK_FULL
        assertNull(ProductDetailViewModel.archiveMessage(ArchiveProductResult.AlreadyArchived))
        assertEquals(
            ProductDetailMessage.Failed(true),
            ProductDetailViewModel.archiveMessage(ArchiveProductResult.StorageFailed(disk)),
        )
        assertEquals(
            ProductDetailMessage.HasHistory,
            ProductDetailViewModel.deleteMessage(DeleteProductResult.HasHistory),
        )
        assertEquals(
            ProductDetailMessage.NameTaken,
            ProductDetailViewModel.reactivateMessage(ReactivateProductResult.BarcodeTaken("X")),
        )
        assertEquals(
            ProductDetailMessage.Failed(false),
            ProductDetailViewModel.reactivateMessage(ReactivateProductResult.UnknownTimeZone),
        )
        assertEquals(
            ProductDetailMessage.ParentArchived,
            ProductDetailViewModel.reactivateMessage(ReactivateProductResult.ParentArchived),
        )
    }

    @Test
    fun `the price sheet suggests a price for the target margin and uses it`() =
        runTest(dispatcher) {
            val model = model()

            model.onAction(ProductDetailAction.OpenPriceSheet)
            advanceUntilIdle()
            val sheet = model.uiState.value.priceSheet
            assertEquals("30", sheet?.suggestion?.targetText)
            // 3800,50 con 30 % pide 5429,29, que deja 30,00 % contra el costo sin redondear.
            assertEquals(
                SuggestionOutcome.Suggested(Money(542_929L), Margin.Defined(BasisPoints(3_000L))),
                sheet?.suggestion?.outcome,
            )
            assertTrue(sheet?.canFixCost == true)

            model.onAction(ProductDetailAction.SuggestionTarget("40"))
            advanceUntilIdle()
            assertEquals(Money(633_417L), model.uiState.value.priceSheet?.suggestion?.price)

            model.onAction(ProductDetailAction.UseSuggestedPrice)
            advanceUntilIdle()
            assertEquals("6334,17", model.uiState.value.priceSheet?.price)

            model.onAction(ProductDetailAction.SavePrice)
            advanceUntilIdle()
            assertEquals(listOf(Money(633_417L)), operations.prices)
        }

    @Test
    fun `a target out of range suggests nothing and the price stays`() = runTest(dispatcher) {
        val model = model()
        model.onAction(ProductDetailAction.OpenPriceSheet)
        advanceUntilIdle()

        model.onAction(ProductDetailAction.SuggestionTarget("100"))
        advanceUntilIdle()
        assertEquals(
            SuggestionOutcome.InvalidTarget(TargetMarginProblem.OUT_OF_RANGE),
            model.uiState.value.priceSheet?.suggestion?.outcome,
        )

        model.onAction(ProductDetailAction.UseSuggestedPrice)
        advanceUntilIdle()
        assertEquals("5000", model.uiState.value.priceSheet?.price)
    }

    @Test
    fun `a new business target is where the next suggestion starts`() = runTest(dispatcher) {
        val model = model()
        operations.context.value =
            operations.context.value.copy(defaultTargetMargin = BasisPoints(4_000L))
        advanceUntilIdle()

        model.onAction(ProductDetailAction.OpenPriceSheet)
        advanceUntilIdle()

        assertEquals("40", model.uiState.value.priceSheet?.suggestion?.targetText)
    }

    @Test
    fun `without a cost the sheet asks to correct it first`() = runTest(dispatcher) {
        operations.product.value = operations.product.value?.let { overview ->
            overview.copy(
                product = overview.product.copy(averageCost = Money.ZERO, stockValue = Money.ZERO),
            )
        }
        val model = model()
        model.onAction(ProductDetailAction.OpenPriceSheet)
        advanceUntilIdle()
        assertEquals(
            SuggestionOutcome.NoCost,
            model.uiState.value.priceSheet?.suggestion?.outcome,
        )

        model.onAction(ProductDetailAction.FixCostForSuggestion)
        advanceUntilIdle()

        assertNull(model.uiState.value.priceSheet)
        assertEquals("", model.uiState.value.costSheet?.cost)
    }

    @Test
    fun `a product made of components has no suggestion`() = runTest(dispatcher) {
        operations.product.value = operations.product.value?.let { overview ->
            val details = overview.product.details.copy(stockMode = ProductStockMode.COMPONENTS)
            overview.copy(product = overview.product.copy(details = details))
        }
        val model = model()
        model.onAction(ProductDetailAction.OpenPriceSheet)
        advanceUntilIdle()

        val sheet = model.uiState.value.priceSheet
        assertEquals("5000", sheet?.price)
        assertNull(sheet?.suggestion)
    }

    @Test
    fun `a product with a recipe offers to produce, and without it only to buy`() =
        runTest(dispatcher) {
            val model = model()
            assertFalse(model.uiState.value.producible)

            operations.producible.value = true
            advanceUntilIdle()
            assertTrue(model.uiState.value.producible)
        }

    @Test
    fun `a missing product shows that it could not be opened`() = runTest(dispatcher) {
        operations.product.value = null
        val model = model()

        assertEquals(ProductDetailContent.Missing, model.uiState.value.content)
        model.onAction(ProductDetailAction.OpenCostSheet)
        advanceUntilIdle()
        assertNull(model.uiState.value.costSheet)
    }

    private companion object {
        val NUMBERS =
            ProductNumberFormat(
                moneySymbols = NumberSymbols(',', '-', DigitGrouping('.', 3, 3)),
                currencyDecimals = 2,
                quantitySymbols = NumberSymbols(',', '-', DigitGrouping('.', 3, 3)),
            )
    }
}

/** Respuestas fijas: un arroz con costo 3800,50, y lo que se pidió corregir. */
private class FakeDetailOperations : ProductDetailOperations {
    val product = MutableStateFlow<ProductOverview?>(overview())
    var correctResult: CorrectCostResult = CorrectCostResult.Corrected(1L)
    var reactivateResult: ReactivateProductResult = ReactivateProductResult.Reactivated
    val corrections = mutableListOf<Pair<Money, String?>>()

    override fun overview(productId: Long): Flow<ProductOverview?> = product

    /** Si el arroz se hace con una receta: empieza sin ella. */
    val producible = MutableStateFlow(false)

    override fun producible(productId: Long): Flow<Boolean> = producible

    /** Un negocio sin impuesto, con margen objetivo de 30 % y sin paso de redondeo. */
    val context = MutableStateFlow(
        PricingContext(
            defaultTargetMargin = BasisPoints(3_000L),
            categoryTargets = emptyMap(),
            taxMode = TaxMode.NONE,
            defaultTaxRateId = null,
            taxRates = emptyMap(),
            roundingStep = Money.ZERO,
        ),
    )

    override fun pricing(): Flow<PricingContext> = context

    override suspend fun correctCost(
        productId: Long,
        cost: Money,
        note: String?,
    ): CorrectCostResult {
        corrections += cost to note
        return correctResult
    }

    var priceResult: ChangePriceResult = ChangePriceResult.Changed(1L)
    val prices = mutableListOf<Money>()

    override suspend fun changePrice(productId: Long, price: Money): ChangePriceResult {
        prices += price
        return priceResult
    }

    override suspend fun archive(productId: Long): ArchiveProductResult =
        ArchiveProductResult.Archived

    override suspend fun reactivate(productId: Long): ReactivateProductResult = reactivateResult

    override suspend fun delete(productId: Long): DeleteProductResult = DeleteProductResult.Deleted

    val voided = mutableListOf<Long>()

    override suspend fun voidAdjustment(movementId: Long): VoidAdjustmentResult {
        voided += movementId
        return VoidAdjustmentResult.Voided(99L)
    }

    private companion object {
        fun overview(): ProductOverview {
            val at = Instant.fromEpochMilliseconds(1_700_000_000_000L)
            val details =
                ProductDetails(
                    name = "Arroz 1 kg",
                    normalizedName = "arroz 1 kg",
                    categoryId = null,
                    defaultSupplierId = null,
                    baseUnitId = 1L,
                    stockMode = ProductStockMode.TRACKED,
                    tracksLots = false,
                    fixedUnitCost = Money.ZERO,
                    minStock = QuantityMilli.ZERO,
                    barcode = null,
                    parentProductId = null,
                    variantAxis1 = null,
                    variantAxis2 = null,
                    variantValue1 = null,
                    variantValue2 = null,
                    photoMediaId = null,
                    taxRateId = null,
                    targetMargin = null,
                    referenceCost = null,
                    referencePrice = null,
                    expiryNoticeDays = null,
                )
            val product =
                Product(
                    id = 1L,
                    details = details,
                    salePrice = Money(500_000L),
                    initialCost = Money.ZERO,
                    averageCost = Money(380_050L),
                    stockValue = Money(1_140_150L),
                    onHand = QuantityMilli(3_000L),
                    replacementCost = Money.ZERO,
                    replacementCostAt = null,
                    priceReviewSince = null,
                    archivedAt = null,
                    createdAt = at,
                    updatedAt = at,
                )
            return ProductOverview(product, null, emptyList(), emptyList())
        }
    }
}
