package io.github.yesidrobayo.shopledger.ui.document

import android.graphics.Bitmap
import android.graphics.Color.TRANSPARENT
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.BusinessTimeZone
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.document.CatalogDocument
import io.github.yesidrobayo.shopledger.domain.model.document.CatalogItem
import io.github.yesidrobayo.shopledger.domain.model.document.CatalogSection
import io.github.yesidrobayo.shopledger.domain.model.document.CatalogVariant
import io.github.yesidrobayo.shopledger.domain.model.document.ReceiptHeader
import io.github.yesidrobayo.shopledger.domain.model.print.PaperWidth
import io.github.yesidrobayo.shopledger.domain.model.print.PrintFailure
import io.github.yesidrobayo.shopledger.domain.model.print.PrinterSetup
import io.github.yesidrobayo.shopledger.domain.model.print.SavedPrinter
import io.github.yesidrobayo.shopledger.domain.model.settings.ColorPalette
import io.github.yesidrobayo.shopledger.domain.model.settings.ReceiptTemplateField
import io.github.yesidrobayo.shopledger.domain.rule.document.SaleReceiptDocument
import io.github.yesidrobayo.shopledger.ui.catalog.CatalogChoices
import io.github.yesidrobayo.shopledger.ui.catalog.CatalogScreen
import io.github.yesidrobayo.shopledger.ui.catalog.CatalogUiState
import io.github.yesidrobayo.shopledger.ui.format.LocalAppFormats
import io.github.yesidrobayo.shopledger.ui.printer.PrintersScreen
import io.github.yesidrobayo.shopledger.ui.printer.PrintersUiState
import io.github.yesidrobayo.shopledger.ui.printer.systemPrintSection
import io.github.yesidrobayo.shopledger.ui.receipt.ReceiptShare
import io.github.yesidrobayo.shopledger.ui.receipt.ReceiptTemplateScreen
import io.github.yesidrobayo.shopledger.ui.receipt.ReceiptTemplateUiState
import io.github.yesidrobayo.shopledger.ui.receipt.SaleReceiptScreen
import io.github.yesidrobayo.shopledger.ui.receipt.SaleReceiptUiState
import io.github.yesidrobayo.shopledger.ui.theme.AppTheme
import io.github.yesidrobayo.shopledger.ui.theme.previewFormats
import java.io.File
import kotlinx.datetime.LocalDate
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Capturas de los documentos para la puerta de calidad visual: la plantilla del comprobante, el
 * comprobante de una venta, las impresoras y el catálogo, en claro y en oscuro con la paleta
 * predeterminada y otra, y con la letra al doble. Las vistas previas son el dibujo real. Solo
 * corre con el argumento `captureDir`. Datos inventados.
 */
