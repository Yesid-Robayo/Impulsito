package io.github.yesidrobayo.shopledger.ui.reference

import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.pricing.FxRate
import io.github.yesidrobayo.shopledger.domain.model.pricing.NewFxRate
import io.github.yesidrobayo.shopledger.domain.model.region.NumberSymbols
import io.github.yesidrobayo.shopledger.domain.model.settings.CurrencyCode
import io.github.yesidrobayo.shopledger.domain.model.settings.ReferenceCurrency
import io.github.yesidrobayo.shopledger.domain.port.StorageFailure
import io.github.yesidrobayo.shopledger.domain.usecase.pricing.RecordReferenceRateResult
import io.github.yesidrobayo.shopledger.domain.usecase.pricing.ReferenceRateView
import io.github.yesidrobayo.shopledger.domain.usecase.pricing.SetReferenceCurrencyResult
import kotlin.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * La moneda de referencia en la JVM: elegir sin moneda guarda enseguida y con otra pide confirmar,
 * la hoja lee la cotización con seis decimales y avisa un salto sin bloquear, y una falla conserva lo
 * escrito. Códigos y cotizaciones inventados.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReferenceCurrencyViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val symbols = NumberSymbols(decimalSeparator = ',', minusSign = '-', grouping = null)
    private val other = ReferenceCurrency(requireNotNull(CurrencyCode.of("XXX")), 2)
    private val today = BusinessDate(LocalDate(2026, 11, 13))
    private val view = MutableStateFlow(ReferenceRateView(null, null, emptyList()))
    private val saved = mutableListOf<ReferenceCurrency?>()
    private val recorded = mutableListOf<Triple<Long, String?, BusinessDate?>>()
    private var recordResult: RecordReferenceRateResult = RecordReferenceRateResult.Recorded(9)
    private var failing = false

    @Before
    fun useTestMain() = Dispatchers.setMain(dispatcher)

    @After
    fun resetTestMain() = Dispatchers.resetMain()

    private fun TestScope.model(): ReferenceCurrencyViewModel {
        val model = ReferenceCurrencyViewModel(
            observe = {
                flow {
                    if (failing) error("Falla inventada")
                    view.collect { emit(it) }
                }
            },
            calendar = { flowOf(RateCalendar(today, DayOfWeek.MONDAY)) },
            setReference = {
                saved += it
                SetReferenceCurrencyResult.Saved
            },
            record = { rate, note, date ->
                recorded += Triple(rate, note, date)
                recordResult
            },
            symbols = symbols,
        )
        backgroundScope.launch { model.uiState.collect {} }
        advanceUntilIdle()
        return model
    }

    private fun ReferenceCurrencyViewModel.ready() = uiState.value as ReferenceCurrencyUiState.Ready

    private fun rate(id: Long, micro: Long) = FxRate(
        id,
        NewFxRate(
            other.code,
            micro,
            null,
            Instant.fromEpochMilliseconds(id),
            today,
            Instant.fromEpochMilliseconds(id),
        ),
    )

    @Test
    fun `without a reference it is empty, and picking one saves it at once`() =
        runTest(dispatcher) {
            val model = model()
            assertNull(model.ready().reference)

            model.onAction(ReferenceCurrencyAction.ChooseCurrency)

            advanceUntilIdle()
            assertTrue(model.ready().choosing)
            model.onAction(ReferenceCurrencyAction.PickCurrency("XXX", 2))
            advanceUntilIdle()

            assertEquals(listOf<ReferenceCurrency?>(other), saved)
            assertFalse(model.ready().choosing)
        }

    @Test
    fun `changing to another currency asks first, and decimals save directly`() =
        runTest(dispatcher) {
            view.value = ReferenceRateView(other, null, emptyList())
            val model = model()

            model.onAction(ReferenceCurrencyAction.PickCurrency("XTS", 0))
            advanceUntilIdle()
            val pending = ReferenceCurrency(requireNotNull(CurrencyCode.of("XTS")), 0)
            assertEquals(pending, model.ready().pendingChange)
            assertTrue(saved.isEmpty())

            model.onAction(ReferenceCurrencyAction.ConfirmChange)
            advanceUntilIdle()
            model.onAction(ReferenceCurrencyAction.PickDecimals(3))
            advanceUntilIdle()

            assertEquals(listOf<ReferenceCurrency?>(pending, other.copy(decimals = 3)), saved)
            assertNull(model.ready().pendingChange)
        }

    @Test
    fun `the history carries the change against the previous rate`() = runTest(dispatcher) {
        val newer = rate(2, 4_150_000_000)
        val older = rate(1, 4_000_000_000)
        view.value = ReferenceRateView(other, newer, listOf(newer, older))

        val ready = model().ready()

        assertEquals(newer, ready.latest?.rate)
        assertEquals(listOf(BasisPoints(375), null), ready.history.map { it.variation })
    }

    @Test
    fun `a rate is read with six decimals, warns a jump and saves with today`() =
        runTest(dispatcher) {
            val latest = rate(1, 4_000_000_000)
            view.value = ReferenceRateView(other, latest, listOf(latest))
            val model = model()

            model.onAction(ReferenceCurrencyAction.StartRate)

            advanceUntilIdle()
            assertEquals(today, model.ready().sheet?.date)
            model.onAction(ReferenceCurrencyAction.RateText("40000,5"))
            advanceUntilIdle()
            assertTrue(model.ready().sheet?.jumpWarning == true)
            model.onAction(ReferenceCurrencyAction.RateText("4150,123456"))
            model.onAction(ReferenceCurrencyAction.RateNote("Casa de cambio"))
            advanceUntilIdle()
            assertFalse(model.ready().sheet?.jumpWarning == true)
            assertEquals(BasisPoints(375), model.ready().sheet?.variation)

            model.onAction(ReferenceCurrencyAction.SaveRate)
            advanceUntilIdle()

            assertEquals(
                listOf(Triple(4_150_123_456L, "Casa de cambio" as String?, null as BusinessDate?)),
                recorded,
            )
            assertNull(model.ready().sheet)
            assertEquals(ReferenceMessage.RateSaved, model.ready().message)
        }

    @Test
    fun `zero, text and too many decimals mark the field and save nothing`() = runTest(dispatcher) {
        view.value = ReferenceRateView(other, null, emptyList())
        val model = model()
        model.onAction(ReferenceCurrencyAction.StartRate)

        for ((text, problem) in listOf(
            "" to RateProblem.NOT_POSITIVE,
            "0" to RateProblem.NOT_POSITIVE,
            "1,1234567" to RateProblem.NOT_A_NUMBER,
        )) {
            model.onAction(ReferenceCurrencyAction.RateText(text))
            model.onAction(ReferenceCurrencyAction.SaveRate)
            advanceUntilIdle()
            assertEquals(text, problem, model.ready().sheet?.problem)
        }
        assertTrue(recorded.isEmpty())
    }

    @Test
    fun `a failure keeps what was written and says it in the sheet`() = runTest(dispatcher) {
        view.value = ReferenceRateView(other, null, emptyList())
        recordResult = RecordReferenceRateResult.Failed(StorageFailure.DISK_FULL)
        val model = model()
        model.onAction(ReferenceCurrencyAction.StartRate)
        model.onAction(ReferenceCurrencyAction.RateText("4150"))

        model.onAction(ReferenceCurrencyAction.SaveRate)
        advanceUntilIdle()

        val sheet = requireNotNull(model.ready().sheet)
        assertEquals("4150", sheet.rateText)
        assertEquals(SaveFailure(outOfSpace = true), sheet.saveFailed)

        recordResult = RecordReferenceRateResult.FutureDate
        model.onAction(ReferenceCurrencyAction.SaveRate)
        advanceUntilIdle()
        assertTrue(model.ready().sheet?.futureDate == true)
    }

    @Test
    fun `a failed read says it, and retry reads again`() = runTest(dispatcher) {
        failing = true
        val model = model()
        assertEquals(ReferenceCurrencyUiState.ReadFailed, model.uiState.value)

        failing = false
        model.onAction(ReferenceCurrencyAction.Retry)
        advanceUntilIdle()
        assertNull(model.ready().reference)
    }
}
