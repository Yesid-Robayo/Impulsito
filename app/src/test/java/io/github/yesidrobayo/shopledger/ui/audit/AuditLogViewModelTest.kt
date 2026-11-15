package io.github.yesidrobayo.shopledger.ui.audit

import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.BusinessTimeZone
import io.github.yesidrobayo.shopledger.domain.model.audit.AuditEvent
import io.github.yesidrobayo.shopledger.domain.model.audit.AuditEventType
import io.github.yesidrobayo.shopledger.domain.model.audit.AuditResult
import io.github.yesidrobayo.shopledger.domain.usecase.audit.ObserveAuditLogUseCase
import java.io.IOException
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
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
 * El registro de auditoría en pantalla: espera sin mostrar nada, agrupa por el día del negocio,
 * filtra los fallos, crece por tramos solo cuando el tramo llegó lleno, y una lectura fallida se
 * dice con `Reintentar`. Nada aquí escribe.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AuditLogViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val zone = checkNotNull(BusinessTimeZone.of("Etc/GMT+3"))
    private val noon = Instant.parse("2026-11-12T15:00:00Z")
    private val events = MutableStateFlow<List<AuditEvent>>(emptyList())
    private val asked = mutableListOf<Pair<Int, Boolean>>()
    private val businessZone = MutableStateFlow<BusinessTimeZone?>(zone)
    private var failNext = false

    @Before
    fun useTestMain() = Dispatchers.setMain(dispatcher)

    @After
    fun resetTestMain() = Dispatchers.resetMain()

    private fun observe(limit: Int, failuresOnly: Boolean): Flow<List<AuditEvent>> {
        asked += limit to failuresOnly
        if (failNext) {
            failNext = false
            return flow { throw IOException("sin base") }
        }
        return events
    }

    private fun TestScope.started(): AuditLogViewModel {
        val model = AuditLogViewModel(::observe, businessZone, dispatcher)
        backgroundScope.launch { model.uiState.collect {} }
        advanceUntilIdle()
        return model
    }

    private fun event(id: Long, at: Instant, result: AuditResult = AuditResult.SUCCESS) =
        AuditEvent(id, at, AuditEventType.EXPORT_CSV, result)

    private fun ready(model: AuditLogViewModel) = model.uiState.value as AuditLogUiState.Ready

    @Test
    fun `it starts loading and asks for the first page of every event`() = runTest(dispatcher) {
        val model = AuditLogViewModel(::observe, businessZone, dispatcher)
        assertEquals(AuditLogUiState.Loading, model.uiState.value)

        backgroundScope.launch { model.uiState.collect {} }
        advanceUntilIdle()

        assertEquals(listOf(ObserveAuditLogUseCase.PAGE_SIZE to false), asked)
        assertTrue(ready(model).days.isEmpty())
        assertFalse(ready(model).canLoadMore)
    }

    @Test
    fun `events are grouped by the business day, newest first`() = runTest(dispatcher) {
        events.value =
            listOf(
                event(3, noon),
                event(2, noon - 1.hours),
                // Las 01:00 en UTC son todavía el día anterior en la zona del negocio.
                event(1, Instant.parse("2026-11-12T01:00:00Z")),
            )
        val model = started()

        val days = ready(model).days
        assertEquals(
            listOf(
                BusinessDate(LocalDate(2026, 11, 12)),
                BusinessDate(LocalDate(2026, 11, 11)),
            ),
            days.map { it.date },
        )
        assertEquals(listOf(3L, 2L), days.first().events.map { it.id })
        assertEquals(zone, ready(model).zone)
    }

    @Test
    fun `without a business zone the hour is read in UTC`() = runTest(dispatcher) {
        businessZone.value = null
        events.value = listOf(event(1, Instant.parse("2026-11-12T01:00:00Z")))
        val model = started()

        assertEquals(BusinessDate(LocalDate(2026, 11, 12)), ready(model).days.single().date)
    }

    @Test
    fun `failures only asks for failures and starts again from the first page`() =
        runTest(dispatcher) {
            events.value = List(ObserveAuditLogUseCase.PAGE_SIZE) { event(it.toLong(), noon) }
            val model = started()
            model.onAction(AuditLogAction.LoadMore)
            advanceUntilIdle()

            model.onAction(AuditLogAction.Filter(AuditFilter.FAILURES))
            advanceUntilIdle()
            // Elegir el mismo filtro no pide nada otra vez.
            model.onAction(AuditLogAction.Filter(AuditFilter.FAILURES))
            advanceUntilIdle()

            assertEquals(
                listOf(100 to false, 200 to false, 100 to true),
                asked,
            )
            assertEquals(AuditFilter.FAILURES, ready(model).filter)
        }

    @Test
    fun `a page that is not full has nothing more to load`() = runTest(dispatcher) {
        events.value = listOf(event(1, noon, AuditResult.FAILURE))
        val model = started()

        model.onAction(AuditLogAction.LoadMore)
        advanceUntilIdle()

        assertFalse(ready(model).canLoadMore)
        assertEquals(listOf(100 to false), asked)
    }

    @Test
    fun `a failed read says so and retry reads again`() = runTest(dispatcher) {
        failNext = true
        val model = started()
        assertEquals(AuditLogUiState.ReadFailed, model.uiState.value)

        model.onAction(AuditLogAction.Retry)
        advanceUntilIdle()

        assertTrue(model.uiState.value is AuditLogUiState.Ready)
    }

    @Test
    fun `back changes nothing here`() = runTest(dispatcher) {
        val model = started()
        model.onAction(AuditLogAction.Back)
        advanceUntilIdle()

        assertEquals(listOf(100 to false), asked)
    }
}
