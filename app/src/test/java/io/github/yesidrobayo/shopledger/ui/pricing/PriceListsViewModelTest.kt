package io.github.yesidrobayo.shopledger.ui.pricing

import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.Margin
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.catalog.PricingContext
import io.github.yesidrobayo.shopledger.domain.model.catalog.Product
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductDetails
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductStockMode
import io.github.yesidrobayo.shopledger.domain.model.pricing.PriceList
import io.github.yesidrobayo.shopledger.domain.model.pricing.PriceListDraft
import io.github.yesidrobayo.shopledger.domain.model.pricing.PriceListInputError
import io.github.yesidrobayo.shopledger.domain.model.pricing.PriceListKind
import io.github.yesidrobayo.shopledger.domain.model.pricing.PriceListPricing
import io.github.yesidrobayo.shopledger.domain.model.pricing.PriceListsOverview
import io.github.yesidrobayo.shopledger.domain.model.region.NumberSymbols
import io.github.yesidrobayo.shopledger.domain.model.settings.TaxMode
import io.github.yesidrobayo.shopledger.domain.port.StorageFailure
import io.github.yesidrobayo.shopledger.domain.rule.pricing.ListPriceOrigin
import io.github.yesidrobayo.shopledger.domain.usecase.pricing.PriceListLifecycleResult
import io.github.yesidrobayo.shopledger.domain.usecase.pricing.SavePriceListResult
import io.github.yesidrobayo.shopledger.domain.usecase.pricing.SetListPriceResult
import kotlin.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
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

