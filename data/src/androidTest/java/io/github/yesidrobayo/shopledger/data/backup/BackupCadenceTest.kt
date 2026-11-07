package io.github.yesidrobayo.shopledger.data.backup

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yesidrobayo.shopledger.data.destination.BackupDestination
import io.github.yesidrobayo.shopledger.data.destination.BackupFileName
import io.github.yesidrobayo.shopledger.data.destination.DestinationFailure
import io.github.yesidrobayo.shopledger.data.destination.DestinationResult
import io.github.yesidrobayo.shopledger.data.destination.FakeBackupDestination
import io.github.yesidrobayo.shopledger.data.destination.FakeBackupDestination.Operation
import io.github.yesidrobayo.shopledger.data.destination.InterruptReason
import io.github.yesidrobayo.shopledger.data.destination.StoredCopy
import io.github.yesidrobayo.shopledger.data.destination.TransferControl
import io.github.yesidrobayo.shopledger.data.destination.TransientKind
import io.github.yesidrobayo.shopledger.data.destination.UploadPlan
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupFailureCode
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
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
 * La compuerta de red del paso 0 y la cuota antes de generar, con la base real y el destino falso:
 * con datos móviles una copia automática respeta el intervalo de la política, `Respaldar ahora` y
 * la copia que ya lleva un día no lo miran, un corte en red medida cuenta como subida, y con el
 * servicio lleno una copia automática que no cabe no genera nada.
 */
