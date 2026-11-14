package io.github.yesidrobayo.shopledger.data.export

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yesidrobayo.shopledger.data.backup.MemoryLog
import io.github.yesidrobayo.shopledger.data.local.BUSINESS_DATE
import io.github.yesidrobayo.shopledger.data.local.catalog.CREATED_AT
import io.github.yesidrobayo.shopledger.data.repository.DATE
import io.github.yesidrobayo.shopledger.data.repository.RepositoryTest
import io.github.yesidrobayo.shopledger.domain.model.BusinessTimeZone
import io.github.yesidrobayo.shopledger.domain.model.SeedTable
import io.github.yesidrobayo.shopledger.domain.model.export.CsvDateRange
import io.github.yesidrobayo.shopledger.domain.model.export.CsvExport
import io.github.yesidrobayo.shopledger.domain.model.export.CsvExportTable
import io.github.yesidrobayo.shopledger.domain.model.region.DecimalSeparator
import io.github.yesidrobayo.shopledger.domain.model.settings.CsvSeparator
import io.github.yesidrobayo.shopledger.domain.port.CsvExportRequest
import io.github.yesidrobayo.shopledger.domain.port.CsvNaming
import io.github.yesidrobayo.shopledger.domain.rule.TextNormalizer
import io.github.yesidrobayo.shopledger.domain.rule.export.CsvRowWriter
import io.github.yesidrobayo.shopledger.domain.rule.export.SheetCellWriter
import io.github.yesidrobayo.shopledger.domain.rule.region.CsvFormat
import java.io.File
import java.io.FileInputStream
import java.util.zip.ZipInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Los archivos de las tablas que se suman a los nueve primeros, sobre la base real: cada uno sale
 * con su encabezado y una fila por registro, con tantas celdas como columnas, y en la hoja de
 * cálculo como una hoja más. Textos sin fórmulas, filas iniciales con el nombre de su clave, el
 * saldo de una cuenta con los decimales de su moneda y la cotización con seis decimales. Exportar
 * solo lee: la base queda igual.
 *
 * Los nombres, montos y monedas son inventados; la moneda de la cuenta es un código sin país.
 */
@RunWith(AndroidJUnit4::class)
internal class CsvExportTablesTest : RepositoryTest() {
    private val output by lazy { File(context.cacheDir, "csv-export-tables.zip") }

    @After
    fun deleteOutput() {
        output.delete()
    }

    @Test
    fun everyMarkedTableExportsItsRowsWithItsColumns() = runBlocking {
        recordInventedRows()
        val before = changeSeq()

        val result = exporter().export(request()) {}

        assertTrue("$result", result is CsvExport.Saved)
        val files = unzipCsv(output)
        assertEquals(CsvExportTable.entries.map { "${it.key}.csv" }, files.keys.toList())
        CsvExportTable.entries.forEach { table ->
            val records = csvRecords(files.getValue("${table.key}.csv").removePrefix(BOM))
            assertEquals(table.key, table.columns.map { "\"$it\"" }, records.first())
            records.drop(1).forEach { assertEquals(table.key, table.columns.size, it.size) }
        }
        // Leer no escribe nada: la fila del respaldo no cambió.
        assertEquals(before, changeSeq())

        val customer = rowsOf(files, CsvExportTable.CUSTOMERS).single()
        assertEquals("\"'=Cliente inventado\"", customer.cell(CsvExportTable.CUSTOMERS, "name"))
        assertEquals("0,00", customer.cell(CsvExportTable.CUSTOMERS, "balance"))

        val accounts = rowsOf(files, CsvExportTable.MONEY_ACCOUNTS)
        // La caja inicial, sin nombre escrito, sale con el de su clave.
        val cash = accounts.first()
        assertEquals("\"seed:CASH\"", cash.cell(CsvExportTable.MONEY_ACCOUNTS, "name"))
        val gold = accounts.last()
        assertEquals("\"XAU\"", gold.cell(CsvExportTable.MONEY_ACCOUNTS, "currency_code"))
        assertEquals("0", gold.cell(CsvExportTable.MONEY_ACCOUNTS, "currency_decimals"))
        // Sin decimales en su moneda, el saldo sale entero, no con los dos del negocio.
        assertEquals("1234", gold.cell(CsvExportTable.MONEY_ACCOUNTS, "balance"))

        val rate = rowsOf(files, CsvExportTable.FX_RATES).single()
        assertEquals("4000,500000", rate.cell(CsvExportTable.FX_RATES, "fx_rate"))
        assertEquals(BUSINESS_DATE, rate.cell(CsvExportTable.FX_RATES, "date"))

        val tag = rowsOf(files, CsvExportTable.TAGS).single()
        assertEquals("\"'-descuento\"", tag.cell(CsvExportTable.TAGS, "name"))
        val productTag = rowsOf(files, CsvExportTable.PRODUCT_TAGS).single()
        assertEquals("\"Café\"", productTag.cell(CsvExportTable.PRODUCT_TAGS, "product"))
        assertEquals("\"'-descuento\"", productTag.cell(CsvExportTable.PRODUCT_TAGS, "tag"))

        val main = rowsOf(files, CsvExportTable.LOCATIONS).first()
        assertEquals("\"seed:MAIN\"", main.cell(CsvExportTable.LOCATIONS, "name"))
        val methods =
            rowsOf(files, CsvExportTable.PAYMENT_METHODS).map {
                it.cell(CsvExportTable.PAYMENT_METHODS, "name")
            }
        assertTrue(methods.toString(), "\"seed:CASH\"" in methods)

        // Cada archivo trae una fila por registro de su tabla.
        for ((table, source) in listOf(
            CsvExportTable.UNITS to "units",
            CsvExportTable.PRICE_LISTS to "price_lists",
            CsvExportTable.LOCATIONS to "locations",
            CsvExportTable.PAYMENT_METHODS to "payment_methods",
        )) {
            val rows = rowsOf(files, table).size.toLong()
            assertEquals(table.key, count("SELECT COUNT(*) FROM $source"), rows)
        }
    }

