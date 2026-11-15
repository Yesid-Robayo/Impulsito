package io.github.yesidrobayo.shopledger.data.repository

import android.content.Context
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yesidrobayo.shopledger.data.DataAccess
import io.github.yesidrobayo.shopledger.data.DataGraph
import io.github.yesidrobayo.shopledger.data.DataPorts
import io.github.yesidrobayo.shopledger.data.backup.BackupFixture
import io.github.yesidrobayo.shopledger.data.backup.BackupRun
import io.github.yesidrobayo.shopledger.data.backup.BackupWorkQueue
import io.github.yesidrobayo.shopledger.data.backup.DurableFileOps
import io.github.yesidrobayo.shopledger.data.backup.InvariantQueries
import io.github.yesidrobayo.shopledger.data.backup.MemoryLog
import io.github.yesidrobayo.shopledger.data.backup.OperationListener
import io.github.yesidrobayo.shopledger.data.backup.RunKind
import io.github.yesidrobayo.shopledger.data.backup.TEST_DESTINATION_ID
import io.github.yesidrobayo.shopledger.data.backup.TestControl
import io.github.yesidrobayo.shopledger.data.demo.DemoEntry
import io.github.yesidrobayo.shopledger.data.demo.DemoFiles
import io.github.yesidrobayo.shopledger.data.demo.DemoTexts
import io.github.yesidrobayo.shopledger.data.destination.APP_FOLDER
import io.github.yesidrobayo.shopledger.data.destination.FakeHttpServer
import io.github.yesidrobayo.shopledger.data.destination.FakeResponse
import io.github.yesidrobayo.shopledger.data.destination.InMemoryWebDav
import io.github.yesidrobayo.shopledger.data.destination.WebDavDestination
import io.github.yesidrobayo.shopledger.data.destination.WebDavMediaChannel
import io.github.yesidrobayo.shopledger.data.destination.slots.FileSlotStateStore
import io.github.yesidrobayo.shopledger.data.destination.testClient
import io.github.yesidrobayo.shopledger.data.erase.EraseJournalStore
import io.github.yesidrobayo.shopledger.data.erase.EraseService
import io.github.yesidrobayo.shopledger.data.erase.LocalEraser
import io.github.yesidrobayo.shopledger.data.erase.RemoteEraser
import io.github.yesidrobayo.shopledger.data.erase.SlotDocumentDelete
import io.github.yesidrobayo.shopledger.data.export.CsvExporter
import io.github.yesidrobayo.shopledger.data.export.FileDocument
import io.github.yesidrobayo.shopledger.data.export.recordCsvExported
import io.github.yesidrobayo.shopledger.data.local.DatabaseFiles
import io.github.yesidrobayo.shopledger.data.local.audit.AUDIT_EVENTS_MAX_ROWS
import io.github.yesidrobayo.shopledger.data.local.backup.BackupStateEntity
import io.github.yesidrobayo.shopledger.data.local.insertAuditEventsSql
import io.github.yesidrobayo.shopledger.data.local.inspectWithoutRoom
import io.github.yesidrobayo.shopledger.data.local.longValue
import io.github.yesidrobayo.shopledger.data.local.texts
import io.github.yesidrobayo.shopledger.data.log.DataEvent
import io.github.yesidrobayo.shopledger.data.media.MediaOpen
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.SeedTable
import io.github.yesidrobayo.shopledger.domain.model.audit.AuditEventType
import io.github.yesidrobayo.shopledger.domain.model.audit.AuditResult
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupDestinationKind
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupUploadWork
import io.github.yesidrobayo.shopledger.domain.model.backup.RecoveryCodeView
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductForm
import io.github.yesidrobayo.shopledger.domain.model.erase.EraseOutcome
import io.github.yesidrobayo.shopledger.domain.model.erase.ErasePhase
import io.github.yesidrobayo.shopledger.domain.model.erase.EraseScope
import io.github.yesidrobayo.shopledger.domain.model.erase.EraseStop
import io.github.yesidrobayo.shopledger.domain.model.export.CsvExport
import io.github.yesidrobayo.shopledger.domain.model.export.CsvExportTable
import io.github.yesidrobayo.shopledger.domain.model.region.DecimalSeparator
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleLineRequest
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleRequest
import io.github.yesidrobayo.shopledger.domain.model.settings.CsvSeparator
import io.github.yesidrobayo.shopledger.domain.model.settings.CurrencyCode
import io.github.yesidrobayo.shopledger.domain.model.settings.RegionalSettingsInput
import io.github.yesidrobayo.shopledger.domain.model.surface.AmountDecision
import io.github.yesidrobayo.shopledger.domain.model.surface.DeviceLockState
import io.github.yesidrobayo.shopledger.domain.model.surface.WidgetContent
import io.github.yesidrobayo.shopledger.domain.model.surface.WidgetFacts
import io.github.yesidrobayo.shopledger.domain.port.AuditRecorder
import io.github.yesidrobayo.shopledger.domain.port.CsvNaming
import io.github.yesidrobayo.shopledger.domain.port.PaymentMethodNames
import io.github.yesidrobayo.shopledger.domain.rule.surface.AmountVisibilityPolicy
import io.github.yesidrobayo.shopledger.domain.rule.surface.QuickSaleWidgetRules
import io.github.yesidrobayo.shopledger.domain.usecase.backup.EncryptionUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.CreateProductUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.SaveProductResult
import io.github.yesidrobayo.shopledger.domain.usecase.erase.EraseMyDataUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.export.ExportCsvUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.home.ObserveTodaySalesUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.home.TodaySales
import io.github.yesidrobayo.shopledger.domain.usecase.region.BusinessCalendarSource
import io.github.yesidrobayo.shopledger.domain.usecase.region.CurrencyLock
import io.github.yesidrobayo.shopledger.domain.usecase.region.SaveRegionalSettingsResult
import io.github.yesidrobayo.shopledger.domain.usecase.region.SaveRegionalSettingsUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.sale.RegisterSaleResult
import io.github.yesidrobayo.shopledger.domain.usecase.sale.RegisterSaleUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.stock.ObserveInventoryUseCase
import java.io.File
import java.util.Collections
import kotlin.time.Duration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.datetime.DayOfWeek
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Simulacro de la privacidad sobre una base nueva, por el camino de la app: los puertos de la base
 * abierta, la capa de datos de cada proceso y los casos de uso armados como los arman sus
 * pantallas. Una panadería inventada confirma su moneda, carga dos productos y vende dos veces en
 * efectivo; después recorre lo que la app promete sobre los datos de la persona:
 *
 * 1. **La demostración aislada.** Con la foto de cada tabla del negocio y de `change_seq`, entra a
 *    la demostración, vende en `demo.db` y comprueba que nada llega a la base del negocio, que no
 *    se encola ningún trabajo del respaldo y que un trabajo sigue recibiendo la base real. Al
 *    salir y reiniciar, cada tabla del negocio sigue idéntica.
 * 2. **Sin montos fuera de la app.** El widget lee la base del negocio, nunca la de ejemplo, y la
 *    regla de los montos los oculta por defecto, con el teléfono bloqueado y en modo discreto.
 * 3. **La auditoría.** Cambiar de destino, exportar y pedir el código de recuperación dejan su
 *    fila en `audit_events`, sin montos ni nombres, sin sumar a `change_seq`, y el tope de 5.000
 *    filas se respeta podando la más vieja.
 * 4. **Borrar mis datos.** Con una copia, su `.slmeta` y una foto en un servicio WebDAV falso,
 *    primero se borran en el servicio solo los archivos con los nombres exactos de la app, y los
 *    de la persona quedan; después, todo este teléfono. Aparte: un servicio que no deja borrar
 *    deja este teléfono intacto, y una muerte después de vaciar la nube se retoma hasta el final.
 *
 * Cada cifra se compara con un valor escrito a mano en los comentarios, y cada consulta de
 * invariantes del modelo de datos da cero filas antes del borrado.
 *
 * Lo que no vive en esta capa queda fuera: el dibujo del widget y del mosaico, la lectura real del
 * bloqueo del teléfono y la pantalla de `Borrar mis datos`. El sobre de la credencial lleva aquí
 * bytes inventados, y soltar el acceso se cuenta como un paso: la capa de datos de la app lo hace
 * con su clave de Keystore, sobre su propia carpeta.
 *
 * El negocio, los productos, el usuario del servicio y los montos son inventados. La moneda es
 * `XTS`, el código de pruebas de ISO 4217, con dos decimales: los montos van en su unidad menor.
 */
