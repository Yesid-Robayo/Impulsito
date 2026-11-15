package io.github.yesidrobayo.shopledger.data.backup

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yesidrobayo.shopledger.data.DataAccess
import io.github.yesidrobayo.shopledger.data.crypto.BackupKey
import io.github.yesidrobayo.shopledger.data.crypto.KeystoreSealer
import io.github.yesidrobayo.shopledger.data.crypto.RecoveryCode
import io.github.yesidrobayo.shopledger.data.crypto.RecoveryCodec
import io.github.yesidrobayo.shopledger.data.crypto.RecoveryCustody
import io.github.yesidrobayo.shopledger.data.crypto.RecoveryKeyFaults
import io.github.yesidrobayo.shopledger.data.crypto.RecoveryKeyRead
import io.github.yesidrobayo.shopledger.data.crypto.RecoveryKeySave
import io.github.yesidrobayo.shopledger.data.crypto.RecoveryKeyStep
import io.github.yesidrobayo.shopledger.data.crypto.RecoveryKeyStore
import io.github.yesidrobayo.shopledger.data.crypto.RecoveryState
import io.github.yesidrobayo.shopledger.data.crypto.RecoveryStateStore
import io.github.yesidrobayo.shopledger.data.destination.DestinationOpen
import io.github.yesidrobayo.shopledger.data.destination.DestinationResult
import io.github.yesidrobayo.shopledger.data.destination.FakeBackupDestination
import io.github.yesidrobayo.shopledger.data.destination.FakeBackupDestination.Operation
import io.github.yesidrobayo.shopledger.data.destination.ListedCopy
import io.github.yesidrobayo.shopledger.data.local.AppDatabase
import io.github.yesidrobayo.shopledger.data.local.DatabaseFiles
import io.github.yesidrobayo.shopledger.data.local.inspectWithoutRoom
import io.github.yesidrobayo.shopledger.data.local.longValue
import io.github.yesidrobayo.shopledger.data.local.texts
import io.github.yesidrobayo.shopledger.domain.model.backup.RecoveryCodeRequest
import io.github.yesidrobayo.shopledger.domain.model.backup.RestoreProgress
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Restaurar una copia cifrada con la base real, el destino falso, Keystore real y el reemplazo del
 * arranque:
 *
 * - En un teléfono sin el código, los metadatos dicen la clave y la restauración pide el código
 *   **antes de descargar**. Con otro código tampoco descarga. Con el correcto restaura, y el código
 *   queda guardado en este teléfono solo cuando el reemplazo quedó verificado.
 * - Un archivo elegido no trae metadatos: la clave sale de su cabecera.
 * - Con el código de este teléfono no pide nada y no sella nada.
 * - Un código errado nunca toca la base activa. Cancelar o revertir dejan el código guardado como
 *   estaba, y si la persona apagó el cifrado aquí, restaurar no lo vuelve a encender.
 * - La muerte del proceso tras cada operación, también dos seguidas, deja la base vieja con el
 *   código de antes o la nueva con el suyo, nunca una mezcla.
 *
 * Todos los códigos y datos son inventados.
 */
