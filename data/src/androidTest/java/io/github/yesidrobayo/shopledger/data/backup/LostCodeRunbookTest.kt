package io.github.yesidrobayo.shopledger.data.backup

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yesidrobayo.shopledger.data.DataAccess
import io.github.yesidrobayo.shopledger.data.crypto.BackupKey
import io.github.yesidrobayo.shopledger.data.crypto.KeystoreSealer
import io.github.yesidrobayo.shopledger.data.crypto.RecoveryCode
import io.github.yesidrobayo.shopledger.data.crypto.RecoveryCustody
import io.github.yesidrobayo.shopledger.data.crypto.RecoveryKeyRead
import io.github.yesidrobayo.shopledger.data.crypto.RecoveryKeySave
import io.github.yesidrobayo.shopledger.data.crypto.RecoveryKeyStore
import io.github.yesidrobayo.shopledger.data.crypto.RecoveryStateStore
import io.github.yesidrobayo.shopledger.data.destination.CopyMetadata
import io.github.yesidrobayo.shopledger.data.destination.DestinationOpen
import io.github.yesidrobayo.shopledger.data.destination.DestinationResult
import io.github.yesidrobayo.shopledger.data.destination.FakeBackupDestination
import io.github.yesidrobayo.shopledger.data.destination.FakeBackupDestination.Operation
import io.github.yesidrobayo.shopledger.data.destination.ListedCopy
import io.github.yesidrobayo.shopledger.data.destination.ListedMeta
import io.github.yesidrobayo.shopledger.data.destination.SlMetaCodec
import io.github.yesidrobayo.shopledger.data.local.AppDatabase
import io.github.yesidrobayo.shopledger.data.local.DatabaseFiles
import io.github.yesidrobayo.shopledger.data.local.inspectWithoutRoom
import io.github.yesidrobayo.shopledger.data.local.longValue
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupFailureCode
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * El código de recuperación perdido, de punta a punta, con la base real, Keystore real y la
 * custodia de la app, como dice el runbook de operaciones:
 *
 * - **Sin la clave del teléfono** el respaldo se detiene con `ENCRYPTION_KEY_UNAVAILABLE`: no sube
 *   nada, nunca en claro, y la base y la copia anterior quedan intactas. Escribir el código vuelve
 *   a guardarlo, y la copia siguiente sale cifrada con el mismo. Crear otro código, sin el
 *   anterior, también destraba: la copia siguiente sale con el nuevo, y la anterior sigue pidiendo
 *   el suyo.
 * - **Con el teléfono perdido y sin el código**, en un teléfono nuevo, la copia cifrada no abre con
 *   ningún otro código, y la copia en claro de antes de activar sí se restaura.
 *
 * Los códigos y los datos son inventados.
 */