@RunWith(AndroidJUnit4::class)
internal class PrivacyDrillTest {
    /** La muerte del proceso en una operación: nada la atrapa, como en un teléfono. */
    private class ProcessDeath : Error()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val fixture = BackupFixture(context, "privacy-drill")
    private val control = TestControl()
    private val demoFiles = DemoFiles(fixture.noBackup)
    private val appFolder = File(context.cacheDir, "privacy-drill-files")
    private val slotDirectory = File(fixture.noBackup, "slots")
    private val servers = ArrayList<FakeHttpServer>()

    /** Las capas de datos extra que abrieron la base: se cierran antes del borrado. */
    private val opened = mutableListOf<DataPorts>()

    /** Lo que pasa al borrar, en orden: el servicio, el registro, la auditoría y lo local. */
    private val steps: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private var refuseDeletes = false
    private var released = 0
    private var databaseClosed = false
    private lateinit var dav: InMemoryWebDav
    private lateinit var server: FakeHttpServer

    /** Los puertos de la base del negocio: los mismos que la app da a sus pantallas. */
    private val ports: DataPorts get() = fixture.ports

    /** La auditoría de la base del negocio, como la de la app: sin base abierta no anota nada. */
    private val auditRecorder by lazy {
        RoomAuditRecorder(
            events = { if (databaseClosed) null else ports.auditEvents },
            clock = fixture.clock,
            log = fixture.log,
        )
    }

    private val exportFiles = HashMap<String, File>()

    private val exportData by lazy {
        val exporter =
            CsvExporter(
                rows = { ports.csvRows },
                documentFor = { uri -> FileDocument(exportFiles.getValue(uri)) },
                recordExport = { recordCsvExported(ports, fixture.clock, MemoryLog()) },
                log = MemoryLog(),
                ioContext = Dispatchers.IO,
            )
        ExportCsvUseCase(ports.settings, exporter) { DecimalSeparator.COMMA }
    }

