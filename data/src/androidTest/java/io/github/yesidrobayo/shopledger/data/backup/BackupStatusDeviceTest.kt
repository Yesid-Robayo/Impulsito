package io.github.yesidrobayo.shopledger.data.backup

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yesidrobayo.shopledger.data.destination.DestinationLink
import io.github.yesidrobayo.shopledger.data.destination.DestinationLinkStore
import io.github.yesidrobayo.shopledger.data.destination.FakeBackupDestination
import io.github.yesidrobayo.shopledger.data.destination.slots.FileSlotStateStore
import io.github.yesidrobayo.shopledger.data.destination.slots.SlotEntry
import io.github.yesidrobayo.shopledger.data.destination.slots.SlotState
import io.github.yesidrobayo.shopledger.data.destination.slots.WriteMode
import io.github.yesidrobayo.shopledger.data.safety.PolicyConsentFile
import io.github.yesidrobayo.shopledger.data.safety.PolicyConsentGate
import io.github.yesidrobayo.shopledger.data.work.productionBackupStatusEnvironment
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupDestinationKind
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupNetwork
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupNotice
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupSlot
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupStatus
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupUploadWork
import io.github.yesidrobayo.shopledger.domain.model.safety.PolicyVersions
import io.github.yesidrobayo.shopledger.domain.model.settings.MeteredBackupPolicy
import io.github.yesidrobayo.shopledger.domain.port.PolicyAcceptance
import io.github.yesidrobayo.shopledger.domain.usecase.backup.ObserveBackupStatusUseCase
import java.io.File
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
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
 * El estado del respaldo leído de la base real: al abrir la app un día después, un cambio sin copia
 * se ve como un día sin copia aunque ningún trabajo haya corrido, y también con el reloj atrasado.
 * Lo que se lee fuera de la base, como las marcas de una restauración, la aceptación de la política
 * y el estado de las ranuras, sale de sus archivos.
 */
