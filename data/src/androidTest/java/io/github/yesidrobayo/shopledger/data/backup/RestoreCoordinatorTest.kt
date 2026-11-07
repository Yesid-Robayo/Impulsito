package io.github.yesidrobayo.shopledger.data.backup

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yesidrobayo.shopledger.data.DataAccess
import io.github.yesidrobayo.shopledger.data.destination.BackupDestination
import io.github.yesidrobayo.shopledger.data.destination.DestinationOpen
import io.github.yesidrobayo.shopledger.data.destination.DestinationResult
import io.github.yesidrobayo.shopledger.data.destination.FakeBackupDestination
import io.github.yesidrobayo.shopledger.data.destination.ListedCopy
import io.github.yesidrobayo.shopledger.data.local.AppDatabase
import io.github.yesidrobayo.shopledger.data.local.DatabaseFiles
import io.github.yesidrobayo.shopledger.data.local.inspectWithoutRoom
import io.github.yesidrobayo.shopledger.data.local.longValue
import io.github.yesidrobayo.shopledger.data.local.texts
import io.github.yesidrobayo.shopledger.data.repository.newProduct
import io.github.yesidrobayo.shopledger.data.repository.storageFailureOf
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupLinkState
import io.github.yesidrobayo.shopledger.domain.model.settings.PreferenceChange
import io.github.yesidrobayo.shopledger.domain.model.settings.ThemeMode
import io.github.yesidrobayo.shopledger.domain.model.stock.LowStockNotificationStatus
import io.github.yesidrobayo.shopledger.domain.port.StorageFailure
import io.github.yesidrobayo.shopledger.domain.port.TxResult
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlin.time.Duration.Companion.minutes
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
 * La primera fase de la restauración con la base real, el destino falso en memoria y el reemplazo
 * del arranque: la base activa no cambia hasta reiniciar, ninguna escritura entra mientras dura, y
 * la base restaurada nunca dice que todo está guardado si la copia no está en el destino. Todos
 * los datos son inventados.
 */