    // Lo que comparten los pasos: los dos productos y el medio de pago en efectivo.
    private var bread = 0L
    private var coffee = 0L
    private var cashMethod = 0L

    @Before
    fun open() = runBlocking {
        fixture.open()
        appFolder.deleteRecursively()
        // Una foto de un producto en la carpeta de la app, y lo que la app deja en
        // `noBackupFilesDir`: el sobre de la credencial, aquí con bytes inventados, y las marcas
        // de la higiene de la cuenta.
        File(appFolder, "media/ab").mkdirs()
        File(appFolder, "media/ab/$PHOTO.webp").writeBytes(byteArrayOf(1, 2, 3))
        File(fixture.noBackup, WEBDAV_SEALED).writeBytes(byteArrayOf(7, 7, 7))
        File(fixture.noBackup, ACCOUNT_CHECKLIST).writeText("{}")
        dav =
            InMemoryWebDav(override = { request ->
                if (request.method != "DELETE") return@InMemoryWebDav null
                steps += "remote ${request.path}"
                if (refuseDeletes) FakeResponse.status(FORBIDDEN) else null
            })
        dav.collections += "/dav/app/"
        server = FakeHttpServer(keepAlive = true, handler = dav::handle).also { servers += it }
    }

    @After
    fun close() {
        closeOtherProcesses()
        servers.forEach { it.close() }
        appFolder.deleteRecursively()
        fixture.close()
    }

    @Test
    fun aDayOfPrivacyMatchesHandWrittenChecks() = runBlocking {
        business()
        isolatedDemo()
        copiesInTheCloud()
        sensitiveActionsAreAudited()
        theAuditKeepsItsCap()
        invariants()
        eraseMyData()
    }

    @Test
    fun aServiceThatRefusesLeavesThisPhoneIntact() = runBlocking {
        business()
        copiesInTheCloud()
        refuseDeletes = true
        val before = dav.files.keys.toList()
        val seq = fixture.state().changeSeq

        val refused = erase()(EraseScope.DEVICE_AND_COPIES)

        // El servicio contesta 403 al primer borrado: nada se borró allí, y la fase queda en la
        // nube, para reintentar o pasar a lo local solo con otra confirmación.
        assertEquals(EraseOutcome.Stopped(EraseStop.REFUSED), refused)
        assertEquals(before, dav.files.keys.toList())
        assertEquals(ErasePhase.REMOTE.name, journalText())
        assertThisPhoneIsUntouched()
        // El intento queda en la auditoría como un fallo, sin contar como cambio del negocio.
        assertEquals(
            listOf("DESTINATION_CHANGED|SUCCESS", "DELETE_MY_DATA|FAILURE"),
            auditRows(),
        )
        assertEquals(seq, fixture.state().changeSeq)
    }

    @Test
    fun aDeathAfterTheCloudIsResumedToTheEnd() = runBlocking {
        business()
        copiesInTheCloud()
        val dying =
            DurableFileOps(
                listener =
                    OperationListener { operation ->
                        if (operation.endsWith("/${fixture.file.name}")) throw ProcessDeath()
                    },
            )
        try {
            erase(dying)(EraseScope.DEVICE_AND_COPIES)
            error("El proceso debía morir")
        } catch (_: ProcessDeath) {
            Unit
        }
        // La nube ya está vacía, y la fase guardada lo dice; este teléfono quedó a medias: sin la
        // base, pero con el sobre de la credencial, las marcas y la foto local.
        assertEquals(emptyList<String>(), appFilesInTheCloud())
        assertEquals(ErasePhase.LOCAL.name, journalText())
        assertFalse(fixture.file.exists())
        assertTrue(File(fixture.noBackup, WEBDAV_SEALED).exists())
        assertTrue(File(fixture.noBackup, DatabaseFiles.INITIALIZED_MARKER_NAME).exists())
        assertTrue(File(appFolder, "media/ab/$PHOTO.webp").exists())
        val requests = server.requests.size

        val resumed = erase().resume()

        // Se retoma desde lo local, sin volver a pedirle nada al servicio. Soltar el acceso se
        // repite al retomar, y es seguro: lo que ya no está cuenta como suelto.
        assertEquals(EraseOutcome.Finished, resumed)
        assertEquals(requests, server.requests.size)
        assertThisPhoneIsEmpty(releases = 2)
        assertEquals(FOREIGN_FILES, dav.files.keys.toSet())
    }

