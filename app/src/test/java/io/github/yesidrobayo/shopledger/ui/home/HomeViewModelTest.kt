package io.github.yesidrobayo.shopledger.ui.home

import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.BusinessTimeZone
import io.github.yesidrobayo.shopledger.domain.model.Margin
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupDestinationKind
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupOverview
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupStatus
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupStatusReport
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleHistoryCursor
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleHistoryFilter
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleHistoryRow
import io.github.yesidrobayo.shopledger.domain.model.sale.SalesSummary
import io.github.yesidrobayo.shopledger.domain.model.settings.MeteredBackupPolicy
import io.github.yesidrobayo.shopledger.domain.port.Clock
import io.github.yesidrobayo.shopledger.domain.port.SaleHistorySource
import io.github.yesidrobayo.shopledger.domain.usecase.home.TodaySales
import io.github.yesidrobayo.shopledger.domain.usecase.sale.SaleHistoryReader
import kotlin.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
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
import org.junit.Before
import org.junit.Test

/**
 * Inicio sin base: el héroe según las ventas de hoy, el primer uso sin productos, las últimas cinco
 * ventas, que se vuelven a leer con cada venta, el error que deja reintentar, y la fecha que se
 * vuelve a mirar al volver a primer plano. Datos inventados.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val today = BusinessDate(LocalDate(2026, 11, 2))
    private val zone = requireNotNull(BusinessTimeZone.of("Etc/GMT+5"))

    /** 2026-11-02 15:00 UTC: lunes 2026-11-02 10:00 en `Etc/GMT+5`. */
    private val now = Instant.fromEpochMilliseconds(1_793_631_600_000L)

    private val sales =
        MutableStateFlow<TodaySales>(TodaySales.Ready(today, zone, summary(0, 0, 0)))
    private val hasProducts = MutableStateFlow(true)
    private val history = FakeHistory()

    private fun backupOverview(kind: BackupDestinationKind, status: BackupStatus) = BackupOverview(
        report = BackupStatusReport(status, pendingChanges = 0, savingSoon = false),
        destinationKind = kind,
        lastSuccessAt = now,
        failureCode = null,
        staleReason = null,
        meteredPolicy = MeteredBackupPolicy.EVERY_60_MIN,
        nextMeteredUploadAt = null,
        lastFullReadAt = null,
        computedAt = now,
    )

    private val backupOverviews =
        MutableStateFlow(backupOverview(BackupDestinationKind.WEBDAV, BackupStatus.UP_TO_DATE))
    private var labelReads = 0

    /** Cuántas veces la pantalla volvió a mirar la fecha. */
    private var dayChecks = 0

    @Before
    fun useTestMain() = Dispatchers.setMain(dispatcher)

    @After
    fun resetTestMain() = Dispatchers.resetMain()

    private fun summary(net: Long, count: Long, profit: Long) = SalesSummary(
        netSales = Money(net),
        salesCount = count,
        grossProfit = Money(profit),
        grossMargin = if (net >
            0
        ) {
            Margin.Defined(BasisPoints(profit * 10_000 / net))
        } else {
            Margin.NoData
        },
    )

    /** Un historial en memoria, que anota cada tramo pedido y puede fallar a pedido. */
    private class FakeHistory : SaleHistorySource {
        var rows: List<SaleHistoryRow> = emptyList()
        val limits = mutableListOf<Int>()
        val mark = MutableStateFlow("0")
        var fail = false

        override suspend fun page(
            filter: SaleHistoryFilter,
            after: SaleHistoryCursor?,
            limit: Int,
        ): List<SaleHistoryRow> {
            if (fail) error("Falla inventada")
            limits += limit
            return rows.sortedByDescending { it.occurredAt }.take(limit)
        }

        override suspend fun dayTotals(
            filter: SaleHistoryFilter,
            dates: Set<BusinessDate>,
        ): Map<BusinessDate, Money> = emptyMap()

        override fun observeChanges(): Flow<String> = mark

        // Inicio no filtra por método: esta prueba no lo pide.
        override suspend fun methodsWithSales(methodIds: Set<Long>): Set<Long> = methodIds
    }

    private fun row(id: Long, minutesAgo: Long) = SaleHistoryRow(
        id = id,
        occurredAt = Instant.fromEpochMilliseconds(now.toEpochMilliseconds() - minutesAgo * 60_000),
        businessDate = today,
        total = Money(1_000L * id),
        voided = false,
        firstProductName = "Producto $id",
        lineCount = 1,
        methodName = "Efectivo",
        paymentCount = 1,
    )

    private fun TestScope.viewModel(): HomeViewModel {
        val model = HomeViewModel(
            todaySales = { checks ->
                // La fecha se mira con cada emisión; las cifras siguen al doble.
                combine(checks.map { dayChecks += 1 }, sales) { _, today -> today }
            },
            hasProducts = hasProducts,
            history = SaleHistoryReader(history, Clock { now }),
            computation = dispatcher,
            backupOverview = { refreshes -> combine(refreshes, backupOverviews) { _, it -> it } },
            providerLabel = {
                labelReads += 1
                "Nube"
            },
            zones = MutableStateFlow(zone),
        )
        // La pantalla recoge el estado mientras se ve.
        backgroundScope.launch { model.uiState.collect {} }
        backgroundScope.launch { model.backup.collect {} }
        advanceUntilIdle()
        return model
    }

    private fun HomeViewModel.content() = uiState.value as HomeUiState.Content

    @Test
    fun `a day with sales shows its figures and the business date`() = runTest(dispatcher) {
        sales.value = TodaySales.Ready(today, zone, summary(84_000, 3, 24_000))

        val content = viewModel().content()

        assertEquals(today, content.today)
        assertEquals(zone, content.zone)
        assertEquals(TodayHero.Sales(summary(84_000, 3, 24_000)), content.hero)
        assertEquals(false, content.firstUse)
    }

    @Test
    fun `a day without sales waits for the first one`() = runTest(dispatcher) {
        assertEquals(TodayHero.NoSalesYet, viewModel().content().hero)
    }

    @Test
    fun `a return without sales still changes the figures and shows them`() = runTest(dispatcher) {
        sales.value = TodaySales.Ready(today, zone, summary(-2_500, 0, -1_500))

        assertEquals(TodayHero.Sales(summary(-2_500, 0, -1_500)), viewModel().content().hero)
    }

    @Test
    fun `a sum that does not fit and an unknown zone show no value`() = runTest(dispatcher) {
        sales.value = TodaySales.Ready(today, zone, null)
        val model = viewModel()
        assertEquals(TodayHero.Unavailable, model.content().hero)

        sales.value = TodaySales.UnknownZone
        advanceUntilIdle()

        assertEquals(
            HomeUiState.Content(null, null, TodayHero.Unavailable, false, emptyList()),
            model.content(),
        )
    }

    @Test
    fun `without products the first use leads to create one`() = runTest(dispatcher) {
        hasProducts.value = false
        val model = viewModel()
        assertEquals(true, model.content().firstUse)

        hasProducts.value = true
        advanceUntilIdle()

        assertEquals(false, model.content().firstUse)
    }

    @Test
    fun `the last five sales are read again with each new sale`() = runTest(dispatcher) {
        history.rows = (1L..7L).map { row(it, minutesAgo = 100 - it) }
        val model = viewModel()
        assertEquals(listOf(7L, 6L, 5L, 4L, 3L), model.content().recentSales.map { it.id })

        history.rows = history.rows + row(8L, minutesAgo = 0)
        history.mark.value = "1"
        advanceUntilIdle()

        assertEquals(listOf(8L, 7L, 6L, 5L, 4L), model.content().recentSales.map { it.id })
        assertEquals(listOf(5, 5), history.limits)
    }

    @Test
    fun `a read failure shows the error and retrying reads again`() = runTest(dispatcher) {
        history.fail = true
        val model = viewModel()
        assertEquals(HomeUiState.ReadError, model.uiState.value)

        history.fail = false
        model.onAction(HomeAction.Retry)
        advanceUntilIdle()

        assertEquals(TodayHero.NoSalesYet, model.content().hero)
    }

    @Test
    fun `returning to the foreground looks at the date again`() = runTest(dispatcher) {
        val model = viewModel()
        assertEquals(1, dayChecks)

        model.onReturnToForeground()
        advanceUntilIdle()

        assertEquals(2, dayChecks)
    }

    @Test
    fun `navigation actions do not change the state`() = runTest(dispatcher) {
        val model = viewModel()
        val before = model.uiState.value
        listOf(
            HomeAction.OpenSettings,
            HomeAction.OpenBusiness,
            HomeAction.CreateFirstProduct,
            HomeAction.OpenAllSales,
            HomeAction.OpenSale(1L),
        ).forEach(model::onAction)
        advanceUntilIdle()

        assertEquals(before, model.uiState.value)
    }

    @Test
    fun `the backup state comes apart, with the cloud name read once per destination`() =
        runTest(dispatcher) {
            val model = viewModel()
            assertEquals(BackupStatus.UP_TO_DATE, model.backup.value?.overview?.report?.status)
            assertEquals(null, model.backup.value?.providerLabel)
            assertEquals(zone, model.backup.value?.zone)
            assertEquals(0, labelReads)

            backupOverviews.value =
                backupOverview(BackupDestinationKind.DOCUMENT_SLOTS, BackupStatus.HANDED_OFF)
            advanceUntilIdle()
            backupOverviews.value =
                backupOverview(BackupDestinationKind.DOCUMENT_SLOTS, BackupStatus.CHECK_DUE)
            advanceUntilIdle()

            assertEquals("Nube", model.backup.value?.providerLabel)
            assertEquals(BackupStatus.CHECK_DUE, model.backup.value?.overview?.report?.status)
            assertEquals(1, labelReads)
            // Las acciones del respaldo las resuelve la navegación.
            val before = model.uiState.value
            model.onAction(HomeAction.OpenBackup)
            model.onAction(HomeAction.ChooseDestination)
            assertEquals(before, model.uiState.value)
        }
}
