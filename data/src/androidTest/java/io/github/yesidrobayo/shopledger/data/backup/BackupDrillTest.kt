package io.github.yesidrobayo.shopledger.data.backup

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yesidrobayo.shopledger.data.DataAccess
import io.github.yesidrobayo.shopledger.data.crypto.ActivationResult
import io.github.yesidrobayo.shopledger.data.crypto.EncryptionActivation
import io.github.yesidrobayo.shopledger.data.crypto.KeystoreSealer
import io.github.yesidrobayo.shopledger.data.crypto.RecoveryCode
import io.github.yesidrobayo.shopledger.data.crypto.RecoveryCodec
import io.github.yesidrobayo.shopledger.data.crypto.RecoveryCustody
import io.github.yesidrobayo.shopledger.data.crypto.RecoveryKeyRead
import io.github.yesidrobayo.shopledger.data.crypto.RecoveryKeyStore
import io.github.yesidrobayo.shopledger.data.crypto.RecoveryStateStore
import io.github.yesidrobayo.shopledger.data.destination.APP_FOLDER
import io.github.yesidrobayo.shopledger.data.destination.BackupDestination
import io.github.yesidrobayo.shopledger.data.destination.BackupFileFormat
import io.github.yesidrobayo.shopledger.data.destination.CopyListing
import io.github.yesidrobayo.shopledger.data.destination.CopyMetadata
import io.github.yesidrobayo.shopledger.data.destination.DestinationOpen
import io.github.yesidrobayo.shopledger.data.destination.DestinationOpener
import io.github.yesidrobayo.shopledger.data.destination.DestinationResult
import io.github.yesidrobayo.shopledger.data.destination.FakeHttpServer
import io.github.yesidrobayo.shopledger.data.destination.FakeResponse
import io.github.yesidrobayo.shopledger.data.destination.InMemoryWebDav
import io.github.yesidrobayo.shopledger.data.destination.ListedCopy
import io.github.yesidrobayo.shopledger.data.destination.ListedMeta
import io.github.yesidrobayo.shopledger.data.destination.SlMeta
import io.github.yesidrobayo.shopledger.data.destination.SlMetaCodec
import io.github.yesidrobayo.shopledger.data.destination.TEST_USER
import io.github.yesidrobayo.shopledger.data.destination.TransferControl
import io.github.yesidrobayo.shopledger.data.destination.WebDavDestination
import io.github.yesidrobayo.shopledger.data.destination.identity
import io.github.yesidrobayo.shopledger.data.destination.slots.ContentResolverDocumentAccess
import io.github.yesidrobayo.shopledger.data.destination.slots.DocumentSlotDestination
import io.github.yesidrobayo.shopledger.data.destination.slots.FileSlotStateStore
import io.github.yesidrobayo.shopledger.data.destination.slots.SlotEntry
import io.github.yesidrobayo.shopledger.data.destination.slots.SlotState
import io.github.yesidrobayo.shopledger.data.destination.slots.SlotStateLoad
import io.github.yesidrobayo.shopledger.data.destination.slots.SlotTestProvider
import io.github.yesidrobayo.shopledger.data.destination.slots.WriteMode
import io.github.yesidrobayo.shopledger.data.destination.testClient
import io.github.yesidrobayo.shopledger.data.export.CsvExporter
import io.github.yesidrobayo.shopledger.data.export.FileDocument
import io.github.yesidrobayo.shopledger.data.export.unzipCsv
import io.github.yesidrobayo.shopledger.data.local.AppDatabase
import io.github.yesidrobayo.shopledger.data.local.DatabaseFiles
import io.github.yesidrobayo.shopledger.data.local.deleteDatabaseFiles
import io.github.yesidrobayo.shopledger.data.local.inspectWithoutRoom
import io.github.yesidrobayo.shopledger.data.local.texts
import io.github.yesidrobayo.shopledger.data.repository.newProduct
import io.github.yesidrobayo.shopledger.data.seed.SPANISH_ROW
import io.github.yesidrobayo.shopledger.data.seed.SeedDataGenerator
import io.github.yesidrobayo.shopledger.data.seed.SeedProfile
import io.github.yesidrobayo.shopledger.data.seed.seedConfigOf
import io.github.yesidrobayo.shopledger.domain.model.BusinessTimeZone
import io.github.yesidrobayo.shopledger.domain.model.SeedTable
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupDestinationKind
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupFailureCode
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupLinkState
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupSlot
import io.github.yesidrobayo.shopledger.domain.model.backup.RecoveryCodeRequest
import io.github.yesidrobayo.shopledger.domain.model.backup.RestoreProgress
import io.github.yesidrobayo.shopledger.domain.model.export.CsvExport
import io.github.yesidrobayo.shopledger.domain.model.export.CsvExportTable
import io.github.yesidrobayo.shopledger.domain.model.region.DecimalSeparator
import io.github.yesidrobayo.shopledger.domain.port.CsvExportRequest
import io.github.yesidrobayo.shopledger.domain.port.CsvNaming
import io.github.yesidrobayo.shopledger.domain.port.TxResult
import io.github.yesidrobayo.shopledger.domain.rule.export.CsvRowWriter
import io.github.yesidrobayo.shopledger.domain.rule.region.CsvFormat
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TimeSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * El simulacro de respaldo y restauración de la guía de pruebas, paso a paso, con el cifrado
 * apagado y encendido, sin cuenta real: varios teléfonos inventados en el mismo proceso, cada uno
 * con su base, su carpeta privada y su código guardado en Keystore con alias propios.
 *
 * - **WebDAV:** un servicio falso de verdad por HTTP, con el cliente de la app. Corre los pasos 1 a
 *   21. Sin red se simula cortando la conexión, y el cambio de contraseña respondiendo 401.
 * - **Ranuras:** los tres archivos del proveedor de documentos de pruebas, abiertos con el selector
 *   como en un teléfono. Corre los pasos que no piden tocar la nube desde otro dispositivo: 1
 *   a 11 y 20.
 *
 * Los valores de control son la huella de todas las tablas del negocio, fila por fila, más el CSV
 * exportado. Fuera de la huella quedan solo las columnas que restaurar cambia a propósito: el
 * estado del respaldo, la auditoría de este teléfono, el aviso de stock que no se relanza y el
 * bloqueo sin credencial del dispositivo.
 *
 * Lo que este simulacro no puede hacer: una cuenta y un servicio reales, otro teléfono físico, la
 * subida con la app cerrada por la biblioteca de trabajos, el idioma de la interfaz y la fecha del
 * sistema. Esos pasos los cubren otras pruebas o quedan para el simulacro con cuenta real.
 *
 * Usa el juego `typical` del generador, con datos inventados, y tarda minutos: solo corre con el
 * argumento `backupDrill=true` del runner. `drillProfile=small` usa el juego chico, para probar la
 * prueba. Solo registra pasos, cifras, tiempos y sumas, nunca datos del negocio ni el código.
 */
