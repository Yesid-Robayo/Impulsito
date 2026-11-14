package io.github.yesidrobayo.shopledger.data.export

import android.system.ErrnoException
import android.system.OsConstants
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yesidrobayo.shopledger.data.backup.MemoryLog
import io.github.yesidrobayo.shopledger.data.repository.AT
import io.github.yesidrobayo.shopledger.data.repository.RepositoryTest
import io.github.yesidrobayo.shopledger.data.repository.RoomAppSettingsRepository
import io.github.yesidrobayo.shopledger.data.repository.RoomProductRepository
import io.github.yesidrobayo.shopledger.data.repository.RoomPurchaseRepository
import io.github.yesidrobayo.shopledger.data.repository.RoomSalePaymentRepository
import io.github.yesidrobayo.shopledger.data.repository.RoomSaleRepository
import io.github.yesidrobayo.shopledger.data.repository.RoomStockLedgerRepository
import io.github.yesidrobayo.shopledger.data.repository.RoomSupplierRepository
import io.github.yesidrobayo.shopledger.data.repository.RoomSystemRowRepository
import io.github.yesidrobayo.shopledger.data.repository.RoomTaxRateRepository
import io.github.yesidrobayo.shopledger.data.repository.RoomUnitRepository
import io.github.yesidrobayo.shopledger.data.repository.newProduct
import io.github.yesidrobayo.shopledger.data.transaction.RestoreLock
import io.github.yesidrobayo.shopledger.domain.model.BusinessTimeZone
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.SeedTable
import io.github.yesidrobayo.shopledger.domain.model.export.CsvCell
import io.github.yesidrobayo.shopledger.domain.model.export.CsvDateRange
import io.github.yesidrobayo.shopledger.domain.model.export.CsvExport
import io.github.yesidrobayo.shopledger.domain.model.export.CsvExportProgress
import io.github.yesidrobayo.shopledger.domain.model.export.CsvExportTable
import io.github.yesidrobayo.shopledger.domain.model.purchase.PurchaseLineAmount
import io.github.yesidrobayo.shopledger.domain.model.purchase.PurchaseLineRequest
import io.github.yesidrobayo.shopledger.domain.model.purchase.PurchaseRequest
import io.github.yesidrobayo.shopledger.domain.model.region.DecimalSeparator
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleLineRequest
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleRequest
import io.github.yesidrobayo.shopledger.domain.model.settings.CsvSeparator
import io.github.yesidrobayo.shopledger.domain.port.Clock
import io.github.yesidrobayo.shopledger.domain.port.CsvExportRequest
import io.github.yesidrobayo.shopledger.domain.port.CsvNaming
import io.github.yesidrobayo.shopledger.domain.port.PaymentMethodNames
import io.github.yesidrobayo.shopledger.domain.rule.export.CsvRowWriter
import io.github.yesidrobayo.shopledger.domain.rule.region.CsvFormat
import io.github.yesidrobayo.shopledger.domain.usecase.purchase.RegisterPurchaseResult
import io.github.yesidrobayo.shopledger.domain.usecase.purchase.RegisterPurchaseUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.sale.RegisterSaleResult
import io.github.yesidrobayo.shopledger.domain.usecase.sale.RegisterSaleUseCase
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * La exportación CSV sobre la base real: un `.zip` con un archivo por clave, cada uno con la marca de
 * UTF-8, el encabezado, filas con fin de línea de Windows, textos entre comillas y sin fórmulas, y
 * números sin tocar. Una venta registrada a mitad de la exportación no entra: todos los archivos ven
 * la misma base. Un documento que falla al escribir o al releer se borra, y sin base no se escribe.
 *
 * Los productos y montos son inventados.
 */
@RunWith(AndroidJUnit4::class)
internal class CsvExportOnDatabaseTest : RepositoryTest() {
    private val products by lazy { RoomProductRepository(database) }
    private val ledger by lazy { RoomStockLedgerRepository(database) }
    private val settings by lazy { RoomAppSettingsRepository(database, RestoreLock { false }) }
    private val output by lazy { File(context.cacheDir, "csv-export-test.zip") }
    private val audits = mutableListOf<Unit>()

    private val registerPurchase by lazy {
        RegisterPurchaseUseCase(
            transactions = runner,
            settings = settings,
            products = products,
            purchases = RoomPurchaseRepository(database),
            suppliers = RoomSupplierRepository(database),
            units = RoomUnitRepository(database),
            systemRows = RoomSystemRowRepository(database),
            ledger = ledger,
            taxRates = RoomTaxRateRepository(database),
            clock = Clock { AT },
        )
    }

