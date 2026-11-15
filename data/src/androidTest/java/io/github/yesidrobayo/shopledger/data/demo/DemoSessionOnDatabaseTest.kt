package io.github.yesidrobayo.shopledger.data.demo

import androidx.sqlite.SQLiteConnection
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yesidrobayo.shopledger.data.DataAccess
import io.github.yesidrobayo.shopledger.data.DataGraph
import io.github.yesidrobayo.shopledger.data.DataPorts
import io.github.yesidrobayo.shopledger.data.backup.BackupWorkQueue
import io.github.yesidrobayo.shopledger.data.backup.InvariantQueries
import io.github.yesidrobayo.shopledger.data.backup.MemoryLog
import io.github.yesidrobayo.shopledger.data.backup.RunKind
import io.github.yesidrobayo.shopledger.data.local.DatabaseFiles
import io.github.yesidrobayo.shopledger.data.local.deleteDatabaseFiles
import io.github.yesidrobayo.shopledger.data.local.inspectWithoutRoom
import io.github.yesidrobayo.shopledger.data.local.longValue
import io.github.yesidrobayo.shopledger.data.local.testDatabaseFile
import io.github.yesidrobayo.shopledger.data.local.texts
import io.github.yesidrobayo.shopledger.data.transaction.FIXED_CLOCK
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupUploadWork
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductForm
import io.github.yesidrobayo.shopledger.domain.model.settings.CsvSeparator
import io.github.yesidrobayo.shopledger.domain.model.settings.CurrencyCode
import io.github.yesidrobayo.shopledger.domain.model.settings.RegionalSettingsInput
import io.github.yesidrobayo.shopledger.domain.port.PaymentMethodNames
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.CreateProductUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.SaveProductResult
import io.github.yesidrobayo.shopledger.domain.usecase.region.BusinessCalendarSource
import io.github.yesidrobayo.shopledger.domain.usecase.region.CurrencyLock
import io.github.yesidrobayo.shopledger.domain.usecase.region.SaveRegionalSettingsResult
import io.github.yesidrobayo.shopledger.domain.usecase.region.SaveRegionalSettingsUseCase
import java.io.File
import kotlin.time.Duration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.DayOfWeek
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * El modo demostración sobre bases reales: la base de ejemplo vive aparte, la interfaz la usa
 * mientras existe la marca, y los trabajos y el respaldo siguen con la base del negocio.
 *
 * - Entrar y salir dejan idénticas todas las tablas del negocio de la base real. Solo
 *   `backup_state` queda fuera de la comparación: los trabajos escriben ahí su estado, también
 *   durante una demostración, porque el respaldo real sigue su curso.
 * - Escribir en la base de ejemplo no encola ningún trabajo ni cambia `change_seq` de la real, y
 *   un trabajo que abre la base durante la demostración recibe la real, con sus cambios
 *   pendientes.
 * - Un proceso que muere en la demostración sigue en ella; una demostración a medias se descarta y
 *   abre la base real.
 *
 * Cada "reinicio" es una capa de datos nueva sobre los mismos archivos, como un proceso nuevo. Los
 * datos son inventados, y la moneda es `XTS`, el código de pruebas de ISO 4217.
 */