    /** La moneda, dos productos con stock y dos ventas en efectivo, como en sus pantallas. */
    private suspend fun business() {
        // 1. La persona confirma la moneda en su pantalla. En una base nueva la caja inicial toma
        // esa moneda en la misma transacción: no queda en `XXX`, como en la base de las pruebas
        // del catálogo.
        assertEquals(
            SaveRegionalSettingsResult.Saved,
            SaveRegionalSettingsUseCase(ports.transactions, ports.settings, fixture.clock)(REGION),
        )
        assertEquals(
            listOf("XTS"),
            businessTexts("SELECT currency_code FROM money_accounts WHERE system_key = 'CASH'"),
        )

        // 2. El catálogo: 20 panes a 25,00 y 10 cafés a 40,00, sin mínimo de stock.
        bread = createProduct(ports, BREAD, QuantityMilli(20_000))
        coffee = createProduct(ports, COFFEE, QuantityMilli(10_000))
        cashMethod = businessLong("SELECT id FROM payment_methods WHERE system_key = 'CASH'")

        // 3. Dos ventas en efectivo: 3 panes, 75,00, y 2 cafés, 80,00. Hoy se vendieron 155,00.
        sell(ports, SaleRequest(listOf(SaleLineRequest(bread, QuantityMilli(3_000))), cashMethod))
        sell(ports, SaleRequest(listOf(SaleLineRequest(coffee, QuantityMilli(2_000))), cashMethod))
        assertEquals(2L, businessLong("SELECT COUNT(*) FROM sales"))
        // Ninguna acción sensible todavía: la auditoría está vacía.
        assertEquals(emptyList<String>(), auditRows())
    }

    /** La demostración: otra base, otros puertos, y la del negocio sin un solo cambio. */
    private suspend fun isolatedDemo() {
        // 4. La foto del negocio antes de entrar: cada fila de cada tabla, y lo que falta subir.
        val before = businessTables()
        val changeSeq = fixture.state().changeSeq
        val verifiedSeq = fixture.state().verifiedSeq
        assertTrue("Hay cambios sin respaldar", changeSeq > verifiedSeq)

        // 5. Entrar solo lee la región del negocio, prepara `demo.db` y deja la marca.
        val first = graph()
        ready(first.open())
        assertEquals(DemoEntry.Ready, first.demo.enter(TEXTS))
        assertTrue(demoFiles.marker.isFile)
        assertEquals(before, businessTables())

        // 6. Al reiniciar, la interfaz usa la base de ejemplo, que trae 12 ventas de ejemplo, y se
        // vende una más en ella. El proceso nuevo tiene su propia biblioteca de trabajos.
        val queue = RecordingWorkQueue()
        val inDemo = graph(queue)
        assertTrue(inDemo.demo.isActive())
        val demo = ready(inDemo.open())
        assertEquals(demoFiles.database.absolutePath, demo.databaseFile.absolutePath)
        assertEquals(DEMO_SALES, demoLong("SELECT COUNT(*) FROM sales"))
        val demoProduct = demoLong("SELECT id FROM products WHERE name = '${TEXTS.products[0]}'")
        val demoCash = demoLong("SELECT id FROM payment_methods WHERE system_key = 'CASH'")
        val demoLine = SaleLineRequest(demoProduct, QuantityMilli(1_000))
        sell(demo, SaleRequest(listOf(demoLine), demoCash))
        assertEquals(DEMO_SALES + 1, demoLong("SELECT COUNT(*) FROM sales"))
        // Confirmar en la base de ejemplo no programa ninguna copia.
        delay(SETTLE_MILLIS)
        assertEquals(emptyList<String>(), queue.calls)
        // Nada llegó al negocio: ni una fila, ni un cambio sin respaldar.
        assertEquals(before, businessTables())
        assertEquals(changeSeq, fixture.state().changeSeq)

        // 7. Un trabajo, como el widget o el respaldo, recibe la base del negocio con sus cambios
        // pendientes, y la interfaz sigue con la de ejemplo.
        val work = ready(inDemo.openForWork())
        assertEquals(fixture.file.absolutePath, work.databaseFile.absolutePath)
        assertEquals(changeSeq, requireNotNull(work.backupState.get()).changeSeq)
        assertEquals(verifiedSeq, requireNotNull(work.backupState.get()).verifiedSeq)
        assertEquals(demoFiles.database.absolutePath, ready(inDemo.open()).databaseFile.path)
        amountsStayOffSurfaces(widgetFacts(work))

        // 8. Salir borra la marca, y el proceso termina; al reiniciar, la base de ejemplo se
        // descarta y vuelve la del negocio, idéntica, con 2 ventas y sin ninguna de la
        // demostración.
        assertTrue(inDemo.demo.exit())
        assertFalse(demoFiles.marker.exists())
        delay(SETTLE_MILLIS)
        closeOtherProcesses()
        val back = graph()
        assertFalse(back.demo.isActive())
        assertFalse(demoFiles.directory.exists())
        assertEquals(fixture.file.absolutePath, ready(back.open()).databaseFile.absolutePath)
        assertEquals(before, businessTables())
        assertEquals(changeSeq, fixture.state().changeSeq)
        assertEquals(2L, businessLong("SELECT COUNT(*) FROM sales"))

        // 9. La persona vuelve a la demostración: su base queda lista para el borrado, que también
        // debe llevársela.
        assertEquals(DemoEntry.Ready, back.demo.enter(TEXTS))
        assertTrue(demoFiles.isComplete())
        assertEquals(before, businessTables())
        delay(SETTLE_MILLIS)
        closeOtherProcesses()
    }

