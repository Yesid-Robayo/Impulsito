package io.github.yesidrobayo.shopledger.ui.stats

import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.report.CashFlowProjection
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
import kotlinx.datetime.LocalDate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * El flujo proyectado de `Números` sin base: no aparece sin datos, muestra lo leído, y un error
 * queda en su lugar hasta reintentar. Cada vuelta a primer plano vuelve a leer. Datos inventados.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CashFlowViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val projection = CashFlowProjection(
        today = BusinessDate(LocalDate(2026, 11, 2)),
        availableToday = Money(85_000),
        horizons = emptyList(),
    )
    private val read = MutableStateFlow<CashFlowProjection?>(null)
    private var reads = 0
    private var fail = false

    @Before
    fun useTestMain() = Dispatchers.setMain(dispatcher)

    @After
    fun resetTestMain() = Dispatchers.resetMain()

    private fun observe(): Flow<CashFlowProjection?> {
        reads++
        return if (fail) flow { error("lectura inventada") } else read
    }

    @Test
    fun `without data it does not appear, and with data it shows the projection`() =
        runTest(dispatcher) {
            val model = CashFlowViewModel { observe() }
            assertEquals(CashFlowUiState.Loading, model.uiState.value)
            advanceUntilIdle()
            assertEquals(CashFlowUiState.Hidden, model.uiState.value)

            read.value = projection
            advanceUntilIdle()
            assertEquals(CashFlowUiState.Ready(projection), model.uiState.value)
        }

    @Test
    fun `a failed read stays until retrying, and coming back reads again`() = runTest(dispatcher) {
        fail = true
        val model = CashFlowViewModel { observe() }
        advanceUntilIdle()
        assertEquals(CashFlowUiState.Failed, model.uiState.value)

        fail = false
        read.value = projection
        model.retry()
        advanceUntilIdle()
        assertEquals(CashFlowUiState.Ready(projection), model.uiState.value)

        model.onReturnToForeground()
        advanceUntilIdle()
        assertEquals(3, reads)
    }
}
