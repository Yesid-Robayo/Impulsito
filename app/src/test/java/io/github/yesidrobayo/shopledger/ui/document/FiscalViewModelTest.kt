package io.github.yesidrobayo.shopledger.ui.document

import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.BusinessTimeZone
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.document.FiscalMarkStatus
import io.github.yesidrobayo.shopledger.domain.model.document.FiscalSale
import io.github.yesidrobayo.shopledger.domain.port.StorageFailure
import io.github.yesidrobayo.shopledger.domain.rule.region.BusinessCalendar
import io.github.yesidrobayo.shopledger.domain.rule.region.BusinessCalendarResult
import io.github.yesidrobayo.shopledger.domain.usecase.document.FiscalMonth
import io.github.yesidrobayo.shopledger.domain.usecase.document.MarkFiscalResult
import io.github.yesidrobayo.shopledger.domain.usecase.document.ReferenceChange
import io.github.yesidrobayo.shopledger.domain.usecase.document.SavePortalLinkResult
import kotlin.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.YearMonth
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Los pendientes de facturar sin base: el mes de hoy, la marca, la hoja de una venta con su
 * referencia, marcar varias, el enlace y los errores, que nunca borran lo escrito. Datos
 * inventados y direcciones de `example.com`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FiscalViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val calendar =
        BusinessCalendar(requireNotNull(BusinessTimeZone.of("Etc/GMT+5")), DayOfWeek.MONDAY)

    /** 2026-11-02 15:00 UTC: lunes 2026-11-02 en la zona del negocio. */
    private val now = Instant.fromEpochMilliseconds(1_793_631_600_000L)
    private val november = YearMonth(2026, 11)
    private val first = sale(1_204L, "Cliente de prueba", reference = "A-1")
    private val second = sale(1_198L, null, hasReturn = true)

    private val reads = mutableListOf<Pair<YearMonth, FiscalMarkStatus>>()
    private val marks = mutableListOf<Triple<List<Long>, FiscalMarkStatus, ReferenceChange>>()
    private var markResult: MarkFiscalResult = MarkFiscalResult.Marked(1)
    private val links = mutableListOf<String>()
    private var linkResult: SavePortalLinkResult = SavePortalLinkResult.Saved(LINK)
    private val sales = MutableStateFlow(listOf(first, second))

    @Before
    fun useTestMain() = Dispatchers.setMain(dispatcher)

    @After
    fun resetTestMain() = Dispatchers.resetMain()

    private fun sale(
        id: Long,
        customer: String?,
        reference: String? = null,
        hasReturn: Boolean = false,
    ) = FiscalSale(
        saleId = id,
        businessDate = BusinessDate(LocalDate(2026, 11, 1)),
        total = Money(20_900),
        customerName = customer,
        status = FiscalMarkStatus.PENDING,
        externalReference = reference,
        hasReturn = hasReturn,
    )

    private fun model(
        month: (YearMonth, FiscalMarkStatus) -> Flow<FiscalMonth> = { m, status ->
            reads += m to status
            sales.map { FiscalMonth(m, status, it, Money(41_800), hasMarks = false) }
        },
        observeSale: (Long) -> Flow<FiscalSale?> = { id -> flowOf(sale(id, null, "Z-9")) },
        initialSaleId: Long? = null,
    ) = FiscalViewModel(
        observeMonth = month,
        observeSale = observeSale,
        portalUrl = flowOf(LINK),
        calendar = flowOf(BusinessCalendarResult.Ready(calendar)),
        now = { now },
        mark = { ids, status, reference ->
            marks += Triple(ids, status, reference)
            markResult
        },
        saveLink = { text ->
            links += text
            linkResult
        },
        initialSaleId = initialSaleId,
    )

    @Test
    fun `opens on the pending sales of this month, without network`() = runTest(dispatcher) {
        val model = model()
        advanceUntilIdle()

        val state = model.uiState.value
        assertEquals(november, state.month)
        assertEquals(FiscalMarkStatus.PENDING, state.filter)
        assertEquals(
            FiscalContent.Sales(listOf(first, second), Money(41_800), false),
            state.content,
        )
        assertEquals(LINK, state.portalUrl)
        assertEquals(listOf(november to FiscalMarkStatus.PENDING), reads)
    }

    @Test
    fun `the month never goes past today and a filter reads again`() = runTest(dispatcher) {
        val model = model()
        advanceUntilIdle()

        model.onAction(FiscalAction.NextMonth)
        advanceUntilIdle()
        model.onAction(FiscalAction.PreviousMonth)
        advanceUntilIdle()
        model.onAction(FiscalAction.FilterChange(FiscalMarkStatus.RECORDED))
        advanceUntilIdle()

        assertEquals(YearMonth(2026, 10), model.uiState.value.month)
        assertEquals(
            listOf(
                november to FiscalMarkStatus.PENDING,
                YearMonth(2026, 10) to FiscalMarkStatus.PENDING,
                YearMonth(2026, 10) to FiscalMarkStatus.RECORDED,
            ),
            reads,
        )
    }

    @Test
    fun `a read failure offers to retry`() = runTest(dispatcher) {
        var attempt = 0
        val model = model(month = { m, status ->
            attempt++
            if (attempt == 1) {
                flow { error("Lectura fallida de prueba") }
            } else {
                flowOf(FiscalMonth(m, status, emptyList(), Money.ZERO, hasMarks = true))
            }
        })
        advanceUntilIdle()
        assertEquals(FiscalContent.ReadError, model.uiState.value.content)

        model.onAction(FiscalAction.Retry)
        advanceUntilIdle()

        assertEquals(
            FiscalContent.Sales(emptyList(), Money.ZERO, true),
            model.uiState.value.content,
        )
    }

    @Test
    fun `the sheet keeps the reference and a failure keeps what was written`() =
        runTest(dispatcher) {
            val model = model()
            advanceUntilIdle()

            model.onAction(FiscalAction.OpenSale(first.saleId))
            advanceUntilIdle()
            assertEquals("A-1", model.uiState.value.sheet?.reference)

            model.onAction(FiscalAction.ReferenceChange("A-1043"))
            markResult = MarkFiscalResult.Failed(StorageFailure.DISK_FULL)
            model.onAction(FiscalAction.MarkSale(FiscalMarkStatus.RECORDED))
            advanceUntilIdle()

            val sheet = requireNotNull(model.uiState.value.sheet)
            assertEquals(FiscalSheetStatus.FAILED, sheet.status)
            assertEquals("A-1043", sheet.reference)
            assertEquals(
                Triple(
                    listOf(first.saleId),
                    FiscalMarkStatus.RECORDED,
                    ReferenceChange.Write("A-1043"),
                ),
                marks.single(),
            )

            markResult = MarkFiscalResult.Marked(1)
            model.onAction(FiscalAction.MarkSale(FiscalMarkStatus.RECORDED))
            advanceUntilIdle()
            assertNull(model.uiState.value.sheet)
        }

    @Test
    fun `a reference too long or a voided sale stays in the sheet`() = runTest(dispatcher) {
        val model = model()
        advanceUntilIdle()
        model.onAction(FiscalAction.OpenSale(second.saleId))

        markResult = MarkFiscalResult.ReferenceTooLong
        model.onAction(FiscalAction.MarkSale(FiscalMarkStatus.NOT_NEEDED))
        advanceUntilIdle()
        assertTrue(requireNotNull(model.uiState.value.sheet).referenceTooLong)

        model.onAction(FiscalAction.ReferenceChange("B"))
        assertFalse(requireNotNull(model.uiState.value.sheet).referenceTooLong)

        markResult = MarkFiscalResult.SaleUnavailable
        model.onAction(FiscalAction.MarkSale(FiscalMarkStatus.NOT_NEEDED))
        advanceUntilIdle()
        assertTrue(requireNotNull(model.uiState.value.sheet).unavailable)

        model.onAction(FiscalAction.CloseSheet)
        assertNull(model.uiState.value.sheet)
    }

    @Test
    fun `from the sale detail the sheet opens with the sale read on its own`() =
        runTest(dispatcher) {
            val model = model(initialSaleId = 77L)
            advanceUntilIdle()

            val sheet = requireNotNull(model.uiState.value.sheet)
            assertEquals(77L, sheet.sale?.saleId)
            assertEquals("Z-9", sheet.reference)

            val gone = model(observeSale = { flowOf(null) }, initialSaleId = 78L)
            advanceUntilIdle()
            assertTrue(requireNotNull(gone.uiState.value.sheet).unavailable)
        }

    @Test
    fun `selecting several marks them together without reference`() = runTest(dispatcher) {
        val model = model()
        advanceUntilIdle()

        model.onAction(FiscalAction.ToggleSelection(first.saleId))
        // Eligiendo, tocar una venta la suma en vez de abrir su hoja.
        model.onAction(FiscalAction.OpenSale(second.saleId))
        assertEquals(setOf(first.saleId, second.saleId), model.uiState.value.selection)
        assertNull(model.uiState.value.sheet)

        markResult = MarkFiscalResult.Failed(StorageFailure.IO_ERROR)
        model.onAction(FiscalAction.MarkSelection(FiscalMarkStatus.RECORDED))
        advanceUntilIdle()
        assertEquals(FiscalSheetStatus.FAILED, model.uiState.value.bulkStatus)
        assertEquals(2, model.uiState.value.selection.size)

        markResult = MarkFiscalResult.Marked(2)
        model.onAction(FiscalAction.MarkSelection(FiscalMarkStatus.NOT_NEEDED))
        advanceUntilIdle()

        assertTrue(model.uiState.value.selection.isEmpty())
        assertEquals(
            Triple(
                listOf(second.saleId, first.saleId),
                FiscalMarkStatus.NOT_NEEDED,
                ReferenceChange.Keep,
            ),
            marks.last(),
        )

        model.onAction(FiscalAction.ToggleSelection(first.saleId))
        model.onAction(FiscalAction.FilterChange(FiscalMarkStatus.RECORDED))
        assertTrue(model.uiState.value.selection.isEmpty())
    }

    @Test
    fun `the link sheet shows its errors and closes when saved`() = runTest(dispatcher) {
        val model = model()
        advanceUntilIdle()

        model.onAction(FiscalAction.ConfigureLink)
        assertEquals(LINK, model.uiState.value.linkSheet?.text)

        model.onAction(FiscalAction.LinkChange("http://example.com"))
        linkResult = SavePortalLinkResult.NotHttps
        model.onAction(FiscalAction.SaveLink)
        advanceUntilIdle()
        assertEquals(FiscalLinkError.NOT_HTTPS, model.uiState.value.linkSheet?.error)
        assertEquals("http://example.com", model.uiState.value.linkSheet?.text)

        linkResult = SavePortalLinkResult.TooLong
        model.onAction(FiscalAction.SaveLink)
        advanceUntilIdle()
        assertEquals(FiscalLinkError.TOO_LONG, model.uiState.value.linkSheet?.error)

        linkResult = SavePortalLinkResult.Failed(StorageFailure.DISK_FULL)
        model.onAction(FiscalAction.SaveLink)
        advanceUntilIdle()
        assertEquals(FiscalSheetStatus.FAILED, model.uiState.value.linkSheet?.status)

        linkResult = SavePortalLinkResult.Saved(LINK)
        model.onAction(FiscalAction.LinkChange(LINK))
        model.onAction(FiscalAction.SaveLink)
        advanceUntilIdle()
        assertNull(model.uiState.value.linkSheet)
        assertEquals(LINK, links.last())
    }

    @Test
    fun `no app for the link shows a message once`() = runTest(dispatcher) {
        val model = model()
        advanceUntilIdle()

        model.onAction(FiscalAction.NoAppForLink)
        assertEquals(FiscalMessage.NO_APP_FOR_LINK, model.uiState.value.message)

        model.onAction(FiscalAction.MessageShown)
        assertNull(model.uiState.value.message)
    }

    @Test
    fun `an unknown zone asks for it`() = runTest(dispatcher) {
        val model = FiscalViewModel(
            observeMonth = { _, _ -> flowOf() },
            observeSale = { flowOf(null) },
            portalUrl = flowOf(null),
            calendar = flowOf(BusinessCalendarResult.UnknownZone("Zona/Inventada")),
            now = { now },
            mark = { _, _, _ -> MarkFiscalResult.NothingSelected },
            saveLink = { SavePortalLinkResult.Saved(null) },
        )
        advanceUntilIdle()

        assertEquals(FiscalContent.UnknownZone, model.uiState.value.content)
    }

    private companion object {
        const val LINK = "https://example.com/portal"
    }
}
