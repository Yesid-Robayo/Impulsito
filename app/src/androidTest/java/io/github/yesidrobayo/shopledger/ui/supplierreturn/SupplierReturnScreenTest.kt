package io.github.yesidrobayo.shopledger.ui.supplierreturn

import android.graphics.Bitmap
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.SemanticsNodeInteractionCollection
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.catalog.MeasureUnit
import io.github.yesidrobayo.shopledger.domain.model.catalog.UnitKind
import io.github.yesidrobayo.shopledger.domain.model.returns.PurchaseReturnResolution
import io.github.yesidrobayo.shopledger.domain.model.settings.ColorPalette
import io.github.yesidrobayo.shopledger.ui.format.LocalAppFormats
import io.github.yesidrobayo.shopledger.ui.format.LocalDiscreetMode
import io.github.yesidrobayo.shopledger.ui.theme.AppTheme
import io.github.yesidrobayo.shopledger.ui.theme.previewFormats
import java.io.File
import kotlinx.datetime.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * La devolución a proveedor en pantalla: el modo discreto oculta el costo, el efecto en el costo,
 * el saldo con el proveedor y el total, también para TalkBack; cada estado se dibuja y sus acciones
 * llegan; y, con `captureDir`, las capturas de la puerta de calidad en claro y en oscuro. Datos y
 * cifras inventados, lo bastante raros para no confundirse con otra cosa.
 */
