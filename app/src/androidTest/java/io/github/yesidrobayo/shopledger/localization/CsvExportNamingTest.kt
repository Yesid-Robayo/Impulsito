package io.github.yesidrobayo.shopledger.localization

import android.content.res.Configuration
import android.content.res.Resources
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yesidrobayo.shopledger.ShopLedgerApp
import io.github.yesidrobayo.shopledger.data.DataAccess
import io.github.yesidrobayo.shopledger.data.DataPorts
import io.github.yesidrobayo.shopledger.domain.model.BusinessTimeZone
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductForm
import io.github.yesidrobayo.shopledger.domain.model.catalog.SupplierDetails
import io.github.yesidrobayo.shopledger.domain.model.export.CsvExport
import io.github.yesidrobayo.shopledger.domain.model.export.CsvExportTable
import io.github.yesidrobayo.shopledger.domain.model.region.DecimalSeparator
import io.github.yesidrobayo.shopledger.domain.model.settings.CsvSeparator
import io.github.yesidrobayo.shopledger.domain.model.settings.CurrencyCode
import io.github.yesidrobayo.shopledger.domain.model.settings.RegionalSettingsInput
import io.github.yesidrobayo.shopledger.domain.port.CsvExportRequest
import io.github.yesidrobayo.shopledger.domain.port.StorageFailureException
import io.github.yesidrobayo.shopledger.domain.port.TxResult
import io.github.yesidrobayo.shopledger.domain.rule.TextNormalizer
import io.github.yesidrobayo.shopledger.domain.rule.export.CsvRowWriter
import io.github.yesidrobayo.shopledger.domain.rule.region.CsvFormat
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.CreateProductUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.SaveProductResult
import io.github.yesidrobayo.shopledger.domain.usecase.region.BusinessCalendarSource
import io.github.yesidrobayo.shopledger.domain.usecase.region.CurrencyLock
import io.github.yesidrobayo.shopledger.format.CsvFormatFactory
import io.github.yesidrobayo.shopledger.format.FormatLocale
import java.io.File
import java.util.Locale
import java.util.zip.ZipInputStream
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.DayOfWeek
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * En el teléfono: la exportación CSV en el idioma de la interfaz. `sales` es `ventas.csv`, `sales.csv`
 * o `vendas.csv`; cada columna de cada archivo tiene su encabezado, sin repetirse en el archivo; y una
 * exportación real, con la base de la app, nombra sus archivos y escribe sus encabezados así.
 *
 * Con el argumento `csvRegions=<carpeta>` registra además un producto y un proveedor inventados y deja,
 * en esa carpeta de la caché, una exportación por idioma con el separador y los decimales de una región
 * de cada idioma, para abrirla en una hoja de cálculo.
 */
