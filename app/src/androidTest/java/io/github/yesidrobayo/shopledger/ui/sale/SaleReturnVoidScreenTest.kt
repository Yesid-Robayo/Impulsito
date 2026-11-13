package io.github.yesidrobayo.shopledger.ui.sale

import android.graphics.Bitmap
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.VoidReason
import io.github.yesidrobayo.shopledger.domain.model.returns.ReturnResolution
import io.github.yesidrobayo.shopledger.domain.model.settings.ColorPalette
import io.github.yesidrobayo.shopledger.ui.format.LocalAppFormats
import io.github.yesidrobayo.shopledger.ui.format.LocalDiscreetMode
import io.github.yesidrobayo.shopledger.ui.product.SAMPLE_UNIT
import io.github.yesidrobayo.shopledger.ui.theme.AppTheme
import io.github.yesidrobayo.shopledger.ui.theme.previewFormats
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Anular una devolución desde su hoja en el detalle de la venta: la hoja la ofrece si está
 * activa, el paso de anular manda el motivo y confirma, una falla lo dice sin cerrar, un cambio
 * con su venta nueva activa explica por qué no se ofrece, y la anulación se avisa. Con
 * `captureDir`, las capturas de la puerta de calidad en claro y en oscuro. Datos y cifras
 * inventados.
 */
@RunWith(AndroidJUnit4::class)
class SaleReturnVoidScreenTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val resources = instrumentation.targetContext.resources
    private val actions = mutableListOf<SaleDetailAction>()

    private fun text(id: Int) = resources.getString(id)

    private val row = SaleReturnRow(
        returnId = 41L,
        date = DETAIL_DATE.plusDays(1),
        resolution = ReturnResolution.REFUND,
        total = Money(9_500L),
        voided = false,
        note = "Talla equivocada",
    )

    private val lines = listOf(
        SaleReturnSheetLine(
            "Jabón de avena",
            QuantityMilli(1_000L),
            SAMPLE_UNIT,
            Money(3_500L),
            true,
        ),
        SaleReturnSheetLine(LONG_NAME, QuantityMilli(1_000L), SAMPLE_UNIT, Money(6_000L), false),
    )

    private val open = SaleReturnSheet(row, lines)
    private val exchange = SaleReturnSheet(
        row.copy(resolution = ReturnResolution.EXCHANGE, replacementSaleId = REPLACEMENT),
        lines,
        replacementActive = true,
    )

    /** El detalle de la venta con la devolución [sheet] abierta encima. */
    private fun detail(sheet: SaleReturnSheet?, returnVoided: Boolean = false) =
        SaleDetailUiState.Ready(
            saleDetailView().copy(
                hasActiveReturns = !(sheet?.row?.voided ?: false),
                canReturn = true,
                returns = listOfNotNull(sheet?.row),
            ),
            returnSheet = sheet,
            returnVoided = returnVoided,
        )

    @Test
    fun anActiveReturnOffersToVoidFromItsSheet() {
        show(detail(open))

        compose.onNodeWithText(text(R.string.sale_return_void_action)).performClick()

        assertEquals(listOf<SaleDetailAction>(SaleDetailAction.OpenReturnVoid), actions)
    }

    @Test
    fun theVoidStepSendsTheReasonAndConfirms() {
        show(detail(open.copy(void = VoidSheet())))

        compose.onNodeWithText(text(R.string.sale_return_void_confirm_title)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.sale_return_void_confirm_body)).assertIsDisplayed()
        // Devolver lo devuelto no es un motivo: anular la devolución lo deshace.
        val returns = compose.onAllNodesWithText(text(R.string.void_reason_customer_return))
        assertEquals(0, returns.fetchSemanticsNodes().size)
        compose.onNodeWithText(text(R.string.void_reason_other)).performClick()
        compose.onNodeWithText(text(R.string.sale_return_void_action)).performClick()

        assertEquals(
            listOf(
                SaleDetailAction.ReturnVoidReasonChange(VoidReason.OTHER),
                SaleDetailAction.ConfirmReturnVoid,
            ),
            actions,
        )
    }

    @Test
    fun aFailedVoidSaysTheReturnIsStillActive() {
        show(detail(open.copy(void = VoidSheet(), voidStatus = VoidStatus.FAILED)))

        compose.onNodeWithText(text(R.string.error_sale_return_void)).performScrollTo()
            .assertIsDisplayed()
        compose.onNodeWithText(text(R.string.sale_return_void_action)).assertIsDisplayed()
    }

    @Test
    fun anExchangeWithItsNewSaleActiveExplainsInsteadOfOffering() {
        show(detail(exchange))

        val why = resources.getString(R.string.sale_return_void_exchange_locked, "$REPLACEMENT")
        compose.onNodeWithText(why).performScrollTo().assertIsDisplayed()
        val offers = compose.onAllNodesWithText(text(R.string.sale_return_void_action))
        assertEquals(0, offers.fetchSemanticsNodes().size)
    }

    @Test
    fun aVoidedReturnIsNotOfferedAgain() {
        show(detail(open.copy(row = row.copy(voided = true))))

        val offers = compose.onAllNodesWithText(text(R.string.sale_return_void_action))
        assertEquals(0, offers.fetchSemanticsNodes().size)
    }

    @Test
    fun theVoidIsAnnouncedOnce() {
        show(detail(sheet = null, returnVoided = true))

        compose.waitUntil(TIMEOUT_MILLIS) {
            compose.onAllNodesWithText(text(R.string.sale_return_voided))
                .fetchSemanticsNodes().isNotEmpty()
        }
        assertTrue(SaleDetailAction.ReturnVoidedShown in actions)
    }

    @Test
    fun drawsTheReturnVoidInBothModes() {
        val name = InstrumentationRegistry.getArguments().getString("captureDir")
        assumeTrue("Sin captureDir no hay capturas", name != null)
        val dir = File(instrumentation.targetContext.cacheDir, requireNotNull(name)).apply {
            mkdirs()
        }
        val scenes = listOf(
            "sheet" to detail(open),
            "void" to detail(open.copy(void = VoidSheet(note = "Se registró dos veces"))),
            "void-failed" to detail(open.copy(void = VoidSheet(), voidStatus = VoidStatus.FAILED)),
            "exchange" to detail(exchange),
            "voided" to detail(open.copy(row = row.copy(voided = true))),
        )
        for (dark in listOf(false, true)) {
            val mode = if (dark) "dark" else "light"
            for ((scene, state) in scenes) {
                show(state, dark)
                SystemClock.sleep(SETTLE_MILLIS)
                save(File(dir, "$mode-sale-return-$scene.png"))
            }
        }
    }

    private fun show(state: SaleDetailUiState, dark: Boolean = false) {
        compose.runOnUiThread {
            compose.activity.setContent {
                AppTheme(palette = ColorPalette.INDIGO_MUSTARD, darkTheme = dark) {
                    CompositionLocalProvider(
                        LocalAppFormats provides previewFormats(),
                        LocalDiscreetMode provides false,
                    ) { SaleDetailScreen(state, { actions += it }) }
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
        const val REPLACEMENT = 9L
        const val SETTLE_MILLIS = 900L
        const val TIMEOUT_MILLIS = 5_000L
        const val LONG_NAME =
            "Café molido de altura en bolsa resellable con válvula, tostión media, 500 gramos"
    }
}
