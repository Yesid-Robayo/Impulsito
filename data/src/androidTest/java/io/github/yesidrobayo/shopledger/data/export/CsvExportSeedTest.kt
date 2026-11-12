package io.github.yesidrobayo.shopledger.data.export

import android.util.Log
import androidx.room3.useReaderConnection
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yesidrobayo.shopledger.data.backup.MemoryLog
import io.github.yesidrobayo.shopledger.data.local.AppDatabase
import io.github.yesidrobayo.shopledger.data.local.deleteDatabaseFiles
import io.github.yesidrobayo.shopledger.data.local.testDatabaseFile
import io.github.yesidrobayo.shopledger.data.seed.SPANISH_ROW
import io.github.yesidrobayo.shopledger.data.seed.SeedDataGenerator
import io.github.yesidrobayo.shopledger.data.seed.SeedProfile
import io.github.yesidrobayo.shopledger.data.seed.seedConfigOf
import io.github.yesidrobayo.shopledger.domain.model.BusinessTimeZone
import io.github.yesidrobayo.shopledger.domain.model.SeedTable
import io.github.yesidrobayo.shopledger.domain.model.export.CsvExport
import io.github.yesidrobayo.shopledger.domain.model.export.CsvExportTable
import io.github.yesidrobayo.shopledger.domain.model.region.DecimalSeparator
import io.github.yesidrobayo.shopledger.domain.port.CsvExportRequest
import io.github.yesidrobayo.shopledger.domain.port.CsvNaming
import io.github.yesidrobayo.shopledger.domain.rule.export.CsvRowWriter
import io.github.yesidrobayo.shopledger.domain.rule.region.CsvFormat
import java.io.File
import kotlin.time.TimeSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * La exportación de un negocio inventado completo, del generador de datos: cada archivo trae una
 * fila por registro de su tabla, todas con tantas celdas como su encabezado, y sin ninguna fórmula
 * sin neutralizar. Con el argumento `csvVolume=true` del runner, exporta además el volumen de
 * referencia y anota cuánto tardó y cuánto pesa: un emulador no es el teléfono de referencia, así
 * que no exige un tope. Con `csvCopy=true` deja una copia de cada `.zip` en la caché, para abrirla en una
 * hoja de cálculo. Solo registra cifras, nunca un dato del negocio.
 */
@RunWith(AndroidJUnit4::class)
internal class CsvExportSeedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val target = testDatabaseFile(context, "csv-export-seed.db")
    private val output = File(context.cacheDir, "csv-export-seed.zip")

    @After
    fun deleteFiles() {
        deleteDatabaseFiles(target)
        output.delete()
    }

    @Test
    fun aSmallBusinessExportsEveryRecordWithItsColumns() = runBlocking<Unit> {
        val (result, _) = exportProfile(SeedProfile.SMALL)
        assertTrue("$result", result is CsvExport.Saved)
    }

    @Test
    fun theReferenceVolumeExportMeasurement() = runBlocking<Unit> {
        assumeTrue(InstrumentationRegistry.getArguments().getString(ARGUMENT) == "true")
        val (result, millis) = exportProfile(SeedProfile.VOLUME)
        assertTrue("$result", result is CsvExport.Saved)
        Log.i(TAG, "volumen: ${(result as CsvExport.Saved).rows} filas en $millis ms")
        Log.i(TAG, "volumen: ${output.length()} bytes")
    }

    /** Genera [profile], lo exporta y lo revisa. Devuelve el resultado y cuánto tardó exportar. */
    private suspend fun exportProfile(profile: SeedProfile): Pair<CsvExport, Long> {
        deleteDatabaseFiles(target)
        output.delete()
        val config = requireNotNull(seedConfigOf(SPANISH_ROW))
        SeedDataGenerator(SeedDataGenerator.DEFAULT_SEED, profile, config).generate(context, target)
        val database = AppDatabase.open(context, target, Dispatchers.IO)
        try {
            val format =
                CsvFormat(config.csvSeparator, DecimalSeparator.COMMA, config.currencyDecimals)
            val zone = requireNotNull(BusinessTimeZone.of(config.businessTimeZoneId))
            val request =
                CsvExportRequest("content://test/seed", CsvRowWriter(format, zone), NAMING)
            val exporter =
                CsvExporter(
                    rows = { RoomCsvRowSource(database) },
                    documentFor = { FileDocument(output) },
                    recordExport = {},
                    log = MemoryLog(),
                    ioContext = Dispatchers.IO,
                )
            val mark = TimeSource.Monotonic.markNow()
            val result = exporter.export(request) {}
            val millis = mark.elapsedNow().inWholeMilliseconds
            check(database, config.csvSeparator.char)
            // Con `csvCopy=true`, deja una copia para abrirla en una hoja de cálculo fuera del teléfono.
            if (InstrumentationRegistry.getArguments().getString(COPY_ARGUMENT) == "true") {
                output.copyTo(File(context.cacheDir, "csv-export-$profile.zip"), overwrite = true)
            }
            return result to millis
        } finally {
            database.close()
        }
    }

    private suspend fun check(database: AppDatabase, separator: Char) {
        val files = unzipCsv(output)
        CsvExportTable.entries.forEach { table ->
            val records = csvRecords(files.getValue("${table.key}.csv"), separator)
            val header = records.first()
            assertEquals(table.columns.size, header.size)
            val rows = records.drop(1)
            assertEquals(table.key, count(database, SOURCE.getValue(table)), rows.size.toLong())
            rows.forEach { cells ->
                assertEquals(table.key, header.size, cells.size)
                // Una celda de texto nunca empieza como fórmula: lleva el apóstrofo delante.
                cells.filter { it.startsWith("\"") }.forEach { cell ->
                    val text = cell.removePrefix("\"").trimStart(' ')
                    assertTrue(cell, text.firstOrNull() !in FORMULA_STARTS)
                }
            }
        }
    }

    private suspend fun count(database: AppDatabase, tableName: String): Long =
        database.useReaderConnection { connection ->
            connection.usePrepared("SELECT COUNT(*) FROM $tableName") {
                it.step()
                it.getLong(0)
            }
        }

    private companion object {
        const val ARGUMENT = "csvVolume"
        const val COPY_ARGUMENT = "csvCopy"
        const val TAG = "CsvExportSeed"

        val FORMULA_STARTS = setOf('=', '+', '-', '@', '\t', '\r', '\n')

        /** La tabla de cada archivo, para contar sus registros. */
        val SOURCE: Map<CsvExportTable, String> =
            mapOf(
                CsvExportTable.PRODUCTS to "products",
                CsvExportTable.SUPPLIERS to "suppliers",
                CsvExportTable.SALES to "sales",
                CsvExportTable.SALE_LINES to "sale_lines",
                CsvExportTable.PURCHASES to "purchases",
                CsvExportTable.PURCHASE_LINES to "purchase_lines",
                CsvExportTable.STOCK_MOVEMENTS to "stock_movements",
                CsvExportTable.EXPENSES to "expenses",
                CsvExportTable.PRICE_HISTORY to "product_price_history",
            )

        val NAMING =
            object : CsvNaming {
                override fun fileName(table: CsvExportTable): String = "${table.key}.csv"

                override fun header(column: String): String = column

                override fun seedName(table: SeedTable, systemKey: String): String = systemKey
            }
    }
}