/** La pantalla de listas de precios y sus hojas, con respuestas fijas del dominio. Datos inventados. */
@OptIn(ExperimentalCoroutinesApi::class)
class PriceListsViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val operations = FakeOperations()

    @Before
    fun useTestMain() = Dispatchers.setMain(dispatcher)

    @After
    fun resetTestMain() = Dispatchers.resetMain()

    private fun model() = PriceListsViewModel(
        operations = operations,
        names = { it.name ?: "General" },
        numbers = PriceListNumberFormat(SYMBOLS, SYMBOLS, currencyDecimals = 0),
        formatAdjustment = { adjustmentInputText(it, SYMBOLS) },
        formatPrice = { it.minor.toString() },
        computation = dispatcher,
    )

    @Test
    fun `the lists come with their kind, own prices and base name`() = runTest(dispatcher) {
        val model = model()
        assertTrue(model.uiState.value.loading)

        advanceUntilIdle()

        val state = model.uiState.value
        assertFalse(state.loading)
        assertEquals(listOf("General", "Mayoristas", "Especial"), state.active.map { it.name })
        assertEquals(1, state.active[1].ownPrices)
        assertEquals("General", state.active[2].baseName)
        assertEquals(listOf("Feria"), state.archived.map { it.name })
        assertFalse(state.onlyDefault)
    }

    @Test
    fun `with only the initial list the screen invites to create one`() = runTest(dispatcher) {
        operations.overview.value = PriceListsOverview(listOf(DEFAULT), emptyList(), emptySet())
        val model = model()
        advanceUntilIdle()

        assertTrue(model.uiState.value.onlyDefault)
    }

    @Test
    fun `the detail resolves each price and says where it comes from`() = runTest(dispatcher) {
        val model = model()
        advanceUntilIdle()

        model.onAction(PriceListsAction.Open(SPECIAL.id))
        assertTrue(model.uiState.value.rowsLoading)
        advanceUntilIdle()

        val rows = model.uiState.value.rows
        // 10000 con −15 % son 8500; el costo de 6800 deja 20 % de margen.
        assertEquals(Money(8_500L), rows.single { it.productId == RICE.id }.price)
        assertEquals(ListPriceOrigin.CALCULATED, rows.first().origin)
        assertEquals(Margin.Defined(BasisPoints(2_000L)), rows.first().margin)

        model.onAction(PriceListsAction.Open(WHOLESALE.id))
        advanceUntilIdle()
        val wholesale = model.uiState.value.rows.associateBy { it.productId }
        assertEquals(ListPriceOrigin.OWN, wholesale.getValue(RICE.id).origin)
        assertEquals(Money(9_000L), wholesale.getValue(RICE.id).price)
        assertEquals(ListPriceOrigin.SALE_PRICE, wholesale.getValue(OIL.id).origin)
    }

    @Test
    fun `searching filters the products without accents nor case`() = runTest(dispatcher) {
        val model = model()
        advanceUntilIdle()
        model.onAction(PriceListsAction.Open(WHOLESALE.id))
        advanceUntilIdle()

        model.onAction(PriceListsAction.Search("ACEITÉ"))
        advanceUntilIdle()

        assertEquals(listOf(OIL.id), model.uiState.value.rows.map { it.productId })
        model.onAction(PriceListsAction.CloseDetail)
        assertNull(model.uiState.value.selectedId)
    }

    @Test
    fun `a new relative list is saved with its base and signed adjustment`() = runTest(dispatcher) {
        val model = model()
        advanceUntilIdle()

        model.onAction(PriceListsAction.NewList)
        model.listEditor.onAction(PriceListEditorAction.Name("Especial 2"))
        model.listEditor.onAction(PriceListEditorAction.Kind(PriceListKind.RELATIVE))
        model.listEditor.onAction(PriceListEditorAction.Base(DEFAULT.id))
        model.listEditor.onAction(PriceListEditorAction.Adjustment("-12,5"))
        model.listEditor.onAction(PriceListEditorAction.Save)
        advanceUntilIdle()

        assertEquals(
            listOf(
                null to
                    PriceListDraft(
                        "Especial 2",
                        PriceListKind.RELATIVE,
                        DEFAULT.id,
                        BasisPoints(-1_250L),
                    ),
            ),
            operations.saved,
        )
        assertNull(model.listEditor.editor.value)
        assertEquals(PriceListsMessage.ListSaved, model.uiState.value.message)
        assertEquals(40L, model.uiState.value.selectedId)
    }

    @Test
    fun `editing opens with the stored adjustment, and a cycle stays in its field`() =
        runTest(dispatcher) {
            operations.saveResult = SavePriceListResult.CreatesCycle
            val model = model()
            advanceUntilIdle()
            model.onAction(PriceListsAction.Open(SPECIAL.id))

            model.onAction(PriceListsAction.EditSelected)
            val editor = requireNotNull(model.listEditor.editor.value)
            assertEquals("-15", editor.adjustmentText)
            assertFalse(editor.bases.any { it.list.id == SPECIAL.id })
            model.listEditor.onAction(PriceListEditorAction.Save)
            advanceUntilIdle()

            assertEquals(ListBaseProblem.CYCLE, model.listEditor.editor.value?.baseProblem)
        }

    @Test
    fun `every refusal of a save lands in its field and keeps what was written`() =
        runTest(dispatcher) {
            val model = model()
            advanceUntilIdle()
            val cases = listOf(
                SavePriceListResult.Invalid(
                    setOf(
                        PriceListInputError.NAME_EMPTY,
                        PriceListInputError.BASE_MISSING,
                        PriceListInputError.ADJUSTMENT_MISSING,
                    ),
                ),
                SavePriceListResult.Invalid(
                    setOf(
                        PriceListInputError.NAME_TOO_LONG,
                        PriceListInputError.ADJUSTMENT_OUT_OF_RANGE,
                    ),
                ),
                SavePriceListResult.DuplicateName,
                SavePriceListResult.BaseUnavailable,
                SavePriceListResult.HasOwnPrices,
                SavePriceListResult.NotFound,
                SavePriceListResult.StorageFailed(StorageFailure.DISK_FULL),
            )
            val seen = cases.map { result ->
                operations.saveResult = result
                model.onAction(PriceListsAction.NewList)
                model.listEditor.onAction(PriceListEditorAction.Name("x"))
                model.listEditor.onAction(PriceListEditorAction.Save)
                advanceUntilIdle()
                requireNotNull(model.listEditor.editor.value).also {
                    assertEquals("x", it.name)
                    assertFalse(it.saving)
                }
            }
            assertEquals(ListNameProblem.EMPTY, seen[0].nameProblem)
            assertEquals(ListBaseProblem.MISSING, seen[0].baseProblem)
            assertEquals(ListAdjustmentProblem.MISSING, seen[0].adjustmentProblem)
            assertEquals(ListNameProblem.TOO_LONG, seen[1].nameProblem)
            assertEquals(ListAdjustmentProblem.OUT_OF_RANGE, seen[1].adjustmentProblem)
            assertEquals(ListNameProblem.DUPLICATE, seen[2].nameProblem)
            assertEquals(ListBaseProblem.UNAVAILABLE, seen[3].baseProblem)
            assertTrue(seen[4].hasOwnPrices)
            assertEquals(ListSaveFailure(outOfSpace = false), seen[5].saveFailed)
            assertEquals(ListSaveFailure(outOfSpace = true), seen[6].saveFailed)
        }

    @Test
    fun `an adjustment that is not a percent is refused before saving`() = runTest(dispatcher) {
        val model = model()
        advanceUntilIdle()
        model.onAction(PriceListsAction.NewList)
        model.listEditor.onAction(PriceListEditorAction.Kind(PriceListKind.RELATIVE))
        model.listEditor.onAction(PriceListEditorAction.Adjustment("1,234"))

        model.listEditor.onAction(PriceListEditorAction.Save)
        advanceUntilIdle()

        assertEquals(
            ListAdjustmentProblem.OUT_OF_RANGE,
            model.listEditor.editor.value?.adjustmentProblem,
        )
        assertEquals(emptyList<Any>(), operations.saved)
    }

    @Test
    fun `the initial list is neither edited nor archived`() = runTest(dispatcher) {
        val model = model()
        advanceUntilIdle()
        model.onAction(PriceListsAction.Open(DEFAULT.id))

        model.onAction(PriceListsAction.EditSelected)
        model.onAction(PriceListsAction.AskArchiveSelected)

        assertNull(model.listEditor.editor.value)
        assertNull(model.uiState.value.dialog)
    }

    @Test
    fun `archiving asks first with the customers that go back, or not at all without them`() =
        runTest(dispatcher) {
            val model = model()
            advanceUntilIdle()
            model.onAction(PriceListsAction.Open(WHOLESALE.id))

            model.onAction(PriceListsAction.AskArchiveSelected)
            assertEquals(
                PriceListsDialog.Archive(WHOLESALE.id, "Mayoristas", 3),
                model.uiState.value.dialog,
            )
            model.onAction(PriceListsAction.ConfirmDialog)
            advanceUntilIdle()
            assertEquals(listOf("archive" to WHOLESALE.id), operations.lifecycle)

            model.onAction(PriceListsAction.Open(SPECIAL.id))
            model.onAction(PriceListsAction.AskArchiveSelected)
            advanceUntilIdle()
            assertNull(model.uiState.value.dialog)
            assertEquals("archive" to SPECIAL.id, operations.lifecycle.last())
        }

    @Test
    fun `reactivating a list whose name was taken says so`() = runTest(dispatcher) {
        operations.lifecycleResult = PriceListLifecycleResult.NameTaken
        val model = model()
        advanceUntilIdle()

        model.onAction(PriceListsAction.Reactivate(OLD.id))
        advanceUntilIdle()

        assertEquals(PriceListsMessage.NameTaken, model.uiState.value.message)
        operations.lifecycleResult =
            PriceListLifecycleResult.StorageFailed(StorageFailure.DISK_FULL)
        model.onAction(PriceListsAction.Reactivate(OLD.id))
        advanceUntilIdle()
        assertEquals(PriceListsMessage.Failed(outOfSpace = true), model.uiState.value.message)
    }

    @Test
    fun `a price is set and removed from its sheet, and zero stays in the field`() =
        runTest(dispatcher) {
            val model = model()
            advanceUntilIdle()
            model.onAction(PriceListsAction.Open(WHOLESALE.id))
            advanceUntilIdle()

            model.onAction(PriceListsAction.EditPrice(RICE.id))
            val editor = requireNotNull(model.priceEditor.editor.value)
            assertEquals("9000", editor.priceText)
            assertTrue(editor.hasOwnPrice)
            model.priceEditor.onAction(ListPriceEditorAction.Price("9500"))
            // 9500 sobre un costo de 6800 deja 28,42 %.
            assertEquals(
                Margin.Defined(BasisPoints(2_842L)),
                model.priceEditor.editor.value?.let {
                    it.pricing.marginOf(requireNotNull(it.typedPrice))
                },
            )
            model.priceEditor.onAction(ListPriceEditorAction.Save)
            advanceUntilIdle()
            assertEquals(Triple(WHOLESALE.id, RICE.id, Money(9_500L)), operations.prices.last())
            assertEquals(PriceListsMessage.PriceSaved, model.uiState.value.message)

            model.onAction(PriceListsAction.EditPrice(RICE.id))
            model.priceEditor.onAction(ListPriceEditorAction.RemoveOwn)
            advanceUntilIdle()
            assertEquals(Triple(WHOLESALE.id, RICE.id, null), operations.prices.last())

            operations.priceResult = SetListPriceResult.ZeroOwnPrice
            model.onAction(PriceListsAction.EditPrice(OIL.id))
            assertFalse(requireNotNull(model.priceEditor.editor.value).hasOwnPrice)
            model.priceEditor.onAction(ListPriceEditorAction.Price("0"))
            model.priceEditor.onAction(ListPriceEditorAction.Save)
            advanceUntilIdle()
            assertEquals(ListPriceProblem.ZERO, model.priceEditor.editor.value?.problem)

            model.priceEditor.onAction(ListPriceEditorAction.Price("abc"))
            model.priceEditor.onAction(ListPriceEditorAction.Save)
            assertEquals(ListPriceProblem.INVALID, model.priceEditor.editor.value?.problem)
            model.priceEditor.onAction(ListPriceEditorAction.Dismiss)
            assertNull(model.priceEditor.editor.value)
        }

    @Test
    fun `a relative list has no price sheet`() = runTest(dispatcher) {
        val model = model()
        advanceUntilIdle()
        model.onAction(PriceListsAction.Open(SPECIAL.id))
        advanceUntilIdle()

        model.onAction(PriceListsAction.EditPrice(RICE.id))

        assertNull(model.priceEditor.editor.value)
    }

    @Test
    fun `a failed read shows the error and retry reads again`() = runTest(dispatcher) {
        operations.failingReads = 1
        val model = model()
        advanceUntilIdle()
        assertTrue(model.uiState.value.readFailed)

        model.onAction(PriceListsAction.Retry)
        advanceUntilIdle()

        assertFalse(model.uiState.value.readFailed)
        assertEquals(3, model.uiState.value.active.size)
    }

    private class FakeOperations : PriceListsOperations {
        val overview = MutableStateFlow(
            PriceListsOverview(
                listOf(DEFAULT, WHOLESALE, SPECIAL),
                listOf(OLD),
                setOf(WHOLESALE.id),
            ),
        )
        var saveResult: SavePriceListResult? = null
        var lifecycleResult: PriceListLifecycleResult = PriceListLifecycleResult.Done
        var priceResult: SetListPriceResult = SetListPriceResult.Changed(1L)
        val saved = mutableListOf<Pair<Long?, PriceListDraft>>()
        val lifecycle = mutableListOf<Pair<String, Long>>()
        val prices = mutableListOf<Triple<Long, Long, Money?>>()
        var failingReads = 0

        override fun lists(): Flow<PriceListsOverview> = if (failingReads > 0) {
            failingReads--
            flow { throw IllegalStateException("lectura fallida de prueba") }
        } else {
            overview
        }

        override fun ownPrices(): Flow<Map<Long, Map<Long, Money>>> =
            MutableStateFlow(mapOf(WHOLESALE.id to mapOf(RICE.id to Money(9_000L))))

        override fun customerCounts(): Flow<Map<Long, Int>> =
            MutableStateFlow(mapOf(WHOLESALE.id to 3))

        override fun products(): Flow<List<Product>> = MutableStateFlow(listOf(RICE, OIL))

        override fun pricing(): Flow<PricingContext> = MutableStateFlow(
            PricingContext(
                BasisPoints(3_000L),
                emptyMap(),
                TaxMode.NONE,
                null,
                emptyMap(),
                Money.ZERO,
            ),
        )

        override suspend fun saveList(
            editingId: Long?,
            draft: PriceListDraft,
        ): SavePriceListResult {
            val result = saveResult ?: SavePriceListResult.Saved(editingId ?: 40L)
            if (result is SavePriceListResult.Saved) saved += editingId to draft
            return result
        }

        override suspend fun archive(id: Long): PriceListLifecycleResult {
            lifecycle += "archive" to id
            return lifecycleResult
        }

        override suspend fun reactivate(id: Long): PriceListLifecycleResult {
            lifecycle += "reactivate" to id
            return lifecycleResult
        }

        override suspend fun setPrice(
            listId: Long,
            productId: Long,
            price: Money?,
        ): SetListPriceResult {
            prices += Triple(listId, productId, price)
            return priceResult
        }
    }

    private companion object {
        val SYMBOLS = NumberSymbols(decimalSeparator = ',', minusSign = '-', grouping = null)
        val DEFAULT = PriceList(1L, "DEFAULT", null, PriceListPricing.Fixed, null)
        val WHOLESALE = PriceList(2L, null, "Mayoristas", PriceListPricing.Fixed, null)
        val SPECIAL = PriceList(
            3L,
            null,
            "Especial",
            PriceListPricing.Relative(DEFAULT.id, BasisPoints(-1_500L)),
            null,
        )
        val OLD =
            PriceList(4L, null, "Feria", PriceListPricing.Fixed, Instant.fromEpochMilliseconds(5L))

        fun product(id: Long, name: String, normalized: String) = Product(
            id = id,
            details = ProductDetails(
                name, normalized, null, null, 1L, ProductStockMode.UNTRACKED, false,
                Money(6_800L), QuantityMilli.ZERO, null, null, null, null, null, null, null, null,
                null, null, null, null,
            ),
            salePrice = Money(10_000L),
            initialCost = Money.ZERO,
            averageCost = Money.ZERO,
            stockValue = Money.ZERO,
            onHand = QuantityMilli.ZERO,
            replacementCost = Money.ZERO,
            replacementCostAt = null,
            priceReviewSince = null,
            archivedAt = null,
            createdAt = Instant.fromEpochMilliseconds(0L),
            updatedAt = Instant.fromEpochMilliseconds(0L),
        )

        val RICE = product(7L, "Arroz", "arroz")
        val OIL = product(8L, "Aceite", "aceite")
    }
}
