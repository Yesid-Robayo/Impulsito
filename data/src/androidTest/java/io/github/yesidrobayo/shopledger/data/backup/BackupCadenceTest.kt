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
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupDestinationKind
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
 * servicio lleno una copia automática que no cabe no genera nada. Con la nube del teléfono, el
 * intervalo propio de esa nube vale con cualquier red o sin ella, y `Respaldar ahora` lo ignora.
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

    @Test
    fun withThePhoneCloudASaleEveryMinuteForThreeHoursGivesOneCopyPerHourWithAnyNetwork() =
        runBlocking {
            // Lo peor que puede pasar: una subida automática despierta cada minuto, como si
            // conservar hubiera dejado vivo un trabajo viejo. La compuerta es lo único que hace
            // cumplir el intervalo, con la red que haya. Sin red el trabajo la ve como medida, y
            // solo con Wi-Fi tampoco detiene a la nube del teléfono.
            fixture.connect(BackupDestinationKind.DOCUMENT_SLOTS)
            fixture.sql("UPDATE app_settings SET metered_backup_policy = 'WIFI_ONLY' WHERE id = 1")
            val destination = FakeBackupDestination(BackupDestinationKind.DOCUMENT_SLOTS)
            val written = mutableListOf<Long>()

            for (minute in 0L until 180L) {
                fixture.addProduct("Producto de prueba $minute")
                control.metered = minute % 3 != 0L
                when (val run = fixture.runner(destination, uploadGate = gate).run(control)) {
                    is BackupRun.Verified -> written += minute
                    BackupRun.Skipped(SkipReason.WAITING_FOR_INTERVAL) -> Unit
                    else -> throw AssertionError("Minuto $minute: $run")
                }
                fixture.advance(1.minutes)
            }

            assertEquals(listOf(0L, 60L, 120L), written)
            assertEquals(3, destination.calls.count { it == Operation.UPLOAD })
        }

    @Test
    fun withThePhoneCloudAndNoNetworkBackupNowWritesInsideTheInterval() = runBlocking {
        fixture.connect(BackupDestinationKind.DOCUMENT_SLOTS)
        val destination = FakeBackupDestination(BackupDestinationKind.DOCUMENT_SLOTS)
        // Sin red, el trabajo ve la red como medida.
        control.metered = true
        fixture.addProduct("Pan de prueba")
        assertEquals(
            BackupRun.Verified(1),
            fixture.runner(destination, uploadGate = gate).run(control),
        )
        fixture.addProduct("Leche de prueba")
        fixture.advance(5.minutes)
        assertEquals(
            BackupRun.Skipped(SkipReason.WAITING_FOR_INTERVAL),
            fixture.runner(destination, uploadGate = gate).run(control),
        )

        control.backupNow = true
        assertEquals(
            BackupRun.Verified(2),
            fixture.runner(destination, uploadGate = gate).run(control),
        )
        assertEquals(fixture.now.toEpochMilliseconds(), fixture.state().lastUploadFinishedAt)
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