@RunWith(AndroidJUnit4::class)
class BackupDrillTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val cache = context.cacheDir
    private val phones = ArrayList<Phone>()
    private val servers = ArrayList<FakeHttpServer>()
    private val slotSets = ArrayList<Slots>()
    private val started = TimeSource.Monotonic.markNow()

    /** Lo que responde el servicio falso: normal, sin red o con la contraseña cambiada. */
    @Volatile private var network = Network.UP

    @Before
    fun skipUnlessAsked() {
        assumeTrue(InstrumentationRegistry.getArguments().getString(ARGUMENT) == "true")
        SlotTestProvider.reset(cache)
    }

    @After
    fun close() {
        servers.forEach { it.close() }
        phones.forEach { it.close() }
        slotSets.forEach { it.close() }
        SlotTestProvider.reset(cache)
    }

    @Test
    fun webDavDrillWithEncryptionOff() = webDavDrill(encrypted = false)

    @Test
    fun webDavDrillWithEncryptionOn() = webDavDrill(encrypted = true)

    @Test
    fun slotDrillWithEncryptionOff() = slotDrill(encrypted = false)

    @Test
    fun slotDrillWithEncryptionOn() = slotDrill(encrypted = true)

    // Rama WebDAV

    private fun webDavDrill(encrypted: Boolean) = runBlocking<Unit> {
        val branch = if (encrypted) "webdav-cifrado" else "webdav-claro"
        val dav = InMemoryWebDav(override = { networkAnswer() })
        dav.collections += "/dav/app/"
        val server = FakeHttpServer(keepAlive = true, handler = dav::handle).also { servers += it }
        val webDav = {
            WebDavDestination(client = testClient(server), folder = APP_FOLDER, sleep = {
            }, jitterMillis = { 0 })
        }
        val a = phone("a", DEVICE_A)
        val b = phone("b", DEVICE_B)
        val c = phone("c", DEVICE_C)
        val d = phone("d", DEVICE_D)

        // 1. Valores de control en A.
        a.seed()
        a.fixture.connect(BackupDestinationKind.WEBDAV)
        val code = if (encrypted) a.activateEncryption() else null
        val initial = a.fingerprint()
        val rows = initial.counts.values.sum()
        note(branch, 1, "A con $rows filas en ${initial.counts.size} tablas")

        // 2. Una venta y su copia.
        a.sale()
        val first = a.backUp(webDav())
        assertTrue("$first", first is BackupRun.Verified)
        a.assertBackedUp()
        assertFormat(webDav(), encrypted)
        note(branch, 2, "copia ${(first as BackupRun.Verified).generation} confirmada")

        // 3. Sin red, tres ventas: quedan sin respaldar y nada se pierde.
        network = Network.DOWN
        repeat(3) { a.sale() }
        val offline = a.backUp(webDav())
        assertTrue("$offline", offline is BackupRun.Retry)
        assertEquals("3 cambios sin respaldar", 3L, a.unsynced())
        note(branch, 3, "sin red: ${a.unsynced()} cambios sin respaldar, $offline")

        // 4. Vuelve la red y la copia sube.
        network = Network.UP
        assertTrue(a.backUp(webDav()) is BackupRun.Verified)
        a.assertBackedUp()
        note(branch, 4, "con red, la copia sube")

        // 5. Los valores de control incluyen las 4 ventas.
        val control = a.fingerprint()
        assertEquals(initial.count("products") + 4, control.count("products"))
        note(branch, 5, "control ${control.sha256.take(12)}")

        // 6. B conecta el mismo destino y ve la copia de A, con su teléfono de origen.
        b.fixture.open()
        b.fixture.connect(BackupDestinationKind.WEBDAV)
        val offered = newest(webDav())
        val offeredMeta = requireNotNull(metaOf(offered))
        assertEquals(DEVICE_A, offeredMeta.deviceId)
        // Con el cifrado activo, los metadatos en claro no dicen fabricante ni modelo.
        assertEquals(!encrypted, offeredMeta.deviceLabel != null)
        if (encrypted) {
            assertEquals(a.keyId(), offeredMeta.keyId)
            askForTheCodeBeforeDownloading(b, server, offered, requireNotNull(code)) { webDav() }
        }

        // 7. Restaura y reinicia.
        assertReady(b.restore(RestoreSource.Listed(offered, code)) { webDav() })
        b.reopen()
        note(branch, 7, "B restauró la copia ${offered.name?.generation}")

        // 8. Todos los valores idénticos en B.
        assertSameData(control, b.fingerprint())
        val restored = b.fixture.state()
        assertEquals(BackupLinkState.CONNECTED.name, restored.linkState)
        assertEquals("Ya está en el destino", restored.changeSeq, restored.verifiedSeq)
        if (encrypted) b.assertEncryptionOnWith(a.keyId())
        note(branch, 8, "B igual a A, ${control.counts.values.sum()} filas")

        // 9. El CSV de A y el de B, sin diferencias.
        assertEquals(a.csv(), b.csv())
        note(branch, 9, "CSV iguales")

        // 10. Una venta en B sube por encima.
        b.sale()
        val fromB = b.backUp(webDav()) as BackupRun.Verified
        assertTrue(fromB.generation > offered.name!!.generation)
        note(branch, 10, "B subió la copia ${fromB.generation}")

        // 11. A registra otra venta y no pisa la copia de B.
        a.sale()
        val beforeConflict = server.requests.size
        val conflict = a.backUp(webDav())
        assertTrue("$conflict", (conflict as BackupRun.Stopped).cause is StopCause.Conflict)
        assertEquals(BackupLinkState.CONFLICT.name, a.fixture.state().linkState)
        assertTrue(server.requests.drop(beforeConflict).none { it.method in WRITES })
        note(branch, 11, "A en conflicto: ${conflict.cause}")

        // 12. A restaura la copia de la nube y registra una venta: la siguiente sube.
        assertReady(a.restore(RestoreSource.Listed(newest(webDav()))) { webDav() })
        a.reopen()
        assertEquals(BackupLinkState.CONNECTED.name, a.fixture.state().linkState)
        a.sale()
        val twelve = a.backUp(webDav()) as BackupRun.Verified
        assertTrue(twelve.generation > fromB.generation)
        val atTwelve = a.fingerprint()
        val pinned = newest(webDav())
        note(branch, 12, "A restauró y subió la copia ${twelve.generation}")

        // 13. C limpio, empezar de cero y un producto: conflicto, sin pisar la copia.
        c.fixture.open()
        c.fixture.connect(BackupDestinationKind.WEBDAV)
        c.fixture.addProduct("Producto nuevo de prueba")
        val filesBefore = dav.files.keys.toSet()
        if (encrypted) {
            // Copias cifradas y este teléfono sin código: nunca sube en claro encima de ellas. La
            // persona crea su propio código, y entonces aparece el conflicto.
            assertEquals(
                BackupRun.Stopped(
                    StopCause.Failure(BackupFailureCode.ENCRYPTION_KEY_UNAVAILABLE),
                ),
                c.backUp(webDav()),
            )
            assertEquals(filesBefore, dav.files.keys.toSet())
            c.activateEncryption()
            note(branch, 13, "C sin código no subió nada, y creó el suyo")
        }
        val fresh = c.backUp(webDav())
        assertTrue("$fresh", (fresh as BackupRun.Stopped).cause is StopCause.Conflict)
        assertEquals(filesBefore, dav.files.keys.toSet())
        note(branch, 13, "C empezó de cero: ${fresh.cause}, sin escribir")

        // 14. Conservar este teléfono pide confirmación y fija la cabeza de la nube.
        val resolver = c.resolver(webDav())
        val asked = resolver.keepThisPhone(fewerRecordsConfirmed = false, TestControl())
        assertTrue("$asked", asked is KeepOutcome.NeedsConfirmation)
        assertEquals(
            KeepOutcome.Kept,
            resolver.keepThisPhone(fewerRecordsConfirmed = true, TestControl()),
        )
        assertTrue(requireNotNull(metaOf(find(webDav(), pinned))).pinned)
        note(branch, 14, "cabeza ${pinned.name?.generation} fijada")

        // 15. Diez días simulados con una venta por día: la copia fijada sigue.
        for (day in 1..10) {
            c.fixture.advance(1.days)
            c.fixture.addProduct("Producto del día $day")
            val run = c.backUp(webDav())
            assertTrue("Día $day: $run", run is BackupRun.Verified)
        }
        val afterTenDays = listing(webDav())
        assertTrue(requireNotNull(metaOf(find(webDav(), pinned))).pinned)
        note(branch, 15, "tras 10 días, ${afterTenDays.copies.size} copias y la fijada sigue")

        // 16. C restaura la copia fijada: igual a A cuando la subió.
        if (encrypted) {
            // C tiene su propio código: el de la copia lo reemplazará, y se avisa.
            assertEquals(
                RestoreStage.NeedsRecoveryCode(otherCode = false, replacesCurrent = true),
                c.restore(RestoreSource.Listed(find(webDav(), pinned))) { webDav() },
            )
        }
        assertReady(c.restore(RestoreSource.Listed(find(webDav(), pinned), code)) { webDav() })
        c.reopen()
        assertSameData(atTwelve, c.fingerprint())
        note(branch, 16, "C igual a A en la copia fijada")

        // 17. B restaura una copia anterior con cambios sin respaldar, y lo deshace.
        b.sale()
        val beforeUndo = b.fingerprint()
        val unsynced = b.fixture.state()
        assertTrue(unsynced.changeSeq > unsynced.verifiedSeq)
        // Una anterior a la más nueva, escrita por A o por B: con el cifrado encendido, B tiene su
        // código. Las de C, con el cifrado encendido, llevan el código propio de C.
        val newestNow = newest(webDav())
        val older =
            listing(webDav()).copies
                .filter { it.ref != newestNow.ref }
                .filter { metaOf(it)?.deviceId in setOf(DEVICE_A, DEVICE_B) }
                .maxBy { requireNotNull(it.name).generation }
        assertReady(b.restore(RestoreSource.Listed(older, code)) { webDav() })
        b.reopen()
        assertNotEquals(beforeUndo, b.fingerprint())
        val safety = b.safetyCopies().filter { it.facts?.hasUnsyncedChanges == true }
        assertReady(
            b.restore(RestoreSource.Undo(safety.single()), keep = safety.single()) {
                webDav()
            },
        )
        b.reopen()
        assertSameData(beforeUndo, b.fingerprint())
        val undone = b.fixture.state()
        assertTrue("Nunca todo guardado antes de subir", undone.changeSeq > undone.verifiedSeq)
        val afterUndo = b.backUp(webDav())
        assertTrue("$afterUndo", afterUndo is BackupRun.Verified)
        b.assertBackedUp()
        note(branch, 17, "B deshizo la restauración y subió la copia de su base")

        // 18. La contraseña cambió en el servicio: sin acceso, y la venta queda guardada.
        network = Network.PASSWORD_CHANGED
        val products = b.fingerprint().count("products")
        b.sale()
        assertEquals(BackupRun.Stopped(StopCause.PermissionLost), b.backUp(webDav()))
        assertEquals(BackupLinkState.PERMISSION_LOST.name, b.fixture.state().linkState)
        assertEquals(products + 1, b.fingerprint().count("products"))
        note(branch, 18, "B sin acceso, ${b.unsynced()} cambios guardados")

        // 19. Reconectar: la copia pendiente sube, pasando antes por la lista del destino.
        network = Network.UP
        val now = b.fixture.clock.nowEpochMillis()
        assertEquals(
            1,
            b.fixture.ports.backupState.connectDestination(
                BackupDestinationKind.WEBDAV.name,
                TEST_USER,
                TEST_DESTINATION_ID,
                "https://dav.example.com/app/",
                now,
            ),
        )
        assertEquals(BackupLinkState.CONNECTED.name, b.fixture.state().linkState)
        val beforeReconnect = server.requests.size
        assertTrue(b.backUp(webDav()) is BackupRun.Verified)
        b.assertBackedUp()
        val sent = server.requests.drop(beforeReconnect)
        assertEquals("PROPFIND", sent.first().method)
        assertTrue(
            sent.indexOfFirst { it.method == "PROPFIND" } <
                sent.indexOfFirst { it.method == "PUT" },
        )
        val atNineteen = b.fingerprint()
        note(branch, 19, "B reconectó y subió")

        // 20. El archivo bajado del destino, elegido en un teléfono limpio.
        val head = newest(webDav())
        val bytes = download(webDav(), head)
        d.fixture.open()
        if (encrypted) {
            assertEquals(
                RestoreStage.NeedsRecoveryCode(otherCode = false),
                d.restore(picked(bytes, null)) { error("D no tiene destino") },
            )
        }
        assertReady(d.restore(picked(bytes, code)) { error("D no tiene destino") })
        d.reopen()
        assertSameData(atNineteen, d.fingerprint())
        assertEquals(BackupLinkState.NOT_CONNECTED.name, d.fixture.state().linkState)
        if (encrypted) d.assertEncryptionOnWith(a.keyId())
        note(branch, 20, "D abrió ${bytes.size} bytes elegidos, igual a B")

        // 21. El destino: cada copia con su metadato, nada vacío, y liberar la fijada.
        val final = listing(webDav())
        assertTrue(final.copies.isNotEmpty())
        for (copy in final.copies) {
            assertTrue("${copy.ref.value} sin metadatos", copy.meta is ListedMeta.Present)
            assertTrue("${copy.ref.value} vacía", copy.sizeBytes > 0)
        }
        assertEquals(emptyList<Any>(), final.orphanMetas)
        assertEquals(emptyList<Any>(), final.temporaries)
        val pinnedNow = final.copies.filter { metaOf(it)?.pinned == true }
        assertEquals(listOf(pinned.ref), pinnedNow.map { it.ref })
        val released = CopyPinning.release(webDav(), pinned.ref, TestControl())
        assertTrue("$released", released is DestinationResult.Success)
        assertFalse(requireNotNull(metaOf(find(webDav(), pinned))).pinned)
        val byDevice = final.copies.groupBy { metaOf(it)?.deviceId?.take(8) }
            .mapValues { it.value.size }
        note(branch, 21, "${final.copies.size} copias por teléfono: $byDevice; fijada liberada")
        if (encrypted) {
            val plainFromEncryptedPhones =
                final.copies.filter {
                    metaOf(it)?.deviceId in setOf(DEVICE_A, DEVICE_B) &&
                        it.name?.format != BackupFileFormat.ENCRYPTED
                }
            assertEquals(emptyList<ListedCopy>(), plainFromEncryptedPhones)
        }
        note(branch, 0, "terminado en ${started.elapsedNow().inWholeSeconds} s")
    }

    /**
     * Con el cifrado encendido, el teléfono sin el código lo pide antes de bajar nada, y con el
     * código de otras copias tampoco baja.
     */
    private suspend fun askForTheCodeBeforeDownloading(
        phone: Phone,
        server: FakeHttpServer,
        copy: ListedCopy,
        code: RecoveryCode,
        destination: () -> BackupDestination,
    ) {
        val before = server.requests.size
        assertEquals(
            RestoreStage.NeedsRecoveryCode(otherCode = false),
            phone.restore(RestoreSource.Listed(copy), destination = destination),
        )
        val other = RecoveryCode(code.canonical.reversed())
        assertEquals(
            RestoreStage.NeedsRecoveryCode(otherCode = true),
            phone.restore(RestoreSource.Listed(copy, other), destination = destination),
        )
        assertTrue(server.requests.drop(before).none { it.method == "GET" })
        assertFalse(phone.files.pendingKey.exists())
    }

    // Rama de ranuras

    private fun slotDrill(encrypted: Boolean) = runBlocking<Unit> {
        val branch = if (encrypted) "ranuras-cifrado" else "ranuras-claro"
        val a = phone("a", DEVICE_A)
        val b = phone("b", DEVICE_B)
        val d = phone("d", DEVICE_D)
        val aSlots = Slots("a", listOf("a1", "a2", "a3"))
        val bSlots = Slots("b", listOf("b1", "b2", "b3"))

        // 1. Valores de control en A, con sus tres archivos preparados.
        a.seed()
        aSlots.prepare()
        a.fixture.connect(BackupDestinationKind.DOCUMENT_SLOTS)
        val code = if (encrypted) a.activateEncryption() else null
        val initial = a.fingerprint()
        note(branch, 1, "A con ${initial.counts.values.sum()} filas")

        // 2. Una venta y `Respaldar ahora`.
        a.sale()
        assertTrue(a.backUp(aSlots.destination(a)) is BackupRun.Verified)
        SlotTestProvider.awaitDrains()
        a.assertBackedUp()
        note(branch, 2, "copia entregada")

        // 3 y 4. Tres ventas más. Con ranuras la escritura no espera a la red: se entrega igual.
        repeat(3) { a.sale() }
        assertEquals(3L, a.unsynced())
        a.fixture.advance(2.minutes)
        assertTrue(a.backUp(aSlots.destination(a)) is BackupRun.Verified)
        SlotTestProvider.awaitDrains()
        a.assertBackedUp()
        note(branch, 4, "tres cambios entregados")

        // 5. Los valores de control.
        val control = a.fingerprint()
        assertEquals(initial.count("products") + 4, control.count("products"))
        note(branch, 5, "control ${control.sha256.take(12)}")

        // 6 y 7. B abre el archivo más reciente con `Buscar un archivo`.
        b.fixture.open()
        val newestA = aSlots.newestFirst().first()
        val service = b.service()
        if (encrypted) {
            assertEquals(
                RestoreProgress.AskRecoveryCode(RecoveryCodeRequest.NEEDED),
                service.restoreFile(newestA, null),
            )
            val other = RecoveryCode(requireNotNull(code).canonical.reversed())
            assertEquals(
                RestoreProgress.AskRecoveryCode(RecoveryCodeRequest.OTHER_CODE),
                service.restoreFile(newestA, RecoveryCodec.printable(other)),
            )
        }
        assertEquals(
            RestoreProgress.ReadyToRestart(0),
            service.restoreFile(newestA, code?.let(RecoveryCodec::printable)),
        )
        b.reopen()
        note(branch, 7, "B abrió el archivo más reciente")

        // 8. Todos los valores idénticos, y sin destino nada cuenta como respaldado.
        assertSameData(control, b.fingerprint())
        val restored = b.fixture.state()
        assertEquals(BackupLinkState.NOT_CONNECTED.name, restored.linkState)
        assertTrue(restored.changeSeq > restored.verifiedSeq)
        if (encrypted) b.assertEncryptionOnWith(a.keyId())
        note(branch, 8, "B igual a A")

        // 9. CSV iguales.
        assertEquals(a.csv(), b.csv())
        note(branch, 9, "CSV iguales")

        // 10. B prepara sus tres archivos y entrega una venta. Los de A no cambian.
        val filesOfA = aSlots.contents()
        bSlots.prepare()
        b.fixture.connect(BackupDestinationKind.DOCUMENT_SLOTS)
        b.sale()
        assertTrue(b.backUp(bSlots.destination(b)) is BackupRun.Verified)
        SlotTestProvider.awaitDrains()
        b.assertBackedUp()
        assertEquals(filesOfA, aSlots.contents())
        note(branch, 10, "B entregó en sus archivos y los de A no cambiaron")

        // 11. A registra otra venta: sin conflicto, cada teléfono sigue en sus archivos.
        val filesOfB = bSlots.contents()
        a.sale()
        a.fixture.advance(2.minutes)
        assertTrue(a.backUp(aSlots.destination(a)) is BackupRun.Verified)
        SlotTestProvider.awaitDrains()
        assertEquals(BackupLinkState.CONNECTED.name, a.fixture.state().linkState)
        assertEquals(filesOfB, bSlots.contents())
        val atEleven = a.fingerprint()
        note(branch, 11, "A entregó sin conflicto y los de B no cambiaron")

        // 20. El archivo más reciente de A, elegido en un teléfono limpio.
        d.fixture.open()
        assertEquals(
            RestoreProgress.ReadyToRestart(0),
            d.service().restoreFile(
                aSlots.newestFirst().first(),
                code?.let(RecoveryCodec::printable),
            ),
        )
        d.reopen()
        assertSameData(atEleven, d.fingerprint())
        note(branch, 20, "D igual a A")
        note(branch, 0, "terminado en ${started.elapsedNow().inWholeSeconds} s")
    }

    /** Los tres archivos de un teléfono en el proveedor de pruebas, con su `slots.json`. */
    private inner class Slots(name: String, private val ids: List<String>) {
        private val stateDir = File(cache, "drill-slots-$name")

        init {
            slotSets += this
        }

        suspend fun prepare() {
            stateDir.deleteRecursively()
            ids.forEach { SlotTestProvider.create(cache, it) }
            store().save(
                SlotState(
                    slots =
                        BackupSlot.entries.zip(ids).associate { (slot, id) ->
                            slot to SlotEntry(uriOf(id), "Nube", null, null, null)
                        },
                    firstHandoffAt = null,
                    writeMode = WriteMode.WT,
                    sessionRequired = false,
                ),
            )
        }

        fun destination(phone: Phone) = DocumentSlotDestination(
            store = store(),
            documents = ContentResolverDocumentAccess(context.contentResolver) { true },
            clock = phone.fixture.clock,
            ioContext = Dispatchers.IO,
        )

        /** Las URI, de la ranura con la copia más nueva a la más vieja. */
        suspend fun newestFirst(): List<String> {
            val loaded = store().load() as SlotStateLoad.Loaded
            return loaded.state.slots.entries
                .sortedByDescending { (_, entry) -> entry.copy?.generation ?: -1 }
                .map { (_, entry) -> entry.uri }
        }

        /** La suma de cada archivo, para ver que nadie más lo tocó. */
        fun contents(): Map<String, String> =
            ids.associateWith { sha256(File(cache, "slot-test/$it").readBytes()) }

        private fun store() = FileSlotStateStore(stateDir, Dispatchers.IO)

        fun close() {
            stateDir.deleteRecursively()
        }
    }

    // Un teléfono

    /** Un teléfono inventado: su base, su carpeta privada y su código con alias propios. */
    private inner class Phone(val name: String, val deviceId: String) {
        val fixture = BackupFixture(context, "backup-drill-$name")
        val files = RestoreFiles(DatabaseFiles(fixture.file, fixture.noBackup))
        private val aliases = "backup-drill-$name-a" to "backup-drill-$name-b"
        private var sales = 0
        private val csvFile = File(cache, "backup-drill-$name.zip")

        /** La custodia como la arma un proceso nuevo. */
        fun custody() = RecoveryCustody(
            keys = RecoveryKeyStore({ files.recoveryDirectory }, Dispatchers.IO, aliases),
            state = RecoveryStateStore({ files.recoveryDirectory }, Dispatchers.IO),
            nowUtcMillis = { fixture.clock.nowEpochMillis() },
        )

        /** Llena la base con el juego del simulacro, como un negocio con un año de uso. */
        suspend fun seed() {
            deleteDatabaseFiles(fixture.file)
            fixture.noBackup.deleteRecursively()
            seededTemplate().copyTo(fixture.file)
            reopen()
        }

        /**
         * Activa el cifrado como su pantalla: un código nuevo, la prueba de ida y vuelta y el
         * sellado. Devuelve el código, que la persona anotó.
         */
        suspend fun activateEncryption(): RecoveryCode {
            val activation =
                EncryptionActivation(
                    custody = custody(),
                    backupMutex = Mutex(),
                    discardPendingPlainUpload = {},
                    forceUpload = {
                        fixture.ports.backupState.forceUpload(fixture.clock.nowEpochMillis())
                    },
                    nowUtcMillis = { fixture.clock.nowEpochMillis() },
                    ioContext = Dispatchers.IO,
                )
            val code = activation.newCode()
            assertTrue(activation.roundTrip(code))
            val result = activation.activate(code)
            assertTrue("$result", result is ActivationResult.Activated)
            assertTrue(custody().alignAtStartup().enabled)
            return code
        }

        suspend fun keyId(): String =
            (custody().keys.open() as RecoveryKeyRead.Available).key.keyIdHex

        suspend fun assertEncryptionOnWith(keyIdHex: String) {
            val state = custody().alignAtStartup()
            assertTrue("El cifrado queda activo en el teléfono nuevo", state.enabled)
            assertEquals(keyIdHex, state.keyIdHex)
            assertFalse(files.pendingKey.exists())
        }

        /** Una venta del simulacro: un cambio del negocio en su transacción, que cuenta. */
        suspend fun sale() {
            sales++
            fixture.ports.transactions.write {
                TxResult.Commit(fixture.ports.products.insert(newProduct("Venta $name $sales")))
            }
        }

        suspend fun unsynced(): Long = fixture.state().let { it.changeSeq - it.verifiedSeq }

        suspend fun assertBackedUp() {
            val state = fixture.state()
            assertEquals("Todo respaldado", state.changeSeq, state.verifiedSeq)
            assertEquals(fixture.now.toEpochMilliseconds(), state.lastSuccessAt)
        }

        /** El respaldo de la app sobre [destination], con su poda, su cifrado y su teléfono. */
        suspend fun backUp(destination: BackupDestination): BackupRun {
            val custody = custody()
            val state = custody.alignAtStartup()
            val currentKeyId = if (state.enabled) state.keyIdHex else null
            return fixture
                .runner(
                    destination,
                    pruner =
                        RetentionPruner(
                            deviceId = { deviceId },
                            log = fixture.log,
                            marks = { RetentionMarks(currentKeyId = currentKeyId) },
                            forceUpload = {
                                val now = fixture.clock.nowEpochMillis()
                                fixture.ports.backupState.forceUpload(now)
                            },
                        ),
                    packager = BackupPackager(Dispatchers.IO, encryption = {
                        custody.packEncryption()
                    }),
                    encryption = {
                        val state = custody.alignAtStartup()
                        val key = custody.keys.open()
                        LocalEncryption(
                            active = state.enabled,
                            keyAvailable = key is RecoveryKeyRead.Available,
                            hasRecoveryCode = key !is RecoveryKeyRead.Absent,
                            offConfirmed = state.offConfirmedAtUtcMillis != null,
                        )
                    },
                    deviceId = deviceId,
                ).run(TestControl())
        }

        fun resolver(destination: BackupDestination) = ConflictResolver(
            state = fixture.ports.backupState,
            destinations = { DestinationOpen.Opened(destination) },
            deviceId = { deviceId },
            clock = fixture.clock,
            schemaVersion = AppDatabase.SCHEMA_VERSION,
            mutex = Mutex(),
            pinner = CopyPinning,
        )

        private fun coordinator(destination: () -> BackupDestination) =
            productionRestoreCoordinator(
                context = context,
                ioContext = Dispatchers.IO,
                clock = fixture.clock,
                log = fixture.log,
                files = files,
                ports = { fixture.ports },
                destinations = DestinationOpener { DestinationOpen.Opened(destination()) },
                backupMutex = Mutex(),
                scheduledWork = ScheduledBackupWork.None,
                keys = CustodyRestoreKeys(custody()),
            )

        /**
         * Prepara la restauración de [source] hasta dejarla lista o pedir algo. Si hay que
         * descartar una copia de seguridad, descarta la más vieja que no sea [keep], como haría la
         * persona.
         */
        suspend fun restore(
            source: RestoreSource,
            keep: SafetyCopy? = null,
            destination: () -> BackupDestination,
        ): RestoreStage {
            val coordinator = coordinator(destination)
            var stage = coordinator.start(source)
            while (stage is RestoreStage.ChooseSafetyCopyToDiscard) {
                val discard =
                    stage.copies
                        .filter { it != keep }
                        .minBy { it.facts?.createdAt?.epochSeconds ?: 0 }
                stage = coordinator.decide(RestoreDecision.DiscardSafetyCopy(discard))
            }
            return stage
        }

        fun safetyCopies(): List<SafetyCopy> =
            SafetyCopyStore(files, DurableFileOps(), Dispatchers.IO).available(fixture.now)

        /** La restauración de la pantalla, con el selector de archivos del sistema. */
        fun service(): BackupRestoreService {
            val coordinator = coordinator { error("Sin destino conectado") }
            return BackupRestoreService(
                saved = { fixture.ports.backupState.get()?.savedDestination() },
                destinations = { error("Sin destino conectado") },
                coordinator = { coordinator },
                files = ResolverPickedFiles(context.contentResolver),
                beforeUpdate = { File(fixture.noBackup, "sin-copia-previa.db") },
                ioContext = Dispatchers.IO,
            )
        }

        /** Reinicia como un proceso nuevo: el arranque termina el reemplazo. */
        suspend fun reopen() {
            val access = fixture.reopen()
            assertTrue("$access", access is DataAccess.Ready)
        }

        fun fingerprint(): Fingerprint = fingerprintOf(fixture.file)

        /** Exporta el CSV como la pantalla y lo devuelve por archivo, ya descomprimido. */
        suspend fun csv(): Map<String, String> {
            csvFile.delete()
            val config = requireNotNull(seedConfigOf(SPANISH_ROW))
            val format =
                CsvFormat(config.csvSeparator, DecimalSeparator.COMMA, config.currencyDecimals)
            val zone = requireNotNull(BusinessTimeZone.of(config.businessTimeZoneId))
            val result =
                CsvExporter(
                    rows = { fixture.ports.csvRows },
                    documentFor = { FileDocument(csvFile) },
                    recordExport = {},
                    log = MemoryLog(),
                    ioContext = Dispatchers.IO,
                ).export(CsvExportRequest(CSV_URI, CsvRowWriter(format, zone), NAMING)) {}
            assertTrue("$result", result is CsvExport.Saved)
            return unzipCsv(csvFile)
        }

        fun close() {
            fixture.close()
            csvFile.delete()
            listOf(aliases.first, aliases.second).forEach { KeystoreSealer(it).deleteKey() }
        }
    }

    private fun uriOf(id: String) = SlotTestProvider.uriOf("${context.packageName}.slottest", id)

    private fun phone(name: String, deviceId: String): Phone = Phone(name, deviceId).also {
        it.close()
        phones += it
    }

    // Apoyo

    private fun networkAnswer(): FakeResponse? = when (network) {
        Network.UP -> null

        // Sin red: la conexión se corta sin respuesta.
        Network.DOWN -> FakeResponse.Drop

        Network.PASSWORD_CHANGED -> FakeResponse.status(401)
    }

    private suspend fun listing(destination: BackupDestination): CopyListing =
        (destination.listCopies(TransferControl.Unbounded) as DestinationResult.Success).value

    private suspend fun newest(destination: BackupDestination): ListedCopy =
        listing(destination).copies.maxBy { requireNotNull(it.name).generation }

    private suspend fun find(destination: BackupDestination, copy: ListedCopy): ListedCopy =
        listing(destination).copies.single { it.ref == copy.ref }

    private fun metaOf(copy: ListedCopy): SlMeta? {
        val bytes = (copy.meta as? ListedMeta.Present)?.bytes ?: return null
        val name = copy.name ?: return null
        return (SlMetaCodec.decode(bytes, name.identity()) as? CopyMetadata.Valid)?.meta
    }

    private suspend fun download(destination: BackupDestination, copy: ListedCopy): ByteArray {
        val sink = ByteArrayOutputStream()
        val result = destination.download(copy.ref, sink, Long.MAX_VALUE, TransferControl.Unbounded)
        assertTrue("$result", result is DestinationResult.Success)
        return sink.toByteArray()
    }

    private fun picked(bytes: ByteArray, code: RecoveryCode?) =
        RestoreSource.PickedFile(bytes.size.toLong(), code) { bytes.inputStream() }

    /** Cada copia sale en el formato del cifrado: cifrada sin nada en claro, o gzip. */
    private suspend fun assertFormat(destination: BackupDestination, encrypted: Boolean) {
        val copy = newest(destination)
        val bytes = download(destination, copy)
        val gzip = bytes.size > 2 && bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte()
        val sqlite = String(bytes.copyOf(16), Charsets.US_ASCII).startsWith("SQLite format 3")
        assertFalse("Nunca la base en claro sin comprimir", sqlite)
        if (encrypted) {
            assertEquals(BackupFileFormat.ENCRYPTED, copy.name?.format)
            assertFalse("Nada en claro con el cifrado activo", gzip)
            assertTrue(metaOf(copy)?.keyId != null)
        } else {
            assertEquals(BackupFileFormat.GZIP, copy.name?.format)
            assertTrue(gzip)
        }
    }

    private fun assertReady(stage: RestoreStage) {
        assertTrue("$stage", stage is RestoreStage.ReadyToRestart)
    }

    private fun assertSameData(expected: Fingerprint, actual: Fingerprint) {
        val differing =
            (expected.counts.keys + actual.counts.keys).filter {
                expected.tableSums[it] != actual.tableSums[it]
            }
        assertEquals("Tablas distintas: $differing", expected, actual)
        assertNotEquals(0, actual.counts.values.sum())
    }

    private fun note(branch: String, step: Int, text: String) {
        Log.i(TAG, "$branch paso $step: $text (${started.elapsedNow().inWholeSeconds} s)")
    }

    /** La base del juego del simulacro, generada una vez por proceso. */
    private suspend fun seededTemplate(): File {
        val profile =
            when (InstrumentationRegistry.getArguments().getString(PROFILE_ARGUMENT)) {
                "small" -> SeedProfile.SMALL
                else -> SeedProfile.TYPICAL
            }
        val template = File(cache, "backup-drill-${profile.name.lowercase()}.db")
        // Las pruebas de una clase corren una tras otra: la base se genera una sola vez.
        if (seeded != template) {
            deleteDatabaseFiles(template)
            val config = requireNotNull(seedConfigOf(SPANISH_ROW))
            val mark = TimeSource.Monotonic.markNow()
            SeedDataGenerator(SeedDataGenerator.DEFAULT_SEED, profile, config)
                .generate(context, template)
            Log.i(
                TAG,
                "juego $profile: ${template.length()} bytes en " +
                    "${mark.elapsedNow().inWholeSeconds} s",
            )
            seeded = template
        }
        return template
    }

    /** La huella de las tablas del negocio: una suma por tabla, y las filas de cada una. */
    data class Fingerprint(
        val sha256: String,
        val counts: Map<String, Long>,
        val tableSums: Map<String, String>,
    ) {
        fun count(table: String): Long = counts.getValue(table)

        override fun toString(): String =
            "Fingerprint(${sha256.take(12)}, ${counts.values.sum()} filas)"
    }

    private fun fingerprintOf(file: File): Fingerprint = inspectWithoutRoom(file) { db ->
        val tables =
            db.texts(
                "SELECT name FROM sqlite_master WHERE type = 'table' " +
                    "AND name NOT LIKE 'sqlite_%' ORDER BY name",
            ).filterNot { it in LOCAL_TABLES }
        val counts = linkedMapOf<String, Long>()
        val sums = linkedMapOf<String, String>()
        val all = MessageDigest.getInstance("SHA-256")
        for (table in tables) {
            val columns =
                db.texts("SELECT name FROM pragma_table_info('$table') ORDER BY cid")
                    .filterNot { "$table.$it" in LOCAL_COLUMNS }
            val select = columns.joinToString(", ") { "quote(\"$it\")" }
            val order = columns.indices.joinToString(", ") { "${it + 1}" }
            val digest = MessageDigest.getInstance("SHA-256")
            var rows = 0L
            db.prepare("SELECT $select FROM \"$table\" ORDER BY $order").use { statement ->
                while (statement.step()) {
                    rows++
                    for (index in columns.indices) {
                        digest.update(statement.getText(index).toByteArray(Charsets.UTF_8))
                        digest.update(CELL)
                    }
                    digest.update(ROW)
                }
            }
            val sum = digest.digest().toHex()
            counts[table] = rows
            sums[table] = sum
            all.update("$table:$rows:$sum\n".toByteArray(Charsets.UTF_8))
        }
        Fingerprint(all.digest().toHex(), counts, sums)
    }

    private enum class Network { UP, DOWN, PASSWORD_CHANGED }

    private companion object {
        const val ARGUMENT = "backupDrill"
        const val PROFILE_ARGUMENT = "drillProfile"
        const val TAG = "BackupDrill"
        const val CSV_URI = "content://test/drill"

        // Teléfonos inventados.
        const val DEVICE_A = "a1b2c3d4-0000-4000-8000-00000000000a"
        const val DEVICE_B = "b2c3d4e5-0000-4000-8000-00000000000b"
        const val DEVICE_C = "c3d4e5f6-0000-4000-8000-00000000000c"
        const val DEVICE_D = "d4e5f6a7-0000-4000-8000-00000000000d"

        /** Los métodos que escriben en el servicio. */
        val WRITES = setOf("PUT", "MOVE", "DELETE", "MKCOL")

        /** Tablas de este teléfono, que restaurar reescribe a propósito. */
        val LOCAL_TABLES =
            setOf("backup_state", "audit_events", "room_master_table", "android_metadata")

        /**
         * Columnas que restaurar cambia a propósito: un aviso de stock de la copia no se relanza, y
         * sin credencial del dispositivo el bloqueo de la app se apaga.
         */
        val LOCAL_COLUMNS =
            setOf("low_stock_alerts.notification_status", "app_settings.app_lock_enabled")

        val CELL = byteArrayOf(1)
        val ROW = byteArrayOf(2)

        /** La base del juego ya generada en este proceso. */
        var seeded: File? = null

        val NAMING =
            object : CsvNaming {
                override fun fileName(table: CsvExportTable): String = "${table.key}.csv"

                override fun header(column: String): String = column

                override fun seedName(table: SeedTable, systemKey: String): String = systemKey
            }

        fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

        fun sha256(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).toHex()
    }
}