    @Test
    fun aDateRangeLimitsTheLedgersAndKeepsTheCatalogs() = runBlocking {
        recordInventedRows()
        val earlier = requireNotNull(CsvDateRange.of(DATE.plusDays(-10), DATE.plusDays(-1)))

        exporter().export(request(earlier)) {}
        val files = unzipCsv(output)

        assertTrue(rowsOf(files, CsvExportTable.FX_RATES).isEmpty())
        assertEquals(1, rowsOf(files, CsvExportTable.CUSTOMERS).size)
        assertEquals(1, rowsOf(files, CsvExportTable.TAGS).size)
    }

    @Test
    fun theSpreadsheetHasOneSheetPerFile() = runBlocking {
        recordInventedRows()
        val sheet = SheetCellWriter(currencyDecimals = 2, zone = zone)

        val result = exporter().export(request(spreadsheet = sheet)) {}

        assertTrue("$result", result is CsvExport.Saved)
        val sheets = zipEntries(output).filter { it.startsWith("xl/worksheets/sheet") }
        assertEquals(CsvExportTable.entries.size, sheets.size)
    }

    // Apoyo

    /** Un cliente, una cuenta en otra moneda, una cotización y una etiqueta del producto. */
    private suspend fun recordInventedRows() {
        val customer = "=Cliente inventado"
        execute(
            "INSERT INTO customers (name, normalized_name, phone, balance, points_balance, " +
                "created_at, updated_at) VALUES ('$customer', " +
                "'${TextNormalizer.normalize(customer)}', '3000000000', 0, 0, $CREATED_AT, " +
                "$CREATED_AT)",
        )
        val account = "Cuenta en oro"
        execute(
            "INSERT INTO money_accounts (name, normalized_name, kind, currency_code, " +
                "currency_decimals, balance, created_at, updated_at) VALUES ('$account', " +
                "'${TextNormalizer.normalize(account)}', 'OTHER', 'XAU', 0, 1234, $CREATED_AT, " +
                "$CREATED_AT)",
        )
        execute(
            "INSERT INTO fx_rates (currency_code, rate_micro, occurred_at, business_date, " +
                "created_at) VALUES ('XAU', 4000500000, $CREATED_AT, '$BUSINESS_DATE', " +
                "$CREATED_AT)",
        )
        execute(
            "INSERT INTO tags (name, normalized_name, created_at, updated_at) VALUES " +
                "('-descuento', '-descuento', $CREATED_AT, $CREATED_AT)",
        )
        val tagId = count("SELECT id FROM tags")
        execute("INSERT INTO product_tags (product_id, tag_id) VALUES ($productId, $tagId)")
    }

    private val zone = requireNotNull(BusinessTimeZone.of("Etc/GMT+5"))

    private val writer =
        CsvRowWriter(
            CsvFormat(CsvSeparator.SEMICOLON, DecimalSeparator.COMMA, currencyDecimals = 2),
            zone,
        )

    private val naming =
        object : CsvNaming {
            override fun fileName(table: CsvExportTable): String = "${table.key}.csv"

            override fun header(column: String): String = column

            override fun seedName(table: SeedTable, systemKey: String): String = "seed:$systemKey"
        }

    private fun request(range: CsvDateRange? = null, spreadsheet: SheetCellWriter? = null) =
        CsvExportRequest("content://test/export", writer, naming, range, spreadsheet)

    private fun exporter() = CsvExporter(
        rows = { RoomCsvRowSource(database) },
        documentFor = { FileDocument(output) },
        recordExport = {},
        log = MemoryLog(),
        ioContext = Dispatchers.IO,
    )

    /** Las filas de datos de [table], sin el encabezado, con las celdas como están escritas. */
    private fun rowsOf(files: Map<String, String>, table: CsvExportTable): List<List<String>> =
        csvRecords(files.getValue("${table.key}.csv").removePrefix(BOM)).drop(1)

    private fun List<String>.cell(table: CsvExportTable, column: String): String =
        this[table.columns.indexOf(column)]

    private fun zipEntries(file: File): List<String> {
        val names = mutableListOf<String>()
        ZipInputStream(FileInputStream(file)).use { zip ->
            while (true) names += (zip.nextEntry ?: break).name
        }
        return names
    }

    private companion object {
        val BOM: String = CsvRowWriter.BYTE_ORDER_MARK
    }
}