    /**
     * El widget con lo que lee de la base del negocio, y la regla única de los montos. El mosaico
     * usa la misma regla. Las dos ventas del negocio, no las 13 de la demostración: 155,00.
     */
    private fun amountsStayOffSurfaces(facts: WidgetFacts) {
        assertEquals(WidgetFacts(true, 2L, Money(15_500), 0, false), facts)
        val open = DeviceLockState(screenOn = true, keyguardShowing = false, deviceLocked = false)
        val locked = DeviceLockState(screenOn = true, keyguardShowing = true, deviceLocked = true)
        assertEquals(AmountDecision.HIDDEN_BY_DEFAULT, decide(chosen = false, open, false))
        assertEquals(AmountDecision.HIDDEN_LOCKED, decide(chosen = true, locked, false))
        assertEquals(
            AmountDecision.HIDDEN_LOCKED,
            decide(chosen = true, DeviceLockState.fullyLocked, false),
        )
        assertEquals(AmountDecision.HIDDEN_DISCREET, decide(chosen = true, open, true))
        assertEquals(AmountDecision.VISIBLE, decide(chosen = true, open, false))
        // Un widget recién puesto muestra los conteos, nunca el monto.
        assertEquals(
            WidgetContent.Summary(
                salesCount = 2L,
                lowStockCount = 0,
                amountsChosen = false,
                amount = null,
                backupFailed = false,
            ),
            QuickSaleWidgetRules.contentOf(facts, false, open, discreetMode = false),
        )
        assertNull(amountOf(QuickSaleWidgetRules.contentOf(facts, true, locked, false)))
        assertNull(amountOf(QuickSaleWidgetRules.contentOf(facts, true, open, true)))
        assertEquals(
            Money(15_500),
            amountOf(QuickSaleWidgetRules.contentOf(facts, true, open, false)),
        )
    }

    /** Conectar el servicio, subir la primera copia y dejar una foto y archivos de la persona. */
    private suspend fun copiesInTheCloud() {
        // 10. Conectar un servicio WebDAV, como lo guarda su pantalla: es un cambio del negocio,
        // que sube `change_seq` en 1, y queda en la auditoría después, sin sumar nada.
        val seq = fixture.state().changeSeq
        assertEquals(
            1,
            ports.backupState.connectDestination(
                kind = BackupDestinationKind.WEBDAV.name,
                account = "usuario-inventado",
                destinationId = TEST_DESTINATION_ID,
                folder = "https://dav.example.com/app/",
                now = fixture.clock.nowEpochMillis(),
            ),
        )
        recordAuditEvent(
            ports.auditEvents,
            AuditEventType.DESTINATION_CHANGED,
            AuditResult.SUCCESS,
            fixture.clock,
            fixture.log,
            DataEvent.DESTINATION_AUDIT_FAILED,
        )
        assertEquals(seq + 1, fixture.state().changeSeq)

        // 11. La primera copia: la generación 1, con su `.slmeta` al lado.
        assertEquals(BackupRun.Verified(1), fixture.runner(webDav()).run(control))
        val copies = appFilesInTheCloud().sorted()
        assertEquals(2, copies.size)
        assertTrue(copies.first().startsWith("/dav/app/shopledger-backup-g00000001-"))
        assertEquals(copies.first() + ".slmeta", copies.last())

        // 12. Una foto con su `.slmeta`, subidas por las fotos del catálogo, y tres archivos de la
        // persona en la misma carpeta, uno parecido a una copia.
        dav.collections += "/dav/app/media/"
        dav.collections += "/dav/app/media/ab/"
        dav.files[CLOUD_PHOTO] = byteArrayOf(9, 9)
        dav.files["$CLOUD_PHOTO.slmeta"] = "{}".toByteArray()
        for (foreign in FOREIGN_FILES) dav.files[foreign] = "de la persona".toByteArray()
        assertEquals(4, appFilesInTheCloud().size)
    }

    /** Exportar y pedir el código quedan en la auditoría, sin montos ni nombres. */
    private suspend fun sensitiveActionsAreAudited() {
        val seq = fixture.state().changeSeq

        // 13. Exportar los datos a un `.zip` de CSV en la carpeta de la app.
        File(appFolder, "exports").mkdirs()
        exportFiles[EXPORT_URI] = File(appFolder, "exports/export.zip")
        assertTrue(exportData(EXPORT_URI, NAMING) {} is CsvExport.Saved)

        // 14. `Ver mi código` con el cifrado apagado: no hay sobre que abrir, y el intento queda
        // anotado como fallo. Si otra prueba dejó un sobre que abre, se anota como éxito.
        val encryption = EncryptionUseCase(graph().encryption, auditRecorder)
        val view = encryption.viewCode()
        val viewed = if (view is RecoveryCodeView.Shown) "SUCCESS" else "FAILURE"

        // 15. Tres filas, en orden, con solo qué pasó, cuándo y cómo terminó.
        assertEquals(
            listOf(
                "DESTINATION_CHANGED|SUCCESS",
                "EXPORT_CSV|SUCCESS",
                "RECOVERY_CODE_VIEWED|$viewed",
            ),
            auditRows(),
        )
        assertEquals(
            listOf("id", "occurred_at", "event_type", "result"),
            businessTexts("SELECT name FROM pragma_table_info('audit_events') ORDER BY cid"),
        )
        assertEquals(
            List(3) { fixture.clock.nowEpochMillis().toString() },
            businessTexts("SELECT CAST(occurred_at AS TEXT) FROM audit_events ORDER BY id"),
        )
        // Ninguna de las tres suma a `change_seq`: nada nuevo que respaldar.
        assertEquals(seq, fixture.state().changeSeq)
    }

