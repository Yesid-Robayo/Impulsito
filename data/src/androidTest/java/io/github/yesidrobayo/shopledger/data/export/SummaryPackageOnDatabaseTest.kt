package io.github.yesidrobayo.shopledger.data.export

import android.system.ErrnoException
import android.system.OsConstants
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yesidrobayo.shopledger.data.backup.MemoryLog
import io.github.yesidrobayo.shopledger.data.repository.AT
import io.github.yesidrobayo.shopledger.data.repository.RepositoryTest
import io.github.yesidrobayo.shopledger.data.repository.RoomAppSettingsRepository
import io.github.yesidrobayo.shopledger.data.repository.RoomManagementSummaryRepository
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
import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.BusinessTimeZone
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.ReportPeriod
import io.github.yesidrobayo.shopledger.domain.model.SeedTable
import io.github.yesidrobayo.shopledger.domain.model.catalog.SupplierDetails
import io.github.yesidrobayo.shopledger.domain.model.export.CsvDateRange
import io.github.yesidrobayo.shopledger.domain.model.export.CsvExport
import io.github.yesidrobayo.shopledger.domain.model.export.CsvExportProgress
import io.github.yesidrobayo.shopledger.domain.model.export.CsvExportTable
import io.github.yesidrobayo.shopledger.domain.model.purchase.PurchaseLineAmount
import io.github.yesidrobayo.shopledger.domain.model.purchase.PurchaseLineRequest
import io.github.yesidrobayo.shopledger.domain.model.purchase.PurchaseRequest
import io.github.yesidrobayo.shopledger.domain.model.region.DecimalSeparator
import io.github.yesidrobayo.shopledger.domain.model.report.SummaryAvailability
import io.github.yesidrobayo.shopledger.domain.model.report.SummaryFile
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleLineRequest
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleRequest
import io.github.yesidrobayo.shopledger.domain.model.settings.CsvSeparator
import io.github.yesidrobayo.shopledger.domain.port.Clock
import io.github.yesidrobayo.shopledger.domain.port.PaymentMethodNames
import io.github.yesidrobayo.shopledger.domain.port.SummaryNaming
import io.github.yesidrobayo.shopledger.domain.port.SummaryPackageRequest
import io.github.yesidrobayo.shopledger.domain.rule.export.CsvRowWriter
import io.github.yesidrobayo.shopledger.domain.rule.region.CsvFormat
import io.github.yesidrobayo.shopledger.domain.usecase.purchase.RegisterPurchaseResult
import io.github.yesidrobayo.shopledger.domain.usecase.purchase.RegisterPurchaseUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.sale.RegisterSaleResult
import io.github.yesidrobayo.shopledger.domain.usecase.sale.RegisterSaleUseCase
import java.io.File
import java.io.IOException
import java.util.zip.ZipInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * El paquete del resumen de gestión sobre la base real: el PDF primero, tal cual, y después los
 * CSV pedidos con las fechas del período y el inventario al cierre sumado del libro de stock. Un
 * paquete que falla al escribir o al releer se borra. También lo que el resumen ofrece según los
 * datos: compras del período, stock hasta su cierre y clientes o proveedores.
 *
 * Los productos, proveedores y montos son inventados.
 */
@RunWith(AndroidJUnit4::class)
internal class SummaryPackageOnDatabaseTest : RepositoryTest() {
    private val products by lazy { RoomProductRepository(database) }
    private val ledger by lazy { RoomStockLedgerRepository(database) }
    private val settings by lazy { RoomAppSettingsRepository(database, RestoreLock { false }) }
    private val output by lazy { File(context.cacheDir, "summary-package-test.zip") }
    private val pdf by lazy {
        File(context.cacheDir, "summary-package-test.pdf").apply { writeBytes(PDF_BYTES) }
    }
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
    fun deleteFiles() {
        output.delete()
        pdf.delete()
    }

