package io.github.yesidrobayo.shopledger.data.backup

import io.github.yesidrobayo.shopledger.data.work.BackupWorkNames
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupDestinationKind
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupTimings
import io.github.yesidrobayo.shopledger.domain.model.backup.UploadGateVerdict
import io.github.yesidrobayo.shopledger.domain.model.settings.MeteredBackupPolicy
import io.github.yesidrobayo.shopledger.domain.port.Clock
import io.github.yesidrobayo.shopledger.domain.rule.backup.UploadCadence
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * El ritmo de las copias con el programador real, segundo a segundo, sobre una biblioteca de
 * trabajos en memoria: las ventas, la espera, las dos subidas, el cerrojo que deja correr una a la
 * vez y la compuerta del paso 0. Cuenta cuántas copias salen en cada caso del documento de pruebas,
 * con WebDAV y con la nube del teléfono.
 */
class BackupCadenceSimulationTest {
    @Test
    fun tenSalesInARowWithWiFiGiveOneCopyBetweenOneAndTwoMinutesAfterTheLast() {
        val sim = Simulation()
        val sales = (0 until 10).map { (it * 50).seconds }

        sim.run(sales, until = 30.minutes)

        assertEquals(1, sim.copies.size)
        val lastSale = sales.last()
        val started = sim.copies.single().started
        assertTrue("$started", started - lastSale in 60.seconds..120.seconds)
        assertTrue(sim.allChangesBackedUp())
    }

    @Test
    fun aSaleEveryMinuteForThreeHoursWithWiFiGivesAboutEighteenCopies() {
        // Con una subida que tarda más que el minuto entre ventas, cada copia deja una venta nueva,
        // y la espera máxima cuenta desde esa copia: una cada 10 minutos.
        val slow = Simulation(uploadTakes = 75.seconds)
        slow.run(everyMinuteFor(3.hours), until = 3.hours + 10.minutes)
        assertEquals(18, slow.copies.size)

        // Con una subida corta la racha empieza en la venta siguiente: una cada 11 minutos.
        val fast = Simulation(uploadTakes = 5.seconds)
        fast.run(everyMinuteFor(3.hours), until = 3.hours + 10.minutes)
        assertEquals(17, fast.copies.size)

        for (sim in listOf(slow, fast)) {
            assertTrue(sim.allChangesBackedUp())
            // Ninguna racha pospone la copia más que la espera máxima y la venta siguiente.
            sim.copies.zipWithNext().forEach { (a, b) ->
                assertTrue("${b.started - a.started}", b.started - a.started <= 11.minutes)
            }
        }
    }

    @Test
    fun onMobileDataAtMostOneAutomaticCopyPerInterval() {
        for (
        (policy, interval) in
        listOf(
            MeteredBackupPolicy.EVERY_60_MIN to 60.minutes,
            MeteredBackupPolicy.EVERY_30_MIN to 30.minutes,
        )
        ) {
            val sim = Simulation(policy = policy, unmetered = { false })
            sim.run(everyMinuteFor(4.hours), until = 5.hours)

            assertTrue("$policy", sim.copies.size >= 4)
            sim.copies.zipWithNext().forEach { (a, b) ->
                assertTrue(
                    "$policy ${b.finished - a.finished}",
                    b.finished - a.finished >= interval,
                )
            }
            assertTrue("$policy", sim.allChangesBackedUp())
        }
    }

    @Test
    fun withWiFiOnlyAndMobileDataNoAutomaticCopyLeaves() {
        val sim = Simulation(policy = MeteredBackupPolicy.WIFI_ONLY, unmetered = { false })

        sim.run(everyMinuteFor(2.hours), until = 3.hours)

        assertEquals(0, sim.copies.size)
        assertFalse(BackupWorkNames.UPLOAD in sim.queue.queued)
        assertTrue(BackupWorkNames.UPLOAD_UNMETERED in sim.queue.queued)
    }

    @Test
    fun wiFiThatComesBackUploadsWithoutWaitingTheRestOfTheInterval() {
        // Datos móviles hasta los 20 minutos: la primera copia sale a los 10, y el intervalo de 60
        // dejaría la siguiente para la hora y diez. Con el Wi-Fi de vuelta sale en ese momento.
        val wifiBack = 20.minutes
        val sim = Simulation(unmetered = { it >= wifiBack })

        sim.run(everyMinuteFor(15.minutes), until = 2.hours)

        assertEquals(2, sim.copies.size)
        assertEquals(wifiBack, sim.copies[1].started)
        assertTrue(sim.allChangesBackedUp())
    }

