package io.github.yesidrobayo.shopledger.data.work

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.Configuration
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.TestDriver
import androidx.work.testing.WorkManagerTestInitHelper
import io.github.yesidrobayo.shopledger.data.backup.BackupEngine
import io.github.yesidrobayo.shopledger.data.backup.BackupFixture
import io.github.yesidrobayo.shopledger.data.backup.BackupNowRequest
import io.github.yesidrobayo.shopledger.data.backup.BackupScheduler
import io.github.yesidrobayo.shopledger.data.backup.PolicyUploadGate
import io.github.yesidrobayo.shopledger.data.backup.ScheduleFacts
import io.github.yesidrobayo.shopledger.data.destination.TransferControl
import io.github.yesidrobayo.shopledger.data.destination.slots.ContentResolverDocumentAccess
import io.github.yesidrobayo.shopledger.data.destination.slots.DocumentSlotDestination
import io.github.yesidrobayo.shopledger.data.destination.slots.FileSlotStateStore
import io.github.yesidrobayo.shopledger.data.destination.slots.SlotEntry
import io.github.yesidrobayo.shopledger.data.destination.slots.SlotState
import io.github.yesidrobayo.shopledger.data.destination.slots.SlotStateLoad
import io.github.yesidrobayo.shopledger.data.destination.slots.SlotTestProvider
import io.github.yesidrobayo.shopledger.data.destination.slots.WriteMode
import io.github.yesidrobayo.shopledger.domain.model.backup.AppOpenUpload
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupDestinationKind
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupSlot
import io.github.yesidrobayo.shopledger.domain.model.settings.MeteredBackupPolicy
import java.io.File
import java.util.UUID
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * La copia con la nube del teléfono en modo avión de verdad, con la radio apagada y ninguna red
 * activa: la app no pide red, porque la decide la app de esa nube. Y nada sale solo: solo
 * `Guardar en {provider}`, con la app a la vista.
 *
 * El programador real encola en la biblioteca de trabajos, el trabajo de subida corre con la
 * lectura de red de la app y el motor escribe en las tres ranuras de un proveedor de documentos
 * local del APK de pruebas. La política es solo con Wi-Fi, que con la nube del teléfono no cuenta.
 * Ninguna prueba toca una nube real.
 */
