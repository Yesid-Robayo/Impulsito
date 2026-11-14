package io.github.yesidrobayo.shopledger.ui.catalog

import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.catalog.Category
import io.github.yesidrobayo.shopledger.domain.model.catalog.CategoryDetails
import io.github.yesidrobayo.shopledger.domain.model.catalog.Product
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductDetails
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductStockMode
import io.github.yesidrobayo.shopledger.domain.model.document.ReceiptHeader
import io.github.yesidrobayo.shopledger.domain.model.pricing.PriceList
import io.github.yesidrobayo.shopledger.domain.model.pricing.PriceListPricing
import io.github.yesidrobayo.shopledger.domain.rule.document.CatalogFilter
import kotlin.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.LocalDate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * El catálogo sin base ni disco: lo elegido rehace el documento, la lista de precios cambia los
 * precios, sin productos con precio o sin moneda tiene su estado, y crear el archivo es de a uno.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CatalogViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val sources = MutableStateFlow(sources())
    private var failRead = false

    @Before
    fun useTestMain() = Dispatchers.setMain(dispatcher)

    @After
    fun resetTestMain() = Dispatchers.resetMain()

    private fun TestScope.model(): CatalogViewModel {
        val model = CatalogViewModel(
            observeSources = { if (failRead) flow { error("Falla inventada") } else sources },
            computation = dispatcher,
        )
        backgroundScope.launch(dispatcher) { model.uiState.collect {} }
        advanceUntilIdle()
        return model
    }

    private fun CatalogViewModel.ready() = uiState.value as CatalogUiState.Ready

    @Test
    fun `the catalog follows what is chosen`() = runTest(dispatcher) {
        val model = model()
        assertEquals(listOf("Agua", "Arroz"), names(model))
        assertEquals(DEFAULT, model.ready().listId)
        assertTrue(model.ready().hasPhotos)

        model.onAction(CatalogAction.Filter(CatalogFilter.InCategory(DRINKS)))
        advanceUntilIdle()
        assertEquals(listOf("Agua"), names(model))

        model.onAction(CatalogAction.Filter(CatalogFilter.All))
        model.onAction(CatalogAction.IncludeOutOfStock(true))
        model.onAction(CatalogAction.GroupByCategory(true))
        model.onAction(CatalogAction.ShowPhotos(true))
        model.onAction(CatalogAction.TitleChanged("Precios de hoy"))
        advanceUntilIdle()
        assertEquals("Precios de hoy", model.ready().document.title)
        assertEquals(3, model.ready().document.productCount)
        assertEquals(2, model.ready().document.sections.size)

        model.onAction(CatalogAction.PriceListChosen(WHOLESALE))
        advanceUntilIdle()
        assertEquals(
            Money(900L),
            model.ready().document.sections.flatMap { it.items }
                .single { it.name == "Agua" }.price,
        )
    }

    @Test
    fun `an unknown list falls back to the initial one`() = runTest(dispatcher) {
        val model = model()

        model.onAction(CatalogAction.PriceListChosen(99L))
        advanceUntilIdle()

        assertEquals(DEFAULT, model.ready().listId)
    }

    @Test
    fun `without priced products, without currency or a failed read, its own state`() =
        runTest(dispatcher) {
            sources.value = sources().copy(products = listOf(product(1L, "Nada", 0L, 1_000L)))
            assertEquals(CatalogUiState.NoPricedProducts, model().uiState.value)

            sources.value = sources().copy(lists = emptyList())
            assertEquals(CatalogUiState.NoPricedProducts, model().uiState.value)

            sources.value = sources().copy(currencyConfirmed = false)
            assertEquals(CatalogUiState.CurrencyUnconfirmed, model().uiState.value)

            failRead = true
            val failed = model()
            assertEquals(CatalogUiState.ReadFailed, failed.uiState.value)
            failRead = false
            sources.value = sources()
            failed.onAction(CatalogAction.Retry)
            advanceUntilIdle()
            assertTrue(failed.uiState.value is CatalogUiState.Ready)
        }

    @Test
    fun `creating the file is one at a time, and a failure can be retried`() = runTest(dispatcher) {
        val model = model()

        model.onAction(CatalogAction.Create(CatalogOutput.SHARE))
        model.onAction(CatalogAction.Create(CatalogOutput.SAVE))
        advanceUntilIdle()
        assertEquals(CatalogOutput.SHARE, model.ready().sharing)

        model.onAction(CatalogAction.Created(ok = false))
        advanceUntilIdle()
        assertTrue(model.ready().shareFailed)
        assertEquals(null, model.ready().sharing)

        model.onAction(CatalogAction.Create(CatalogOutput.SAVE))
        model.onAction(CatalogAction.Created(ok = true))
        model.onAction(CatalogAction.Back)
        advanceUntilIdle()
        assertFalse(model.ready().shareFailed)
    }

    private fun names(model: CatalogViewModel) =
        model.ready().document.sections.flatMap { it.items }.map { it.name }

    private fun sources() = CatalogSources(
        products = listOf(
            product(1L, "Arroz", 4_200L, 5_000L, GROCERY),
            product(2L, "Agua", 1_000L, 2_000L, DRINKS, photo = 7L),
            product(3L, "Aceite", 12_000L, 0L, GROCERY),
        ),
        categories = listOf(category(DRINKS, "Bebidas"), category(GROCERY, "Abarrotes")),
        lists = listOf(
            PriceList(DEFAULT, "DEFAULT", null, PriceListPricing.Fixed, null),
            PriceList(
                WHOLESALE,
                null,
                "Mayorista",
                PriceListPricing.Relative(DEFAULT, BasisPoints(-1_000L)),
                null,
            ),
        ),
        ownPrices = emptyMap(),
        header = ReceiptHeader(null, "Tienda", null),
        today = BusinessDate(LocalDate(2026, 11, 14)),
        currencyConfirmed = true,
    )

    private fun category(id: Long, name: String) =
        Category(id, CategoryDetails(name, name.lowercase(), null), AT, AT)

    private fun product(
        id: Long,
        name: String,
        price: Long,
        onHand: Long,
        category: Long? = null,
        photo: Long? = null,
    ) = Product(
        id = id,
        details = ProductDetails(
            name = name,
            normalizedName = name.lowercase(),
            categoryId = category,
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
            photoMediaId = photo,
            taxRateId = null,
            targetMargin = null,
            referenceCost = null,
            referencePrice = null,
            expiryNoticeDays = null,
        ),
        salePrice = Money(price),
        initialCost = Money.ZERO,
        averageCost = Money.ZERO,
        stockValue = Money.ZERO,
        onHand = QuantityMilli(onHand),
        replacementCost = Money.ZERO,
        replacementCostAt = null,
        priceReviewSince = null,
        archivedAt = null,
        createdAt = AT,
        updatedAt = AT,
    )

    private companion object {
        const val DEFAULT = 1L
        const val WHOLESALE = 2L
        const val DRINKS = 10L
        const val GROCERY = 11L
        val AT: Instant = Instant.fromEpochMilliseconds(1_700_000_000_000L)
    }
}