@RunWith(AndroidJUnit4::class)
class RestoreCoordinatorTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val fixture = BackupFixture(context, "restore-coordinator-test")
    private val control = TestControl()
    private val destination = FakeBackupDestination()
    private var backupMutex = Mutex()
    private val files by lazy { RestoreFiles(DatabaseFiles(fixture.file, fixture.noBackup)) }
    private var ops = DurableFileOps()

    @Before
    fun open() = runBlocking<Unit> {
        fixture.open()
        fixture.connect()
    }

    @After
    fun close() {
        fixture.close()
        CandidateFixtures.clean()
    }

    @Test
    fun aCopyThatIsInTheDestinationIsRestoredAsBackedUp() = runBlocking<Unit> {
        fixture.addProduct("Pan de prueba")
        // Lo que la copia trae y no debe seguir en este teléfono.
        fixture.sql(
            "UPDATE backup_state SET failure_code = 'UNEXPECTED', invariant_failures = 3, " +
                "consecutive_failures = 2, last_failure_kind = 'NETWORK' WHERE id = 1",
        )
        fixture.sql(
            "INSERT INTO low_stock_alerts (product_id, on_hand_milli_at_trigger, " +
                "min_stock_milli_at_trigger, notification_status, occurred_at, business_date, " +
                "resolved_at) SELECT id, 0, 1000, 'PENDING', 1, '2026-11-07', NULL " +
                "FROM products LIMIT 1",
        )
        assertEquals(BackupRun.Verified(1), fixture.runner(destination).run(control))
        fixture.addProduct("Leche de prueba")
        fixture.sql("UPDATE backup_state SET last_human_check_at = 77, human_check_generation = 1")
        val before = fixture.state()

        val stage = coordinator().start(RestoreSource.Listed(listed(1)))

        assertEquals(RestoreStage.ReadyToRestart(0), stage)
        // Hasta reiniciar, la base activa sigue igual.
        assertEquals(2L, count("products"))
        assertTrue(files.journal.exists())
        assertTrue(files.candidate.exists())
        assertFalse(backupMutex.tryLock().also { if (it) backupMutex.unlock() })
        val safety = SafetyCopyStore(files, ops, Dispatchers.IO).copies().single()
        assertEquals(
            SafetyCopyFacts(before.changeSeq, before.verifiedSeq, fixture.now),
            safety.facts,
        )

        reopen()

        assertEquals(1L, count("products"))
        val state = fixture.state()
        assertEquals(
            "La copia está en el destino: nada sin respaldar",
            state.changeSeq,
            state.verifiedSeq,
        )
        assertNull(state.firstUnsyncedAt)
        assertEquals(BackupLinkState.CONNECTED.name, state.linkState)
        assertEquals(TEST_DESTINATION_ID, state.destinationId)
        assertEquals("https://dav.example.com/app/", state.destinationFolder)
        assertEquals(1L, state.headGeneration)
        assertEquals(TEST_DEVICE_ID, state.headDeviceId)
        assertEquals(listed(1).ref.value, state.headFileRef)
        assertEquals(1L, state.shrinkAcceptedGeneration)
        assertEquals(77L, state.lastHumanCheckAt)
        assertEquals(1L, state.humanCheckGeneration)
        assertNull(state.failureCode)
        assertEquals(0L, state.invariantFailures)
        assertEquals(0L, state.consecutiveFailures)
        assertNull(state.lastFailureKind)
        assertNull(state.lastUploadFinishedAt)
        assertNull(state.copyGeneration)
        assertNull(state.copyDeviceId)
        assertNull(state.copyCreatedAt)
        assertNull(state.pendingGeneration)
        assertEquals(
            listOf(LowStockNotificationStatus.SUPPRESSED.name),
            texts("SELECT notification_status FROM low_stock_alerts"),
        )
        assertFalse(files.lock.exists())
        assertFalse(files.journal.exists())
        assertFalse(files.candidate.exists())
        assertFalse(files.previous.exists())
        assertTrue("La copia de seguridad se conserva", safety.files.database.exists())
    }

    @Test
    fun restoringFromAConflictLeavesItConnectedAndTheNextCopyUploads() = runBlocking<Unit> {
        fixture.addProduct("Pan de prueba")
        assertEquals(BackupRun.Verified(1), fixture.runner(destination).run(control))
        fixture.addProduct("Leche de prueba")
        fixture.sql(
            "UPDATE backup_state SET link_state = 'CONFLICT', conflict_kind = 'CLOUD_AHEAD', " +
                "conflict_generation = 1, conflict_device_id = '$OTHER_DEVICE_ID', " +
                "conflict_file_ref = 'otra' WHERE id = 1",
        )

        assertEquals(
            RestoreStage.ReadyToRestart(0),
            coordinator().start(RestoreSource.Listed(listed(1))),
        )
        reopen()

        val restored = fixture.state()
        assertEquals(BackupLinkState.CONNECTED.name, restored.linkState)
        assertNull(restored.conflictKind)
        assertNull(restored.conflictGeneration)
        assertNull(restored.conflictDeviceId)
        assertNull(restored.conflictFileRef)
        fixture.addProduct("Queso de prueba")
        assertEquals(BackupRun.Verified(2), fixture.runner(destination).run(control))
        assertEquals(fixture.state().changeSeq, fixture.state().verifiedSeq)
    }

    @Test
    fun undoingWithUnsyncedChangesForcesAnUpload() = runBlocking<Unit> {
        fixture.addProduct("Pan de prueba")
        assertEquals(BackupRun.Verified(1), fixture.runner(destination).run(control))
        for (name in listOf("Leche de prueba", "Queso de prueba", "Café de prueba")) {
            fixture.addProduct(name)
        }
        val unsynced = fixture.state()
        assertEquals(3L, unsynced.changeSeq - unsynced.verifiedSeq)
        assertEquals(
            RestoreStage.ReadyToRestart(0),
            coordinator().start(RestoreSource.Listed(listed(1))),
        )
        reopen()
        assertEquals(1L, count("products"))
        val copy = SafetyCopyStore(files, ops, Dispatchers.IO).available(fixture.now).single()
        assertTrue(checkNotNull(copy.facts).hasUnsyncedChanges)
        fixture.advance(5.minutes)

        assertEquals(RestoreStage.ReadyToRestart(0), coordinator().start(RestoreSource.Undo(copy)))
        assertTrue("El origen del deshacer se apartó", files.undoSource.database.exists())
        reopen()

        assertEquals(4L, count("products"))
        val state = fixture.state()
        assertEquals("Fuerza una subida", unsynced.changeSeq + 1, state.changeSeq)
        assertEquals(unsynced.verifiedSeq, state.verifiedSeq)
        assertEquals(fixture.now.toEpochMilliseconds(), state.firstUnsyncedAt)
        assertEquals("Conserva su última copia buena", unsynced.lastSuccessAt, state.lastSuccessAt)
        assertFalse(files.undoSource.database.exists())
        // La copia de seguridad nueva es la de la base que se deshizo.
        val kept = SafetyCopyStore(files, ops, Dispatchers.IO).copies().single()
        assertFalse(checkNotNull(kept.facts).hasUnsyncedChanges)
        assertEquals(BackupRun.Verified(2), fixture.runner(destination).run(control))
    }

    @Test
    fun aPickedFileOnlyCountsAsBackedUpWhenItIsInTheDestination() = runBlocking<Unit> {
        fixture.addProduct("Pan de prueba")
        assertEquals(BackupRun.Verified(1), fixture.runner(destination).run(control))
        val bytes = checkNotNull(destination.file(listed(1).ref.value))

        // El mismo archivo que está en el destino: no queda nada sin respaldar.
        assertEquals(RestoreStage.ReadyToRestart(0), coordinator().start(picked(bytes)))
        reopen()
        fixture.state().let { assertEquals(it.changeSeq, it.verifiedSeq) }

        // La misma base, con otro archivo: no está en el destino, y se fuerza una subida.
        fixture.addProduct("Leche de prueba")
        assertEquals(RestoreStage.ReadyToRestart(0), coordinator().start(picked(rewrapped(bytes))))
        reopen()

        val state = fixture.state()
        assertEquals(1L, count("products"))
        assertTrue(state.changeSeq > state.verifiedSeq)
        assertEquals(fixture.now.toEpochMilliseconds(), state.firstUnsyncedAt)
        assertEquals("La cabeza sigue siendo la del destino", 1L, state.headGeneration)
    }

    @Test
    fun noWriteEntersWhileThePreparationWaitsAndCancellingLeavesEverythingAsItWas() =
        runBlocking<Unit> {
            fixture.addProduct("Pan de prueba")
            val coordinator = coordinator()

            assertEquals(RestoreStage.ConfirmEmptyCopy, coordinator.start(picked(emptyCopy())))

            assertTrue(files.lock.exists())
            val ports = fixture.ports
            assertEquals(
                StorageFailure.RESTORE_IN_PROGRESS,
                storageFailureOf {
                    ports.transactions.write<Long> {
                        TxResult.Commit(ports.products.insert(newProduct("Venta de prueba")))
                    }
                },
            )
            assertEquals(
                StorageFailure.RESTORE_IN_PROGRESS,
                storageFailureOf {
                    ports.settings.updatePreference(
                        PreferenceChange.Theme(ThemeMode.DARK),
                        fixture.now,
                    )
                },
            )
            assertEquals(
                StorageFailure.RESTORE_IN_PROGRESS,
                storageFailureOf { ports.settings.markOnboardingCompleted(fixture.now) },
            )
            assertEquals(
                StorageFailure.RESTORE_IN_PROGRESS,
                storageFailureOf {
                    ports.lowStockAlerts.setNotificationStatus(1, LowStockNotificationStatus.SHOWN)
                },
            )
            assertTrue("El respaldo está en pausa", backupMutex.isLocked)

            assertTrue(coordinator.cancel())

            assertNothingLeft()
            assertFalse(backupMutex.isLocked)
            assertEquals(1L, count("products"))
            fixture.addProduct("Leche de prueba")
            assertEquals(2L, count("products"))
        }

    @Test
    fun anEmptyCopyReplacesTheDataOnlyWithAnExpressDecision() = runBlocking<Unit> {
        fixture.addProduct("Pan de prueba")
        val coordinator = coordinator()
        assertEquals(RestoreStage.ConfirmEmptyCopy, coordinator.start(picked(emptyCopy())))

        assertEquals(
            RestoreStage.ReadyToRestart(0),
            coordinator.decide(RestoreDecision.ReplaceWithEmptyCopy),
        )
        reopen()

        assertEquals(0L, count("products"))
    }

    @Test
    fun aRejectedFileLeavesTheDatabaseAndNothingElseBehind() = runBlocking<Unit> {
        fixture.addProduct("Pan de prueba")

        val stage = coordinator().start(picked("no es una copia".toByteArray()))

        assertEquals(
            RestoreStage.Refused(RestoreRefusal.Candidate(CandidateRejection.UNKNOWN_FORMAT)),
            stage,
        )
        assertNothingLeft()
        assertFalse(backupMutex.isLocked)
        fixture.addProduct("Leche de prueba")
        assertEquals(2L, count("products"))
    }

    @Test
    fun aPendingJournalRefusesANewPreparationWithoutTouchingIt() = runBlocking<Unit> {
        files.restoreDirectory.mkdirs()
        files.journal.writeText("diario de otra restauración")

        assertEquals(RestoreStage.Busy, coordinator().start(picked(emptyCopy())))

        assertEquals("diario de otra restauración", files.journal.readText())
        assertFalse(files.lock.exists())
        assertFalse(backupMutex.isLocked)
        files.journal.delete()
    }

    @Test
    fun withoutSpaceItSaysHowMuchIsMissingAndDownloadsNothing() = runBlocking<Unit> {
        fixture.addProduct("Pan de prueba")
        assertEquals(BackupRun.Verified(1), fixture.runner(destination).run(control))

        val stage = coordinator(freeSpace = { 0L }).start(RestoreSource.Listed(listed(1)))

        assertTrue("$stage", stage is RestoreStage.NotEnoughSpace)
        assertTrue((stage as RestoreStage.NotEnoughSpace).missingBytes > 50L * 1024 * 1024)
        assertFalse("No bajó nada", FakeBackupDestination.Operation.DOWNLOAD in destination.calls)
        assertNothingLeft()
    }

    @Test
    fun aDamagedActiveDatabaseIsKeptAsideWhenThereIsNoSafetyCopy() = runBlocking<Unit> {
        fixture.addProduct("Pan de prueba")
        assertEquals(BackupRun.Verified(1), fixture.runner(destination).run(control))
        val damaged = File(fixture.noBackup, "damaged-for-test.db").apply {
            parentFile?.mkdirs()
            writeBytes(ByteArray(8192) { 7 })
        }
        val coordinator = coordinatorWithDamagedActive(damaged)

        assertEquals(
            RestoreStage.ConfirmWithoutSafetyCopy,
            coordinator.start(RestoreSource.Listed(listed(1))),
        )
        assertFalse(files.journal.exists())
        assertEquals(
            RestoreStage.ReadyToRestart(0),
            coordinator.decide(RestoreDecision.ContinueWithoutSafetyCopy),
        )
        reopen()

        val first = damagedOriginals().single()
        assertEquals(1L, productsIn(first))
        assertFalse(files.previous.exists())

        // Una segunda restauración sin copia de seguridad, dentro de los 30 días, no pisa la
        // primera base apartada: queda cada una en su carpeta.
        fixture.addProduct("Leche de prueba")
        fixture.addProduct("Queso de prueba")
        fixture.advance(1.minutes)
        val again = coordinatorWithDamagedActive(damaged)
        assertEquals(
            RestoreStage.ConfirmWithoutSafetyCopy,
            again.start(RestoreSource.Listed(listed(1))),
        )
        assertEquals(
            RestoreStage.ReadyToRestart(0),
            again.decide(RestoreDecision.ContinueWithoutSafetyCopy),
        )
        reopen()

        val folders = damagedOriginals()
        assertEquals(2, folders.size)
        assertEquals(first, folders.first())
        assertEquals(1L, productsIn(folders.first()))
        assertEquals(3L, productsIn(folders.last()))
    }

    @Test
    fun aDatabaseWaitingInPreviousAtTheStartIsKeptAsideWithTheUnreadableJournal() =
        runBlocking<Unit> {
            fixture.addProduct("Pan de prueba")
            assertEquals(BackupRun.Verified(1), fixture.runner(destination).run(control))
            // Lo que deja un diario ilegible: la base original en previous/, y el diario apartado.
            files.previousDirectory.mkdirs()
            files.previous.writeText("base original de prueba")
            RestoreFiles.sideFile(files.previous, RestoreFiles.WAL_SUFFIX).writeText("su -wal")
            files.journalUnreadable.writeText("{\"state\":")

            assertEquals(
                RestoreStage.ReadyToRestart(0),
                coordinator().start(RestoreSource.Listed(listed(1))),
            )

            val aside = damagedOriginals().single()
            assertEquals("base original de prueba", File(aside, "shopledger.db").readText())
            assertEquals("su -wal", File(aside, "shopledger.db-wal").readText())
            assertEquals("{\"state\":", File(aside, "journal.bad").readText())
            assertTrue("El diario ilegible sigue hasta terminar", files.journalUnreadable.exists())
            reopen()

            assertFalse("Una restauración terminada lo retira", files.journalUnreadable.exists())
            assertTrue(File(aside, "shopledger.db").exists())
            assertEquals(1L, count("products"))
        }

    @Test
    fun withTheDatabaseUnreadableTheConnectionComesFromTheLink() = runBlocking<Unit> {
        fixture.addProduct("Pan de prueba")
        assertEquals(BackupRun.Verified(1), fixture.runner(destination).run(control))
        fixture.sql(
            "UPDATE backup_state SET destination_kind = NULL, destination_id = NULL, " +
                "destination_folder = NULL, link_state = 'NOT_CONNECTED' WHERE id = 1",
        )
        val link =
            DestinationColumns(
                kind = "WEBDAV",
                account = "persona@example.com",
                id = TEST_DESTINATION_ID,
                folder = "https://dav.example.com/app/",
            )

        val stage =
            coordinator(active = { null }, linkColumns = { link })
                .start(RestoreSource.Listed(listed(1)))

        assertEquals(RestoreStage.ReadyToRestart(0), stage)
        reopen()
        val state = fixture.state()
        assertEquals(BackupLinkState.CONNECTED.name, state.linkState)
        assertEquals("WEBDAV", state.destinationKind)
        assertEquals("persona@example.com", state.destinationAccount)
        assertEquals(TEST_DESTINATION_ID, state.destinationId)
        assertEquals("https://dav.example.com/app/", state.destinationFolder)
        assertEquals(state.changeSeq, state.verifiedSeq)
    }

    /** La preparación con una base activa que no se deja copiar, como una dañada. */
    private fun coordinatorWithDamagedActive(damaged: File) = coordinator(
        safetyCopies =
            SafetyCopyStore(files, ops, Dispatchers.IO, SnapshotCreator(damaged, Dispatchers.IO)),
    )

    private fun damagedOriginals(): List<File> =
        DamagedOriginals(files, ops, fixture.clock).folders()

    private fun productsIn(folder: File): Long = inspectWithoutRoom(File(folder, "shopledger.db")) {
        it.longValue("SELECT COUNT(*) FROM products")
    }

    @Test
    fun aLockThatGetsCloseToExpiringStopsBeforeTheJournal() = runBlocking<Unit> {
        fixture.addProduct("Pan de prueba")
        val coordinator = coordinator()
        assertEquals(RestoreStage.ConfirmEmptyCopy, coordinator.start(picked(emptyCopy())))

        fixture.advance(RestoreLockFile.LIFETIME - RestoreLockFile.OWNER_MARGIN)

        assertEquals(
            RestoreStage.Refused(RestoreRefusal.LockLost),
            coordinator.decide(RestoreDecision.ReplaceWithEmptyCopy),
        )
        assertFalse(files.journal.exists())
        assertNothingLeft()
        assertTrue(
            "Sin cerrojo no se hace la copia de seguridad",
            SafetyCopyStore(files, ops, Dispatchers.IO).copies().isEmpty(),
        )
        assertEquals(1L, count("products"))
    }

    @Test
    fun anOlderSchemaMigratesOnTheCandidateAndAFailedMigrationChangesNothing() = runBlocking<Unit> {
        fixture.addProduct("Pan de prueba")
        assertEquals(BackupRun.Verified(1), fixture.runner(destination).run(control))
        val migrated = mutableListOf<File>()

        val failing =
            coordinator(appSchemaVersion = 2, migrator = { error("migración rota de prueba") })
        assertEquals(
            RestoreStage.Refused(RestoreRefusal.MigrationFailed),
            failing.start(RestoreSource.Listed(listed(1))),
        )
        assertNothingLeft()

        val working = coordinator(appSchemaVersion = 2, migrator = { migrated += it })
        assertEquals(
            RestoreStage.ReadyToRestart(0),
            working.start(RestoreSource.Listed(listed(1))),
        )
        assertEquals(listOf(files.candidate), migrated)
    }

    @Test
    fun withoutAScreenLockTheAppLockIsTurnedOff() = runBlocking<Unit> {
        fixture.addProduct("Pan de prueba")
        fixture.sql("UPDATE app_settings SET app_lock_enabled = 1 WHERE id = 1")
        assertEquals(BackupRun.Verified(1), fixture.runner(destination).run(control))

        assertEquals(
            RestoreStage.ReadyToRestart(0),
            coordinator(deviceSecure = false).start(RestoreSource.Listed(listed(1))),
        )
        reopen()

        assertEquals(listOf("0"), texts("SELECT app_lock_enabled FROM app_settings"))
    }

    @Test
    fun aCodePromotedByTheRestoreIsWrittenToTheAudit() = runBlocking<Unit> {
        fixture.addProduct("Pan de prueba")
        assertEquals(BackupRun.Verified(1), fixture.runner(destination).run(control))
        assertEquals(
            RestoreStage.ReadyToRestart(0),
            coordinator().start(RestoreSource.Listed(listed(1))),
        )
        // Lo que dejaría una copia cifrada: el código sellado que pasa a vigente al reemplazar.
        files.recoveryDirectory.mkdirs()
        files.pendingKey.writeBytes(byteArrayOf(1, 2, 3))

        reopen()

        assertTrue(files.sealedKey.exists())
        assertFalse(files.pendingKey.exists())
        assertEquals(
            listOf("ENCRYPTION_CHANGED SUCCESS"),
            texts("SELECT event_type || ' ' || result FROM audit_events"),
        )
        // Se anota una sola vez.
        reopen()
        assertEquals(1L, count("audit_events"))
    }

    @Test
    fun aDeathAfterEachOperationOfThePreparationLeavesTheOldOrTheNewDatabase() = runBlocking<Unit> {
        var death = 1
        while (true) {
            if (death > 1) {
                // Cada caso empieza de cero, con un proceso nuevo.
                fixture.ports.closeForRestore()
                fixture.close()
                reopen()
                fixture.connect()
            }
            fixture.addProduct("Pan de prueba")
            assertEquals(BackupRun.Verified(1), fixture.runner(destination).run(control))
            fixture.addProduct("Leche de prueba")
            var operations = 0
            backupMutex = Mutex()
            ops = DurableFileOps(listener = { if (++operations == death) throw ProcessDeath() })
            val copy = listed(1)

            val died =
                try {
                    coordinator().start(RestoreSource.Listed(copy))
                    false
                } catch (_: ProcessDeath) {
                    true
                }
            // El proceso nuevo no hereda nada: la base vieja se cierra como al morir.
            if (died) runCatching { fixture.ports.closeForRestore() }
            val journaled = files.journal.exists()
            ops = DurableFileOps()
            reopen()

            val expected = if (journaled || !died) 1L else 2L
            assertEquals("Muerte tras la operación $death", expected, count("products"))
            assertFalse(files.lock.exists())
            assertFalse(files.journal.exists())
            assertFalse(files.incoming.exists())
            assertFalse(files.candidate.exists())
            assertFalse(files.safetyCopyTemporary.exists())
            destination.removeFile(copy.ref.value)
            destination.removeFile(copy.ref.value + ".slmeta")
            if (!died) break
            death++
        }
        assertTrue("La preparación hizo operaciones en disco: $death", death > 3)
    }

    /** La muerte del proceso: nada la atrapa, igual que en un teléfono. */
    private class ProcessDeath : Error()

    private fun coordinator(
        target: BackupDestination = destination,
        freeSpace: (File) -> Long = File::getUsableSpace,
        safetyCopies: SafetyCopyStore = SafetyCopyStore(files, ops, Dispatchers.IO),
        migrator: CandidateMigrator = CandidateMigrator { error("Esta prueba no migra") },
        appSchemaVersion: Int = AppDatabase.SCHEMA_VERSION,
        deviceSecure: Boolean = true,
        active: suspend () -> ActiveDatabase? = { activeDatabaseOf(fixture.ports) },
        linkColumns: suspend () -> DestinationColumns? = { null },
    ): RestoreCoordinator {
        val validator = CandidateValidator(Dispatchers.IO, appSchemaVersion)
        return RestoreCoordinator(
            files = files,
            ops = ops,
            lock = RestoreLockFile(files, ops, fixture.clock, Dispatchers.IO),
            pause = MutexBackupPause(backupMutex),
            active = active,
            linkColumns = linkColumns,
            destinations = { DestinationOpen.Opened(target) },
            unpacker = CandidateUnpacker(Dispatchers.IO),
            validator = validator,
            repair = CandidateRepair(Dispatchers.IO),
            migrator = migrator,
            preparation = CandidatePreparation(Dispatchers.IO),
            safetyCopies = safetyCopies,
            journalStore = RestoreJournalStore(files, ops),
            closeDatabase = { fixture.ports.closeForRestore() },
            deviceSecure = { deviceSecure },
            clock = fixture.clock,
            log = fixture.log,
            ioContext = Dispatchers.IO,
            freeSpace = freeSpace,
            appSchemaVersion = appSchemaVersion,
        )
    }

    /** La copia [generation] de este teléfono, tal como la lista el destino. */
    private suspend fun listed(generation: Long): ListedCopy {
        val listing = destination.listCopies(control) as DestinationResult.Success
        return listing.value.copies.single { it.name?.generation == generation }
    }

    /** Un archivo elegido por la persona, con estos bytes. */
    private fun picked(bytes: ByteArray) =
        RestoreSource.PickedFile(bytes.size.toLong()) { bytes.inputStream() }

    /** La misma base en otro archivo gzip, de dos miembros: otra suma, el mismo contenido. */
    private fun rewrapped(bytes: ByteArray): ByteArray {
        val raw = GZIPInputStream(bytes.inputStream()).use { it.readBytes() }
        val output = ByteArrayOutputStream()
        val half = raw.size / 2
        GZIPOutputStream(output).use { it.write(raw, 0, half) }
        GZIPOutputStream(output).use { it.write(raw, half, raw.size - half) }
        return output.toByteArray()
    }

    /** Una base recién creada por la app, sin datos del negocio, comprimida como una copia. */
    private fun emptyCopy(): ByteArray {
        val raw = CandidateFixtures.emptyCopy("restore-empty").readBytes()
        val output = ByteArrayOutputStream()
        GZIPOutputStream(output).use { it.write(raw) }
        return output.toByteArray()
    }

    /** Reinicia como un proceso nuevo: el arranque hace el reemplazo. */
    private suspend fun reopen() {
        // El proceso nuevo trae su propio cerrojo del respaldo: la pausa del anterior murió con él.
        backupMutex = Mutex()
        val access = fixture.reopen()
        assertTrue("$access", access is DataAccess.Ready)
    }

    private fun assertNothingLeft() {
        for (file in listOf(
            files.lock,
            files.lockTemporary,
            files.incoming,
            files.candidate,
            files.journal,
        )) {
            assertFalse("Quedó ${file.name}", file.exists())
        }
        assertTrue(
            files.candidateDirectory.list().orEmpty().isEmpty(),
        )
    }

    private fun count(table: String): Long =
        inspectWithoutRoom(fixture.file) { it.longValue("SELECT COUNT(*) FROM $table") }

    private fun texts(sql: String): List<String> = inspectWithoutRoom(fixture.file) {
        it.texts(sql)
    }
}