    private val registerSale by lazy {
        RegisterSaleUseCase(
            transactions = runner,
            settings = settings,
            products = products,
            sales = RoomSaleRepository(database),
            payments = RoomSalePaymentRepository(database),
            units = RoomUnitRepository(database),
            systemRows = RoomSystemRowRepository(database),
            ledger = ledger,
            methodNames = PaymentMethodNames { key -> "Initial ${key.lowercase()}" },
            taxRates = RoomTaxRateRepository(database),
            clock = Clock { AT },
        )
    }

    @After
    fun deleteOutput() {
        output.delete()
    }

    @Test
    fun everyFileHasItsHeaderRowsAndNoFormulas() = runBlocking {
        val formula = commit { products.insert(newProduct("=1+1")) }
        val accented = commit { products.insert(newProduct("Maçã ñandú; \"grande\"")) }
        buy(formula, units = 10)
        val saleId = sell(formula, units = 2)
        val progress = mutableListOf<CsvExportProgress>()
        val document = FileDocument(output)

        val result = exporter { document }.export(request(), progress::add)

        val files = unzipCsv(output)
        assertEquals(CsvExportTable.entries.map { "${it.key}.csv" }, files.keys.toList())
        files.values.forEach { content ->
            assertTrue(content.startsWith(CsvRowWriter.BYTE_ORDER_MARK))
            assertTrue(content.endsWith("\r\n"))
        }
        val total = CsvExportTable.entries.size
        assertEquals((1..total).map { CsvExportProgress(it, total) }, progress)
        assertEquals(listOf(Unit), audits)
        assertEquals(0, document.discards)

        val productLines = csvLines(files.getValue("products.csv"))
        assertEquals(
            CsvRowWriter.BYTE_ORDER_MARK +
                CsvExportTable.PRODUCTS.columns.joinToString(";") { "\"$it\"" },
            productLines.first(),
        )
        // Café nace con la base de las pruebas; los otros dos, con esta prueba, en ese orden.
        assertEquals(3, productLines.size - 1)
        val formulaRow = csvCells(productLines[2])
        assertEquals("\"'=1+1\"", formulaRow[0])
        assertEquals("\"seed:UNIT\"", formulaRow[3])
        assertEquals("25,00", formulaRow[6])
        // Diez compradas, dos vendidas: ocho, con tres decimales.
        assertEquals("8,000", formulaRow[10])
        assertEquals("", formulaRow[12])
        assertEquals("\"Maçã ñandú; \"\"grande\"\"\"", csvCells(productLines[3])[0])
        assertTrue(accented > formula)

        val sale = requireNotNull(RoomSaleRepository(database).findById(saleId)).content
        val saleRow = csvCells(csvLines(files.getValue("sales.csv"))[1])
        assertEquals(saleId.toString(), saleRow[0])
        assertEquals(writer.cell(amountOf(sale.netTotal)), saleRow[6])
        assertEquals(writer.cell(amountOf(sale.totalCost)), saleRow[13])
        assertEquals(writer.cell(amountOf(sale.netTotal - sale.totalCost)), saleRow[14])
        assertEquals("\"ACTIVE\"", saleRow[15])
        val lineRow = csvCells(csvLines(files.getValue("sale_lines.csv"))[1])
        assertEquals("\"'=1+1\"", lineRow[1])
        assertEquals("2,000", lineRow[2])
        val movements = csvLines(files.getValue("stock_movements.csv")).drop(1).map(::csvCells)
        assertEquals(listOf("\"PURCHASE\"", "\"SALE\""), movements.map { it[7] })
        assertEquals("-2,000", movements[1][9])
        assertEquals(1, csvLines(files.getValue("purchases.csv")).size - 1)
        assertEquals(1, csvLines(files.getValue("expenses.csv")).size)

        // Las filas de datos, sin los encabezados, en todos los archivos.
        val dataRows = files.values.sumOf { csvLines(it).size - 1 }
        assertEquals(CsvExport.Saved(files = total, rows = dataRows.toLong()), result)
    }

    @Test
    fun aDateRangeFiltersTheDocumentsAndKeepsTheCatalogsWhole() = runBlocking {
        val id = commit { products.insert(newProduct("Pan inventado")) }
        buy(id, units = 5)
        val saleId = sell(id, units = 1)
        val day = requireNotNull(RoomSaleRepository(database).findById(saleId)).content.businessDate
        val sameDay = requireNotNull(CsvDateRange.of(day, day))
        val earlier = requireNotNull(CsvDateRange.of(day.plusDays(-10), day.plusDays(-1)))

        exporter { FileDocument(output) }.export(request(sameDay)) {}
        val inside = unzipCsv(output)
        output.delete()
        exporter { FileDocument(output) }.export(request(earlier)) {}
        val outside = unzipCsv(output)

        // El día de la venta: la venta, su línea, la compra, su línea y los dos movimientos.
        for ((file, rows) in listOf(
            "sales.csv" to 1,
            "sale_lines.csv" to 1,
            "purchases.csv" to 1,
            "purchase_lines.csv" to 1,
            "stock_movements.csv" to 2,
        )) {
            assertEquals(file, rows, csvLines(inside.getValue(file)).size - 1)
            // Diez días antes no hay ningún documento: solo el encabezado.
            assertEquals(file, 1, csvLines(outside.getValue(file)).size)
        }
        // Los catálogos van completos con cualquier rango.
        assertEquals(inside.getValue("products.csv"), outside.getValue("products.csv"))
        assertEquals(inside.getValue("suppliers.csv"), outside.getValue("suppliers.csv"))
    }

