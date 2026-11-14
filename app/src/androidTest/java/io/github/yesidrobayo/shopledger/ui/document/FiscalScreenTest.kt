package io.github.yesidrobayo.shopledger.ui.document

import android.graphics.Bitmap
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.model.document.FiscalMarkStatus
import io.github.yesidrobayo.shopledger.domain.model.settings.ColorPalette
import io.github.yesidrobayo.shopledger.ui.format.LocalAppFormats
import io.github.yesidrobayo.shopledger.ui.format.LocalDiscreetMode
import io.github.yesidrobayo.shopledger.ui.theme.AppTheme
import io.github.yesidrobayo.shopledger.ui.theme.previewFormats
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Los pendientes de facturar en el dispositivo: el aviso fijo de que la app no emite nada, las
 * marcas, la hoja de una venta, elegir varias con la acción `Seleccionar` de TalkBack, la barra de
 * abajo con `Abrir enlace` solo si hay enlace, y los vacíos. Con `captureDir`, las capturas de la
 * puerta de calidad en claro y en oscuro. Datos inventados.
 */
@RunWith(AndroidJUnit4::class)
class FiscalScreenTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val resources = instrumentation.targetContext.resources
    private val actions = mutableListOf<FiscalAction>()

    private fun text(id: Int) = resources.getString(id)

    @Test
    fun theListSaysItIssuesNothingAndOpensTheSheetOfASale() {
        show { FiscalScreen(previewFiscal(), { actions += it }) }

        val app = text(R.string.app_name)
        compose.onNodeWithText(resources.getString(R.string.fiscal_disclaimer, app))
            .assertIsDisplayed()
        // La fila es un solo foco: su etiqueta y su referencia van en lo que dice TalkBack.
        compose.onNode(hasContentDescription(text(R.string.fiscal_return_tag), substring = true))
            .assertIsDisplayed()
        val reference = resources.getString(R.string.fiscal_reference_row, "A-1043")
        compose.onNode(hasContentDescription(reference, substring = true)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.fiscal_filter_recorded)).performClick()
        compose.onNodeWithText(text(R.string.fiscal_share_summary_action)).performClick()
        compose.onNodeWithText(text(R.string.fiscal_open_link_action)).performClick()
        assertTrue(FiscalAction.FilterChange(FiscalMarkStatus.RECORDED) in actions)
        assertTrue(FiscalAction.ShareSummary in actions)
        assertTrue(FiscalAction.OpenLink in actions)
    }

    @Test
    fun withoutLinkThereIsNoOpenLink() {
        show { FiscalScreen(previewFiscal().copy(portalUrl = null), { actions += it }) }

        compose.onAllNodesWithText(text(R.string.fiscal_open_link_action)).assertCountEquals(0)
        compose.onNodeWithText(text(R.string.fiscal_share_summary_action)).assertIsDisplayed()
    }

    @Test
    fun selectIsACustomActionAndTheBarMarksTheSelection() {
        show { FiscalScreen(previewFiscal(), { actions += it }) }

        val select = text(R.string.a11y_doc_select_action)
        compose.onAllNodes(hasCustomAction(select)).onFirst().performCustomAction(select)
        assertTrue(FiscalAction.ToggleSelection(1_204L) in actions)

        show { FiscalScreen(previewFiscal().copy(selection = setOf(1_204L)), { actions += it }) }
        val markAll = resources.getQuantityString(
            R.plurals.fiscal_mark_many_recorded_action,
            1,
            1,
        )
        compose.onNodeWithText(markAll).performClick()
        assertTrue(FiscalAction.MarkSelection(FiscalMarkStatus.RECORDED) in actions)
    }

    @Test
    fun aLongPressStartsTheSelection() {
        show { FiscalScreen(previewFiscal(), { actions += it }) }

        val select = text(R.string.a11y_doc_select_action)
        // La tercera fila es la venta 1.187.
        compose.onAllNodes(hasCustomAction(select))[2].performTouchInput { longClick() }
        assertTrue(FiscalAction.ToggleSelection(1_187L) in actions)
    }

    @Test
    fun theSheetMarksWithTheReference() {
        val sale = previewFiscalSales()[1]
        show {
            FiscalScreen(
                previewFiscal().copy(sheet = FiscalMarkSheet(sale.saleId, sale, "B-7")),
                { actions += it },
            )
        }

        compose.onNodeWithText(text(R.string.fiscal_return_help)).assertIsDisplayed()
        // En inglés, el chip de la marca y el botón dicen lo mismo: se toca el botón.
        compose.onNode(hasText(text(R.string.fiscal_mark_recorded_action)) and isButton())
            .performClick()
        assertTrue(FiscalAction.MarkSale(FiscalMarkStatus.RECORDED) in actions)
    }

    @Test
    fun theFirstUseSaysWhatTheListIsFor() {
        show {
            FiscalScreen(previewFiscal(sales = emptyList(), hasMarks = false), { actions += it })
        }

        compose.onNodeWithText(text(R.string.fiscal_empty_title)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.fiscal_first_use_body)).assertIsDisplayed()
    }

    @Test
    fun drawsPendingInvoicingInBothModes() {
        val name = InstrumentationRegistry.getArguments().getString("captureDir")
        assumeTrue("Sin captureDir no hay capturas", name != null)
        val dir = File(instrumentation.targetContext.cacheDir, requireNotNull(name)).apply {
            mkdirs()
        }
        val sale = previewFiscalSales()[1]
        for (dark in listOf(false, true)) {
            val mode = if (dark) "dark" else "light"
            show(dark) { FiscalScreen(previewFiscal(), {}) }
            SystemClock.sleep(SETTLE_MILLIS)
            save(File(dir, "$mode-fiscal-list.png"))
            show(dark) { FiscalScreen(previewFiscal().copy(selection = setOf(1_204L)), {}) }
            SystemClock.sleep(SETTLE_MILLIS)
            save(File(dir, "$mode-fiscal-selecting.png"))
            show(dark) {
                FiscalScreen(
                    previewFiscal().copy(sheet = FiscalMarkSheet(sale.saleId, sale, "B-7")),
                    {},
                )
            }
            SystemClock.sleep(SETTLE_MILLIS)
            save(File(dir, "$mode-fiscal-sheet.png"))
            show(dark) {
                FiscalScreen(previewFiscal().copy(linkSheet = FiscalLinkSheet("https://")), {})
            }
            SystemClock.sleep(SETTLE_MILLIS)
            save(File(dir, "$mode-fiscal-link.png"))
        }
    }

    private fun hasCustomAction(label: String) = SemanticsMatcher("acción $label") { node ->
        node.config.getOrElseNullable(SemanticsActions.CustomActions) { null }
            ?.any { it.label == label } == true
    }

    private fun isButton() = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button)

    private fun SemanticsNodeInteraction.performCustomAction(label: String) {
        val actions = fetchSemanticsNode().config[SemanticsActions.CustomActions]
        val action = actions.first { it.label == label }
        compose.runOnUiThread { action.action() }
        compose.waitForIdle()
    }

    private fun show(dark: Boolean = false, content: @Composable () -> Unit) {
        compose.runOnUiThread {
            compose.activity.setContent {
                AppTheme(palette = ColorPalette.INDIGO_MUSTARD, darkTheme = dark) {
                    CompositionLocalProvider(
                        LocalAppFormats provides previewFormats(),
                        LocalDiscreetMode provides false,
                    ) { content() }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun save(file: File) {
        val bitmap =
            requireNotNull(instrumentation.uiAutomation.takeScreenshot()) { "Sin captura" }
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    private companion object {
        const val SETTLE_MILLIS = 900L
    }
}