    @Test
    fun backupNowOnMobileDataLeavesWithTheIntervalRunning() {
        val sim = Simulation(unmetered = { false })
        // La copia de los 10 minutos deja pendiente la venta de los 11.
        sim.run(everyMinuteFor(12.minutes), until = 12.minutes)
        assertEquals(1, sim.copies.size)
        assertFalse(sim.allChangesBackedUp())

        runBlocking { assertEquals(BackupNowRequest.QUEUED, sim.scheduler.backupNow()) }
        sim.run(emptyList(), until = 14.minutes)

        assertEquals(2, sim.copies.size)
        assertTrue(sim.copies[1].finished - sim.copies[0].finished < 60.minutes)
    }

    @Test
    fun aSaleEveryMinuteForThreeHoursWithThePhoneCloudGivesAtMostOneCopyPerHourWithAnyNetwork() {
        // Sin red, con Wi-Fi, con datos móviles y con la red cambiando cada 7 minutos entre las
        // tres: la nube del teléfono no mira la red, y el ritmo es el mismo en todos los casos.
        val networks =
            mapOf<String, (Duration) -> Network>(
                "modo avión" to { Network.NONE },
                "Wi-Fi" to { Network.UNMETERED },
                "datos móviles" to { Network.METERED },
                "cambiante" to { Network.entries[(it.inWholeMinutes / 7 % 3).toInt()] },
            )
        for ((name, network) in networks) {
            for (uploadTakes in listOf(5.seconds, 75.seconds)) {
                val case = "$name, subida de $uploadTakes"
                // La política de datos móviles tampoco cuenta: ni siquiera solo con Wi-Fi.
                val sim =
                    Simulation(
                        uploadTakes = uploadTakes,
                        policy = MeteredBackupPolicy.WIFI_ONLY,
                        kind = BackupDestinationKind.DOCUMENT_SLOTS,
                        network = network,
                    )
                sim.run(everyMinuteFor(3.hours), until = 4.hours + 30.minutes)

                // La primera sale con la espera máxima, a los 10 minutos, y cada una de las
                // siguientes a la hora de terminar la anterior: tres dentro de las 3 horas, y
                // una más con las ventas ya terminadas.
                assertEquals(case, 3, sim.copies.count { it.started < 3.hours })
                assertEquals(case, 4, sim.copies.size)
                sim.copies.zipWithNext().forEach { (a, b) ->
                    assertTrue(
                        "$case ${b.started - a.finished}",
                        b.started - a.finished >= BackupTimings.SLOT_MIN_INTERVAL,
                    )
                }
                assertTrue(case, sim.allChangesBackedUp())
                // Nunca hubo una subida que pidiera red, ni la que espera una red sin datos
                // móviles.
                assertTrue(case, sim.queue.calls.none { it == "keep unmetered" })
                assertTrue(case, sim.queue.calls.none { "con red" in it })
            }
        }
    }

    @Test
    fun backupNowWithThePhoneCloudAndNoNetworkWritesInsideTheInterval() {
        val sim =
            Simulation(
                kind = BackupDestinationKind.DOCUMENT_SLOTS,
                network = { Network.NONE },
            )
        // La copia de los 10 minutos deja pendiente la venta de los 11.
        sim.run(everyMinuteFor(12.minutes), until = 12.minutes)
        assertEquals(1, sim.copies.size)
        assertFalse(sim.allChangesBackedUp())

        runBlocking { assertEquals(BackupNowRequest.QUEUED, sim.scheduler.backupNow()) }
        sim.run(emptyList(), until = 14.minutes)

        assertEquals(2, sim.copies.size)
        assertTrue(sim.copies[1].finished - sim.copies[0].finished < 5.minutes)
        assertTrue(sim.allChangesBackedUp())
        assertTrue(sim.queue.calls.contains("replace upload sin red BACKUP_NOW"))
    }

    private fun everyMinuteFor(length: Duration): List<Duration> =
        (0 until length.inWholeMinutes).map { it.minutes }

    /** La red activa en un instante: ninguna, con datos móviles o sin ellos. */
    private enum class Network { NONE, METERED, UNMETERED }

    /** Una copia: cuándo empezó y cuándo terminó de enviarse, desde el inicio de la simulación. */
    private data class Copy(val started: Duration, val finished: Duration)

