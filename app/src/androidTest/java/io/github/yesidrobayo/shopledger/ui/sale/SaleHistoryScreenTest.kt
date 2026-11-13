package io.github.yesidrobayo.shopledger.ui.sale

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.model.BusinessTimeZone
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleHistoryRow
import io.github.yesidrobayo.shopledger.ui.theme.PreviewTheme
import kotlin.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * El historial en el teléfono: tocar una venta la abre, el período y los filtros abren sus hojas
 * y avisan su cambio, y los estados vacío con filtros y de error ofrecen su salida. Ventas y
 * nombres inventados.
 */
@RunWith(AndroidJUnit4::class)
class SaleHistoryScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val resources = InstrumentationRegistry.getInstrumentation().targetContext.resources
    private val actions = mutableListOf<SaleHistoryAction>()

    private fun text(id: Int) = resources.getString(id)

    private var state by mutableStateOf(SaleHistoryUiState())
    private var shown = false

    // El contenido se pone una sola vez: los cambios de estado de una misma prueba lo recomponen.
    private fun show(
        content: HistoryContent,
        filters: HistoryFilters = HistoryFilters(),
        periodSheet: Boolean = false,
        filterSheet: Boolean = false,
    ) {
        state = SaleHistoryUiState(
            filters = filters,
            content = content,
            today = DETAIL_DATE,
            zone = BusinessTimeZone.of("Etc/GMT+5"),
            periodSheet = periodSheet,
            filterSheet = filterSheet,
        )
        if (!shown) {
            shown = true
            compose.setContent {
                PreviewTheme { SaleHistoryScreen(state, { actions += it }) }
            }
        }
        compose.waitForIdle()
    }

    private fun row(id: Long, product: String) = SaleHistoryRow(
        id = id,
        occurredAt = Instant.fromEpochMilliseconds(1_793_631_600_000L - id * 60_000L),
        businessDate = DETAIL_DATE,
        total = Money(4_200L),
        voided = false,
        firstProductName = product,
        lineCount = 1,
        methodName = "Efectivo",
        paymentCount = 1,
    )

    private val days = HistoryContent.Days(
        listOf(
            HistoryDay(
                DETAIL_DATE,
                Money(8_400L),
                listOf(row(2L, "Arroz 1 kg"), row(1L, "Huevos x 30")),
            ),
        ),
        endReached = true,
    )

    @Test
    fun touchingASaleOpensIt() {
        show(days)

        compose.onNodeWithText("Huevos x 30").performClick()

        assertEquals(listOf<SaleHistoryAction>(SaleHistoryAction.OpenSale(1L)), actions)
    }

    @Test
    fun thePeriodAndTheFiltersOpenTheirSheets() {
        show(days)

        compose.onNodeWithText(text(R.string.period_all)).performClick()
        compose.onNodeWithText(text(R.string.filter_more)).performClick()

        assertEquals(
            listOf(SaleHistoryAction.OpenPeriods, SaleHistoryAction.OpenFilters),
            actions,
        )
    }

    @Test
    fun theSheetsReportTheirChoices() {
        show(days, periodSheet = true)
        compose.onNodeWithText(text(R.string.period_day)).performClick()
        assertEquals(
            listOf<SaleHistoryAction>(SaleHistoryAction.PeriodKindChange(HistoryPeriodKind.DAY)),
            actions,
        )

        actions.clear()
        show(days, filterSheet = true)
        compose.onNodeWithText(text(R.string.filter_only_voided)).performClick()
        assertEquals(listOf<SaleHistoryAction>(SaleHistoryAction.ToggleVoided), actions)
    }

    @Test
    fun withOneFilterTheButtonSaysWhichAndWithMoreHowMany() {
        show(days, filters = HistoryFilters(onlyVoided = true))
        compose.onNodeWithText(text(R.string.filter_voided)).assertIsDisplayed()

        show(days, filters = HistoryFilters(onlyVoided = true, methodId = 1L))
        compose.onNodeWithText(resources.getString(R.string.filter_more_count, 2))
            .assertIsDisplayed()
    }

    @Test
    fun withoutResultsTheFiltersCanBeCleared() {
        show(HistoryContent.EmptyFiltered)

        compose.onNodeWithText(text(R.string.empty_sales_filtered_title)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.action_clear_filters)).performClick()

        assertEquals(listOf<SaleHistoryAction>(SaleHistoryAction.ClearFilters), actions)
    }

    @Test
    fun aReadErrorCanBeRetried() {
        show(HistoryContent.ReadError)

        compose.onNodeWithText(text(R.string.action_retry)).performClick()

        assertEquals(listOf<SaleHistoryAction>(SaleHistoryAction.Retry), actions)
    }
}