    @Test
    fun thePdfGoesFirstAndEachChosenFileFollowsWithTheDatesOfThePeriod() = runBlocking {
        val id = commit { products.insert(newProduct("Pan inventado")) }
        buy(id, units = 10)
        val saleId = sell(id, units = 2)
        val day = saleDay(saleId)
        val progress = mutableListOf<CsvExportProgress>()
        val files =
            listOf(
                SummaryFile.Table(CsvExportTable.SALES),
                SummaryFile.Table(CsvExportTable.EXPENSES),
                SummaryFile.ClosingInventory,
            )

        val result = writer().write(request(files, day, day), progress::add)

        val entries = unzipBytes(output)
        assertEquals(
            listOf("summary.pdf", "sales.csv", "expenses.csv", "closing_inventory.csv"),
            entries.keys.toList(),
        )
        assertTrue(PDF_BYTES.contentEquals(entries.getValue("summary.pdf")))
        val sales = textOf(entries, "sales.csv")
        assertTrue(sales.first().startsWith(CsvRowWriter.BYTE_ORDER_MARK))
        assertEquals(2, sales.size)
        assertEquals(saleId.toString(), csvCells(sales[1])[0])
        val inventory = textOf(entries, "closing_inventory.csv")
        assertEquals(
            CsvRowWriter.BYTE_ORDER_MARK +
                SummaryFile.CLOSING_INVENTORY_COLUMNS.joinToString(";") { "\"$it\"" },
            inventory.first(),
        )
        // Diez compradas y dos vendidas: ocho al cierre, con tres decimales.
        val bread = inventory.drop(1).map { csvCells(it) }.single { it[0] == "\"Pan inventado\"" }
        assertEquals("\"seed:UNIT\"", bread[1])
        assertEquals("8,000", bread[2])
        assertEquals((1..4).map { CsvExportProgress(it, 4) }, progress)
        assertEquals(listOf(Unit), audits)
        val dataRows = listOf("sales.csv", "expenses.csv", "closing_inventory.csv").sumOf {
            textOf(entries, it).size - 1
        }
        assertEquals(CsvExport.Saved(files = 4, rows = dataRows.toLong()), result)
    }

    @Test
    fun aPeriodBeforeAnyMovementHasNoDocumentsAndNoClosingStock() = runBlocking {
        val id = commit { products.insert(newProduct("Pan inventado")) }
        buy(id, units = 5)
        val day = saleDay(sell(id, units = 1))
        val files = listOf(SummaryFile.Table(CsvExportTable.SALES), SummaryFile.ClosingInventory)

        writer().write(request(files, day.plusDays(-10), day.plusDays(-1))) {}

        val entries = unzipBytes(output)
        assertEquals(1, textOf(entries, "sales.csv").size)
        val inventory = textOf(entries, "closing_inventory.csv")
        assertFalse(inventory.any { it.contains("Pan inventado") })
    }

    @Test
    fun aFailedWriteOrAMissingPdfLeavesNoPackage() = runBlocking {
        commit { products.insert(newProduct("Arroz inventado")) }
        val day = requireNotNull(BusinessDate.parse("2026-10-01"))
        val files = listOf(SummaryFile.Table(CsvExportTable.SALES))
        val full =
            FileDocument(
                output,
                failWrite =
                    IOException("sin espacio", ErrnoException("write", OsConstants.ENOSPC)),
            )
        assertEquals(CsvExport.NoSpace, writer { full }.write(request(files, day, day)) {})
        assertEquals(1, full.discards)

        val different = FileDocument(output, rereadAs = ByteArray(10))
        assertEquals(CsvExport.Failed, writer { different }.write(request(files, day, day)) {})
        assertEquals(1, different.discards)

        pdf.delete()
        assertEquals(CsvExport.Failed, writer().write(request(files, day, day)) {})
        assertFalse("Nada queda con el nombre final", output.exists())
        assertTrue(audits.isEmpty())
    }