@RunWith(AndroidJUnit4::class)
class CsvExportNamingTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val app = context.applicationContext as ShopLedgerApp

    @Test
    fun theSalesFileFollowsTheLanguage() {
        assertEquals("ventas.csv", naming("es").fileName(CsvExportTable.SALES))
        assertEquals("sales.csv", naming("en").fileName(CsvExportTable.SALES))
        assertEquals("vendas.csv", naming("pt").fileName(CsvExportTable.SALES))
        assertEquals("Venta", naming("es").header("sale_id"))
        assertEquals("Venda", naming("pt").header("sale_id"))
        // Una columna sin texto sale con su clave: nunca queda sin encabezado.
        assertEquals("not_a_column", naming("en").header("not_a_column"))
    }

    @Test
    fun everyFileAndColumnHasItsTextInEachLanguage() {
        for (language in LANGUAGES) {
            val names = naming(language)
            val files = CsvExportTable.entries.map(names::fileName)
            assertEquals(language, files.size, files.toSet().size)
            files.forEach { assertTrue("$language: $it", it.endsWith(".csv") && !it.contains('/')) }
            CsvExportTable.entries.forEach { table ->
                table.columns.forEach { column ->
                    assertNotNull("${table.key}.$column", CsvExportNaming.headerRes(column))
                }
                val headers = table.columns.map(names::header)
                headers.forEach { assertTrue("$language ${table.key}", it.isNotBlank()) }
                assertEquals("$language ${table.key}", headers.size, headers.toSet().size)
            }
        }
    }

    @Test
    fun aRealExportNamesItsFilesAndHeadersInTheLanguage() = runBlocking {
        val regions = InstrumentationRegistry.getArguments().getString(REGIONS_ARGUMENT)
        if (regions != null) recordInventedData(ports())
        for ((language, region) in REGIONS) {
            val file = File(context.cacheDir, "csv-$language-${region.tag}.zip")
            val result = export(file, naming(language), region)
            assertTrue("$result", result is CsvExport.Saved)
            val entries = unzip(file)
            val names = naming(language)
            assertEquals(CsvExportTable.entries.map(names::fileName), entries.keys.toList())
            val sales = entries.getValue(names.fileName(CsvExportTable.SALES))
            val separator = region.separator.char.toString()
            val header =
                CsvExportTable.SALES.columns.joinToString(separator) { "\"${names.header(it)}\"" }
            assertTrue(language, sales.startsWith(CsvRowWriter.BYTE_ORDER_MARK + header + "\r\n"))
            if (regions != null) {
                val dir = File(context.cacheDir, regions).apply { mkdirs() }
                file.copyTo(File(dir, file.name), overwrite = true)
            }
            file.delete()
        }
    }

    private suspend fun export(file: File, naming: CsvExportNaming, region: Region): CsvExport {
        file.delete()
        file.createNewFile()
        val format = CsvFormat(region.separator, region.decimal, currencyDecimals = 2)
        val writer = CsvRowWriter(format, requireNotNull(BusinessTimeZone.of("Etc/GMT+5")))
        val request = CsvExportRequest(Uri.fromFile(file).toString(), writer, naming)
        return app.container.data.csvExport.export(request) {}
    }

    /**
     * Un proveedor y un producto inventados, con tildes, comillas, un nombre que parece fórmula y un
     * código de barras con cero inicial. El producto entra por el caso de uso de la app, como lo
     * crearía la persona. Con un nombre ya usado por una corrida anterior, sigue con el que había.
     */
    private suspend fun recordInventedData(ports: DataPorts) {
        val now = app.container.clock.now()
        val supplier = "Distribuidora Ñandú"
        // Un nombre de proveedor ya usado por una corrida anterior choca con su único: se sigue.
        try {
            ports.transactions.write {
                ports.suppliers.insert(
                    SupplierDetails(
                        supplier,
                        TextNormalizer.normalize(supplier),
                        phone = null,
                        email = null,
                        notes = "-2+3",
                        paymentTermsDays = 30,
                    ),
                    now,
                )
                TxResult.Commit(Unit)
            }
        } catch (_: StorageFailureException) {
            Unit
        }
        // Un negocio recién instalado no tiene moneda: se le da la reservada para pruebas.
        if (!ports.settings.get().regional.currency.code.isConfirmed) {
            ports.transactions.write {
                ports.settings.updateRegional(
                    RegionalSettingsInput(
                        currencyCode = requireNotNull(CurrencyCode.of("XTS")),
                        currencyDecimals = 2,
                        currencySymbol = null,
                        cashRounding = Money.ZERO,
                        formatLocaleTag = "es-ES",
                        businessTimeZoneId = "Etc/GMT+5",
                        weekStartDay = DayOfWeek.MONDAY,
                        csvSeparator = CsvSeparator.SEMICOLON,
                    ),
                    now,
                )
                TxResult.Commit(Unit)
            }
        }
        val create =
            CreateProductUseCase(
                ports.transactions, ports.products, ports.categories, ports.suppliers, ports.units,
                ports.systemRows, ports.priceHistory, ports.stockLedger, ports.lowStockAlerts,
                BusinessCalendarSource(ports.settings), CurrencyLock(ports.settings),
                app.container.clock,
            )
        val form =
            ProductForm(
                name = "Maçã \"grande\" =1+1",
                salePrice = Money(1_234_567),
                cost = Money(450_000),
                minStock = QuantityMilli(3_000),
                barcode = "0123456789012",
            )
        val saved = create(form, QuantityMilli(12_000))
        assertTrue(
            "$saved",
            saved is SaveProductResult.Saved || saved is SaveProductResult.DuplicateName,
        )
    }

    private fun naming(language: String): CsvExportNaming {
        val configuration = Configuration(context.resources.configuration)
        configuration.setLocale(Locale.forLanguageTag(language))
        val resources: Resources = context.createConfigurationContext(configuration).resources
        return CsvExportNaming { resources }
    }

    private suspend fun ports(): DataPorts = (app.container.data.open() as DataAccess.Ready).ports

    private fun unzip(file: File): Map<String, String> {
        val entries = linkedMapOf<String, String>()
        ZipInputStream(file.inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entries[entry.name] = zip.readBytes().toString(Charsets.UTF_8)
            }
        }
        return entries
    }

    /**
     * El separador de columnas y el decimal de una región, como los elige la app: el decimal de ICU y
     * el separador con que nace el ajuste.
     */
    private class Region(val tag: String) {
        val decimal: DecimalSeparator =
            CsvFormatFactory.decimalSeparator(requireNotNull(FormatLocale.of(tag)))
        val separator: CsvSeparator = CsvSeparator.defaultFor(decimal)
    }

    private companion object {
        const val REGIONS_ARGUMENT = "csvRegions"
        val LANGUAGES = listOf("es", "en", "pt")

        /** Cada idioma con una región que lo habla: la de las pruebas de Excel de los requisitos. */
        val REGIONS =
            listOf(
                "es" to Region("es-ES"),
                "en" to Region("en-US"),
                "pt" to Region("pt-BR"),
            )
    }
}