@RunWith(AndroidJUnit4::class)
class DocumentsGalleryTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun drawsEachDocumentScreenInBothModesAndWithLargeText() {
        val dir =
            InstrumentationRegistry.getArguments().getString("captureDir")?.let { name ->
                File(instrumentation.targetContext.cacheDir, name).apply { mkdirs() }
            }
        assumeTrue("Sin captureDir no hay capturas", dir != null)
        val formats = previewFormats()
        val runs = listOf(
            Triple(ColorPalette.INDIGO_MUSTARD, false, 1f),
            Triple(ColorPalette.INDIGO_MUSTARD, true, 1f),
            Triple(ColorPalette.GARNET_HONEY, false, 1f),
            Triple(ColorPalette.INDIGO_MUSTARD, false, 2f),
        )
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            for ((palette, dark, scale) in runs) {
                for ((name, screen) in shots()) {
                    scenario.onActivity { activity ->
                        val bars = SystemBarStyle.auto(TRANSPARENT, TRANSPARENT) { dark }
                        activity.enableEdgeToEdge(
                            statusBarStyle = bars,
                            navigationBarStyle = bars,
                        )
                        activity.setContent {
                            val base = LocalDensity.current
                            AppTheme(palette = palette, darkTheme = dark) {
                                CompositionLocalProvider(
                                    LocalAppFormats provides formats,
                                    LocalDensity provides Density(base.density, scale),
                                ) {
                                    key(name) { screen() }
                                }
                            }
                        }
                    }
                    instrumentation.waitForIdleSync()
                    SystemClock.sleep(SETTLE_MILLIS)
                    val mode = if (dark) "dark" else "light"
                    val file = File(
                        requireNotNull(dir),
                        "${palette.name.lowercase()}-$mode-x${scale.toInt()}-$name.png",
                    )
                    val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
                    file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    bitmap.recycle()
                }
            }
        }
    }

    private fun shots(): List<Pair<String, @Composable () -> Unit>> {
        val document = SaleReceiptDocument.of(
            sampleSale(),
            SAMPLE_TEMPLATE,
            "Tienda Central",
            null,
            Money.ZERO,
        )
        val receipt = SaleReceiptUiState.Ready(
            document = document,
            logo = null,
            units = emptyMap(),
            zone = BusinessTimeZone.of("UTC"),
        )
        val template = ReceiptTemplateUiState.Editing(
            businessName = "Tienda Central",
            logo = null,
            details = "Calle Mayor 8, local 2",
            footer = "Gracias por venir",
        )
        val printer = SavedPrinter("00:11:22:33:44:55", "Térmica de mostrador", PaperWidth.MM_58)
        val printers = PrintersUiState(
            setup = PrinterSetup(listOf(printer), printer.address),
            loading = false,
        )
        val catalog = CatalogDocument(
            title = null,
            header = ReceiptHeader(null, "Tienda Central", null),
            pricesOn = BusinessDate(LocalDate(2026, 11, 14)),
            sections = listOf(
                CatalogSection(
                    "Abarrotes",
                    listOf(
                        CatalogItem("Arroz blanco 1 kg", 1L, Money(4_200L), null, emptyList()),
                        CatalogItem(
                            "Camiseta de algodón",
                            1L,
                            null,
                            null,
                            listOf(
                                CatalogVariant("Roja / M", Money(20_000L)),
                                CatalogVariant("Azul / S", Money(20_000L)),
                            ),
                        ),
                    ),
                ),
            ),
        )
        val catalogReady = CatalogUiState.Ready(
            document = catalog,
            choices = CatalogChoices(),
            categories = emptyList(),
            lists = emptyList(),
            listId = 1L,
            hasPhotos = false,
        )
        return listOf(
            "template" to { ReceiptTemplateScreen(template, {}) },
            "template-too-long" to {
                ReceiptTemplateScreen(
                    template.copy(tooLong = setOf(ReceiptTemplateField.FOOTER), saveFailed = true),
                    {},
                )
            },
            "receipt" to { SaleReceiptScreen(receipt, {}) },
            "receipt-share-failed" to {
                SaleReceiptScreen(receipt.copy(shareFailed = ReceiptShare.PDF), {})
            },
            "printers" to {
                PrintersScreen(printers, {}, systemSection = { systemPrintSection {} })
            },
            "printers-empty-denied" to {
                PrintersScreen(
                    PrintersUiState(loading = false, blocked = PrintFailure.PERMISSION_MISSING),
                    {},
                    systemSection = { systemPrintSection {} },
                )
            },
            "catalog" to {
                val context = instrumentation.targetContext
                val image = CatalogRenderer(context, previewFormats(), ColorPalette.INDIGO_MUSTARD)
                    .renderFirstPage(catalog, CatalogAssets(null, emptyMap(), emptyMap()), 1f)
                    .asImageBitmap()
                CatalogScreen(catalogReady, {}, preview = image, pages = 1)
            },
            "catalog-filtered-empty" to {
                val empty = catalogReady.copy(document = catalog.copy(sections = emptyList()))
                CatalogScreen(empty, {})
            },
            "catalog-no-products" to { CatalogScreen(CatalogUiState.NoPricedProducts, {}) },
        )
    }

    private companion object {
        const val SETTLE_MILLIS = 1_200L
    }
}
