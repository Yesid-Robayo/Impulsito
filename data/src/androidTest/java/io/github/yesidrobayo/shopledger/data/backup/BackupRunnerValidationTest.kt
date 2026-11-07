package io.github.yesidrobayo.shopledger.data.backup

import androidx.sqlite.execSQL
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yesidrobayo.shopledger.data.destination.BackupFileName
import io.github.yesidrobayo.shopledger.data.destination.CopyMetadata
import io.github.yesidrobayo.shopledger.data.destination.FakeBackupDestination
import io.github.yesidrobayo.shopledger.data.destination.FakeBackupDestination.Operation
import io.github.yesidrobayo.shopledger.data.destination.SlMeta
import io.github.yesidrobayo.shopledger.data.destination.SlMetaCodec
import io.github.yesidrobayo.shopledger.data.destination.identity
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupDestinationKind
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupFailureCode
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Cada copia nueva pasa por el mismo validador que decide si una copia se puede restaurar, sobre
 * la instantánea sellada. Una copia con saldos por recalcular sube igual, marcada; una que la app no
 * podría restaurar sube solo para un rescate con WebDAV, y con ranuras no se escribe. Ninguna de
 * las dos se ve como copia buena.
 */
@RunWith(AndroidJUnit4::class)
class BackupRunnerValidationTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val fixture = BackupFixture(context, "backup-runner-validation-test")
    private val control = TestControl()

    @Before
    fun open() = runBlocking {
        fixture.open()
        fixture.connect()
        fixture.addProduct("Pan de prueba")
    }

    @After
    fun close() {
        fixture.close()
    }

    @Test
    fun aDriftedStockUploadsMarkedNotValidAndKeepsTheCountForTheNotice() = runBlocking {
        driftStock(by = 1_000)
        val destination = FakeBackupDestination()

        val run = fixture.runner(destination).run(control)

        assertEquals(BackupRun.Verified(1), run)
        val meta = metaOf(destination)
        assertFalse(meta.valid)
        assertFalse(meta.fatal)
        val state = fixture.state()
        assertEquals(BackupFailureCode.LOCAL_INVARIANT.name, state.failureCode)
        // Un producto descuadrado, aunque falle a la vez en su stock y en su saldo de cabecera.
        assertEquals(1L, state.invariantFailures)
        // Una copia dudosa vale más que ninguna: los cambios quedan respaldados.
        assertEquals(state.changeSeq, state.verifiedSeq)
        assertEquals(1L, state.headGeneration)
    }

    @Test
    fun aValidCopyAfterARepairableOneClearsTheFailureAndTheCount() = runBlocking {
        driftStock(by = 1_000)
        fixture.runner(FakeBackupDestination()).run(control)

        driftStock(by = -1_000)
        fixture.addProduct("Leche de prueba")
        val destination = FakeBackupDestination()
        fixture.runner(destination).run(control)

        assertTrue(metaOf(destination).valid)
        assertNull(fixture.state().failureCode)
        assertEquals(0L, fixture.state().invariantFailures)
    }

    @Test
    fun anUnrestorableCopyUploadsWithWebDavOnlyForRescueAndChangesStayPending() = runBlocking {
        val before = fixture.state()
        breakForeignKey()
        val destination = FakeBackupDestination()

        val run = fixture.runner(destination).run(control)

        assertEquals(BackupRun.Verified(1, unrestorable = true), run)
        val meta = metaOf(destination)
        assertFalse(meta.valid)
        assertTrue(meta.fatal)
        val state = fixture.state()
        assertEquals(BackupFailureCode.LOCAL_INVARIANT_FATAL.name, state.failureCode)
        // Es la cabeza, para que la guarda la reconozca como propia.
        assertEquals(1L, state.headGeneration)
        assertEquals(TEST_DEVICE_ID, state.headDeviceId)
        // Pero los cambios siguen pendientes: no cuenta como respaldo.
        assertEquals(before.verifiedSeq, state.verifiedSeq)
        assertEquals(before.firstUnsyncedAt, state.firstUnsyncedAt)
        assertNull(state.lastSuccessAt)
        assertNull(state.pendingGeneration)
        assertEquals(0L, state.consecutiveFailures)
        // El trabajo deja en el registro que esa copia es solo para un rescate.
        fixture.log.report(run)
        assertTrue(fixture.log.lines.any { it.contains("rescate") })
    }

    @Test
    fun anUnrestorableCopyIsNeverWrittenToASlot() = runBlocking {
        fixture.connect(BackupDestinationKind.DOCUMENT_SLOTS)
        val before = fixture.state()
        breakForeignKey()
        val destination = FakeBackupDestination(BackupDestinationKind.DOCUMENT_SLOTS)

        val run = fixture.runner(destination).run(control)

        assertEquals(BackupRun.Skipped(SkipReason.UNRESTORABLE_NOT_WRITTEN), run)
        // Solo listó: ninguna ranura se eligió ni se abrió.
        assertEquals(listOf(Operation.LIST), destination.calls)
        val state = fixture.state()
        assertEquals(BackupFailureCode.LOCAL_INVARIANT_FATAL.name, state.failureCode)
        assertEquals(before.headGeneration, state.headGeneration)
        assertEquals(before.headFileRef, state.headFileRef)
        assertEquals(before.verifiedSeq, state.verifiedSeq)
        assertNull(state.pendingGeneration)
        assertEquals(emptyList<String>(), fixture.stagingFiles())
    }

    @Test
    fun aRestorableCopyAfterAnUnrestorableOneCatchesUpTheChanges() = runBlocking {
        breakForeignKey()
        fixture.runner(FakeBackupDestination()).run(control)

        repairForeignKey()
        fixture.addProduct("Leche de prueba")
        val run = fixture.runner(FakeBackupDestination()).run(control)

        assertEquals(BackupRun.Verified(2), run)
        val state = fixture.state()
        assertEquals(state.changeSeq, state.verifiedSeq)
        assertNull(state.failureCode)
        assertNull(state.firstUnsyncedAt)
    }

    @Test
    fun aWarningOnlyCountsAndTheCopyStaysValid() = runBlocking {
        fixture.sql("UPDATE backup_state SET invariant_failures = 3 WHERE id = 1")
        corrupt(fixture.file) {
            withoutGuards("app_settings") {
                execSQL("UPDATE app_settings SET color_palette = 'NEON'")
            }
        }
        val destination = FakeBackupDestination()

        fixture.runner(destination).run(control)

        assertTrue(metaOf(destination).valid)
        assertFalse(metaOf(destination).fatal)
        assertNull(fixture.state().failureCode)
        assertEquals(0L, fixture.state().invariantFailures)
    }

    private fun driftStock(by: Long) = corrupt(fixture.file) {
        execSQL("PRAGMA busy_timeout = 5000")
        withoutGuards("products") {
            execSQL("UPDATE products SET on_hand_milli = on_hand_milli + $by")
        }
    }

    /** Un producto apunta a una categoría que no existe: una llave foránea rota es fatal. */
    private fun breakForeignKey() = corrupt(fixture.file) {
        execSQL("PRAGMA busy_timeout = 5000")
        withoutGuards("products") { execSQL("UPDATE products SET category_id = 999999") }
    }

    private fun repairForeignKey() = corrupt(fixture.file) {
        execSQL("PRAGMA busy_timeout = 5000")
        withoutGuards("products") { execSQL("UPDATE products SET category_id = NULL") }
    }

    private fun metaOf(destination: FakeBackupDestination): SlMeta {
        val name = requireNotNull(destination.fileNames.firstNotNullOfOrNull(BackupFileName::parse))
        val decoded = SlMetaCodec.decode(destination.file(name.metaFileName), name.identity())
        return (decoded as CopyMetadata.Valid).meta
    }
}
