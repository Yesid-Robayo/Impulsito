package io.github.yesidrobayo.shopledger.ui.document

import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.document.CatalogDocument
import io.github.yesidrobayo.shopledger.domain.model.document.CatalogItem
import io.github.yesidrobayo.shopledger.domain.model.document.CatalogSection
import io.github.yesidrobayo.shopledger.domain.model.document.CatalogVariant
import io.github.yesidrobayo.shopledger.domain.model.document.ReceiptHeader
import io.github.yesidrobayo.shopledger.domain.model.settings.ColorPalette
import io.github.yesidrobayo.shopledger.ui.theme.previewFormats
import java.io.File
import kotlinx.datetime.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * El catálogo en papel, dibujado en el teléfono: título por defecto, la fecha de los precios, las
 * variantes bajo su producto y tantas páginas como hagan falta, cada una con su pie. Sin red y con
 * datos inventados.
 */
@RunWith(AndroidJUnit4::class)
class CatalogRendererTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val renderer = CatalogRenderer(context, previewFormats(), ColorPalette.INDIGO_MUSTARD)
    private val assets = CatalogAssets(null, emptyMap(), emptyMap())
    private val folder = File(context.getExternalFilesDir(null), "receipt-captures")
        .apply { mkdirs() }

    @Test
    fun theCatalogReadsInOrderWithItsDefaultTitleAndVariants() {
        val texts = renderer.textsOf(catalog(2), assets).map { it.substringBefore('\t') }

        assertEquals(
            listOf(
                "Tienda Central",
                context.getString(R.string.catalog_default_title),
                context.getString(
                    R.string.catalog_doc_prices_date,
                    previewFormats().date.dayMonthYear(DATE),
                ),
                "Abarrotes",
                "Producto de prueba 1",
                "Producto de prueba 2",
                "Camiseta",
                "Roja / M",
            ),
            texts,
        )
    }

    @Test
    fun aLongCatalogGoesOnManyPagesInThePdf() {
        val document = catalog(300)
        val file = File(folder, "catalog-long.pdf")

        assertTrue(renderer.writePdf(document, assets, file))
        val pages = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use {
            PdfRenderer(it).use { pdf -> pdf.pageCount }
        }
        assertTrue(pages > 1)
        assertEquals(pages, renderer.pageCount(document, assets))
        val preview = renderer.renderFirstPage(document, assets, 1f)
        assertEquals(PaperSize.A4.width, preview.width)
    }

    private fun catalog(count: Int) = CatalogDocument(
        title = null,
        header = ReceiptHeader(null, "Tienda Central", null),
        pricesOn = DATE,
        sections = listOf(
            CatalogSection(
                "Abarrotes",
                (1..count).map {
                    CatalogItem("Producto de prueba $it", 1L, Money(1_000L * it), null, emptyList())
                } + CatalogItem(
                    "Camiseta",
                    1L,
                    null,
                    null,
                    listOf(CatalogVariant("Roja / M", Money(20_000L))),
                ),
            ),
        ),
    )

    private companion object {
        val DATE = BusinessDate(LocalDate(2026, 11, 14))
    }
}
