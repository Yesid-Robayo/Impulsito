package io.github.yesidrobayo.shopledger.ui.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.export.CsvDateRange
import io.github.yesidrobayo.shopledger.domain.model.export.CsvExportProgress
import io.github.yesidrobayo.shopledger.ui.theme.PreviewTheme
import kotlinx.datetime.DayOfWeek
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * En el teléfono: `Exportar un rango de fechas`. La fila solo aparece con la semana del negocio y sin
 * otra exportación en curso; la hoja abre del primer día del mes a hoy y pide exportar ese rango.
 */
@RunWith(AndroidJUnit4::class)
class CsvExportRangeTest {
    @get:Rule
    val compose = createComposeRule()

    private val resources = InstrumentationRegistry.getInstrumentation().targetContext.resources

    private fun text(id: Int) = resources.getString(id)

    private val today = requireNotNull(BusinessDate.parse("2026-11-12"))
    private val calendar = CsvRangeCalendar(today, DayOfWeek.MONDAY)

    @Test
    fun theRowShowsOnlyWithTheBusinessWeekAndWhileIdle() {
        var opened = 0
        val states =
            listOf(
                CsvExportUiState(),
                CsvExportUiState(calendar = calendar, progress = CsvExportProgress(1, 9)),
                CsvExportUiState(calendar = calendar),
            )
        var shown by mutableStateOf(states[0])
        compose.setContent {
            PreviewTheme { CsvExportRangeRow(shown, onOpen = { opened += 1 }, onObscured = {}) }
        }
        compose.onNodeWithText(text(R.string.settings_export_range)).assertDoesNotExist()

        shown = states[1]
        compose.onNodeWithText(text(R.string.settings_export_range)).assertDoesNotExist()

        shown = states[2]
        compose.onNodeWithText(text(R.string.settings_export_range)).performClick()
        assertEquals(1, opened)
    }

    @Test
    fun theSheetExportsFromTheFirstOfTheMonthToToday() {
        val exported = mutableListOf<CsvDateRange>()
        compose.setContent {
            PreviewTheme {
                CsvExportRangeSheet(calendar, onExport = { exported += it }, onDismiss = {})
            }
        }

        compose.onNodeWithText(text(R.string.settings_export_range_help)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.settings_export_range_action))
            .assertIsEnabled()
            .performClick()

        assertTrue(exported.isNotEmpty())
        assertEquals(BusinessDate.parse("2026-11-01"), exported.single().from)
        assertEquals(today, exported.single().to)
    }
}