    /**
     * Un teléfono con el destino [kind] conectado, WebDAV si no se dice otro, que vende a las horas
     * dadas. Cada subida tarda [uploadTakes], y la instantánea se toma al empezar, como el paso 7.
     * [network] dice, para cada instante, qué red está activa. Sin red, un trabajo que pide red no
     * empieza, y la compuerta la ve como medida, igual que el trabajo de subida.
     */
    private class Simulation(
        val uploadTakes: Duration = 5.seconds,
        val policy: MeteredBackupPolicy = MeteredBackupPolicy.EVERY_60_MIN,
        val kind: BackupDestinationKind = BackupDestinationKind.WEBDAV,
        val network: (Duration) -> Network = { Network.UNMETERED },
    ) {
        /** Con WebDAV y una red siempre activa, que [unmetered] dice si es sin datos móviles. */
        constructor(
            uploadTakes: Duration = 5.seconds,
            policy: MeteredBackupPolicy = MeteredBackupPolicy.EVERY_60_MIN,
            unmetered: (Duration) -> Boolean,
        ) : this(
            uploadTakes,
            policy,
            BackupDestinationKind.WEBDAV,
            { if (unmetered(it)) Network.UNMETERED else Network.METERED },
        )

        private val start = Instant.parse("2026-11-07T08:00:00Z")
        private var elapsed = Duration.ZERO
        private val now get() = start + elapsed

        val queue = FakeWorkQueue { now }
        val copies = mutableListOf<Copy>()

        private var changeSeq = 0L
        private var verifiedSeq = 0L
        private var firstUnsyncedAt: Instant? = null
        private var lastUploadFinishedAt: Instant? = null
        private var inFlight: InFlight? = null

        val scheduler =
            BackupScheduler(
                facts = {
                    ScheduleFacts(
                        connectedKind = kind,
                        hasPending = changeSeq > verifiedSeq,
                        firstUnsyncedAt = firstUnsyncedAt,
                        lastUploadFinishedAt = lastUploadFinishedAt,
                        failureCode = null,
                        policy = policy,
                    )
                },
                restorePending = { false },
                policyAccepted = { true },
                queue = queue,
                clock = Clock { now },
            )

        /** Una subida que corre: su trabajo, su instantánea y cuándo termina. */
        private class InFlight(
            val work: String,
            val seq: Long,
            val copiedAt: Instant,
            val ends: Duration,
        )

        fun run(sales: List<Duration>, until: Duration) = runBlocking {
            val pending = ArrayDeque(sales.sorted())
            while (elapsed <= until) {
                while (pending.firstOrNull() == elapsed) {
                    pending.removeFirst()
                    sell()
                }
                finishUpload()
                fireDebounce()
                startUpload()
                elapsed += 1.seconds
            }
        }

        fun allChangesBackedUp(): Boolean = changeSeq == verifiedSeq

        private suspend fun sell() {
            changeSeq++
            if (firstUnsyncedAt == null) firstUnsyncedAt = now
            scheduler.onDataChanged()
        }

        private suspend fun fireDebounce() {
            val debounce = queue.queued[BackupWorkNames.DEBOUNCE] ?: return
            if (debounce.notBefore > now) return
            queue.queued.remove(BackupWorkNames.DEBOUNCE)
            scheduler.onDebounceElapsed()
        }

        /** Una subida a la vez: la otra espera el cerrojo del respaldo, como en la app. */
        private suspend fun startUpload() {
            if (inFlight != null) return
            val ready =
                queue.queued.entries.firstOrNull { (name, work) ->
                    val active = network(elapsed)
                    name in BackupWorkNames.UPLOADS &&
                        work.notBefore <= now &&
                        (!work.needsNetwork || active != Network.NONE) &&
                        (name != BackupWorkNames.UPLOAD_UNMETERED || active == Network.UNMETERED)
                } ?: return
            val (name, work) = ready
            queue.queued.remove(name)
            queue.running += name
            val run = beginRun(work)
            if (run != null) {
                queue.running -= name
                scheduler.onRunFinished(run, name)
            } else {
                inFlight = InFlight(name, changeSeq, now, elapsed + uploadTakes)
            }
        }

        /** El paso 0 del motor: lo que sale sin subir, o nulo si la subida empieza. */
        private suspend fun beginRun(work: FakeWorkQueue.Queued): BackupRun? {
            if (changeSeq == verifiedSeq) return BackupRun.Skipped(SkipReason.NOTHING_PENDING)
            if (work.kind != RunKind.AUTOMATIC) return null
            val verdict =
                UploadCadence.gate(
                    kind,
                    policy,
                    metered = network(elapsed) != Network.UNMETERED,
                    lastUploadFinishedAt,
                    now,
                )
            return when (verdict) {
                UploadGateVerdict.PROCEED -> null

                UploadGateVerdict.WAIT_FOR_INTERVAL ->
                    BackupRun.Skipped(SkipReason.WAITING_FOR_INTERVAL)

                UploadGateVerdict.WAIT_FOR_UNMETERED ->
                    BackupRun.Skipped(SkipReason.WAITING_FOR_UNMETERED)
            }
        }

        /** Los pasos 10 a 12: la hora del último byte, y la copia confirmada con su instantánea. */
        private suspend fun finishUpload() {
            val upload = inFlight ?: return
            if (upload.ends > elapsed) return
            inFlight = null
            lastUploadFinishedAt = now
            verifiedSeq = upload.seq
            firstUnsyncedAt = if (changeSeq > verifiedSeq) upload.copiedAt else null
            copies += Copy(upload.copiedAt - start, elapsed)
            queue.running -= upload.work
            scheduler.onRunFinished(BackupRun.Verified(copies.size.toLong()), upload.work)
        }
    }
}