@RunWith(AndroidJUnit4::class)
class DemoSessionOnDatabaseTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val file = testDatabaseFile(context, "demo-session-business.db")
    private val businessNoBackup = File(context.noBackupFilesDir, "demo-session-business")
    private val demoNoBackup = File(context.noBackupFilesDir, "demo-session-demo")
    private val demoFiles = DemoFiles(demoNoBackup)
    private val opened = mutableListOf<DataPorts>()

    @Before
    fun clean() {
        deleteEverything()
    }

    @After
    fun cleanUp() {
        runBlocking {
            for (ports in opened) {
                try {
                    ports.closeForRestore()
                } catch (_: Exception) {
                    // Ya cerrada: nada que hacer.
                }
            }
        }
        deleteEverything()
    }

    @Test
    fun enteringAndLeavingLeaveEveryBusinessTableIdentical() = runBlocking {
        val first = graph()
        val business = openBusinessWithData(first)
        val before = businessTables()
        val changeSeqBefore = changeSeqOf(business)

        assertEquals(DemoEntry.Ready, first.demo.enter(TEXTS))
        assertTrue(demoFiles.marker.isFile)
        assertTrue(demoFiles.database.isFile)
        assertEquals(before, businessTables())

        // Reinicio: la interfaz usa la base de ejemplo, y se escribe en ella.
        val inDemo = graph()
        assertTrue(inDemo.demo.isActive())
        val demo = ready(inDemo.open())
        assertEquals(demoFiles.database.absolutePath, demo.databaseFile.absolutePath)
        createProduct(demo, DEMO_ONLY_PRODUCT)
        assertEquals(before, businessTables())

        // Reinicio: salir borra la marca, y la base real vuelve tal como estaba.
        assertTrue(inDemo.demo.exit())
        assertFalse(demoFiles.marker.exists())
        val afterDemo = graph()
        assertFalse(afterDemo.demo.isActive())
        val back = ready(afterDemo.open())
        assertEquals(file.absolutePath, back.databaseFile.absolutePath)
        assertEquals(before, businessTables())
        assertEquals(changeSeqBefore, changeSeqOf(back))
        assertFalse(names(file).contains(DEMO_ONLY_PRODUCT))
        // Lo escrito en la demostración se perdió con ella.
        assertFalse(demoFiles.directory.exists())
    }

    @Test
    fun writingInTheDemoQueuesNothingAndWorkersKeepTheBusinessDatabase() = runBlocking {
        val first = graph()
        val business = openBusinessWithData(first)
        val changeSeqBefore = changeSeqOf(business)
        val verifiedBefore = requireNotNull(business.backupState.get()).verifiedSeq
        assertTrue("La prueba necesita cambios sin respaldar", changeSeqBefore > verifiedBefore)
        assertEquals(DemoEntry.Ready, first.demo.enter(TEXTS))

        // El proceso nuevo tiene su propia biblioteca de trabajos, para ver solo lo que él pide.
        val demoQueue = RecordingWorkQueue()
        val inDemo = graph(demoQueue)
        val demo = ready(inDemo.open())
        createProduct(demo, DEMO_ONLY_PRODUCT)
        // Confirmar en la base de ejemplo no programa nada: lo que se programa va aparte.
        delay(SETTLE_MILLIS)
        assertEquals(emptyList<String>(), demoQueue.calls)

        // Un trabajo recibe la base del negocio, con sus cambios pendientes de subir.
        val work = ready(inDemo.openForWork())
        assertNotSame(demo, work)
        assertEquals(file.absolutePath, work.databaseFile.absolutePath)
        assertEquals(changeSeqBefore, changeSeqOf(work))
        assertEquals(verifiedBefore, requireNotNull(work.backupState.get()).verifiedSeq)
        assertFalse(names(file).contains(DEMO_ONLY_PRODUCT))
        assertTrue(names(demoFiles.database).contains(DEMO_ONLY_PRODUCT))
        // Y la interfaz sigue con la de ejemplo.
        assertEquals(demoFiles.database.absolutePath, ready(inDemo.open()).databaseFile.path)
    }

    @Test
    fun aProcessThatDiesInTheDemoReopensInTheDemo() = runBlocking {
        val first = graph()
        openBusinessWithData(first)
        assertEquals(DemoEntry.Ready, first.demo.enter(TEXTS))
        createProduct(ready(graph().open()), DEMO_ONLY_PRODUCT)

        // Sin salir: el proceso murió. El arranque siguiente sigue en la demostración.
        val again = graph()
        assertTrue(again.demo.isActive())
        assertTrue(again.demo.active.value)
        val demo = ready(again.open())
        assertEquals(demoFiles.database.absolutePath, demo.databaseFile.absolutePath)
        assertTrue(names(demoFiles.database).contains(DEMO_ONLY_PRODUCT))
    }

    @Test
    fun theDemoBusinessIsWrittenWithTheBusinessRegionAndKeepsEveryInvariant() = runBlocking {
        val first = graph()
        openBusinessWithData(first)
        assertEquals(DemoEntry.Ready, first.demo.enter(TEXTS))

        val demo = ready(graph().open())
        val settings = demo.settings.get()
        assertEquals(REGION.currencyCode, settings.regional.currency.code)
        assertEquals(REGION.currencyDecimals, settings.regional.currency.decimals)
        assertEquals(REGION.formatLocaleTag, settings.regional.formatLocaleTag)
        assertEquals(REGION.businessTimeZoneId, settings.regional.businessTimeZoneId)
        // La demostración abre en Inicio, sin el primer uso, y con el nombre de ejemplo.
        assertTrue(settings.onboardingCompletedAt != null)
        assertEquals(TEXTS.businessName, settings.profile.businessName)
        inspectWithoutRoom(demoFiles.database) { connection ->
            assertEquals(TEXTS.products.sorted(), connection.texts(PRODUCT_NAMES).sorted())
            assertEquals(1L, connection.longValue("SELECT COUNT(*) FROM customers"))
            assertEquals(1L, connection.longValue("SELECT COUNT(*) FROM suppliers"))
            assertEquals(1L, connection.longValue("SELECT COUNT(*) FROM purchases"))
            assertEquals(1L, connection.longValue("SELECT COUNT(*) FROM expenses"))
            assertEquals(SALES, connection.longValue("SELECT COUNT(*) FROM sales"))
            for (query in InvariantQueries.MODEL) {
                val sql = query.sql.trimEnd().removeSuffix(";")
                assertEquals(query.label, 0L, connection.longValue("SELECT COUNT(*) FROM ($sql)"))
            }
        }
        // La base real no ganó nada del negocio de ejemplo.
        assertFalse(names(file).any { it in TEXTS.products })
    }

    @Test
    fun aHalfWrittenDemoIsDiscardedAndTheBusinessOpens() = runBlocking {
        openBusinessWithData(graph())
        // La marca quedó sin los datos de ejemplo enteros, como si el proceso hubiera muerto.
        demoFiles.prepareDirectory()
        File(demoFiles.directory, DemoFiles.DATABASE_NAME).writeText("a medias")
        demoFiles.activate()

        val next = graph()
        assertFalse(next.demo.isActive())
        assertEquals(file.absolutePath, ready(next.open()).databaseFile.absolutePath)
        assertFalse(demoFiles.marker.exists())
        assertFalse(demoFiles.directory.exists())
    }

    @Test
    fun aDemoDatabaseThatDoesNotOpenIsDiscardedAndTheBusinessOpens() = runBlocking {
        val first = graph()
        openBusinessWithData(first)
        assertEquals(DemoEntry.Ready, first.demo.enter(TEXTS))
        // La base de ejemplo se dañó: tiene la marca y sus datos, pero no es una base.
        deleteDatabaseFiles(demoFiles.database)
        demoFiles.database.writeText("no es una base")

        val next = graph()
        assertTrue(next.demo.isActive())
        assertEquals(file.absolutePath, ready(next.open()).databaseFile.absolutePath)
        assertFalse(next.demo.active.value)
        assertFalse(demoFiles.marker.exists())
        assertFalse(demoFiles.directory.exists())
    }

    @Test
    fun enteringWithoutAConfirmedCurrencyChangesNothing() = runBlocking {
        val first = graph()
        val business = ready(first.open())
        val before = businessTables()

        assertEquals(DemoEntry.CurrencyMissing, first.demo.enter(TEXTS))

        assertFalse(demoFiles.hasLeftovers())
        assertEquals(before, businessTables())
        assertEquals(file.absolutePath, business.databaseFile.absolutePath)
    }

    @Test
    fun enteringTwiceFromTheDemoChangesNothing() = runBlocking {
        val first = graph()
        openBusinessWithData(first)
        assertEquals(DemoEntry.Ready, first.demo.enter(TEXTS))
        val inDemo = graph()
        val demo = ready(inDemo.open())
        createProduct(demo, DEMO_ONLY_PRODUCT)

        assertEquals(DemoEntry.AlreadyActive, inDemo.demo.enter(TEXTS))
        assertTrue(names(demoFiles.database).contains(DEMO_ONLY_PRODUCT))
    }

    @Test
    fun theDemoFilesNeverNameTheBusinessDatabase() {
        val app = DemoFiles(context.noBackupFilesDir)
        assertNotEquals(DatabaseFiles.DATABASE_NAME, app.database.name)
        assertEquals(context.noBackupFilesDir, app.marker.parentFile)
        assertEquals(DemoFiles.MARKER_NAME, app.marker.name)
        assertNotEquals(
            DatabaseFiles.databaseFile(context).absoluteFile.parentFile,
            app.database.absoluteFile.parentFile,
        )
    }

    /** Una capa de datos nueva sobre los mismos archivos: un proceso nuevo. */
    private fun graph(queue: RecordingWorkQueue = RecordingWorkQueue()): DataGraph = DataGraph(
        context,
        Dispatchers.IO,
        FIXED_CLOCK,
        { DatabaseFiles(file, businessNoBackup) },
        MemoryLog(),
        workQueue = { queue },
        demoFiles = { demoFiles },
    )

    /** Abre la base del negocio, confirma la moneda y crea un producto con stock. */
    private suspend fun openBusinessWithData(graph: DataGraph): DataPorts {
        val ports = ready(graph.open())
        assertEquals(file.absolutePath, ports.databaseFile.absolutePath)
        assertEquals(
            SaveRegionalSettingsResult.Saved,
            SaveRegionalSettingsUseCase(ports.transactions, ports.settings, FIXED_CLOCK)(REGION),
        )
        createProduct(ports, BUSINESS_PRODUCT)
        return ports
    }

    private suspend fun createProduct(ports: DataPorts, name: String) {
        val create = CreateProductUseCase(
            ports.transactions, ports.products, ports.categories, ports.suppliers, ports.units,
            ports.systemRows, ports.priceHistory, ports.stockLedger, ports.lowStockAlerts,
            BusinessCalendarSource(ports.settings), CurrencyLock(ports.settings), FIXED_CLOCK,
            ports.mediaAssets,
        )
        val form = ProductForm(name = name, salePrice = Money(1_500), cost = Money(900))
        val result = create(form, QuantityMilli(5_000))
        assertTrue("No se guardó: $result", result is SaveProductResult.Saved)
    }

    private fun ready(access: Any): DataPorts {
        assertTrue("No abrió: $access", access is DataAccess.Ready)
        return (access as DataAccess.Ready).ports.also { opened += it }
    }

    private suspend fun changeSeqOf(ports: DataPorts): Long =
        requireNotNull(ports.backupState.get()).changeSeq

    private fun names(database: File): List<String> =
        inspectWithoutRoom(database) { it.texts(PRODUCT_NAMES) }

    /**
     * Cada fila de cada tabla de la base del negocio, con todas sus columnas, salvo
     * `backup_state`, que es del respaldo y no del negocio, y las tablas internas de SQLite y
     * Room.
     */
    private fun businessTables(): Map<String, List<String>> = inspectWithoutRoom(file) {
        val tables = it.texts(
            "SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' " +
                "AND name NOT IN ('room_master_table', 'backup_state') ORDER BY name",
        )
        tables.associateWith { table -> rowsOf(it, table) }
    }

    private fun rowsOf(connection: SQLiteConnection, table: String): List<String> {
        val columns = connection.texts("SELECT name FROM pragma_table_info('$table')")
        val row = columns.joinToString(" || '|' || ") { "quote(\"$it\")" }
        return connection.texts("SELECT $row FROM \"$table\"").sorted()
    }

    private fun deleteEverything() {
        deleteDatabaseFiles(file)
        businessNoBackup.deleteRecursively()
        demoNoBackup.deleteRecursively()
    }

    /** La biblioteca de trabajos, que solo anota lo que se le pide. */
    private class RecordingWorkQueue : BackupWorkQueue {
        private val recorded = mutableListOf<String>()

        val calls: List<String> get() = synchronized(recorded) { recorded.toList() }

        private fun record(call: String) {
            synchronized(recorded) { recorded += call }
        }

        override suspend fun restartDebounce(delay: Duration) = record("debounce")

        override suspend fun keepUnmetered() = record("keep unmetered")

        override suspend fun keepUpload(delay: Duration, needsNetwork: Boolean) =
            record("keep upload")

        override suspend fun replaceUpload(needsNetwork: Boolean, kind: RunKind) =
            record("replace upload")

        override suspend fun cancel(name: String) = record("cancel")

        override suspend fun uploadWork(): BackupUploadWork = BackupUploadWork.NONE

        override suspend fun awaitNoneRunning(names: List<String>) = record("await")

        override suspend fun keepDailyMaintenance() = record("keep maintenance")

        override suspend fun keepHeadChecks(needsNetwork: Boolean) = record("keep head checks")

        override suspend fun keepHeadProbeOnce(needsNetwork: Boolean) = record("probe once")

        override suspend fun keepSecondDestination() = record("keep second destination")

        override suspend fun keepMediaSync(onMetered: Boolean) = record("keep media sync")
    }

    private companion object {
        const val SETTLE_MILLIS = 500L
        const val SALES = 12L
        const val BUSINESS_PRODUCT = "Producto real inventado"
        const val DEMO_ONLY_PRODUCT = "Producto escrito en la demostración"
        const val PRODUCT_NAMES = "SELECT name FROM products"

        /** La región que confirmó la persona: moneda de pruebas de dos decimales. */
        val REGION = RegionalSettingsInput(
            currencyCode = requireNotNull(CurrencyCode.of("XTS")),
            currencyDecimals = 2,
            currencySymbol = "¤",
            cashRounding = Money.ZERO,
            formatLocaleTag = "pt-CV",
            businessTimeZoneId = "Etc/GMT+3",
            weekStartDay = DayOfWeek.MONDAY,
            csvSeparator = CsvSeparator.SEMICOLON,
        )

        val TEXTS = DemoTexts(
            businessName = "Negocio de ejemplo",
            products = List(DemoTexts.PRODUCT_COUNT) { "Producto de ejemplo ${it + 1}" },
            customer = "Clienta de ejemplo",
            supplier = "Proveedor de ejemplo",
            expense = "Gasto de ejemplo",
            paymentMethodNames = PaymentMethodNames { key -> "Inicial ${key.lowercase()}" },
        )
    }
}