@RunWith(AndroidJUnit4::class)
class LostCodeRunbookTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val fixture = BackupFixture(context, "lost-code-runbook")
    private val newPhone = BackupFixture(context, "lost-code-new-phone")
    private val destination = FakeBackupDestination()
    private val control = TestControl()
    private val code = RecoveryCode("0123456789ABCDEFGHJKMNPQRS")
    private val newCode = RecoveryCode("SRQPNMKJHGFEDCBA9876543210")
    private val aliases = "test-runbook-code-a" to "test-runbook-code-b"

    private fun custody(phone: BackupFixture = fixture): RecoveryCustody {
        val directory = File(phone.noBackup, RecoveryKeyStore.DIRECTORY)
        return RecoveryCustody(
            keys = RecoveryKeyStore({ directory }, Dispatchers.IO, aliases),
            state = RecoveryStateStore({ directory }, Dispatchers.IO),
            nowUtcMillis = { phone.clock.nowEpochMillis() },
        )
    }

    @Before
    fun open() = runBlocking<Unit> {
        deleteKeys()
        fixture.open()
        fixture.connect()
        newPhone.open()
        newPhone.connect()
    }

    @After
    fun close() {
        fixture.close()
        newPhone.close()
        deleteKeys()
    }

    @Test
    fun withoutTheKeyNothingGoesOutAndTypingTheCodeResumesTheSameEncryption() = runBlocking<Unit> {
        activate(code)
        fixture.addProduct("Pan de prueba")
        assertEquals(BackupRun.Verified(1), run())

        // Android perdió la clave del sobre: el código ya no abre en este teléfono.
        deleteKeys()
        fixture.addProduct("Leche de prueba")
        val stopped = run()

        assertEquals(
            BackupRun.Stopped(StopCause.Failure(BackupFailureCode.ENCRYPTION_KEY_UNAVAILABLE)),
            stopped,
        )
        assertEquals(BackupFailureCode.ENCRYPTION_KEY_UNAVAILABLE.name, fixture.state().failureCode)
        assertEquals(listOf(1L), generations())
        assertEquals(2L, count(fixture, "products"))
        assertTrue(fixture.stagingFiles().isEmpty())

        // `Escribir el código`: el mismo código vuelve a quedar guardado, y las copias siguen.
        assertTrue(custody().keys.seal(code) is RecoveryKeySave.Sealed)
        assertEquals(BackupRun.Verified(2), run())
        assertNull(fixture.state().failureCode)
        assertEquals(listOf(BackupKey.from(code).keyIdHex), keyIds().distinct())
    }

    @Test
    fun anotherCodeWithoutTheOldOneResumesAndOldCopiesStillNeedTheirs() = runBlocking<Unit> {
        activate(code)
        fixture.addProduct("Pan de prueba")
        assertEquals(BackupRun.Verified(1), run())
        deleteKeys()
        fixture.addProduct("Leche de prueba")
        assertTrue(run() is BackupRun.Stopped)

        // `Crear otro código`: no necesita el anterior, porque la base local está en claro.
        activate(newCode)
        assertEquals(BackupRun.Verified(2), run())

        assertEquals(
            listOf(BackupKey.from(code).keyIdHex, BackupKey.from(newCode).keyIdHex),
            keyIds(),
        )
        // En un teléfono nuevo, la copia anterior sigue pidiendo su código, y el nuevo no la abre.
        val first = listed(1)
        assertEquals(
            RestoreStage.NeedsRecoveryCode(otherCode = true),
            coordinator(newPhone).start(RestoreSource.Listed(first, newCode)),
        )
        assertEquals(
            RestoreStage.ReadyToRestart(0),
            coordinator(newPhone).start(RestoreSource.Listed(first, code)),
        )
        assertTrue(newPhone.reopen() is DataAccess.Ready)
        assertEquals(1L, count(newPhone, "products"))
    }

    @Test
    fun aLostPhoneWithoutTheCodeKeepsOnlyThePlainCopyFromBefore() = runBlocking<Unit> {
        fixture.addProduct("Pan de prueba")
        assertEquals(BackupRun.Verified(1), run())
        activate(code)
        fixture.addProduct("Leche de prueba")
        assertEquals(BackupRun.Verified(2), run())
        assertFalse(destination.calls.contains(Operation.DOWNLOAD))

        // El teléfono nuevo no tiene el código: la copia cifrada no abre con ningún otro.
        assertEquals(
            RestoreStage.NeedsRecoveryCode(otherCode = false),
            coordinator(newPhone).start(RestoreSource.Listed(listed(2))),
        )
        assertEquals(
            RestoreStage.NeedsRecoveryCode(otherCode = true),
            coordinator(newPhone).start(RestoreSource.Listed(listed(2), newCode)),
        )
        assertFalse(destination.calls.contains(Operation.DOWNLOAD))
        // La copia en claro de antes de activar sí se restaura.
        assertEquals(
            RestoreStage.ReadyToRestart(0),
            coordinator(newPhone).start(RestoreSource.Listed(listed(1))),
        )
        assertTrue(newPhone.reopen() is DataAccess.Ready)
        assertEquals(1L, count(newPhone, "products"))
        assertEquals(
            RecoveryKeyRead.Absent,
            custody(newPhone).keys.open(),
        )
    }

    // Apoyo

    /** Activa el cifrado con [active], como lo deja la activación: el sobre y luego el estado. */
    private suspend fun activate(active: RecoveryCode) {
        assertTrue(custody().keys.seal(active) is RecoveryKeySave.Sealed)
        val state = custody().alignAtStartup()
        assertTrue(state.enabled)
        assertEquals(BackupKey.from(active).keyIdHex, state.keyIdHex)
    }

    /** Una ejecución del respaldo con la decisión de cifrado de la app, sobre la custodia real. */
    private suspend fun run(): BackupRun {
        val custody = custody()
        val packager = BackupPackager(Dispatchers.IO, encryption = { custody.packEncryption() })
        return fixture
            .runner(destination, packager = packager, encryption = { localEncryption(custody) })
            .run(control)
    }

    private suspend fun localEncryption(custody: RecoveryCustody): LocalEncryption {
        val state = custody.alignAtStartup()
        val key = custody.keys.open()
        return LocalEncryption(
            active = state.enabled,
            keyAvailable = key is RecoveryKeyRead.Available,
            hasRecoveryCode = key !is RecoveryKeyRead.Absent,
            offConfirmed = state.offConfirmedAtUtcMillis != null,
        )
    }

    private suspend fun listing(): List<ListedCopy> =
        (destination.listCopies(control) as DestinationResult.Success).value.copies

    private suspend fun listed(generation: Long): ListedCopy =
        listing().single { it.name?.generation == generation }

    private suspend fun generations(): List<Long> =
        listing().mapNotNull { it.name?.generation }.sorted()

    /** El `sl_key_id` de cada copia cifrada, por generación. */
    private suspend fun keyIds(): List<String> = listing()
        .sortedBy { it.name?.generation }
        .mapNotNull { copy ->
            val bytes = (copy.meta as? ListedMeta.Present)?.bytes ?: return@mapNotNull null
            (SlMetaCodec.decodeUnnamed(bytes) as? CopyMetadata.Valid)?.meta?.keyId
        }

    private fun coordinator(phone: BackupFixture): RestoreCoordinator {
        val files = RestoreFiles(DatabaseFiles(phone.file, phone.noBackup))
        val ops = DurableFileOps()
        val validator = CandidateValidator(Dispatchers.IO, AppDatabase.SCHEMA_VERSION)
        return RestoreCoordinator(
            files = files,
            ops = ops,
            lock = RestoreLockFile(files, ops, phone.clock, Dispatchers.IO),
            pause = MutexBackupPause(Mutex()),
            active = { activeDatabaseOf(phone.ports) },
            linkColumns = { null },
            destinations = { DestinationOpen.Opened(destination) },
            unpacker = CandidateUnpacker(Dispatchers.IO),
            validator = validator,
            repair = CandidateRepair(Dispatchers.IO),
            migrator = CandidateMigrator { error("Esta prueba no migra") },
            preparation = CandidatePreparation(Dispatchers.IO),
            safetyCopies = SafetyCopyStore(files, ops, Dispatchers.IO),
            journalStore = RestoreJournalStore(files, ops),
            closeDatabase = { phone.ports.closeForRestore() },
            deviceSecure = { true },
            clock = phone.clock,
            log = phone.log,
            ioContext = Dispatchers.IO,
            keys = CustodyRestoreKeys(custody(phone)),
        )
    }

    private fun count(phone: BackupFixture, table: String): Long =
        inspectWithoutRoom(phone.file) { it.longValue("SELECT COUNT(*) FROM $table") }

    private fun deleteKeys() {
        listOf(aliases.first, aliases.second).forEach { KeystoreSealer(it).deleteKey() }
    }
}