    @Test
    fun withoutTheDatabaseNothingIsWritten() = runBlocking {
        val document = FileDocument(output)
        val day = requireNotNull(BusinessDate.parse("2026-10-01"))
        val writer =
            SummaryPackageWriter(
                rows = { null },
                recordExport = { audits += Unit },
                log = MemoryLog(),
                ioContext = Dispatchers.IO,
                documentFor = { document },
            )

        assertEquals(CsvExport.Failed, writer.write(request(emptyList(), day, day)) {})
        assertEquals(0, document.writes)
        assertTrue(audits.isEmpty())
    }

    @Test
    fun theSummaryOffersWhatTheDatabaseHas() = runBlocking {
        val summaries = RoomManagementSummaryRepository(database)
        val id = commit { products.insert(newProduct("Pan inventado")) }
        buy(id, units = 5)
        val day = saleDay(sell(id, units = 1))
        val period = ReportPeriod.Range(day, day)
        val before = ReportPeriod.Range(day.plusDays(-10), day.plusDays(-1))

        val parties = count(
            "SELECT (SELECT COUNT(*) FROM customers WHERE archived_at IS NULL) + " +
                "(SELECT COUNT(*) FROM suppliers WHERE archived_at IS NULL)",
        ) > 0
        assertEquals(
            SummaryAvailability(hasPurchases = true, hasInventory = true, hasParties = parties),
            summaries.observeAvailability(period).first(),
        )
        // Diez días antes no había compras ni stock.
        val earlier = summaries.observeAvailability(before).first()
        assertFalse(earlier.hasPurchases)
        assertFalse(earlier.hasInventory)

        commit {
            RoomSupplierRepository(database).insert(
                SupplierDetails("Granja inventada", "granja inventada", null, null, null, null),
                AT,
            )
        }
        assertTrue(summaries.observeAvailability(before).first().hasParties)
    }

    // Apoyo

    private val rowWriter =
        CsvRowWriter(
            CsvFormat(CsvSeparator.SEMICOLON, DecimalSeparator.COMMA, currencyDecimals = 2),
            requireNotNull(BusinessTimeZone.of("Etc/GMT+5")),
        )

    private val naming =
        object : SummaryNaming {
            override fun fileName(table: CsvExportTable): String = "${table.key}.csv"

            override fun header(column: String): String = column

            override fun seedName(table: SeedTable, systemKey: String): String = "seed:$systemKey"

            override fun closingInventoryFileName(): String =
                "${SummaryFile.CLOSING_INVENTORY_KEY}.csv"
        }

    private fun request(files: List<SummaryFile>, from: BusinessDate, to: BusinessDate) =
        SummaryPackageRequest(
            targetPath = output.absolutePath,
            files = files,
            rows = rowWriter,
            naming = naming,
            range = requireNotNull(CsvDateRange.of(from, to)),
            pdfName = "summary.pdf",
            pdfPath = pdf.absolutePath,
        )

    private fun writer(document: (() -> CsvDocument)? = null) = SummaryPackageWriter(
        rows = { RoomCsvRowSource(database) },
        recordExport = { audits += Unit },
        log = MemoryLog(),
        ioContext = Dispatchers.IO,
        documentFor = { path -> document?.invoke() ?: FileCsvDocument(File(path)) },
    )

    private suspend fun saleDay(saleId: Long): BusinessDate =
        requireNotNull(RoomSaleRepository(database).findById(saleId)).content.businessDate

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

    /** Las líneas del CSV [name] de [entries]. */
    private fun textOf(entries: Map<String, ByteArray>, name: String): List<String> =
        csvLines(entries.getValue(name).toString(Charsets.UTF_8))

    /** Los archivos del `.zip` en [file], por nombre y en su orden, en bytes. */
    private fun unzipBytes(file: File): Map<String, ByteArray> {
        val files = linkedMapOf<String, ByteArray>()
        ZipInputStream(file.inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                files[entry.name] = zip.readBytes()
            }
        }
        return files
    }

    private companion object {
        /** Un PDF inventado: el paquete lo copia tal cual, sin leerlo. */
        val PDF_BYTES = "%PDF-1.4 inventado".toByteArray(Charsets.US_ASCII)
    }
}
