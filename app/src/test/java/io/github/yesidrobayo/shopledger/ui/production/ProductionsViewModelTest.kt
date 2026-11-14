package io.github.yesidrobayo.shopledger.ui.production

import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.VoidReason
import io.github.yesidrobayo.shopledger.domain.model.Voiding
import io.github.yesidrobayo.shopledger.domain.model.catalog.RecipeComponent
import io.github.yesidrobayo.shopledger.domain.model.production.NewProductionBatch
import io.github.yesidrobayo.shopledger.domain.model.production.NewProductionBatchLine
import io.github.yesidrobayo.shopledger.domain.model.production.ProductionBatch
import io.github.yesidrobayo.shopledger.domain.model.production.ProductionBatchLine
import io.github.yesidrobayo.shopledger.domain.port.StorageFailure
import io.github.yesidrobayo.shopledger.domain.usecase.production.ProductionCatalog
import io.github.yesidrobayo.shopledger.domain.usecase.production.ProductionDetail
import io.github.yesidrobayo.shopledger.domain.usecase.production.VoidProductionResult
import io.github.yesidrobayo.shopledger.ui.product.COUNT_UNIT
import io.github.yesidrobayo.shopledger.ui.product.CREATED
import io.github.yesidrobayo.shopledger.ui.product.product
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
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

/**
 * La lista de producciones sin base: por fecha, con el detalle en una hoja y su anulación, que
 * avisa si lo producido quedaría negativo. Nombres y montos inventados.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ProductionsViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val candle = product(1L, "Vela inventada", onHand = 6_000L)
    private val wax = product(2L, "Cera inventada")
    private val today = requireNotNull(BusinessDate.parse("2026-11-14"))
    private val yesterday = requireNotNull(BusinessDate.parse("2026-11-13"))
    private val batches = MutableStateFlow(
        listOf(batch(3L, today, 10_000L), batch(2L, today, 5_000L), batch(1L, yesterday, 1_000L)),
    )
    private val catalog = ProductionCatalog(
        products = listOf(candle, wax).associateBy { it.id },
        recipes = mapOf(
            candle.id to listOf(RecipeComponent(1L, candle.id, wax.id, QuantityMilli(1))),
        ),
        units = mapOf(COUNT_UNIT.id to COUNT_UNIT),
    )
    private val voids = mutableListOf<Pair<Long, VoidReason>>()
    private var result: VoidProductionResult = VoidProductionResult.Voided

    @Before
    fun useTestMain() = Dispatchers.setMain(dispatcher)

    @After
    fun resetTestMain() = Dispatchers.resetMain()

    private fun TestScope.viewModel(): ProductionsViewModel {
        val model = ProductionsViewModel(
            catalogSource = { flowOf(catalog) },
            recentSource = { limit -> batches.map { it.take(limit) } },
            detailSource = { id ->
                batches.map { all ->
                    all.firstOrNull { it.id == id }?.let { found ->
                        ProductionDetail(
                            found,
                            listOf(
                                ProductionBatchLine(
                                    1L,
                                    id,
                                    NewProductionBatchLine(
                                        wax.id,
                                        QuantityMilli(2_000),
                                        Money(8_000L),
                                        Money(16_000L),
                                    ),
                                ),
                            ),
                        )
                    }
                }
            },
            void = { id, reason ->
                voids += id to reason
                result
            },
            computation = dispatcher,
        )
        backgroundScope.launch { model.uiState.collect {} }
        advanceUntilIdle()
        return model
    }

    private fun ProductionsViewModel.act(scope: TestScope, action: ProductionsAction) {
        onAction(action)
        scope.advanceUntilIdle()
    }

    @Test
    fun `productions come by business date, the newest first`() = runTest(dispatcher) {
        val state = viewModel().uiState.value

        assertEquals(listOf(today, yesterday), state.days.map { it.date })
        assertEquals(listOf(3L, 2L), state.days.first().items.map { it.id })
        assertEquals("Vela inventada", state.days.first().items.first().productName)
        assertTrue(state.hasRecipes)
        assertFalse(state.canLoadMore)
    }

    @Test
    fun `voiding asks first and warns when the produced stock would go negative`() =
        runTest(dispatcher) {
            val model = viewModel()

            model.act(this, ProductionsAction.Open(3L))
            // Hay 6 velas y la producción hizo 10: anularla dejaría 4 menos de lo que hay.
            assertEquals(QuantityMilli(-4_000), model.uiState.value.detail?.leftIfVoided)
            assertEquals(1, model.uiState.value.detail?.lines?.size)
            model.act(this, ProductionsAction.AskVoid)
            assertTrue(model.uiState.value.voidAsked)
            model.act(this, ProductionsAction.ConfirmVoid)

            assertEquals(listOf(3L to VoidReason.ENTRY_ERROR), voids)
            assertTrue(model.uiState.value.voided)
            model.act(this, ProductionsAction.VoidedShown)
            assertNull(model.uiState.value.detail)
        }

    @Test
    fun `a voided production shows no void action and no warning`() = runTest(dispatcher) {
        batches.value = listOf(
            batch(3L, today, 10_000L).copy(voiding = Voiding(CREATED, VoidReason.UNDO, null)),
        )
        val model = viewModel()

        model.act(this, ProductionsAction.Open(3L))

        assertTrue(model.uiState.value.detail?.voided == true)
        assertNull(model.uiState.value.detail?.leftIfVoided)
        assertTrue(model.uiState.value.days.single().items.single().voided)
    }

    @Test
    fun `a failed void is said and can be retried`() = runTest(dispatcher) {
        result = VoidProductionResult.StorageFailed(StorageFailure.DISK_FULL)
        val model = viewModel()
        model.act(this, ProductionsAction.Open(2L))

        model.act(this, ProductionsAction.ConfirmVoid)

        assertTrue(model.uiState.value.voidFailed)
        result = VoidProductionResult.Voided
        model.act(this, ProductionsAction.ConfirmVoid)
        assertTrue(model.uiState.value.voided)
    }

    @Test
    fun `a long list loads by tranches`() = runTest(dispatcher) {
        batches.value = (1L..60L).map { batch(it, today, 1_000L) }
        val model = viewModel()

        assertEquals(50, model.uiState.value.days.single().items.size)
        assertTrue(model.uiState.value.canLoadMore)
        model.act(this, ProductionsAction.LoadMore)

        assertEquals(60, model.uiState.value.days.single().items.size)
        assertFalse(model.uiState.value.canLoadMore)
    }

    private fun batch(id: Long, date: BusinessDate, milli: Long) = ProductionBatch(
        id = id,
        content = NewProductionBatch(
            productId = candle.id,
            locationId = 1L,
            quantity = QuantityMilli(milli),
            totalCost = Money(17_500L),
            note = null,
            occurredAt = CREATED,
            businessDate = date,
            createdAt = CREATED,
        ),
        voiding = null,
    )
}