    @Test
    fun aSaleRegisteredWhileExportingDoesNotEnterAnyFile() = runBlocking {
        val id = commit { products.insert(newProduct("Pan inventado")) }
        buy(id, units = 5)
        sell(id, units = 1)

        // Al terminar el archivo de productos, otra venta entra por la conexión de escritura.
        val result =
            exporter { FileDocument(output) }.export(request()) { progress ->
                if (progress.done == 1) runBlocking(Dispatchers.IO) { sell(id, units = 1) }
            }

        assertTrue("$result", result is CsvExport.Saved)
        val files = unzipCsv(output)
        assertEquals(2, csvLines(files.getValue("sales.csv")).size)
        assertEquals(2, csvLines(files.getValue("sale_lines.csv")).size)
        assertEquals(3, csvLines(files.getValue("stock_movements.csv")).size)
        // La venta sí quedó en la base.
        assertEquals(2L, count("SELECT COUNT(*) FROM sales"))
    }

    @Test
    fun aWriteWithoutSpaceOrABadRereadLeavesNoDocument() = runBlocking {
        commit { products.insert(newProduct("Arroz inventado")) }
        val full =
            FileDocument(
                output,
                failWrite = IOException("sin espacio", ErrnoException("write", OsConstants.ENOSPC)),
            )
        assertEquals(CsvExport.NoSpace, exporter { full }.export(request()) {})
        assertEquals(1, full.discards)

        val broken = FileDocument(output, failWrite = IOException("se cortó"))
        assertEquals(CsvExport.Failed, exporter { broken }.export(request()) {})
        assertEquals(1, broken.discards)

        val different = FileDocument(output, rereadAs = ByteArray(10))
        assertEquals(CsvExport.Failed, exporter { different }.export(request()) {})
        assertEquals(1, different.discards)
        assertTrue("Nada queda con el nombre final", !output.exists())
        assertTrue(audits.isEmpty())
    }

    @Test
    fun withoutTheDatabaseNothingIsWritten() = runBlocking {
        val document = FileDocument(output)
        val exporter =
            CsvExporter(
                rows = { null },
                documentFor = { document },
                recordExport = { audits += Unit },
                log = MemoryLog(),
                ioContext = Dispatchers.IO,
            )

        assertEquals(CsvExport.Failed, exporter.export(request()) {})
        assertEquals(0, document.writes)
        assertTrue(audits.isEmpty())
    }

    // Apoyo

    private val writer =
        CsvRowWriter(
            CsvFormat(CsvSeparator.SEMICOLON, DecimalSeparator.COMMA, currencyDecimals = 2),
            requireNotNull(BusinessTimeZone.of("Etc/GMT+5")),
        )

    private val naming =
        object : CsvNaming {
            override fun fileName(table: CsvExportTable): String = "${table.key}.csv"

            override fun header(column: String): String = column

            override fun seedName(table: SeedTable, systemKey: String): String = "seed:$systemKey"
        }

    private fun request(range: CsvDateRange? = null) =
        CsvExportRequest("content://test/export", writer, naming, range)

    private fun exporter(document: () -> CsvDocument) = CsvExporter(
        rows = { RoomCsvRowSource(database) },
        documentFor = { document() },
        recordExport = { audits += Unit },
        log = MemoryLog(),
        ioContext = Dispatchers.IO,
    )

    private suspend fun buy(productId: Long, units: Long) {
        val line =
            PurchaseLineRequest(
                productId,
                QuantityMilli(units * 1_000),
                PurchaseLineAmount.UnitCost(Money(1_000)),
            )
        val result = registerPurchase(PurchaseRequest(listOf(line), invoiceReference = "F-1"))
        assertTrue("$result", result is RegisterPurchaseResult.Registered)
    }

    private suspend fun sell(productId: Long, units: Long): Long {
        val method = count("SELECT id FROM payment_methods WHERE system_key = 'CASH'")
        val line = SaleLineRequest(productId, QuantityMilli(units * 1_000))
        val result = registerSale(SaleRequest(listOf(line), method))
        return (result as RegisterSaleResult.Registered).saleId
    }

    private fun amountOf(value: Money) = CsvCell.Amount(value)
}
