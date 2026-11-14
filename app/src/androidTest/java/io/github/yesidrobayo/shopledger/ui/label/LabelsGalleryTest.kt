package io.github.yesidrobayo.shopledger.ui.label

import android.graphics.Bitmap
import android.graphics.Color.TRANSPARENT
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.ui.graphics.asImageBitmap
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.catalog.Product
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductDetails
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductStockMode
import io.github.yesidrobayo.shopledger.domain.model.print.PairedPrinter
import io.github.yesidrobayo.shopledger.domain.model.print.PrintFailure
import io.github.yesidrobayo.shopledger.domain.model.settings.ColorPalette
import io.github.yesidrobayo.shopledger.domain.rule.TextNormalizer
import io.github.yesidrobayo.shopledger.ui.components.LabelPreview
import io.github.yesidrobayo.shopledger.ui.format.LocalAppFormats
import io.github.yesidrobayo.shopledger.ui.theme.AppTheme
import io.github.yesidrobayo.shopledger.ui.theme.previewFormats
import java.io.File
import kotlin.time.Instant
import kotlinx.datetime.DayOfWeek
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Capturas de `Etiquetas imprimibles` para la puerta de calidad visual: vacío, con productos y
 * avisos, un código que no cabe, la hoja PDF y un error al imprimir, con la paleta
 * predeterminada y otra, en claro y en oscuro. La vista previa es el dibujo real. La escala de la
 * letra es la del sistema. Solo corre con el argumento `captureDir`. Productos inventados.
 */
@RunWith(AndroidJUnit4::class)
class LabelsGalleryTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun drawsEachStateInBothPalettesAndModes() {
        val dir =
            InstrumentationRegistry.getArguments().getString("captureDir")?.let { name ->
                File(instrumentation.targetContext.cacheDir, name).apply { mkdirs() }
            }
        assumeTrue("Sin captureDir no hay capturas", dir != null)
        val formats = previewFormats()
        val renderer = LabelRenderer(instrumentation.targetContext, formats)
        val preview: LabelPreviewSlot = { item, template, size, modifier ->
            val image = renderer.render(item, template, size).asImageBitmap()
            LabelPreview(image, item.name, modifier)
        }
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            for (palette in listOf(ColorPalette.INDIGO_MUSTARD, ColorPalette.GARNET_HONEY)) {
                for (dark in listOf(false, true)) {
                    for ((name, state) in shots()) {
                        scenario.onActivity { activity ->
                            val bars = SystemBarStyle.auto(TRANSPARENT, TRANSPARENT) { dark }
                            activity.enableEdgeToEdge(
                                statusBarStyle = bars,
                                navigationBarStyle = bars,
                            )
                            activity.setContent {
                                AppTheme(palette = palette, darkTheme = dark) {
                                    CompositionLocalProvider(LocalAppFormats provides formats) {
                                        key(name) { LabelsScreen(state, {}, preview) }
                                    }
                                }
                            }
                        }
                        instrumentation.waitForIdleSync()
                        SystemClock.sleep(SETTLE_MILLIS)
                        val mode = if (dark) "dark" else "light"
                        val file =
                            File(
                                requireNotNull(dir),
                                "${palette.name.lowercase()}-$mode-$name.png",
                            )
                        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
                        file.outputStream().use {
                            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                        }
                        bitmap.recycle()
                    }
                }
            }
        }
    }

    private fun shots(): List<Pair<String, LabelsUiState>> {
        val base = LabelsUiState(
            loading = false,
            today = TODAY,
            since = TODAY,
            weekStartDay = DayOfWeek.MONDAY,
            products = PRODUCTS,
        )
        val chosen = base.copy(
            picked = PRODUCTS.map { it.id },
            copies = mapOf(1L to 3),
        )
        val printer = PairedPrinter("Térmica de mostrador", "00:11:22:33:44:55")
        return listOf(
            "empty" to base,
            "chosen" to chosen,
            "too-long" to chosen.copy(picked = listOf(2L, 1L), printer = printer),
            "sheet" to chosen.copy(size = LabelSize.SHEET),
            "print-error" to chosen.copy(
                printer = printer,
                notice = LabelNotice.PrintFailed(PrintFailure.CONNECT_FAILED),
            ),
            "changed-empty" to base.copy(scope = LabelScope.CHANGED, repriced = emptySet()),
        )
    }

    private companion object {
        const val SETTLE_MILLIS = 900L
        val TODAY = requireNotNull(BusinessDate.parse("2026-11-14"))
        val CREATED: Instant = Instant.parse("2026-11-01T12:00:00Z")
        val PRODUCTS = listOf(
            product(1L, "Piña con açaí y limón", 12_500L, "P00000001"),
            product(2L, "Avena en hojuelas 500 g", 4_000L, "2001234567890"),
            product(
                3L,
                "Jabón artesanal de avena, miel y caléndula para piel sensible, barra de 120 g",
                9_900L,
                null,
            ),
            product(4L, "Bolsa reutilizable", 0L, "P00000004"),
        )

        fun product(id: Long, name: String, price: Long, code: String?) = Product(
            id = id,
            details = ProductDetails(
                name = name,
                normalizedName = TextNormalizer.normalize(name),
                categoryId = null,
                defaultSupplierId = null,
                baseUnitId = 1L,
                stockMode = ProductStockMode.TRACKED,
                tracksLots = false,
                fixedUnitCost = Money.ZERO,
                minStock = QuantityMilli.ZERO,
                barcode = code,
                parentProductId = null,
                variantAxis1 = null,
                variantAxis2 = null,
                variantValue1 = null,
                variantValue2 = null,
                photoMediaId = null,
                taxRateId = null,
                targetMargin = null,
                referenceCost = null,
                referencePrice = null,
                expiryNoticeDays = null,
            ),
            salePrice = Money(price),
            initialCost = Money.ZERO,
            averageCost = Money.ZERO,
            stockValue = Money.ZERO,
            onHand = QuantityMilli.ZERO,
            replacementCost = Money.ZERO,
            replacementCostAt = null,
            priceReviewSince = null,
            archivedAt = null,
            createdAt = CREATED,
            updatedAt = CREATED,
        )
    }
}
