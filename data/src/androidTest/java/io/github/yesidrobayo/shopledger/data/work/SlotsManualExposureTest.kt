package io.github.yesidrobayo.shopledger.data.work

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import io.github.yesidrobayo.shopledger.data.backup.BackupEngine
import io.github.yesidrobayo.shopledger.data.backup.BackupFixture
import io.github.yesidrobayo.shopledger.data.backup.BackupNowRequest
import io.github.yesidrobayo.shopledger.data.backup.BackupScheduler
import io.github.yesidrobayo.shopledger.data.backup.BackupStatusEnvironment
import io.github.yesidrobayo.shopledger.data.backup.BackupStatusReader
import io.github.yesidrobayo.shopledger.data.backup.PolicyUploadGate
import io.github.yesidrobayo.shopledger.data.backup.RunKind
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
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupDestinationKind
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupNetwork
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupSlot
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupStatus
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupUploadWork
import io.github.yesidrobayo.shopledger.domain.model.settings.MeteredBackupPolicy
import io.github.yesidrobayo.shopledger.domain.rule.backup.AppOpenUploadRule
import io.github.yesidrobayo.shopledger.domain.rule.backup.BackupStatusResolver
import java.io.File
import java.security.MessageDigest
import java.util.Random
import java.util.UUID
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * La medición comprimida de la nube del teléfono sin copias automáticas: simula al menos 50
 * cierres de la app con cambios pendientes, con el programador, la biblioteca de trabajos, la
 * compuerta y el motor reales, sobre las tres ranuras de un proveedor de documentos local del
 * APK de pruebas. Ninguna prueba toca una nube real. El reloj es el de la prueba: un día
 * simulado dura segundos.
 *
 * Cada día simulado tiene [SESSIONS_PER_DAY] sesiones. En cada una la app se abre, la persona
 * toca `Guardar en {provider}` si Inicio lo ofrece, registra de una a tres ventas inventadas y
 * cierra la app. Entre sesiones pasan de 30 a 150 minutos, y la primera de cada día empieza 24
 * horas después de la primera del anterior.
 *
 * **Con la app cerrada, nada escribe en la nube del teléfono.** Al cerrar, la prueba exige que
 * no quede ningún trabajo del destino, y fuerza lo peor: una subida automática que quedó de una
 * versión anterior y una copia pedida que el sistema corre tarde. Abrir la app tampoco escribe
 * nada.
 *
 * Da las tres medidas en una línea `clave=valor` con la etiqueta [TAG], que lee el guion del PC:
 *
 * 1. Los cierres cuyos cambios quedaron en una copia entregada antes de 24 horas: al menos el
 *    95 %.
 * 2. La mayor exposición, desde el cambio más viejo sin copia hasta su entrega: 1.440 minutos o
 *    menos.
 * 3. Las copias entregadas que no siguen enteras en su ranura al abrir otra vez, o a las 24
 *    horas de la última: cero.
 *
 * Supone que la persona abre la app cada día con ventas y toca el botón cuando Inicio lo
 * ofrece: mide que el camino entero no pierde ni escribe nada de más, no cómo usa la app una
 * persona real. Con `-e closings N` se cambia la cantidad de cierres, nunca por debajo de 50.
 */