@RunWith(AndroidJUnit4::class)
class BackupStatusDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val fixture = BackupFixture(context, "backup-status-test")
    private val outside = File(context.noBackupFilesDir, "backup-status-test-outside")
    private var firstHandoffAt: Long? = null
    private var connectedAt: Long? = null
    private var restorePending = false

    /** Sin red de verdad ni trabajos: lo que el teléfono diría está fijo en la prueba. */
    private val environment =
        BackupStatusEnvironment(
            restorePending = { restorePending },
            policyAccepted = { true },
            uploadWork = { BackupUploadWork.NONE },
            network = {
                BackupNetwork(validated = true, metered = false, dataSaverRestricted = false)
            },
            firstHandoffAt = { firstHandoffAt },
            connectedAt = { connectedAt },
        )

    private lateinit var status: ObserveBackupStatusUseCase

    @Before
    fun open() = runBlocking {
        fixture.open()
        fixture.connect()
        outside.deleteRecursively()
        status = useCaseWith(environment)
    }

    @After
    fun close() {
        fixture.close()
        outside.deleteRecursively()
    }

    private fun useCaseWith(environment: BackupStatusEnvironment) = ObserveBackupStatusUseCase(
        BackupStatusReader(
            state = fixture.ports.backupState,
            meteredPolicy = { MeteredBackupPolicy.EVERY_60_MIN },
            environment = environment,
            ioContext = Dispatchers.IO,
        ),
        fixture.clock,
    )

    @Test
    fun aChangeWithoutACopyIsStaleADayLaterWithoutAnyWorker() = runBlocking {
        fixture.addProduct("Pan de prueba")

        val fresh = status.current()
        assertEquals(BackupStatus.PENDING, fresh.status)
        assertEquals(1L, fresh.pendingChanges)
        assertTrue(fresh.savingSoon)

        fixture.advance(15.minutes)
        assertFalse(status.current().savingSoon)

        fixture.advance(24.hours)
        assertEquals(BackupStatus.STALE, status.current().status)
        assertEquals(BackupStatus.STALE, status().first().status)
    }

    @Test
    fun aClockSetBackBeforeTheOldestChangeIsStale() = runBlocking {
        fixture.addProduct("Pan de prueba")

        fixture.advance(-2.hours)

        assertEquals(BackupStatus.STALE, status.current().status)
    }

    @Test
    fun aConfirmedCopyIsUpToDateWithWebDavAndOnlyHandedOffWithSlots() = runBlocking {
        fixture.addProduct("Pan de prueba")

        assertTrue(fixture.runner(FakeBackupDestination()).run(TestControl()) is BackupRun.Verified)
        assertEquals(BackupStatus.UP_TO_DATE, status.current().status)
        assertEquals(0L, status.current().pendingChanges)

        fixture.connect(BackupDestinationKind.DOCUMENT_SLOTS)
        firstHandoffAt = fixture.now.toEpochMilliseconds()
        assertEquals(BackupStatus.HANDED_OFF, status.current().status)

        // Sin revisar la nube en siete días, toca revisarla: entregada no es subida.
        fixture.advance(24.hours * 7)
        assertEquals(BackupStatus.CHECK_DUE, status.current().status)
    }

    @Test
    fun withSlotsTheCheckNoticeStaysInSightBehindAFailureAndNeverSaysAllSaved() = runBlocking {
        fixture.addProduct("Pan de prueba")
        assertTrue(fixture.runner(FakeBackupDestination()).run(TestControl()) is BackupRun.Verified)
        fixture.connect(BackupDestinationKind.DOCUMENT_SLOTS)
        firstHandoffAt = fixture.now.toEpochMilliseconds()
        fixture.advance(24.hours * 7)
        fixture.sql("UPDATE backup_state SET failure_code = 'LOCAL_INVARIANT' WHERE id = 1")

        val report = status.current()

        assertEquals(BackupStatus.FAILED, report.status)
        assertTrue(BackupNotice.HUMAN_CHECK_DUE in report.notices)
        // Revisar lo quita, y con ranuras nunca se dice que todo está guardado.
        fixture.sql(
            "UPDATE backup_state SET failure_code = NULL, " +
                "last_human_check_at = ${fixture.now.toEpochMilliseconds()} WHERE id = 1",
        )
        val checked = status.current()
        assertEquals(BackupStatus.HANDED_OFF, checked.status)
        assertFalse(BackupNotice.HUMAN_CHECK_DUE in checked.notices)
    }

    @Test
    fun withWebDavAWeekWithoutAWholeReadIsANoticeAndTheStatusStays() = runBlocking {
        fixture.addProduct("Pan de prueba")
        assertTrue(fixture.runner(FakeBackupDestination()).run(TestControl()) is BackupRun.Verified)
        connectedAt = fixture.now.toEpochMilliseconds()
        fixture.sql("UPDATE backup_state SET last_full_verify_at = NULL WHERE id = 1")
        fixture.advance(24.hours * 7)

        val report = status.current()
        assertEquals(BackupStatus.UP_TO_DATE, report.status)
        assertTrue(BackupNotice.FULL_READ_OVERDUE in report.notices)

        // Una lectura completa la quita.
        fixture.ports.backupState.markFullVerify(
            fixture.now.toEpochMilliseconds(),
            TEST_DESTINATION_ID,
        )
        assertFalse(BackupNotice.FULL_READ_OVERDUE in status.current().notices)
    }

    @Test
    fun sixTransportFailuresShowAsFailedAndADayLaterAsStale() = runBlocking {
        fixture.addProduct("Pan de prueba")
        fixture.sql(
            "UPDATE backup_state SET consecutive_failures = 6, last_failure_kind = 'TLS', " +
                "failure_code = 'TRANSPORT_PERSISTENT' WHERE id = 1",
        )

        assertEquals(BackupStatus.FAILED, status.current().status)

        fixture.advance(25.hours)
        assertEquals(BackupStatus.STALE, status.current().status)
    }

    @Test
    fun aRestoreInProgressWinsOverEverything() = runBlocking {
        fixture.addProduct("Pan de prueba")
        restorePending = true

        assertEquals(BackupStatus.RESTORING, status.current().status)
    }

    @Test
    fun theAppReadsTheRestoreMarksAndTheSlotStateFromTheirFiles() = runBlocking {
        val consent =
            PolicyConsentGate(
                PolicyConsentFile({ outside }, Dispatchers.IO),
                PolicyVersions.CURRENT,
                fixture.log,
                onAccepted = {},
            )
        val app = productionBackupStatusEnvironment(context, outside, consent, Dispatchers.IO)

        assertFalse(app.restorePending())
        // Sin aceptar la política, las copias en la nube quedan en pausa.
        assertFalse(app.policyAccepted())
        assertEquals(PolicyAcceptance.SAVED, consent.acceptCurrent())
        assertTrue(app.policyAccepted())
        assertNull(app.firstHandoffAt())
        // Sin trabajos encolados, ninguno corre ni espera.
        assertEquals(BackupUploadWork.NONE, app.uploadWork())
        // La red del emulador cambia: solo se comprueba que leerla no falle.
        app.network()

        val restore = File(outside, RestoreMarkers.RESTORE_DIRECTORY)
        restore.mkdirs()
        File(restore, RestoreMarkers.IN_PROGRESS).writeText("")
        assertTrue(app.restorePending())

        FileSlotStateStore(outside, Dispatchers.IO)
            .save(slotState(firstHandoffAt = 1_793_876_400_000))
        assertEquals(1_793_876_400_000, app.firstHandoffAt())
        assertEquals("Nube", slotProviderLabel(outside, Dispatchers.IO))

        assertNull(app.connectedAt())
        DestinationLinkStore({ outside }, Dispatchers.IO).write(
            DestinationLink(
                kind = BackupDestinationKind.WEBDAV,
                account = "persona",
                destinationId = TEST_DESTINATION_ID,
                folderUrl = "https://dav.example.com/app/",
                connectedAtMillis = 1_793_000_000_000,
            ),
        )
        assertEquals(1_793_000_000_000, app.connectedAt())
    }

    private fun slotState(firstHandoffAt: Long) = SlotState(
        slots =
            BackupSlot.entries.associateWith {
                SlotEntry(
                    "content://example.documents/document/${it.name}",
                    "Nube",
                    null,
                    null,
                    null,
                )
            },
        firstHandoffAt = firstHandoffAt,
        writeMode = WriteMode.WT,
        sessionRequired = false,
    )
}
