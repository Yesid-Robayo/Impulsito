package io.github.yesidrobayo.shopledger.ui.purchase

import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.BusinessTimeZone
import io.github.yesidrobayo.shopledger.domain.model.DocumentStatus
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.ReportPeriod
import io.github.yesidrobayo.shopledger.domain.model.VoidReason
import io.github.yesidrobayo.shopledger.domain.model.Voiding
import io.github.yesidrobayo.shopledger.domain.model.purchase.NewPurchase
import io.github.yesidrobayo.shopledger.domain.model.purchase.Purchase
import io.github.yesidrobayo.shopledger.domain.model.purchase.PurchaseDetail
import io.github.yesidrobayo.shopledger.domain.model.purchase.PurchaseHistoryCursor
import io.github.yesidrobayo.shopledger.domain.model.purchase.PurchaseHistoryFilter
import io.github.yesidrobayo.shopledger.domain.model.purchase.PurchaseHistoryRow
import io.github.yesidrobayo.shopledger.domain.model.purchase.SupplierFilter
import io.github.yesidrobayo.shopledger.domain.port.PurchaseHistorySource
import io.github.yesidrobayo.shopledger.domain.port.StorageFailure
import io.github.yesidrobayo.shopledger.domain.rule.region.BusinessCalendar
import io.github.yesidrobayo.shopledger.domain.rule.region.BusinessCalendarResult
import io.github.yesidrobayo.shopledger.domain.usecase.purchase.PurchaseHistoryReader
import io.github.yesidrobayo.shopledger.domain.usecase.purchase.VoidPurchaseResult
import io.github.yesidrobayo.shopledger.ui.sale.HistoryPeriodKind
import kotlin.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * El historial y el detalle de compras sin base: tramos, filtros, días sin las anuladas, cambios
 * que vuelven a leer, y anular desde el detalle con su motivo, su nota y sus fallas. Datos
 * inventados.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PurchaseRecordsViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val zone = requireNotNull(BusinessTimeZone.of("Etc/GMT+5"))
    private val calendar = BusinessCalendar(zone, DayOfWeek.MONDAY)

    /** 2026-11-02 15:00 UTC: lunes 2026-11-02 en la zona del negocio. */
    private val now = Instant.fromEpochMilliseconds(1_793_631_600_000L)
    private val today = BusinessDate(LocalDate(2026, 11, 2))

    @Before
    fun useTestMain() = Dispatchers.setMain(dispatcher)

    @After
    fun resetTestMain() = Dispatchers.resetMain()

    private fun row(id: Long, date: BusinessDate = today, voided: Boolean = false) =
        PurchaseHistoryRow(id, now, date, Money(id * 100), voided, null, 1, null)

    /** Un historial en memoria: guarda cada filtro que se le pidió. */
    private inner class Source(var rows: List<PurchaseHistoryRow>) : PurchaseHistorySource {
        val filters = mutableListOf<PurchaseHistoryFilter>()
        val changes = MutableStateFlow("0")
        var fail = false

        override suspend fun page(
            filter: PurchaseHistoryFilter,
            after: PurchaseHistoryCursor?,
            limit: Int,
        ): List<PurchaseHistoryRow> {
            if (fail) error("lectura inventada")
            filters += filter
            val sorted = rows.filter { !filter.onlyVoided || it.voided }
                .sortedWith(
                    compareByDescending<PurchaseHistoryRow> {
                        it.businessDate
                    }.thenByDescending { it.id },
                )
            val start =
                after?.let { cursor -> sorted.indexOfFirst { it.cursor == cursor } + 1 } ?: 0
            return sorted.drop(start).take(limit)
        }

        override suspend fun dayTotals(
            filter: PurchaseHistoryFilter,
            dates: Set<BusinessDate>,
        ): Map<BusinessDate, Money> = rows.filter { !it.voided && it.businessDate in dates }
            .groupBy { it.businessDate }
            .mapValues { (_, list) -> Money(list.sumOf { it.total.minor }) }

        override fun observeChanges(): Flow<String> = changes
    }

    private fun TestScope.history(source: Source, initial: SupplierFilter = SupplierFilter.Any) =
        PurchaseHistoryViewModel(
            reader = PurchaseHistoryReader(source) { now },
            suppliers = flowOf(listOf(SupplierChoice(7, "Proveedor inventado"))),
            calendar = flowOf(BusinessCalendarResult.Ready(calendar)),
            computation = dispatcher,
            initialSupplier = initial,
        ).also { advanceUntilIdle() }

    @Test
    fun `days group the purchases and leave the voided ones out of the total`() =
        runTest(dispatcher) {
            val yesterday = today.plusDays(-1)
            val source = Source(listOf(row(1, yesterday), row(2), row(3, voided = true)))
            val model = history(source)

            val days = (model.uiState.value.content as PurchaseHistoryContent.Days).days
            assertEquals(listOf(today, yesterday), days.map { it.date })
            assertEquals(listOf(3L, 2L), days[0].rows.map { it.id })
            assertEquals(Money(200), days[0].total)
            assertEquals("Proveedor inventado", model.uiState.value.filters.suppliers.single().name)
        }

    @Test
    fun `filters read again and an empty result says why`() = runTest(dispatcher) {
        val source = Source(listOf(row(1)))
        val model = history(source, initial = SupplierFilter.Of(7))
        assertEquals("Proveedor inventado", model.uiState.value.filters.supplierName)
        assertEquals(SupplierFilter.Of(7), source.filters.last().supplier)

        model.onAction(PurchaseHistoryAction.ToggleVoided)
        advanceUntilIdle()
        assertEquals(PurchaseHistoryContent.EmptyFiltered, model.uiState.value.content)

        model.onAction(PurchaseHistoryAction.PeriodKindChange(HistoryPeriodKind.WEEK))
        advanceUntilIdle()
        // La semana del lunes 2026-11-02.
        assertEquals(ReportPeriod.Week(today), model.uiState.value.filters.period)
        model.onAction(PurchaseHistoryAction.PreviousPeriod)
        advanceUntilIdle()
        assertEquals(today.plusDays(-7), source.filters.last().from)
        model.onAction(PurchaseHistoryAction.NextPeriod)
        advanceUntilIdle()
        assertEquals(today, source.filters.last().from)

        model.onAction(PurchaseHistoryAction.OpenSuppliers)
        assertTrue(model.uiState.value.supplierSheet)
        model.onAction(PurchaseHistoryAction.SupplierChange(SupplierFilter.None))
        advanceUntilIdle()
        assertEquals(SupplierFilter.None, source.filters.last().supplier)
        model.onAction(PurchaseHistoryAction.CloseSuppliers)

        model.onAction(PurchaseHistoryAction.ClearFilters)
        advanceUntilIdle()
        assertEquals(PurchaseHistoryFilter(), source.filters.last())
        assertTrue(model.uiState.value.content is PurchaseHistoryContent.Days)
    }

    @Test
    fun `an empty history invites to buy and a read failure offers to retry`() =
        runTest(dispatcher) {
            val source = Source(emptyList())
            val model = history(source)
            assertEquals(PurchaseHistoryContent.Empty, model.uiState.value.content)

            source.fail = true
            model.onAction(PurchaseHistoryAction.Retry)
            advanceUntilIdle()
            assertEquals(PurchaseHistoryContent.ReadError, model.uiState.value.content)

            source.fail = false
            source.rows = listOf(row(1))
            source.changes.value = "1"
            advanceUntilIdle()
            assertTrue(model.uiState.value.content is PurchaseHistoryContent.Days)
        }

    @Test
    fun `the next page loads near the end and stops at the end`() = runTest(dispatcher) {
        val source = Source((1L..60L).map { row(it) })
        val model = history(source)
        val first = model.uiState.value.content as PurchaseHistoryContent.Days
        assertEquals(50, first.days.single().rows.size)

        model.onAction(PurchaseHistoryAction.LoadMore)
        advanceUntilIdle()

        val all = model.uiState.value.content as PurchaseHistoryContent.Days
        assertEquals(60, all.days.single().rows.size)
        assertTrue(all.endReached)
        model.onAction(PurchaseHistoryAction.OpenPurchase(5))
        assertEquals(5L, model.uiState.value.selectedPurchaseId)
        model.onAction(PurchaseHistoryAction.ClosePurchase)
        assertNull(model.uiState.value.selectedPurchaseId)
    }

    private fun purchase(voided: Boolean) = Purchase(
        id = 4,
        content = NewPurchase(
            supplierId = null,
            invoiceReference = null,
            netTotal = Money(500),
            taxTotal = Money.ZERO,
            total = Money(500),
            costTotal = Money(500),
            amountPaid = Money(500),
            moneyAccountId = null,
            dueDate = null,
            locationId = 1,
            foreignCurrency = null,
            occurredAt = now,
            businessDate = today,
            createdAt = now,
        ),
        voiding = if (voided) Voiding(now, VoidReason.ENTRY_ERROR, null) else null,
    )

    private fun TestScope.detail(
        source: Flow<PurchaseDetail?>,
        voids: MutableList<Pair<VoidReason, String?>>,
        result: () -> VoidPurchaseResult,
    ) = PurchaseDetailViewModel(
        detailSource = { source },
        voidPurchase = { reason, note ->
            voids += reason to note
            result()
        },
    ).also { model ->
        backgroundScope.launch { model.uiState.collect {} }
        advanceUntilIdle()
    }

    @Test
    fun `voiding sends the reason and the note, and a failure keeps the sheet`() =
        runTest(dispatcher) {
            val shown =
                MutableStateFlow<PurchaseDetail?>(
                    PurchaseDetail(purchase(false), null, emptyList(), emptyList()),
                )
            val voids = mutableListOf<Pair<VoidReason, String?>>()
            var outcome: VoidPurchaseResult = VoidPurchaseResult.StorageFailed(
                StorageFailure.DISK_FULL,
            )
            val model = detail(shown, voids) { outcome }
            val ready = { model.uiState.value as PurchaseDetailUiState.Ready }
            assertTrue(ready().canVoid)

            model.onAction(PurchaseDetailAction.OpenVoid)
            model.onAction(PurchaseDetailAction.ReasonChange(VoidReason.SUPPLIER_RETURN))
            model.onAction(PurchaseDetailAction.NoteChange("  Nota inventada "))
            model.onAction(PurchaseDetailAction.ConfirmVoid)
            advanceUntilIdle()
            assertEquals(listOf(VoidReason.SUPPLIER_RETURN to "Nota inventada"), voids)
            assertEquals(PurchaseVoidStatus.FAILED, ready().status)

            outcome = VoidPurchaseResult.Voided
            model.onAction(PurchaseDetailAction.ConfirmVoid)
            advanceUntilIdle()
            shown.value = PurchaseDetail(purchase(true), null, emptyList(), emptyList())
            advanceUntilIdle()
            assertNull(ready().sheet)
            assertTrue(ready().justVoided)
            assertTrue(!ready().canVoid)
            model.onAction(PurchaseDetailAction.VoidShown)
            advanceUntilIdle()
            assertTrue(!ready().justVoided)
        }

    @Test
    fun `a long note blocks the void and closing the sheet drops it`() = runTest(dispatcher) {
        val shown = flowOf(PurchaseDetail(purchase(false), null, emptyList(), emptyList()))
        val voids = mutableListOf<Pair<VoidReason, String?>>()
        val model = detail(shown, voids) { VoidPurchaseResult.Voided }

        model.onAction(PurchaseDetailAction.OpenVoid)
        model.onAction(PurchaseDetailAction.NoteChange("n".repeat(201)))
        model.onAction(PurchaseDetailAction.ConfirmVoid)
        advanceUntilIdle()
        assertTrue(voids.isEmpty())
        assertTrue((model.uiState.value as PurchaseDetailUiState.Ready).sheet?.noteTooLong == true)

        model.onAction(PurchaseDetailAction.CloseSheet)
        advanceUntilIdle()
        assertNull((model.uiState.value as PurchaseDetailUiState.Ready).sheet)
        // Sin hoja, confirmar no hace nada.
        model.onAction(PurchaseDetailAction.ConfirmVoid)
        advanceUntilIdle()
        assertTrue(voids.isEmpty())
    }

    @Test
    fun `a missing purchase and a read failure have their states`() = runTest(dispatcher) {
        val missing = detail(flowOf(null), mutableListOf()) { VoidPurchaseResult.Voided }
        assertEquals(PurchaseDetailUiState.NotFound, missing.uiState.value)

        var reads = 0
        val failing = PurchaseDetailViewModel(
            detailSource = {
                reads++
                if (reads == 1) flow { error("lectura inventada") } else flowOf(null)
            },
            voidPurchase = { _, _ -> VoidPurchaseResult.Voided },
        )
        backgroundScope.launch { failing.uiState.collect {} }
        advanceUntilIdle()
        assertEquals(PurchaseDetailUiState.ReadError, failing.uiState.value)
        failing.onAction(PurchaseDetailAction.Retry)
        advanceUntilIdle()
        assertEquals(PurchaseDetailUiState.NotFound, failing.uiState.value)
        assertEquals(DocumentStatus.VOIDED, purchase(true).status)
    }
}