@RunWith(AndroidJUnit4::class)
class SlotsManualExposureTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val authority = "${context.packageName}.slottest"
    private val cache = context.cacheDir
    private val stateDir = File(cache, "slot-exposure-state")
    private val store = FileSlotStateStore(stateDir, Dispatchers.IO)
    private val fixture = BackupFixture(context, "slot-exposure-test")
    private val queue = WorkManagerBackupQueue(context)
    private lateinit var manager: WorkManager
    private lateinit var scheduler: BackupScheduler

    /**
     * El estado que vería Inicio, con la política ya aceptada como en la app que usa la persona, y
     * la primera entrega leída de las ranuras de la prueba. Los puertos del arnés no la aceptan.
     */
    private val status by lazy {
        BackupStatusReader(
            state = fixture.ports.backupState,
            meteredPolicy = { fixture.ports.settings.get().preferences.meteredBackupPolicy },
            environment =
                BackupStatusEnvironment(
                    restorePending = { false },
                    policyAccepted = { true },
                    uploadWork = { BackupUploadWork.NONE },
                    network = { BackupNetwork.NONE },
                    firstHandoffAt = {
                        (store.load() as? SlotStateLoad.Loaded)?.state?.firstHandoffAt
                    },
                ),
            ioContext = Dispatchers.IO,
        )
    }

    /** Si la app está a la vista: lo que la compuerta mira antes de escribir en una ranura. */
    @Volatile private var appVisible = true

    @Before
    fun prepare() = runBlocking {
        SlotTestProvider.reset(cache)
        stateDir.deleteRecursively()
        fixture.open()
        fixture.connect(BackupDestinationKind.DOCUMENT_SLOTS)
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
                queue = queue,
                clock = fixture.clock,
            )
        val engine =
            object : BackupEngine {
                override suspend fun run(control: TransferControl) = runner.run(control)

                override suspend fun recordUnexpected() = Unit
            }
        val configuration =
            Configuration
                .Builder()
                .setMinimumLoggingLevel(Log.ERROR)
                .setExecutor(SynchronousExecutor())
                .setWorkerFactory(DataWorkerFactory(engine, fixture.log, scheduler))
                .build()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, configuration)
        manager = WorkManager.getInstance(context)
    }

    @After
    fun clean() {
        if (::manager.isInitialized) {
            manager.cancelAllWork().result.get()
            manager.pruneWork().result.get()
        }
        fixture.close()
        SlotTestProvider.reset(cache)
        stateDir.deleteRecursively()
    }

    @Test
    fun fiftyClosingsWithChangesLoseNothingAndNothingWritesWithTheAppClosed() = runBlocking {
        val closings =
            maxOf(
                MIN_CLOSINGS,
                InstrumentationRegistry.getArguments().getString("closings")?.toIntOrNull() ?: 0,
            )
        val random = Random(SEED)
        val start = fixture.now
        val exposures = mutableListOf<Duration>()
        var closedWrites = 0
        var missing = 0
        for (closing in 0 until closings) {
            val day = closing / SESSIONS_PER_DAY
            if (closing % SESSIONS_PER_DAY == 0) {
                fixture.advance(start + DAY * day - fixture.now)
            }
            val opened = openTheApp()
            opened?.let {
                exposures += it.exposure
                missing += it.missing
                report("closing", "n" to closing - 1, "exposureMin" to it.exposure.inWholeMinutes)
            }
            val sessionStart = fixture.now
            repeat(1 + random.nextInt(MAX_SALES)) { sale ->
                fixture.addProduct("Producto de prueba $closing-$sale")
                scheduler.onDataChanged()
                fixture.advance(2.minutes)
            }
            fixture.advance(sessionStart + SESSION - fixture.now)
            closedWrites += closeTheApp()
            if (closing % SESSIONS_PER_DAY != SESSIONS_PER_DAY - 1) {
                fixture.advance((MIN_GAP_MIN + random.nextInt(GAP_SPREAD_MIN)).minutes)
            }
        }
        // La mañana siguiente: la apertura entrega lo del último cierre.
        fixture.advance(start + DAY * ((closings - 1) / SESSIONS_PER_DAY + 1) - fixture.now)
        val last = requireNotNull(openTheApp()) { "El último cierre no dejó cambios" }
        exposures += last.exposure
        missing += last.missing
        report("closing", "n" to closings - 1, "exposureMin" to last.exposure.inWholeMinutes)
        // A las 24 horas, la última copia sigue entera en su ranura.
        fixture.advance(DAY)
        missing += damagedSlots()

        val withinADay = exposures.count { it < DAY }
        val worst = exposures.maxOrNull() ?: Duration.ZERO
        val pass =
            closedWrites == 0 && withinADay * 100 >= closings * MIN_WITHIN_PERCENT &&
                worst <= DAY && missing == 0
        report(
            "summary",
            "closings" to closings,
            "delivered" to exposures.size,
            "withinADay" to withinADay,
            "withinADayPercent" to withinADay * 100 / closings,
            "maxExposureMin" to worst.inWholeMinutes,
            "missing" to missing,
            "closedWrites" to closedWrites,
            "verdict" to if (pass) "PASS" else "FAIL",
        )
        assertEquals(closings, exposures.size)
        assertEquals("Escrituras con la app cerrada", 0, closedWrites)
        assertTrue(
            "Menos del 95 % antes de 24 horas",
            withinADay * 100 >= closings * MIN_WITHIN_PERCENT,
        )
        assertTrue("Exposición de más de 1.440 minutos: $worst", worst <= DAY)
        assertEquals("Copias entregadas que no siguen en su ranura", 0, missing)
    }

    /** Lo que pasó al abrir la app: cuánto esperaron los cambios y si la copia quedó entera. */
    private class Delivery(val exposure: Duration, val missing: Int)

    /**
     * La app se abre, con el estado que vería la persona. Abrir no escribe nada. Con cambios
     * sin copia, Inicio ofrece `Guardar en {provider}`, y la persona lo toca: la copia sale en
     * seguida, y debe llevar todos los cambios y quedar entera en su ranura. Sin cambios, nulo.
     */
    private suspend fun openTheApp(): Delivery? {
        appVisible = true
        val before = slotFingerprint()
        val missing = damagedSlots()
        val facts = status.current()
        val report = BackupStatusResolver.resolve(facts, fixture.now)
        assertNotEquals(BackupStatus.UP_TO_DATE, report.status)
        scheduler.onAppOpened(AppOpenUploadRule.decide(report))
        assertTrue("Abrir dejó un trabajo del destino", destinationWork().isEmpty())
        assertEquals("Abrir escribió en una ranura", before, slotFingerprint())
        report("open", "status" to report.status, "pending" to report.pendingChanges)
        // Inicio ofrece el botón con cambios sin copia y un estado en que pedirla sirve.
        if (report.pendingChanges <= 0 || report.status !in OFFERED) return null
        val oldest = Instant.fromEpochMilliseconds(requireNotNull(fixture.state().firstUnsyncedAt))
        val run = runUpload { assertEquals(BackupNowRequest.QUEUED, scheduler.backupNow()) }
        assertEquals(WorkInfo.State.SUCCEEDED, run.state)
        val state = fixture.state()
        val notAll = if (state.changeSeq == state.verifiedSeq) 0 else 1
        val notWritten = if (slotFingerprint() == before) 1 else 0
        return Delivery(fixture.now - oldest, missing + notAll + notWritten + damagedSlots())
    }

    /**
     * La app se cierra: no queda ningún trabajo del destino. Después corren con la app cerrada una
     * subida automática vieja y una copia pedida que el sistema repite tarde. Devuelve 1 si alguna
     * cambió una ranura, y 0 si ninguna escribió.
     */
    private suspend fun closeTheApp(): Int {
        appVisible = false
        scheduler.onAppBackgrounded()
        assertTrue("Quedó un trabajo del destino con la app cerrada", destinationWork().isEmpty())
        val before = slotFingerprint()
        val stale = runUpload { queue.keepUpload(Duration.ZERO, needsNetwork = false) }
        val late =
            runUpload { queue.replaceUpload(needsNetwork = false, kind = RunKind.BACKUP_NOW) }
        assertEquals(WorkInfo.State.SUCCEEDED, stale.state)
        assertEquals(WorkInfo.State.SUCCEEDED, late.state)
        assertTrue(destinationWork().isEmpty())
        return if (slotFingerprint() == before) 0 else 1
    }

    /** Encola con [enqueue] y espera a que termine la subida nueva. */
    private suspend fun runUpload(enqueue: suspend () -> Unit): WorkInfo {
        val known = manager.getWorkInfosForUniqueWork(BackupWorkNames.UPLOAD).get().map { it.id }
        enqueue()
        return withTimeout(WAIT) {
            manager
                .getWorkInfosForUniqueWorkFlow(BackupWorkNames.UPLOAD)
                .first { infos -> infos.any { it.isNew(known) } }
                .first { it.isNew(known) }
        }
    }

    private fun WorkInfo.isNew(known: List<UUID>): Boolean = id !in known && state.isFinished

    /** Los trabajos del destino que todavía esperan o corren: la espera y las dos subidas. */
    private fun destinationWork(): List<WorkInfo> = BackupWorkNames.MAIN.flatMap { name ->
        manager.getWorkInfosForUniqueWork(name).get().filterNot { it.state.isFinished }
    }

    /** Lo que guarda cada ranura: su generación según el estado y la suma de su documento. */
    private suspend fun slotFingerprint(): Map<BackupSlot, Pair<Long?, String>> {
        val state = (store.load() as SlotStateLoad.Loaded).state
        return BackupSlot.entries.associateWith { slot ->
            state[slot].copy?.generation to sha256(SlotTestProvider.bytesOf(cache, slot.id))
        }
    }

    /** Las ranuras con una copia entregada cuyo documento ya no tiene ese tamaño o esa suma. */
    private suspend fun damagedSlots(): Int {
        val state = (store.load() as SlotStateLoad.Loaded).state
        return BackupSlot.entries.count { slot ->
            val record = state[slot].copy ?: return@count false
            val bytes = SlotTestProvider.bytesOf(cache, slot.id)
            bytes.size.toLong() != record.sizeBytes || sha256(bytes) != record.sha256
        }
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /** Una línea `clave=valor` para el guion: solo conteos, minutos y resultados. */
    private fun report(event: String, vararg fields: Pair<String, Any>) {
        Log.i(TAG, "event=$event " + fields.joinToString(" ") { (key, value) -> "$key=$value" })
    }

    private val BackupSlot.id: String get() = name.lowercase()

    private companion object {
        /** La etiqueta de las líneas que lee el guion del PC. */
        const val TAG = "SlotsExposure"

        const val MIN_CLOSINGS = 50
        const val SESSIONS_PER_DAY = 7
        const val MAX_SALES = 3
        const val MIN_GAP_MIN = 30
        const val GAP_SPREAD_MIN = 121
        const val MIN_WITHIN_PERCENT = 95
        const val SEED = 20261116L

        /** Los estados en que Inicio ofrece `Guardar en {provider}` con cambios sin copia. */
        val OFFERED =
            setOf(
                BackupStatus.PENDING,
                BackupStatus.STALE,
                BackupStatus.FAILED,
                BackupStatus.CHECK_DUE,
            )

        val DAY = 24.hours
        val SESSION = 10.minutes
        val WAIT = 60.seconds
    }
}
