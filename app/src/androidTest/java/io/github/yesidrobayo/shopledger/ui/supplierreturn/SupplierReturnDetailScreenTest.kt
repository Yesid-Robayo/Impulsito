package io.github.yesidrobayo.shopledger.ui.supplierreturn

import android.graphics.Bitmap
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.VoidReason
import io.github.yesidrobayo.shopledger.domain.model.Voiding
import io.github.yesidrobayo.shopledger.domain.model.catalog.MeasureUnit
import io.github.yesidrobayo.shopledger.domain.model.catalog.UnitKind
import io.github.yesidrobayo.shopledger.domain.model.returns.NewPurchaseReturn
import io.github.yesidrobayo.shopledger.domain.model.returns.NewPurchaseReturnLine
import io.github.yesidrobayo.shopledger.domain.model.returns.PurchaseReturn
import io.github.yesidrobayo.shopledger.domain.model.returns.PurchaseReturnLine
import io.github.yesidrobayo.shopledger.domain.model.returns.PurchaseReturnResolution
import io.github.yesidrobayo.shopledger.domain.model.settings.ColorPalette
import io.github.yesidrobayo.shopledger.domain.usecase.returns.SupplierReturnDetail
import io.github.yesidrobayo.shopledger.domain.usecase.returns.SupplierReturnDetailLine
import io.github.yesidrobayo.shopledger.ui.format.LocalAppFormats
import io.github.yesidrobayo.shopledger.ui.format.LocalDiscreetMode
import io.github.yesidrobayo.shopledger.ui.theme.AppTheme
import io.github.yesidrobayo.shopledger.ui.theme.previewFormats
import java.io.File
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * El detalle de una devolución a proveedor: el modo discreto oculta sus montos, también para
 * TalkBack; anular pide confirmar; y, con `captureDir`, las capturas en claro y en oscuro, activa y
 * anulada. Datos y cifras inventados.
 */
@RunWith(AndroidJUnit4::class)
class SupplierReturnDetailScreenTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val at = Instant.parse("2026-11-02T15:00:00Z")
    private val unit = MeasureUnit(1L, "UNIT", null, null, UnitKind.COUNT, false, null)
    private val purchaseReturn = PurchaseReturn(
        id = 12L,
        content = NewPurchaseReturn(
            purchaseId = 318L,
            supplierId = 5L,
            resolution = PurchaseReturnResolution.SUPPLIER_CREDIT,
            total = Money(TOTAL),
            moneyAccountId = null,
            note = "Llegó golpeada",
            occurredAt = at,
            businessDate = BusinessDate(LocalDate(2026, 11, 2)),
            createdAt = at,
        ),
        voiding = null,
    )
    private val ready = SupplierReturnDetailUiState.Ready(
        SupplierReturnDetail(
            purchaseReturn = purchaseReturn,
            supplierName = "Molino inventado",
            lines = listOf(
                SupplierReturnDetailLine(
                    PurchaseReturnLine(
                        30L,
                        12L,
                        NewPurchaseReturnLine(
                            40L,
                            1L,
                            null,
                            QuantityMilli(2_000L),
                            Money(COST),
                            Money(TOTAL),
                        ),
                    ),
                    "Harina inventada",
                    unit,
                ),
            ),
        ),
    )

    @Test
    fun discreetModeHidesTheAmountsAndVoidingAsksFirst() {
        val actions = mutableListOf<SupplierReturnDetailAction>()
        show(ready, discreet = false) { actions += it }
        for (figure in FIGURES) {
            assertTrue(
                figure,
                compose.onAllNodes(
                    hasText(figure, substring = true) or
                        hasContentDescription(figure, substring = true),
                ).fetchSemanticsNodes().isNotEmpty(),
            )
        }
        compose.onNodeWithText(
            instrumentation.targetContext.getString(R.string.supplier_return_void_action),
        ).performClick()
        assertEquals(
            listOf<SupplierReturnDetailAction>(SupplierReturnDetailAction.AskVoid),
            actions,
        )

        show(ready, discreet = true)
        for (figure in FIGURES) {
            val nodes = compose.onAllNodes(
                hasText(
                    figure,
                    substring = true,
                ) or hasContentDescription(figure, substring = true),
                useUnmergedTree = true,
            )
            assertEquals(figure, 0, nodes.fetchSemanticsNodes().size)
        }
    }

    @Test
    fun drawsTheDetailInBothModes() {
        val name = InstrumentationRegistry.getArguments().getString("captureDir")
        assumeTrue("Sin captureDir no hay capturas", name != null)
        val dir = File(instrumentation.targetContext.cacheDir, requireNotNull(name)).apply {
            mkdirs()
        }
        val voided = ready.copy(
            detail = ready.detail.copy(
                purchaseReturn = purchaseReturn.copy(
                    voiding = Voiding(at, VoidReason.ENTRY_ERROR, null),
                ),
            ),
        )
        val scenes = listOf(
            "active" to ready,
            "confirm" to ready.copy(confirmVoid = true),
            "voided" to voided,
        )
        for (dark in listOf(false, true)) {
            for ((scene, state) in scenes) {
                show(state, discreet = false, dark = dark)
                SystemClock.sleep(SETTLE_MILLIS)
                val mode = if (dark) "dark" else "light"
                save(File(dir, "$mode-supplier-return-detail-$scene.png"))
            }
        }
    }

    private fun show(
        state: SupplierReturnDetailUiState,
        discreet: Boolean,
        dark: Boolean = false,
        onAction: (SupplierReturnDetailAction) -> Unit = {},
    ) {
        compose.runOnUiThread {
            compose.activity.setContent {
                AppTheme(palette = ColorPalette.INDIGO_MUSTARD, darkTheme = dark) {
                    CompositionLocalProvider(
                        LocalAppFormats provides previewFormats(),
                        LocalDiscreetMode provides discreet,
                    ) { SupplierReturnDetailScreen(state, onAction) }
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
        const val TOTAL = 97_531L
        const val COST = 86_429L
        val FIGURES = listOf("97", "86")
        const val SETTLE_MILLIS = 900L
    }
}