@RunWith(AndroidJUnit4::class)
class SupplierReturnScreenTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val unit = MeasureUnit(1L, "UNIT", null, null, UnitKind.COUNT, false, null)

    private val withLines = SupplierReturnUiState(
        loading = false,
        withPurchase = true,
        supplierName = "Molino inventado",
        supplierLocked = true,
        origin = ReturnOriginUi(318L, BusinessDate(LocalDate(2026, 10, 28))),
        lines = listOf(
            SupplierReturnLineUi(
                key = 1L,
                name = "Harina inventada",
                unit = unit,
                quantityText = "2",
                unitCost = Money(COST),
                lineTotal = Money(LINE),
                costBefore = Money(BEFORE),
                costAfter = Money(AFTER),
            ),
            SupplierReturnLineUi(
                key = 2L,
                name = "Sal inventada",
                unit = unit,
                quantityText = "5",
                error = SupplierReturnLineError.AboveRemaining(QuantityMilli(3_000L)),
            ),
            SupplierReturnLineUi(
                key = 3L,
                name = "Aceite inventado",
                unit = unit,
                quantityText = "4",
                unitCost = Money(COST),
                lineTotal = Money(LINE),
                costBefore = Money(BEFORE),
                costAfter = Money(BEFORE),
                leftAfter = QuantityMilli(-2_000L),
            ),
        ),
        resolution = PurchaseReturnResolution.SUPPLIER_CREDIT,
        balanceAfter = Money(BALANCE),
        total = Money(TOTAL),
    )

    @Test
    fun discreetModeHidesEveryFigureAlsoForTalkBack() {
        // La lista es perezosa: las líneas se miran arriba, y el saldo con el proveedor al final.
        show(withLines, discreet = false)
        for (figure in LINE_FIGURES) {
            assertTrue(figure, figures(figure).fetchSemanticsNodes().isNotEmpty())
        }
        scrollToEnd()
        assertTrue(BALANCE_TEXT, figures(BALANCE_TEXT).fetchSemanticsNodes().isNotEmpty())

        show(withLines, discreet = true)
        for (figure in LINE_FIGURES) assertHidden(figure)
        scrollToEnd()
        assertHidden(BALANCE_TEXT)
    }

    @Test
    fun eachStateDrawsAndItsActionsArrive() {
        val actions = mutableListOf<SupplierReturnAction>()
        val context = instrumentation.targetContext

        show(SupplierReturnUiState(loading = false), discreet = false) { actions += it }
        compose.onNodeWithText(
            context.getString(R.string.supplier_return_empty_title),
        ).assertExists()
        compose.onNodeWithText(context.getString(R.string.supplier_return_add_product_action))
            .performClick()
        compose.onNodeWithText(context.getString(R.string.supplier_return_register_action))
            .performClick()

        show(withLines, discreet = false) { actions += it }
        // Con letra grande o a 360 dp los avisos no se componen: se baja a cada uno.
        val notices =
            listOf(R.string.supplier_return_error_max, R.string.supplier_return_warning_negative)
        for (notice in notices) {
            val text = fixedPart(notice)
            compose.onNode(hasScrollToNodeAction())
                .performScrollToNode(hasText(text, substring = true))
            compose.onNodeWithText(text, substring = true).assertExists()
        }
        val refund = context.getString(R.string.supplier_return_resolution_cash_refund)
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(refund))
        compose.onNodeWithText(refund).performClick()

        show(
            withLines.copy(
                creditNeedsSupplier = true,
                supplierName = null,
                supplierLocked = false,
                notice = SupplierReturnNotice.RegisterFailed(outOfSpace = false),
            ),
            discreet = false,
        ) { actions += it }
        // La lista conserva el desplazamiento del paso anterior: el aviso queda arriba.
        val noSupplier = context.getString(R.string.supplier_return_error_no_supplier)
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(noSupplier))
        compose.onNodeWithText(noSupplier).assertExists()
        compose.onNodeWithText(context.getString(R.string.action_retry)).performClick()

        assertEquals(
            listOf(
                SupplierReturnAction.OpenProducts,
                SupplierReturnAction.Register,
                SupplierReturnAction.ResolutionChange(PurchaseReturnResolution.CASH_REFUND),
                SupplierReturnAction.Retry,
            ),
            actions,
        )
    }

    @Test
    fun drawsTheReturnInBothModes() {
        val name = InstrumentationRegistry.getArguments().getString("captureDir")
        assumeTrue("Sin captureDir no hay capturas", name != null)
        val dir = File(instrumentation.targetContext.cacheDir, requireNotNull(name)).apply {
            mkdirs()
        }
        val scenes = listOf(
            "lines" to withLines,
            "refund" to withLines.copy(resolution = PurchaseReturnResolution.CASH_REFUND),
            "empty" to SupplierReturnUiState(loading = false),
            "failed" to withLines.copy(
                creditNeedsSupplier = true,
                supplierName = null,
                supplierLocked = false,
                notice = SupplierReturnNotice.RegisterFailed(outOfSpace = false),
            ),
        )
        for (dark in listOf(false, true)) {
            for (discreet in listOf(false, true)) {
                for ((scene, state) in scenes) {
                    if (discreet && scene != "lines") continue
                    show(state, discreet, dark) {}
                    SystemClock.sleep(SETTLE_MILLIS)
                    val mode = if (dark) "dark" else "light"
                    val suffix = if (discreet) "-discreet" else ""
                    save(File(dir, "$mode-supplier-return-$scene$suffix.png"))
                }
            }
        }
    }

    private fun scrollToEnd() {
        val note = instrumentation.targetContext.getString(R.string.supplier_return_note_label)
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(note))
        compose.waitForIdle()
    }

    private fun assertHidden(figure: String) {
        assertEquals(figure, 0, figures(figure).fetchSemanticsNodes().size)
        assertEquals(figure, 0, figures(figure, unmerged = true).fetchSemanticsNodes().size)
    }

    /** El trozo fijo más largo de un texto con una cantidad, en el idioma del teléfono. */
    private fun fixedPart(id: Int): String = instrumentation.targetContext.resources.getString(id)
        .split("%1\$s").maxBy { it.length }.trim()

    /** Los nodos que dicen [figure], en el texto o en lo que lee TalkBack. */
    private fun figures(
        figure: String,
        unmerged: Boolean = false,
    ): SemanticsNodeInteractionCollection = compose.onAllNodes(
        hasText(figure, substring = true) or hasContentDescription(figure, substring = true),
        useUnmergedTree = unmerged,
    )

    private fun show(
        state: SupplierReturnUiState,
        discreet: Boolean,
        dark: Boolean = false,
        onAction: (SupplierReturnAction) -> Unit = {},
    ) {
        compose.runOnUiThread {
            compose.activity.setContent {
                AppTheme(palette = ColorPalette.INDIGO_MUSTARD, darkTheme = dark) {
                    CompositionLocalProvider(
                        LocalAppFormats provides previewFormats(),
                        LocalDiscreetMode provides discreet,
                    ) { SupplierReturnScreen(state, onAction) }
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
        const val COST = 98_761L
        const val LINE = 97_531L
        const val BEFORE = 86_429L
        const val AFTER = 75_319L
        const val BALANCE = 64_207L
        const val TOTAL = 53_197L

        /** Las primeras cifras de cada monto a la vista arriba, con el total que va siempre abajo. */
        val LINE_FIGURES = listOf("98", "97", "86", "75", "53")

        /** Las primeras cifras del saldo con el proveedor, que va al final de la lista. */
        const val BALANCE_TEXT = "64"
        const val SETTLE_MILLIS = 900L
    }
}