    /** En el tope, cada evento nuevo poda el más viejo, y el registro sigue en 5.000 filas. */
    private suspend fun theAuditKeepsItsCap() {
        val seq = fixture.state().changeSeq
        // 16. Con las 3 filas del recorrido y 4.997 más, el registro llega justo a 5.000.
        fixture.sql(insertAuditEventsSql(AUDIT_EVENTS_MAX_ROWS - 3))
        assertEquals(5_000L, businessLong("SELECT COUNT(*) FROM audit_events"))

        // 17. Una segunda exportación: entra la fila 5.001 y se poda la 1, el cambio de destino.
        exportFiles[SECOND_EXPORT_URI] = File(appFolder, "exports/export-2.zip")
        assertTrue(exportData(SECOND_EXPORT_URI, NAMING) {} is CsvExport.Saved)
        assertEquals(5_000L, businessLong("SELECT COUNT(*) FROM audit_events"))
        assertEquals(2L, businessLong("SELECT min(id) FROM audit_events"))
        assertEquals(5_001L, businessLong("SELECT max(id) FROM audit_events"))
        assertEquals(
            listOf("EXPORT_CSV|SUCCESS"),
            businessTexts(
                "SELECT event_type || '|' || result FROM audit_events WHERE id = 5001",
            ),
        )
        assertEquals(
            0L,
            businessLong(
                "SELECT COUNT(*) FROM audit_events WHERE event_type = 'DESTINATION_CHANGED'",
            ),
        )
        assertEquals(seq, fixture.state().changeSeq)
    }

    /** Cada consulta de invariantes da cero filas en la base del negocio. */
    private fun invariants() {
        inspectWithoutRoom(fixture.file) { connection ->
            for (query in InvariantQueries.MODEL) {
                val sql = query.sql.trimEnd().removeSuffix(";")
                assertEquals(query.label, 0L, connection.longValue("SELECT COUNT(*) FROM ($sql)"))
            }
        }
    }

    /** Primero el servicio, después la fase guardada, la auditoría y todo este teléfono. */
    private suspend fun eraseMyData() {
        val appFiles = appFilesInTheCloud().map { "remote $it" }.toSet()
        val journal = "/${EraseJournalStore.FILE_NAME}"
        val opsSteps =
            DurableFileOps(
                listener =
                    OperationListener { operation ->
                        val renamed = operation.startsWith("rename ")
                        if (operation.startsWith("delete ")) {
                            steps += "local " + operation.removePrefix("delete ")
                        } else if (renamed && operation.endsWith(journal)) {
                            steps += "journal ${journalText()}"
                        }
                    },
            )
        steps.clear()

        // 18. La persona confirma `Borrar mis datos` con las copias.
        assertEquals(EraseOutcome.Finished, erase(opsSteps)(EraseScope.DEVICE_AND_COPIES))

        // 19. El orden, paso por paso: la fase de la nube, los 4 archivos de la app en el
        // servicio, la fase local, la auditoría antes de que no haya base, soltar la credencial y,
        // de primero en lo local, la base. Lo último es el registro y la marca de la base creada.
        val recorded = steps.toList()
        val noBackup = fixture.noBackup.name
        assertEquals("journal REMOTE", recorded[0])
        assertEquals(appFiles, recorded.subList(1, 5).toSet())
        assertEquals(
            listOf(
                "journal LOCAL",
                "audit DELETE_MY_DATA SUCCESS",
                "release access",
                "local ${fixture.file.parentFile?.name}/${fixture.file.name}",
            ),
            recorded.subList(5, 9),
        )
        assertEquals(
            listOf(
                "local $noBackup/${EraseJournalStore.FILE_NAME}",
                "local $noBackup/${DatabaseFiles.INITIALIZED_MARKER_NAME}",
            ),
            recorded.takeLast(2),
        )
        // Ningún archivo del servicio se borra después de tocar este teléfono.
        assertTrue(recorded.drop(5).none { it.startsWith("remote ") })
        for (local in LOCAL_NAMES) {
            assertTrue(local, "local $noBackup/$local" in recorded)
        }
        assertTrue("local ${appFolder.name}/media" in recorded)
        assertTrue("local ${appFolder.name}/exports" in recorded)

        // 20. En el servicio quedan solo los archivos de la persona, con su carpeta; en este
        // teléfono, nada.
        assertEquals(FOREIGN_FILES, dav.files.keys.toSet())
        assertTrue("/dav/app/" in dav.collections)
        assertThisPhoneIsEmpty()
        assertFalse(demoFiles.marker.exists())
        assertFalse(demoFiles.directory.exists())
    }

    // Apoyo

    private fun webDav() = WebDavDestination(
        client = testClient(server),
        folder = APP_FOLDER,
        sleep = {},
        jitterMillis = { 0 },
    )

