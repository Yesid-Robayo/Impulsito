package io.github.yesidrobayo.shopledger.ui.purchase

import android.graphics.Bitmap
import android.graphics.Color.TRANSPARENT
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.catalog.MeasureUnit
import io.github.yesidrobayo.shopledger.domain.model.catalog.UnitKind
import io.github.yesidrobayo.shopledger.domain.model.settings.ColorPalette
import io.github.yesidrobayo.shopledger.ui.format.LocalAppFormats
import io.github.yesidrobayo.shopledger.ui.format.LocalDiscreetMode
import io.github.yesidrobayo.shopledger.ui.theme.AppTheme
import io.github.yesidrobayo.shopledger.ui.theme.previewFormats
import java.io.File
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Capturas de la compra nueva para la puerta de calidad visual: con líneas, con un costo muy
 * distinto, vacía, con una falla al registrar y el resumen tras registrar, en claro y en oscuro. La
 * letra y la ventana son las del sistema. Solo corre con `captureDir`. Datos inventados.
 */
@RunWith(AndroidJUnit4::class)
class NewPurchaseGalleryTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val captureDir: File? =
        InstrumentationRegistry.getArguments().getString("captureDir")?.let { name ->
            File(instrumentation.targetContext.cacheDir, name).apply { mkdirs() }
        }

    private val unit = MeasureUnit(1L, "UNIT", null, null, UnitKind.COUNT, false, null)
    private val today = BusinessDate(LocalDate(2026, 11, 2))

    private val lines = NewPurchaseUiState(
        loading = false,
        supplierName = "Distribuidora inventada",
        today = today,
        weekStartDay = DayOfWeek.MONDAY,
        reference = "F-0042",
        lines = listOf(
            PurchaseLineUi(
                key = 1L,
                productId = 1L,
                name = "Jabón de avena",
                unit = unit,
                quantityText = "24",
                amountText = "417",
                byTotal = false,
                lineTotal = Money(10_008L),
                derived = Money(10_008L),
                costBefore = Money(380L),
                costAfter = Money(401L),
            ),
            PurchaseLineUi(
                key = 2L,
                productId = 2L,
                name = "Aceite 900 ml",
                unit = unit,
                quantityText = "12",
                amountText = "54000",
                byTotal = true,
                lineTotal = Money(54_000L),
                derived = Money(4_500L),
                costBefore = Money(1_200L),
                costAfter = Money(4_500L),
                costDiffersFrom = Money(1_200L),
            ),
        ),
        total = Money(64_008L),
    )

    @Test
    fun drawsTheNewPurchaseInBothModes() {
        val dir = captureDir
        assumeTrue("Sin captureDir no hay capturas", dir != null)
        val scenes = listOf(
            "lines" to lines,
            "empty" to
                NewPurchaseUiState(loading = false, today = today, weekStartDay = DayOfWeek.MONDAY),
            "failed" to lines.copy(notice = PurchaseNotice.RegisterFailed(outOfSpace = false)),
            "missing" to lines.copy(
                lines = lines.lines.map {
                    it.copy(
                        quantityText = "",
                        quantityError = PurchaseFieldError.REQUIRED,
                        lineTotal = null,
                        derived = null,
                        costBefore = null,
                        costAfter = null,
                        costDiffersFrom = null,
                    )
                },
                total = null,
            ),
            "registered" to NewPurchaseUiState(
                loading = false,
                registered = PurchaseSummary(
                    purchaseId = 1L,
                    total = Money(64_008L),
                    costs = listOf(
                        CostChangeUi(1L, "Jabón de avena", Money(380L), Money(401L)),
                        CostChangeUi(2L, "Aceite 900 ml", Money(1_200L), Money(4_500L)),
                    ),
                ),
            ),
        )
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            for (dark in listOf(false, true)) {
                for ((name, state) in scenes) {
                    scenario.onActivity { activity ->
                        val bars = SystemBarStyle.auto(TRANSPARENT, TRANSPARENT) { dark }
                        activity.enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
                        activity.setContent {
                            AppTheme(palette = ColorPalette.INDIGO_MUSTARD, darkTheme = dark) {
                                CompositionLocalProvider(
                                    LocalAppFormats provides previewFormats(),
                                    LocalDiscreetMode provides false,
                                ) {
                                    key(name, dark) { NewPurchaseScreen(state, onAction = {}) }
                                }
                            }
                        }
                    }
                    instrumentation.waitForIdleSync()
                    SystemClock.sleep(SETTLE_MILLIS)
                    val mode = if (dark) "dark" else "light"
                    save(File(requireNotNull(dir), "$mode-purchase-$name.png"))
                }
            }
        }
    }

    private fun save(file: File) {
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot()) { "Sin captura" }
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    private companion object {
        const val SETTLE_MILLIS = 900L
    }
}