@RunWith(AndroidJUnit4::class)
class EncryptedRestoreTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val fixture = BackupFixture(context, "encrypted-restore-test")
    private val control = TestControl()
    private val destination = FakeBackupDestination()
    private var backupMutex = Mutex()
    private val files by lazy { RestoreFiles(DatabaseFiles(fixture.file, fixture.noBackup)) }
    private var ops = DurableFileOps()
    private var keyFaults = RecoveryKeyFaults.None

    private val code = RecoveryCode("0123456789ABCDEFGHJKMNPQRS")
    private val key = BackupKey.from(code)
    private val otherCode = RecoveryCode("SRQPNMKJHGFEDCBA9876543210")
    private val otherKey = BackupKey.from(otherCode)
    private val aliases = "test-restore-code-a" to "test-restore-code-b"

    /** La custodia de este teléfono, como la arma un proceso nuevo, en la carpeta de la prueba. */
    private fun custody() = RecoveryCustody(
        keys = RecoveryKeyStore({ files.recoveryDirectory }, Dispatchers.IO, aliases, keyFaults),
        state = RecoveryStateStore({ files.recoveryDirectory }, Dispatchers.IO),
        nowUtcMillis = { fixture.clock.nowEpochMillis() },
    )

    @Before
    fun open() = runBlocking<Unit> {
        deleteTestKeys()
        fixture.open()
        fixture.connect()
    }

    @After
    fun close() {
        fixture.close()
        deleteTestKeys()
    }

    @Test
    fun withoutTheCodeHereItAsksBeforeDownloadingAndKeepsTheCodeOnceTheSwapIsVerified() =
        runBlocking<Unit> {
            val copy = encryptedCopy()

            assertEquals(
                RestoreStage.NeedsRecoveryCode(otherCode = false),
                coordinator().start(RestoreSource.Listed(copy)),
            )
            assertEquals(
                RestoreStage.NeedsRecoveryCode(otherCode = true),
                coordinator().start(RestoreSource.Listed(copy, otherCode)),
            )
            // Ni el código ausente ni el de otras copias descargan, toman el cerrojo o sellan algo.
            assertFalse(destination.calls.contains(Operation.DOWNLOAD))
            assertNothingLeft()
            assertFalse(files.recoveryDirectory.exists())
            assertEquals(2L, count("products"))

            assertEquals(
                RestoreStage.ReadyToRestart(0),
                coordinator().start(RestoreSource.Listed(copy, code)),
            )
            // Hasta reiniciar, el código espera aparte: todavía no es el de este teléfono.
            assertTrue(files.pendingKey.exists())
            assertFalse(files.sealedKey.exists())

            reopen()

            assertEquals(1L, count("products"))
            assertFalse(files.pendingKey.exists())
            val read = custody().keys.open()
            assertTrue("$read", read is RecoveryKeyRead.Available)
            assertEquals(key.keyIdHex, (read as RecoveryKeyRead.Available).key.keyIdHex)
            // En un teléfono sin estado del cifrado, la copia cifrada lo deja activo con su código.
            val state = custody().alignAtStartup()
            assertTrue(state.enabled)
            assertEquals(key.keyIdHex, state.keyIdHex)
            assertEquals(
                listOf("RESTORE SUCCESS", "ENCRYPTION_CHANGED SUCCESS"),
                texts("SELECT event_type || ' ' || result FROM audit_events ORDER BY id"),
            )
        }

    @Test
    fun aPickedFileSaysItsKeyInItsHeader() = runBlocking<Unit> {
        val copy = encryptedCopy()
        val bytes = requireNotNull(destination.file(copy.ref.value))

        assertEquals(
            RestoreStage.NeedsRecoveryCode(otherCode = false),
            coordinator().start(picked(bytes)),
        )
        assertEquals(
            RestoreStage.NeedsRecoveryCode(otherCode = true),
            coordinator().start(picked(bytes, otherCode)),
        )
        assertNothingLeft()
        assertEquals(2L, count("products"))

        assertEquals(RestoreStage.ReadyToRestart(0), coordinator().start(picked(bytes, code)))
        reopen()

        assertEquals(1L, count("products"))
        assertEquals(
            key.keyIdHex,
            (custody().keys.open() as RecoveryKeyRead.Available).key.keyIdHex,
        )
    }

    @Test
    fun aDamagedEncryptedFileIsRefusedWithoutTouchingAnything() = runBlocking<Unit> {
        val copy = encryptedCopy()
        val bytes = requireNotNull(destination.file(copy.ref.value))
        bytes[bytes.size - 30] = (bytes[bytes.size - 30] + 1).toByte()

        assertEquals(
            RestoreStage.Refused(RestoreRefusal.Candidate(CandidateRejection.DAMAGED_ARCHIVE)),
            coordinator().start(picked(bytes, code)),
        )
        assertNothingLeft()
        assertFalse(files.pendingKey.exists())
        reopen()
        assertEquals(2L, count("products"))
    }

    @Test
    fun withTheCodeOfThisPhoneItAsksNothingAndSealsNothing() = runBlocking<Unit> {
        val copy = encryptedCopy()
        assertTrue(custody().keys.seal(code) is RecoveryKeySave.Sealed)
        val sealed = files.sealedKey.readBytes()

        assertEquals(
            RestoreStage.ReadyToRestart(0),
            coordinator().start(RestoreSource.Listed(copy)),
        )
        assertFalse(files.pendingKey.exists())
        reopen()

        assertEquals(1L, count("products"))
        assertTrue(sealed.contentEquals(files.sealedKey.readBytes()))
        // Solo queda la restauración: el código no cambió.
        assertEquals(
            listOf("RESTORE SUCCESS"),
            texts("SELECT event_type || ' ' || result FROM audit_events"),
        )
    }

    @Test
    fun aTypoNeverReachesThePreparation() = runBlocking<Unit> {
        val service =
            BackupRestoreService(
                saved = { error("Un error de escritura no busca el destino") },
                destinations = { error("Ni lo abre") },
                coordinator = { error("Ni empieza la preparación") },
                files = object : PickedFiles {
                    override fun declaredSize(uri: String): Long? = error("Ni lee el archivo")

                    override fun open(uri: String) = error("Ni lo abre")
                },
                beforeUpdate = { File(fixture.noBackup, "sin-copia-previa.db") },
                ioContext = Dispatchers.IO,
            )
        val typo = RecoveryCodec.printable(code).replace('A', 'B')

        assertEquals(
            RestoreProgress.AskRecoveryCode(RecoveryCodeRequest.TYPO),
            service.restoreCopy("cualquiera", typo),
        )
        assertEquals(
            RestoreProgress.AskRecoveryCode(RecoveryCodeRequest.TYPO),
            service.restoreFile("content://example/copia", "1234"),
        )
        assertNothingLeft()
    }

    @Test
    fun cancellingAfterSealingLeavesTheSavedCodeAsItWas() = runBlocking<Unit> {
        val copy = encryptedCopy()
        sealOtherCodeAsCurrent()
        val sealed = files.sealedKey.readBytes()
        // Con otro código guardado aquí, la hoja avisa que el de la copia lo reemplazará.
        assertEquals(
            RestoreStage.NeedsRecoveryCode(otherCode = false, replacesCurrent = true),
            coordinator().start(RestoreSource.Listed(copy)),
        )
        var cancelled = false
        // La persona cancela justo después de que el código quedó aparte.
        ops = DurableFileOps(listener = {
            if (!cancelled && files.pendingKey.exists()) {
                cancelled = true
                throw CancellationException("Cancelada por la prueba")
            }
        })

        val stopped =
            try {
                coordinator().start(RestoreSource.Listed(copy, code))
                false
            } catch (_: CancellationException) {
                true
            }

        assertTrue(stopped)
        assertFalse(files.pendingKey.exists())
        assertTrue(sealed.contentEquals(files.sealedKey.readBytes()))
        assertNothingLeft()
        ops = DurableFileOps()
        reopen()
        assertEquals(2L, count("products"))
        assertEquals(
            otherKey.keyIdHex,
            (custody().keys.open() as RecoveryKeyRead.Available).key.keyIdHex,
        )
    }

    @Test
    fun aRolledBackSwapDropsTheWaitingCodeAndKeepsTheSavedOne() = runBlocking<Unit> {
        val copy = encryptedCopy()
        sealOtherCodeAsCurrent()
        assertEquals(
            RestoreStage.ReadyToRestart(0),
            coordinator().start(RestoreSource.Listed(copy, code)),
        )
        assertTrue(files.pendingKey.exists())
        // La base nueva no cuadra con el diario: el arranque devuelve la vieja.
        val journal = RestoreJournalStore(files, DurableFileOps())
        val written = (journal.read() as RestoreJournalRead.Present).journal
        journal.write(written.copy(expectedRecordCount = written.expectedRecordCount + 1))

        reopen()

        assertEquals(2L, count("products"))
        assertFalse(files.pendingKey.exists())
        assertEquals(
            otherKey.keyIdHex,
            (custody().keys.open() as RecoveryKeyRead.Available).key.keyIdHex,
        )
        val state = custody().alignAtStartup()
        assertEquals(otherKey.keyIdHex, state.keyIdHex)
        // La restauración que volvió atrás queda como fallo, y el código no cambió.
        assertEquals(
            listOf("RESTORE FAILURE"),
            texts("SELECT event_type || ' ' || result FROM audit_events"),
        )
    }

    @Test
    fun turnedOffHereARestoreNeverTurnsItBackOn() = runBlocking<Unit> {
        val copy = encryptedCopy()
        sealOtherCodeAsCurrent()
        // La persona apagó el cifrado en este teléfono, con el código de otras copias.
        custody().state.write(
            RecoveryState(
                enabled = false,
                keyIdHex = otherKey.keyIdHex,
                enabledAtUtcMillis = 1_000L,
                offConfirmedAtUtcMillis = 2_000L,
                disabledKeyIdHex = otherKey.keyIdHex,
            ),
        )
        val sealed = files.sealedKey.readBytes()
        // Apagado aquí, el código de la copia no reemplazará al guardado: no hay nada que avisar.
        assertEquals(
            RestoreStage.NeedsRecoveryCode(otherCode = false, replacesCurrent = false),
            coordinator().start(RestoreSource.Listed(copy)),
        )

        assertEquals(
            RestoreStage.ReadyToRestart(0),
            coordinator().start(RestoreSource.Listed(copy, code)),
        )
        assertFalse(files.pendingKey.exists())
        reopen()

        assertEquals(1L, count("products"))
        assertTrue(sealed.contentEquals(files.sealedKey.readBytes()))
        val state = custody().alignAtStartup()
        assertFalse(state.enabled)
        assertEquals(otherKey.keyIdHex, state.disabledKeyIdHex)
    }

    @Test
    fun aDeathAfterEachOperationLeavesTheOldDataWithItsCodeOrTheNewWithTheirs() =
        runBlocking<Unit> {
            var death = 1
            while (true) {
                val copy = encryptedCopy()
                sealOtherCodeAsCurrent()
                var operations = 0
                ops = DurableFileOps(listener = { if (++operations == death) throw ProcessDeath() })
                val died = diesRestoring(copy)
                if (died) {
                    // Una segunda muerte seguida, en la primera operación del intento siguiente.
                    afterDeath()
                    var again = 0
                    ops = DurableFileOps(listener = { if (++again == 1) throw ProcessDeath() })
                    diesRestoring(copy)
                    afterDeath()
                } else {
                    ops = DurableFileOps()
                    reopen()
                }
                assertOldWithItsCodeOrNewWithTheirs("Muerte tras la operación $death", died)
                if (!died) break
                startOver()
                death++
            }
            assertTrue("La restauración hizo operaciones en disco: $death", death > 3)
        }

    @Test
    fun aDeathWhileSealingTheCodeLeavesTheOldDataAndItsCode() = runBlocking<Unit> {
        for (step in listOf(RecoveryKeyStep.REPLACE_KEY, RecoveryKeyStep.WRITE_ENVELOPE)) {
            val copy = encryptedCopy()
            sealOtherCodeAsCurrent()
            keyFaults = RecoveryKeyFaults { if (it == step) throw ProcessDeath() }

            assertTrue("$step", diesRestoring(copy))
            keyFaults = RecoveryKeyFaults.None
            afterDeath()

            assertEquals("$step", 2L, count("products"))
            assertFalse("$step", files.pendingKey.exists())
            assertEquals(
                "$step",
                otherKey.keyIdHex,
                (custody().keys.open() as RecoveryKeyRead.Available).key.keyIdHex,
            )
            startOver()
        }
    }

    // Apoyo

    /**
     * Sube una copia cifrada con [code] con un producto, y agrega otro solo en este teléfono: así
     * se distingue la base restaurada, con 1, de la de antes, con 2.
     */
    private suspend fun encryptedCopy(): ListedCopy {
        fixture.addProduct("Pan de prueba")
        val generation = fixture.state().headGeneration + 1
        val run =
            fixture
                .runner(
                    destination,
                    packager = BackupPackager(Dispatchers.IO, encryption = {
                        PackEncryption.Encrypt(code)
                    }),
                    encryption = { ON },
                )
                .run(control)
        assertEquals(BackupRun.Verified(generation), run)
        fixture.addProduct("Leche de prueba")
        val listing = destination.listCopies(control) as DestinationResult.Success
        return listing.value.copies.single { it.name?.generation == generation }
    }

    /** El código de otras copias, vigente y activo en este teléfono. */
    private suspend fun sealOtherCodeAsCurrent() {
        assertTrue(custody().keys.seal(otherCode) is RecoveryKeySave.Sealed)
        assertTrue(custody().alignAtStartup().enabled)
    }

    /** Restaura con el código escrito hasta dejarla lista. `true` si el proceso murió a mitad. */
    private suspend fun diesRestoring(copy: ListedCopy): Boolean = try {
        assertEquals(
            RestoreStage.ReadyToRestart(0),
            coordinator().start(RestoreSource.Listed(copy, code)),
        )
        false
    } catch (_: ProcessDeath) {
        true
    }

    /** El proceso nuevo tras una muerte: no hereda nada, y el arranque termina o limpia. */
    private suspend fun afterDeath() {
        runCatching { fixture.ports.closeForRestore() }
        ops = DurableFileOps()
        reopen()
    }

    private suspend fun assertOldWithItsCodeOrNewWithTheirs(case: String, died: Boolean) {
        val products = count("products")
        val sealed = (custody().keys.open() as RecoveryKeyRead.Available).key.keyIdHex
        if (products == 1L) {
            assertEquals(case, key.keyIdHex, sealed)
        } else {
            assertEquals(case, 2L, products)
            assertEquals(case, otherKey.keyIdHex, sealed)
            assertTrue(case, died)
        }
        assertFalse(case, files.pendingKey.exists())
        assertNothingLeft()
    }

    /** Un caso nuevo desde cero: otra base, otro destino y sin código en este teléfono. */
    private suspend fun startOver() {
        runCatching { fixture.ports.closeForRestore() }
        fixture.close()
        destination.listCopies(control).let { listed ->
            (listed as DestinationResult.Success).value.copies.forEach {
                destination.removeFile(it.ref.value)
                destination.removeFile(it.ref.value + ".slmeta")
            }
        }
        deleteTestKeys()
        reopen()
        fixture.connect()
    }

    private fun coordinator(): RestoreCoordinator {
        val validator = CandidateValidator(Dispatchers.IO, AppDatabase.SCHEMA_VERSION)
        return RestoreCoordinator(
            files = files,
            ops = ops,
            lock = RestoreLockFile(files, ops, fixture.clock, Dispatchers.IO),
            pause = MutexBackupPause(backupMutex),
            active = { activeDatabaseOf(fixture.ports) },
            linkColumns = { null },
            destinations = { DestinationOpen.Opened(destination) },
            unpacker = CandidateUnpacker(Dispatchers.IO),
            validator = validator,
            repair = CandidateRepair(Dispatchers.IO),
            migrator = CandidateMigrator { error("Esta prueba no migra") },
            preparation = CandidatePreparation(Dispatchers.IO),
            safetyCopies = SafetyCopyStore(files, ops, Dispatchers.IO),
            journalStore = RestoreJournalStore(files, ops),
            closeDatabase = { fixture.ports.closeForRestore() },
            deviceSecure = { true },
            clock = fixture.clock,
            log = fixture.log,
            ioContext = Dispatchers.IO,
            keys = CustodyRestoreKeys(custody()),
        )
    }

    private fun picked(bytes: ByteArray, typed: RecoveryCode? = null) =
        RestoreSource.PickedFile(bytes.size.toLong(), typed) { bytes.inputStream() }

    /** Reinicia como un proceso nuevo: el arranque hace el reemplazo. */
    private suspend fun reopen() {
        backupMutex = Mutex()
        val access = fixture.reopen()
        assertTrue("$access", access is DataAccess.Ready)
    }

    private fun assertNothingLeft() {
        for (file in listOf(files.lock, files.incoming, files.candidate, files.journal)) {
            assertFalse("Quedó ${file.name}", file.exists())
        }
    }

    private fun count(table: String): Long =
        inspectWithoutRoom(fixture.file) { it.longValue("SELECT COUNT(*) FROM $table") }

    private fun texts(sql: String): List<String> = inspectWithoutRoom(fixture.file) {
        it.texts(sql)
    }

    private fun deleteTestKeys() {
        listOf(aliases.first, aliases.second).forEach { KeystoreSealer(it).deleteKey() }
    }

    /** La muerte del proceso: nada la atrapa, igual que en un teléfono. */
    private class ProcessDeath : Error()

    private companion object {
        val ON =
            LocalEncryption(
                active = true,
                keyAvailable = true,
                hasRecoveryCode = true,
                offConfirmed = false,
            )
    }
}
