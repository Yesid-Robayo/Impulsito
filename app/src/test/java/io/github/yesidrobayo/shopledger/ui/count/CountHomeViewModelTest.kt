package io.github.yesidrobayo.shopledger.ui.count

import io.github.yesidrobayo.shopledger.domain.model.catalog.Category
import io.github.yesidrobayo.shopledger.domain.model.stock.OpenCountRequest
import io.github.yesidrobayo.shopledger.domain.port.StorageFailure
import io.github.yesidrobayo.shopledger.domain.usecase.stock.CountOverview
import io.github.yesidrobayo.shopledger.domain.usecase.stock.OpenCountResult
import io.github.yesidrobayo.shopledger.ui.location.LocationSamples
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
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

/** El inicio del conteo con respuestas fijas del dominio. Datos inventados. */
@OptIn(ExperimentalCoroutinesApi::class)
class CountHomeViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val operations = FakeCountHomeOperations()

    @Before
    fun useTestMain() = Dispatchers.setMain(dispatcher)

    @After
    fun resetTestMain() = Dispatchers.resetMain()

    private fun TestScope.model(): CountHomeViewModel =
        CountHomeViewModel(operations).also { advanceUntilIdle() }

    private fun CountHomeViewModel.act(scope: TestScope, action: CountHomeAction) {
        onAction(action)
        scope.advanceUntilIdle()
    }

    private val CountHomeViewModel.sheet: NewCountSheet get() = requireNotNull(uiState.value.sheet)

    @Test
    fun `the open counts and the previous ones are read on open`() = runTest(dispatcher) {
        val model = model()

        assertEquals(
            CountHomeContent.Ready(CountSamples.OVERVIEW),
            model.uiState.value.content,
        )
        assertNull(model.uiState.value.sheet)
    }

    @Test
    fun `a failed read offers to retry and the retry reads again`() = runTest(dispatcher) {
        operations.failingReads = 1
        val model = model()
        assertEquals(CountHomeContent.ReadError, model.uiState.value.content)

        model.act(this, CountHomeAction.Retry)

        assertEquals(
            CountHomeContent.Ready(CountSamples.OVERVIEW),
            model.uiState.value.content,
        )
    }

    @Test
    fun `a new count starts blind, at the first location, with every category to choose`() =
        runTest(dispatcher) {
            val model = model()

            model.act(this, CountHomeAction.NewCount)

            val opened = model.sheet
            assertEquals(LocationSamples.MAIN.id, opened.locationId)
            assertNull(opened.categoryId)
            assertTrue(opened.blind)
            assertEquals(listOf(CountSamples.CATEGORY), opened.categories)
        }

    @Test
    fun `starting sends the chosen scope and opens the new session once`() = runTest(dispatcher) {
        val model = model()
        model.act(this, CountHomeAction.NewCount)
        model.act(this, CountHomeAction.SheetLocation(LocationSamples.STALL.id))
        model.act(this, CountHomeAction.OpenCategories)
        assertTrue(model.sheet.choosingCategory)
        model.act(this, CountHomeAction.SheetCategory(CountSamples.CATEGORY.id))
        model.act(this, CountHomeAction.SheetBlind(false))
        model.act(this, CountHomeAction.SheetNote("Cierre de mes"))

        model.act(this, CountHomeAction.Start)

        assertEquals(
            listOf(
                OpenCountRequest(
                    LocationSamples.STALL.id,
                    CountSamples.CATEGORY.id,
                    isBlind = false,
                    note = "Cierre de mes",
                ),
            ),
            operations.requests,
        )
        assertNull(model.uiState.value.sheet)
        assertEquals(OPENED_ID, model.uiState.value.openedId)
        model.act(this, CountHomeAction.OpenedShown)
        assertNull(model.uiState.value.openedId)
    }

    @Test
    fun `asking for a count where one is open leads to that one`() = runTest(dispatcher) {
        operations.result = OpenCountResult.AlreadyOpen(CountSamples.OPEN.id)
        val model = model()
        model.act(this, CountHomeAction.NewCount)

        model.act(this, CountHomeAction.Start)

        assertEquals(CountSamples.OPEN.id, model.uiState.value.openedId)
        assertNull(model.uiState.value.sheet)
    }

    @Test
    fun `each rejection stays in the sheet with its reason`() = runTest(dispatcher) {
        val expected = mapOf(
            OpenCountResult.NothingToCount to NewCountError.SCOPE_EMPTY,
            OpenCountResult.CategoryNotFound to NewCountError.SCOPE_EMPTY,
            OpenCountResult.NoteTooLong to NewCountError.NOTE_TOO_LONG,
            OpenCountResult.LocationUnavailable to NewCountError.LOCATION_UNAVAILABLE,
            OpenCountResult.UnknownTimeZone to NewCountError.UNKNOWN_ZONE,
        )
        val model = model()
        for ((result, error) in expected) {
            operations.result = result
            model.act(this, CountHomeAction.NewCount)

            model.act(this, CountHomeAction.Start)

            val shown = model.sheet
            assertEquals(error, shown.error)
            assertFalse(shown.starting)
            assertNull(model.uiState.value.openedId)
        }
        // Escribir de nuevo la nota quita el error.
        model.act(this, CountHomeAction.SheetNote("Otra"))
        assertNull(model.sheet.error)
    }

    @Test
    fun `a full disk is told apart and keeps what was chosen`() = runTest(dispatcher) {
        operations.result = OpenCountResult.StorageFailed(StorageFailure.DISK_FULL)
        val model = model()
        model.act(this, CountHomeAction.NewCount)
        model.act(this, CountHomeAction.SheetNote("Bodega"))

        model.act(this, CountHomeAction.Start)

        val shown = model.sheet
        assertEquals(true, shown.failedOutOfSpace)
        assertEquals("Bodega", shown.note)
        assertFalse(shown.starting)
    }

    @Test
    fun `creating a product or dismissing closes the sheet`() = runTest(dispatcher) {
        val model = model()
        model.act(this, CountHomeAction.NewCount)
        model.act(this, CountHomeAction.CreateProduct)
        assertNull(model.uiState.value.sheet)

        model.act(this, CountHomeAction.NewCount)
        model.act(this, CountHomeAction.DismissSheet)
        assertNull(model.uiState.value.sheet)
        assertTrue(operations.requests.isEmpty())
    }

    @Test
    fun `navigation actions change nothing here`() = runTest(dispatcher) {
        val model = model()
        val before = model.uiState.value

        listOf(
            CountHomeAction.Back,
            CountHomeAction.OpenSession(CountSamples.OPEN.id),
            CountHomeAction.ChooseZone,
            CountHomeAction.Start,
        ).forEach { model.act(this, it) }

        assertEquals(before, model.uiState.value)
    }

    private class FakeCountHomeOperations : CountHomeOperations {
        var failingReads = 0
        var result: OpenCountResult = OpenCountResult.Opened(OPENED_ID)
        val requests = mutableListOf<OpenCountRequest>()
        private val categories = MutableStateFlow(listOf(CountSamples.CATEGORY))

        override fun overview(): Flow<CountOverview> = flow {
            if (failingReads > 0) {
                failingReads--
                error("Lectura fallida a propósito")
            }
            emit(CountSamples.OVERVIEW)
        }

        override fun categories(): Flow<List<Category>> = categories

        override suspend fun open(request: OpenCountRequest): OpenCountResult {
            requests += request
            return result
        }
    }

    private companion object {
        const val OPENED_ID = 9L
    }
}