@RunWith(AndroidJUnit4::class)
class SlotUploadAirplaneModeTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val authority = "${context.packageName}.slottest"
    private val cache = context.cacheDir
    private val stateDir = File(cache, "slot-airplane-state")
    private val store = FileSlotStateStore(stateDir, Dispatchers.IO)
    private val fixture = BackupFixture(context, "slot-airplane-test")
    private val airplane = AirplaneMode(context)
    private lateinit var manager: WorkManager
    private lateinit var driver: TestDriver
    private lateinit var scheduler: BackupScheduler

    /** Si la app está a la vista: lo que la compuerta mira antes de escribir en una ranura. */
    @Volatile private var appVisible = true

    @Before
    fun prepare() = runBlocking {
        SlotTestProvider.reset(cache)
        stateDir.deleteRecursively()
        fixture.open()
        fixture.connect(BackupDestinationKind.DOCUMENT_SLOTS)
        fixture.sql("UPDATE app_settings SET metered_backup_policy = 'WIFI_ONLY' WHERE id = 1")
        for (slot in BackupSlot.entries) SlotTestProvider.create(cache, slot.id)
        store.save(
            SlotState(
                slots = BackupSlot.entries.associateWith {
                    SlotEntry(SlotTestProvider.uriOf(authority, it.id), "Nube", null, null, null)
                },
                firstHandoffAt = null,
                writeMode = WriteMode.WT,
                sessionRequired = false,
            ),
        )
        val destination =
            DocumentSlotDestination(
                store,
                ContentResolverDocumentAccess(context.contentResolver) { true },
                fixture.clock,
                Dispatchers.IO,
            )
        val policy: suspend () -> MeteredBackupPolicy = {
            fixture.ports.settings.get().preferences.meteredBackupPolicy
        }
        val runner =
            fixture.runner(
                destination,
                uploadGate = PolicyUploadGate(policy, fixture.clock) { appVisible },
            )
        scheduler =
            BackupScheduler(
                facts = { ScheduleFacts.of(fixture.ports.backupState.get(), policy()) },
                restorePending = { false },
                policyAccepted = { true },
                queue = WorkManagerBackupQueue(context),
                clock = fixture.clock,
            )
        val engine =
            object : BackupEngine {
                override suspend fun run(control: TransferControl) = runner.run(control)

                override suspend fun recordUnexpected() = Unit
            }
        // La fábrica de la app: el trabajo de subida lee la red como la app.
        val configuration =
            Configuration
                .Builder()
                .setMinimumLoggingLevel(Log.ERROR)
                .setExecutor(SynchronousExecutor())
                .setWorkerFactory(DataWorkerFactory(engine, fixture.log, scheduler))
                .build()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, configuration)
        manager = WorkManager.getInstance(context)
        driver = requireNotNull(WorkManagerTestInitHelper.getTestDriver(context))
        airplane.turnOn()
    }

    @After
    fun clean() {
        airplane.restore()
        if (::manager.isInitialized) {
            manager.cancelAllWork().result.get()
            manager.pruneWork().result.get()
        }
        fixture.close()
        SlotTestProvider.reset(cache)
        stateDir.deleteRecursively()
    }

    @Test
    fun inAirplaneModeNothingLeavesOnItsOwnAndSaveToProviderWritesAtOnce() = runBlocking {
        assertTrue(airplane.offline)
        // Sin red, la app la ve como medida: lo prudente con WebDAV, y sin efecto con ranuras.
        assertTrue(isMetered(context))

        // Una venta, y la app pasa a segundo plano: no queda ningún trabajo de subida.
        fixture.addProduct("Pan de prueba")
        appVisible = false
        scheduler.onDataChanged()
        scheduler.onAppBackgrounded()
        assertTrue(unfinished(BackupWorkNames.UPLOAD).isEmpty())
        assertTrue(unfinished(BackupWorkNames.DEBOUNCE).isEmpty())
        assertEquals(emptyList<Long>(), generations())

        // La app vuelve: abrirla tampoco escribe nada.
        appVisible = true
        scheduler.onAppOpened(AppOpenUpload.PAST_INTERVAL)
        assertTrue(unfinished(BackupWorkNames.UPLOAD).isEmpty())
        assertEquals(emptyList<Long>(), generations())

        // `Guardar en {provider}` escribe ya, sin pedir red.
        assertEquals(BackupNowRequest.QUEUED, scheduler.backupNow())
        val first = finishedAfter(null)
        assertEquals(WorkInfo.State.SUCCEEDED, first.state)
        assertEquals(NetworkType.NOT_REQUIRED, first.constraints.requiredNetworkType)
        assertEquals(listOf(1L), generations())
        val handedOff = fixture.state()
        assertEquals(handedOff.changeSeq, handedOff.verifiedSeq)

        // Un minuto después, otra venta y otra vez el botón: escribe sin esperar ninguna hora.
        fixture.advance(1.minutes)
        fixture.addProduct("Leche de prueba")
        assertEquals(BackupNowRequest.QUEUED, scheduler.backupNow())
        val second = finishedAfter(first.id)
        assertEquals(WorkInfo.State.SUCCEEDED, second.state)
        assertEquals(listOf(1L, 2L), generations())

        // La subida que espera una red sin datos móviles nunca existió, y la red nunca volvió.
        assertTrue(
            manager.getWorkInfosForUniqueWork(BackupWorkNames.UPLOAD_UNMETERED).get().isEmpty(),
        )
        assertTrue(airplane.offline)
    }

    /** Los trabajos [name] que todavía esperan o corren. */
    private fun unfinished(name: String): List<WorkInfo> =
        manager.getWorkInfosForUniqueWork(name).get().filterNot { it.state.isFinished }

    /**
     * La subida, ya terminada, que vino después de [previous]: al encolar una nueva, la biblioteca
     * borra la anterior terminada.
     */
    private suspend fun finishedAfter(previous: UUID?): WorkInfo = withTimeout(WAIT) {
        manager
            .getWorkInfosForUniqueWorkFlow(BackupWorkNames.UPLOAD)
            .first { infos -> infos.any { it.id != previous && it.state.isFinished } }
            .single { it.id != previous }
    }

    /** Las generaciones que guardan las ranuras, en orden. */
    private suspend fun generations(): List<Long> {
        val state = (store.load() as SlotStateLoad.Loaded).state
        return BackupSlot.entries.mapNotNull { state[it].copy?.generation }.sorted()
    }

    private val BackupSlot.id: String get() = name.lowercase()

    private companion object {
        val WAIT = 60.seconds
    }
}
