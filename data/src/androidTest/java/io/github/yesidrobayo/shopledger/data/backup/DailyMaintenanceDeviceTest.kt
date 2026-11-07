package io.github.yesidrobayo.shopledger.data.backup

import androidx.sqlite.execSQL
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yesidrobayo.shopledger.data.BackupNotification
import io.github.yesidrobayo.shopledger.data.BackupNotifier
import io.github.yesidrobayo.shopledger.data.local.DatabaseFiles
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupFailureCode
import java.io.File
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
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
 * El mantenimiento diario sobre la base real, sin red: la verificación de la base activa cuenta los
 * productos descuadrados y deja el fallo de invariantes sin corregir nada, la notificación de un
 * día sin copia sale una vez y queda anotada, y la limpieza borra a los 30 días las copias locales
 * de una restauración, nunca antes ni con el reloj atrasado. Todos los datos son inventados.
 */
@RunWith(AndroidJUnit4::class)
class DailyMaintenanceDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val fixture = BackupFixture(context, "daily-maintenance-test")
    private val files = RestoreFiles(DatabaseFiles(fixture.file, fixture.noBackup))
    private val shown = mutableListOf<BackupNotification>()
    private var reconciled = 0

    private val maintenance =
        DailyMaintenance(
            clock = fixture.clock,
            log = fixture.log,
            mutex = Mutex(),
            restorePending = { RestoreMarkers(fixture.noBackup).present() },
            open = {
                MaintenanceAccess.Ready(RoomMaintenanceDatabase(fixture.ports, Dispatchers.IO))
            },
            reconcile = { reconciled++ },
            notifier = {
                BackupNotifier {
                    shown += it
                    true
                }
            },
            cleanUp = { now ->
                cleanUpExpiredLocalCopies(files, fixture.clock, fixture.log, Dispatchers.IO, now)
            },
        )

    @Before
    fun open() = runBlocking {
        fixture.open()
        fixture.addProduct("Pan de prueba")
        fixture.addProduct("Leche de prueba")
        fixture.addProduct("Queso de prueba")
    }

    @After
    fun close() {
        fixture.close()
    }

    @Test
    fun aHealthyBaseLeavesNoFailureAndTheCheckWritesNothing() = runBlocking {
        val before = fixture.state()

        assertEquals(
            ActiveInvariants.Checked(repairCases = 0),
            ActiveInvariantCheck(fixture.file, Dispatchers.IO).check(),
        )
        assertEquals(MaintenanceRun.DONE, maintenance.run())

        val after = fixture.state()
        assertNull(after.failureCode)
        assertEquals(0L, after.invariantFailures)
        // Verificar no es un cambio del negocio: no pide una copia.
        assertEquals(before.changeSeq, after.changeSeq)
        assertEquals(1, reconciled)
    }

    @Test
    fun twoDriftedProductsLeaveTheCountAndTheFailureWithoutRepairingAnything() = runBlocking {
        driftStock(products = 2, by = 1_000)

        maintenance.run()

        val state = fixture.state()
        assertEquals(BackupFailureCode.LOCAL_INVARIANT.name, state.failureCode)
        // Cada uno falla a la vez en su stock y en su saldo de cabecera: cuenta una vez.
        assertEquals(2L, state.invariantFailures)
        // La app no corrige sola: los saldos siguen como estaban.
        assertEquals(2L, driftedProducts())
        assertTrue(fixture.log.lines.any { "casos=2" in it })
    }

    @Test
    fun anotherFailureStaysInSightAndTheCountIsKeptForTheNotice() = runBlocking {
        fixture.sql("UPDATE backup_state SET failure_code = 'QUOTA_EXCEEDED' WHERE id = 1")
        driftStock(products = 1, by = 1_000)

        maintenance.run()

        val state = fixture.state()
        assertEquals(BackupFailureCode.QUOTA_EXCEEDED.name, state.failureCode)
        assertEquals(1L, state.invariantFailures)
    }

    @Test
    fun aDayWithoutACopyNotifiesOnceAndNeverWithoutADestination() = runBlocking {
        fixture.advance(25.hours)
        maintenance.run()
        assertEquals(emptyList<BackupNotification>(), shown)

        fixture.connect()
        // Sin aceptar la política las copias están en pausa: ese estado no es un día sin copia.
        maintenance.run()
        assertEquals(emptyList<BackupNotification>(), shown)
        fixture.policyConsent.acceptCurrent()
        maintenance.run()
        fixture.advance(20.hours)
        maintenance.run()

        assertEquals(listOf(BackupNotification.STALE), shown)
        assertEquals(
            fixture.now.toEpochMilliseconds() - 20.hours.inWholeMilliseconds,
            fixture.state().lastStaleNoticeAt,
        )
        fixture.advance(4.hours)
        maintenance.run()
        assertEquals(listOf(BackupNotification.STALE, BackupNotification.STALE), shown)
    }

    @Test
    fun theCleanUpDeletesLocalCopiesAtThirtyDaysAndNeverWithTheClockBehind() = runBlocking {
        val start = fixture.now
        SafetyCopyStore(files, DurableFileOps(), Dispatchers.IO)
            .save(changeSeq = 3, verifiedSeq = 3, now = start)
        val aside = File(files.damagedDirectory, start.toEpochMilliseconds().toString())
        File(aside, "shopledger.db").apply {
            requireNotNull(parentFile).mkdirs()
            writeText("base apartada de prueba")
        }

        fixture.now = start - 5.days
        maintenance.run()
        fixture.now = start + 29.days
        maintenance.run()
        assertTrue(files.safetyCopies[0].database.exists())
        assertTrue(aside.exists())

        fixture.now = start + 30.days
        maintenance.run()
        assertFalse(files.safetyCopies[0].database.exists())
        assertFalse(files.safetyCopies[0].facts.exists())
        assertFalse(aside.exists())
    }

    @Test
    fun withARestoreInProgressNothingIsCheckedCleanedOrNotified() = runBlocking {
        driftStock(products = 1, by = 1_000)
        fixture.connect()
        fixture.advance(25.hours)
        files.restoreDirectory.mkdirs()
        files.lock.writeText(fixture.now.toEpochMilliseconds().toString())

        assertEquals(MaintenanceRun.RETRY, maintenance.run())

        assertNull(fixture.state().failureCode)
        assertEquals(emptyList<BackupNotification>(), shown)
        assertEquals(0, reconciled)
    }

    /** Descuadra el stock de los primeros [products] productos, sin pasar por sus guardas. */
    private fun driftStock(products: Int, by: Long) = corrupt(fixture.file) {
        execSQL("PRAGMA busy_timeout = 5000")
        withoutGuards("products") {
            execSQL(
                "UPDATE products SET on_hand_milli = on_hand_milli + $by WHERE id IN " +
                    "(SELECT id FROM products ORDER BY id LIMIT $products)",
            )
        }
    }

    /** Cuántos productos tienen hoy un stock distinto de la suma de su libro. */
    private fun driftedProducts(): Long {
        var drifted = 0L
        corrupt(fixture.file) {
            prepare(InvariantQueries.MODEL.first { it.label == "I-01" }.sql).use {
                while (it.step()) drifted++
            }
        }
        return drifted
    }
}