    /** `Borrar mis datos` con las piezas de la app, sobre las carpetas y el servicio falso. */
    private fun erase(ops: DurableFileOps = DurableFileOps()): EraseMyDataUseCase {
        val files = DatabaseFiles(fixture.file, fixture.noBackup)
        val state: suspend () -> BackupStateEntity? = {
            if (databaseClosed) null else ports.backupState.get()
        }
        val service =
            EraseService(
                journal = EraseJournalStore({ fixture.noBackup }, ops, Dispatchers.IO),
                state = state,
                restoreInProgress = { false },
                hasNetwork = { true },
                remote =
                    RemoteEraser(
                        state = state,
                        openWebDav = {
                            val media = WebDavMediaChannel(testClient(server), APP_FOLDER)
                            MediaOpen.Opened(webDav(), media)
                        },
                        slots = FileSlotStateStore(slotDirectory, Dispatchers.IO),
                        deleter = { SlotDocumentDelete.DELETED },
                        ioContext = Dispatchers.IO,
                    ),
                local =
                    LocalEraser(
                        files = { files },
                        appFolders = { listOf(appFolder) },
                        ops = ops,
                        releaseAccess = {
                            steps += "release access"
                            released += 1
                        },
                        ioContext = Dispatchers.IO,
                        log = fixture.log,
                    ),
                mutex = Mutex(),
                stopWork = {},
                exclusive = { block ->
                    // Como en la app: la base se cierra una sola vez, y el proceso se reinicia.
                    if (!databaseClosed) ports.closeForRestore()
                    databaseClosed = true
                    block()
                },
                log = fixture.log,
            )
        val audit = AuditRecorder { type, result ->
            steps += "audit ${type.name} ${result.name}"
            auditRecorder.record(type, result)
        }
        return EraseMyDataUseCase(service, audit)
    }

    /** Una capa de datos nueva sobre los mismos archivos: un proceso nuevo. */
    private fun graph(queue: BackupWorkQueue? = null): DataGraph = DataGraph(
        context,
        Dispatchers.IO,
        fixture.clock,
        { DatabaseFiles(fixture.file, fixture.noBackup) },
        fixture.log,
        workQueue = queue?.let { recorder -> { _: Context -> recorder } },
        demoFiles = { demoFiles },
    )

    private fun ready(access: Any): DataPorts {
        assertTrue("No abrió: $access", access is DataAccess.Ready)
        val readyPorts = (access as DataAccess.Ready).ports
        if (readyPorts !== fixture.ports && readyPorts !in opened) opened += readyPorts
        return readyPorts
    }

    /** Cierra las bases que abrieron las otras capas de datos, como al terminar sus procesos. */
    private fun closeOtherProcesses() {
        runBlocking {
            for (other in opened) {
                try {
                    other.closeForRestore()
                } catch (_: Exception) {
                    // Ya cerrada: nada que hacer.
                }
            }
        }
        opened.clear()
    }

    private suspend fun createProduct(
        ports: DataPorts,
        form: ProductForm,
        stock: QuantityMilli,
    ): Long {
        val create = CreateProductUseCase(
            ports.transactions, ports.products, ports.categories, ports.suppliers, ports.units,
            ports.systemRows, ports.priceHistory, ports.stockLedger, ports.lowStockAlerts,
            BusinessCalendarSource(ports.settings), CurrencyLock(ports.settings), fixture.clock,
            ports.mediaAssets,
        )
        val result = create(form, stock)
        return (result as? SaveProductResult.Saved)?.productId
            ?: throw AssertionError("No se guardó: $result")
    }

    private suspend fun sell(ports: DataPorts, request: SaleRequest): Long {
        val register = RegisterSaleUseCase(
            transactions = ports.transactions,
            settings = ports.settings,
            products = ports.products,
            sales = ports.sales,
            payments = ports.salePayments,
            units = ports.units,
            systemRows = ports.systemRows,
            ledger = ports.stockLedger,
            methodNames = PaymentMethodNames { key -> "Initial ${key.lowercase()}" },
            taxRates = ports.taxRates,
            clock = fixture.clock,
            heldSales = ports.heldSales,
            productUnits = ports.productUnits,
            alerts = ports.lowStockAlerts,
            customers = ports.customers,
            customerLedger = ports.customerLedger,
            loyaltyLedger = ports.loyaltyLedger,
            moneyAccounts = ports.moneyAccounts,
            moneyLedger = ports.moneyLedger,
            priceLists = ports.priceLists,
            productPrices = ports.productPrices,
            recipes = ports.productComponents,
            lineComponents = ports.saleLineComponents,
            locations = ports.locations,
            lots = ports.stockLots,
            quotes = ports.quotes,
            orders = ports.orders,
        )
        val result = register(request)
        return (result as? RegisterSaleResult.Registered)?.saleId
            ?: throw AssertionError("No se registró: $result")
    }

    /** Lo que lee el widget, con los mismos casos de uso que Inicio. */
    private suspend fun widgetFacts(ports: DataPorts): WidgetFacts {
        val today =
            ObserveTodaySalesUseCase(
                BusinessCalendarSource(ports.settings),
                ports.salesReports,
                fixture.clock,
            )(flowOf(Unit)).first()
        val summary = requireNotNull((today as? TodaySales.Ready)?.summary) { "Sin día: $today" }
        val inventory = ObserveInventoryUseCase(ports.products, ports.units)().first()
        return WidgetFacts(
            hasProducts = ports.catalogPresence.observeHasProducts().first(),
            salesCount = summary.salesCount,
            netSales = summary.netSales,
            lowStockCount = inventory.lowStock.size,
            // El estado del respaldo en el widget no es parte de este simulacro.
            backupFailed = false,
        )
    }