@RunWith(AndroidJUnit4::class)
class BackupCadenceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val fixture = BackupFixture(context, "backup-cadence-test")
    private val control = TestControl()
    private val gate =
        PolicyUploadGate(
            policy = { fixture.ports.settings.get().preferences.meteredBackupPolicy },
            clock = { fixture.now },
        )

    @Before
    fun open() = runBlocking {
        fixture.open()
        fixture.connect()
    }

    @After
    fun close() {
        fixture.close()
    }

    @Test
    fun onMobileDataTheIntervalHoldsAnAutomaticCopyWithoutCountingAnAttempt() = runBlocking {
        val destination = FakeBackupDestination()
        val firstCopy = firstCopyThenAChange(destination)
        control.metered = true
        fixture.advance(59.minutes)
        val calls = destination.calls

        val held = fixture.runner(destination, uploadGate = gate).run(control)

        assertEquals(BackupRun.Skipped(SkipReason.WAITING_FOR_INTERVAL), held)
        assertEquals(firstCopy, destination.fileNames)
        // Ni siquiera listó.
        assertEquals(calls, destination.calls)
        // Salió antes de empezar: no cuenta como intento.
        assertEquals(
            (fixture.now - 59.minutes).toEpochMilliseconds(),
            fixture.state().lastAttemptAt,
        )

        fixture.advance(1.minutes)
        val run = fixture.runner(destination, uploadGate = gate).run(control)

        assertEquals(BackupRun.Verified(2), run)
    }

    @Test
    fun theHalfHourPolicyHoldsHalfAnHour() = runBlocking {
        fixture.sql("UPDATE app_settings SET metered_backup_policy = 'EVERY_30_MIN' WHERE id = 1")
        val destination = FakeBackupDestination()
        firstCopyThenAChange(destination)
        control.metered = true

        fixture.advance(29.minutes)
        assertEquals(
            BackupRun.Skipped(SkipReason.WAITING_FOR_INTERVAL),
            fixture.runner(destination, uploadGate = gate).run(control),
        )
        fixture.advance(1.minutes)
        assertEquals(
            BackupRun.Verified(2),
            fixture.runner(destination, uploadGate = gate).run(control),
        )
    }

    @Test
    fun withWiFiOnlyMobileDataWaitsForANetworkWithoutCharge() = runBlocking {
        fixture.sql("UPDATE app_settings SET metered_backup_policy = 'WIFI_ONLY' WHERE id = 1")
        fixture.addProduct("Pan de prueba")
        val destination = FakeBackupDestination()
        control.metered = true

        val held = fixture.runner(destination, uploadGate = gate).run(control)

        assertEquals(BackupRun.Skipped(SkipReason.WAITING_FOR_UNMETERED), held)
        assertEquals(emptyList<Operation>(), destination.calls)

        control.metered = false
        assertEquals(
            BackupRun.Verified(1),
            fixture.runner(destination, uploadGate = gate).run(control),
        )
    }

    @Test
    fun backupNowAndADayLateIgnoreTheInterval() = runBlocking {
        val destination = FakeBackupDestination()
        firstCopyThenAChange(destination)
        control.metered = true
        fixture.advance(5.minutes)

        control.backupNow = true
        assertEquals(
            BackupRun.Verified(2),
            fixture.runner(destination, uploadGate = gate).run(control),
        )

        fixture.addProduct("Queso de prueba")
        control.backupNow = false
        control.pastInterval = true
        assertEquals(
            BackupRun.Verified(3),
            fixture.runner(destination, uploadGate = gate).run(control),
        )
    }

    @Test
    fun aNetworkThatTurnsMeteredCutsTheUploadAndTheIntervalCountsFromThere() = runBlocking {
        val destination = FakeBackupDestination()
        val firstCopy = firstCopyThenAChange(destination)
        fixture.advance(10.minutes)
        // Empieza con Wi-Fi, y la red pasa a medida mientras sube.
        val switching = SwitchToMeteredOnUpload(destination, control)

        val run = fixture.runner(switching, uploadGate = gate).run(control)

        assertEquals(
            BackupRun.Retry(RetryReason.INTERRUPTED, interruptReason = InterruptReason.METERED),
            run,
        )
        assertEquals(firstCopy, destination.fileNames.filterNot { it.endsWith(".sltmp") })
        assertEquals(fixture.now.toEpochMilliseconds(), fixture.state().lastUploadFinishedAt)
        // El siguiente intento con datos móviles espera el intervalo desde el corte.
        fixture.advance(30.minutes)
        assertEquals(
            BackupRun.Skipped(SkipReason.WAITING_FOR_INTERVAL),
            fixture.runner(destination, uploadGate = gate).run(control),
        )
    }

    @Test
    fun withTheIntervalOverAMeteredSwitchDoesNotCutTheUpload() = runBlocking {
        val destination = FakeBackupDestination()
        firstCopyThenAChange(destination)
        fixture.advance(2.hours)

        val run =
            fixture
                .runner(SwitchToMeteredOnUpload(destination, control), uploadGate = gate)
                .run(control)

        assertEquals(BackupRun.Verified(2), run)
    }

    @Test
    fun aServerErrorAfterTheUploadOnMobileDataWaitsForTheNextInterval() = runBlocking {
        val destination = FakeBackupDestination()
        firstCopyThenAChange(destination)
        fixture.advance(2.hours)
        control.metered = true
        destination.failNext(
            Operation.UPLOAD,
            DestinationFailure.Transient(TransientKind.SERVER_ERROR),
        )

        val run = fixture.runner(destination, uploadGate = gate).run(control)

        assertEquals(
            BackupRun.Retry(RetryReason.TRANSIENT, transientKind = TransientKind.SERVER_ERROR),
            run,
        )
        assertEquals(fixture.now.toEpochMilliseconds(), fixture.state().lastUploadFinishedAt)
        val uploads = destination.calls.count { it == Operation.UPLOAD }
        // El reintento del sistema llega antes del intervalo: no envía otra vez el archivo.
        fixture.advance(1.minutes)
        assertEquals(
            BackupRun.Skipped(SkipReason.WAITING_FOR_INTERVAL),
            fixture.runner(destination, uploadGate = gate).run(control),
        )
        assertEquals(uploads, destination.calls.count { it == Operation.UPLOAD })
    }

    @Test
    fun aFullServiceThatStillHasNoRoomGeneratesNothingUntilBackupNow() = runBlocking {
        val destination = FakeBackupDestination()
        firstCopyThenAChange(destination)
        val head = destination.fileNames.first { BackupFileName.parse(it) != null }
        destination.quotaAvailableBytes = requireNotNull(destination.file(head)).size - 1L
        fixture.sql("UPDATE backup_state SET failure_code = 'QUOTA_EXCEEDED' WHERE id = 1")
        val attemptBefore = fixture.state().lastAttemptAt
        val uploads = destination.calls.count { it == Operation.UPLOAD }
        fixture.advance(2.hours)

        val held = fixture.runner(destination).run(control)

        assertEquals(BackupRun.Stopped(StopCause.Failure(BackupFailureCode.QUOTA_EXCEEDED)), held)
        assertEquals(uploads, destination.calls.count { it == Operation.UPLOAD })
        assertEquals(emptyList<String>(), fixture.stagingFiles())
        assertEquals(attemptBefore, fixture.state().lastAttemptAt)

        // La cuota avisa, no decide: la persona lo intenta igual, y la copia sube.
        control.backupNow = true
        assertEquals(BackupRun.Verified(2), fixture.runner(destination).run(control))
        assertEquals(uploads + 1, destination.calls.count { it == Operation.UPLOAD })
        assertNull(fixture.state().failureCode)
    }

    @Test
    fun aFullServiceWithRoomAgainUploadsAutomatically() = runBlocking {
        val destination = FakeBackupDestination()
        firstCopyThenAChange(destination)
        destination.quotaAvailableBytes = 100L * 1024 * 1024
        fixture.sql("UPDATE backup_state SET failure_code = 'QUOTA_EXCEEDED' WHERE id = 1")

        assertEquals(BackupRun.Verified(2), fixture.runner(destination).run(control))
    }

    @Test
    fun aFullServiceThatReportsNoSpaceTriesOnceADay() = runBlocking {
        val destination = FakeBackupDestination()
        firstCopyThenAChange(destination)
        fixture.sql("UPDATE backup_state SET failure_code = 'QUOTA_EXCEEDED' WHERE id = 1")
        fixture.advance(23.hours)

        assertEquals(
            BackupRun.Stopped(StopCause.Failure(BackupFailureCode.QUOTA_EXCEEDED)),
            fixture.runner(destination).run(control),
        )
        fixture.advance(1.hours)
        assertEquals(BackupRun.Verified(2), fixture.runner(destination).run(control))
    }

    /** Una primera copia confirmada con Wi-Fi y un cambio después. Da los archivos del destino. */
    private suspend fun firstCopyThenAChange(destination: FakeBackupDestination): List<String> {
        fixture.addProduct("Pan de prueba")
        assertEquals(
            BackupRun.Verified(1),
            fixture.runner(destination, uploadGate = gate).run(control),
        )
        val files = destination.fileNames
        fixture.addProduct("Leche de prueba")
        return files
    }

    /** El destino falso, con la red que pasa a medida justo cuando empieza a subir. */
    private class SwitchToMeteredOnUpload(
        private val destination: FakeBackupDestination,
        private val control: TestControl,
    ) : BackupDestination by destination {
        override suspend fun upload(
            plan: UploadPlan,
            control: TransferControl,
        ): DestinationResult<StoredCopy> {
            this.control.metered = true
            return destination.upload(plan, control)
        }
    }
}