    private fun decide(chosen: Boolean, lock: DeviceLockState, discreet: Boolean) =
        AmountVisibilityPolicy.decide(chosenByOwner = chosen, lock = lock, discreetMode = discreet)

    private fun amountOf(content: WidgetContent): Money? = (content as WidgetContent.Summary).amount

    /** Los archivos de la app en el servicio, en el orden en que llegaron. */
    private fun appFilesInTheCloud(): List<String> = dav.files.keys.filter { it !in FOREIGN_FILES }

    private fun journalText(): String? =
        File(fixture.noBackup, EraseJournalStore.FILE_NAME).takeIf { it.exists() }?.readText()

    /** Cada evento de la auditoría, del más viejo al más nuevo, como `TIPO|RESULTADO`. */
    private fun auditRows(): List<String> =
        businessTexts("SELECT event_type || '|' || result FROM audit_events ORDER BY id")

    private fun businessLong(sql: String): Long = inspectWithoutRoom(fixture.file) {
        it.execSQL("PRAGMA busy_timeout = 5000")
        it.longValue(sql)
    }

    private fun businessTexts(sql: String): List<String> = inspectWithoutRoom(fixture.file) {
        it.execSQL("PRAGMA busy_timeout = 5000")
        it.texts(sql)
    }

    private fun demoLong(sql: String): Long =
        inspectWithoutRoom(demoFiles.database) { it.longValue(sql) }

    /**
     * Cada fila de cada tabla de la base del negocio, con todas sus columnas, salvo
     * `backup_state`, que es del respaldo y no del negocio, y las tablas internas de SQLite y
     * Room.
     */
    private fun businessTables(): Map<String, List<String>> = inspectWithoutRoom(fixture.file) {
        it.execSQL("PRAGMA busy_timeout = 5000")
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

    private fun assertThisPhoneIsUntouched() {
        assertTrue(fixture.file.exists())
        assertTrue(File(fixture.noBackup, DatabaseFiles.INITIALIZED_MARKER_NAME).exists())
        assertTrue(File(fixture.noBackup, WEBDAV_SEALED).exists())
        assertTrue(File(fixture.noBackup, ACCOUNT_CHECKLIST).exists())
        assertTrue(File(appFolder, "media/ab/$PHOTO.webp").exists())
        assertEquals(0, released)
    }

    private fun assertThisPhoneIsEmpty(releases: Int = 1) {
        assertFalse(fixture.file.exists())
        assertFalse(File(fixture.file.path + "-wal").exists())
        assertEquals(emptyList<String>(), fixture.noBackup.list().orEmpty().toList())
        assertEquals(emptyList<String>(), appFolder.list().orEmpty().toList())
        assertEquals(releases, released)
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
        const val FORBIDDEN = 403

        /** Las ventas que trae el negocio de ejemplo. */
        const val DEMO_SALES = 12L

        const val WEBDAV_SEALED = "webdav.sealed"
        const val ACCOUNT_CHECKLIST = "account-checklist.json"
        const val EXPORT_URI = "content://exports/privacy-drill.zip"
        const val SECOND_EXPORT_URI = "content://exports/privacy-drill-2.zip"
        const val PHOTO = "ab0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcd"
        const val CLOUD_PHOTO = "/dav/app/media/ab/$PHOTO.webp"

        /** Lo que la persona dejó en la carpeta del servicio: el borrado nunca lo toca. */
        val FOREIGN_FILES = setOf(
            "/dav/app/notas.txt",
            "/dav/app/shopledger-backup-de-la-persona.db.gz",
            "/dav/app/media/ab/foto-de-la-persona.jpg",
        )

        /** Lo de `noBackupFilesDir` que el borrado debe llevarse por su nombre. */
        val LOCAL_NAMES = listOf(
            WEBDAV_SEALED,
            ACCOUNT_CHECKLIST,
            DemoFiles.MARKER_NAME,
            DemoFiles.DIRECTORY_NAME,
        )

        /**
         * La región que confirma la persona: moneda de pruebas de dos decimales, sin redondeo, y
         * una zona tres horas al oeste de UTC.
         */
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

        val BREAD =
            ProductForm(name = "Pan inventado", salePrice = Money(2_500), cost = Money(1_000))
        val COFFEE =
            ProductForm(name = "Café inventado", salePrice = Money(4_000), cost = Money(1_500))

        val TEXTS = DemoTexts(
            businessName = "Negocio de ejemplo",
            products = List(DemoTexts.PRODUCT_COUNT) { "Producto de ejemplo ${it + 1}" },
            customer = "Clienta de ejemplo",
            supplier = "Proveedor de ejemplo",
            expense = "Gasto de ejemplo",
            paymentMethodNames = PaymentMethodNames { key -> "Inicial ${key.lowercase()}" },
        )

        /** Nombres de archivo y encabezados por su clave, y las filas iniciales por su clave. */
        val NAMING = object : CsvNaming {
            override fun fileName(table: CsvExportTable): String = "${table.key}.csv"

            override fun header(column: String): String = column

            override fun seedName(table: SeedTable, systemKey: String): String = "seed:$systemKey"
        }
    }
}
