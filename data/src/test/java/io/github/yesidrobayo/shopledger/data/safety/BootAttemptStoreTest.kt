package io.github.yesidrobayo.shopledger.data.safety

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Simula procesos sucesivos sobre la misma carpeta, como varios arranques del mismo teléfono. */
class BootAttemptStoreTest {
    @get:Rule
    val folder = TemporaryFolder()

    private var now = 1_000_000L
    private var nextPid = 100

    // Salidas que el sistema mostraría, de la más reciente a la más antigua.
    private val exits = mutableListOf<ProcessExit>()

    private fun tick(): Long {
        now += 1_000
        return now
    }

    private inner class FakeProcess(val pid: Int = nextPid++) {
        val store = BootAttemptStore(folder.root, { exits.toList() }, pid) { tick() }

        fun die(kind: ExitKind) {
            val reason =
                when (kind) {
                    ExitKind.JAVA_CRASH -> CRASH
                    ExitKind.NATIVE_CRASH -> CRASH_NATIVE
                    ExitKind.ANR -> ANR
                    ExitKind.INITIALIZATION_FAILURE -> INITIALIZATION_FAILURE
                    ExitKind.OTHER -> USER_REQUESTED
                }
            dieWithReason(reason)
        }

        // Una salida registrada por el sistema con su código de motivo crudo.
        fun dieWithReason(reason: Int) {
            val exit = ProcessExit(pid, tick(), exitKindOf(reason), reason, 0, 100)
            exits.add(0, exit)
        }

        fun openScreen() {
            store.onApplicationStart()
            store.onApplicationCreated()
            store.onActivityCreated()
        }
    }

    @Test
    fun threeCrashesDuringApplicationStartOpenSafeModeOnTheFourthStart() {
        repeat(3) {
            val process = FakeProcess()
            assertFalse(process.store.onApplicationStart().safeMode)
            process.die(ExitKind.JAVA_CRASH)
        }

        val report = FakeProcess().store.onApplicationStart()

        assertTrue(report.safeMode)
        assertEquals(3, report.countedAttempts)
    }

    @Test
    fun crashesRightAfterTheScreenOpensAlsoCount() {
        repeat(3) {
            val process = FakeProcess()
            process.openScreen()
            process.die(ExitKind.NATIVE_CRASH)
        }

        assertTrue(FakeProcess().store.onApplicationStart().safeMode)
    }

    @Test
    fun processesStartedWithoutTheScreenNeverCount() {
        repeat(3) {
            val process = FakeProcess()
            process.store.onApplicationStart()
            process.store.onApplicationCreated()
            // Un trabajo en segundo plano que cae antes de diez segundos.
            process.die(ExitKind.JAVA_CRASH)
        }

        val report = FakeProcess().store.onApplicationStart()

        assertFalse(report.safeMode)
        assertEquals(0, report.storedAttempts)
    }

    @Test
    fun aStableScreenResetsTheCount() {
        repeat(3) {
            val process = FakeProcess()
            process.openScreen()
            process.store.onStable()
            process.die(ExitKind.JAVA_CRASH)
        }

        val report = FakeProcess().store.onApplicationStart()

        assertFalse(report.safeMode)
        assertEquals(0, report.storedAttempts)
    }

    @Test
    fun exitsThatAreNotFailuresAreDiscarded() {
        repeat(3) {
            val process = FakeProcess()
            process.openScreen()
            // Cierre forzado antes de diez segundos, sin que la pantalla deje de verse antes.
            process.die(ExitKind.OTHER)
        }

        val report = FakeProcess().store.onApplicationStart()

        assertFalse(report.safeMode)
        assertEquals(1, report.storedAttempts)
        assertEquals(1, report.discardedAttempts)
    }

    @Test
    fun attemptsWithoutAnExitRecordStillCount() {
        // Sin motivos de salida, como antes de Android 11 o con el registro del sistema ya rotado.
        repeat(3) { FakeProcess().store.onApplicationStart() }

        assertTrue(FakeProcess().store.onApplicationStart().safeMode)
    }

    @Test
    fun anOlderExitWithARecycledPidDoesNotDiscardTheAttempt() {
        // Un proceso viejo con el mismo pid se cerró bien antes de que empezara el intento nuevo.
        exits.add(0, ProcessExit(500, tick(), ExitKind.OTHER, 10, status = 0, importance = 100))
        FakeProcess(pid = 500).store.onApplicationStart()

        val report = FakeProcess().store.onApplicationStart()

        assertEquals(1, report.countedAttempts)
        assertEquals(0, report.discardedAttempts)
    }

    @Test
    fun safeModeKeepsTheCountUntilRetry() {
        repeat(3) {
            val process = FakeProcess()
            process.store.onApplicationStart()
            process.die(ExitKind.JAVA_CRASH)
        }
        val rescue = FakeProcess()
        assertTrue(rescue.store.onApplicationStart().safeMode)
        rescue.store.onApplicationCreated()
        rescue.store.onActivityCreated()
        rescue.store.onStable()
        rescue.die(ExitKind.OTHER)

        val again = FakeProcess()
        assertTrue(again.store.onApplicationStart().safeMode)
        again.store.resetForRetry()

        assertFalse(again.store.isSafeMode)
        assertFalse(FakeProcess().store.onApplicationStart().safeMode)
    }

    @Test
    fun aCrashFollowedByAForcedCloseOfTheSameProcessStillCounts() {
        repeat(3) {
            val process = FakeProcess()
            process.openScreen()
            // Como en Android 17: la caída y, segundos después, otra salida del mismo proceso.
            process.dieWithReason(CRASH)
            process.dieWithReason(USER_REQUESTED)
        }

        val report = FakeProcess().store.onApplicationStart()

        assertTrue(report.safeMode)
        assertEquals(3, report.countedAttempts)
        assertEquals(0, report.discardedAttempts)
    }

    @Test
    fun anInitializationFailureCounts() {
        repeat(3) {
            val process = FakeProcess()
            process.store.onApplicationStart()
            process.dieWithReason(INITIALIZATION_FAILURE)
        }

        val report = FakeProcess().store.onApplicationStart()

        assertTrue(report.safeMode)
        assertEquals(3, report.countedAttempts)
    }

    @Test
    fun anUnknownExitAloneDoesNotCount() {
        val process = FakeProcess()
        process.openScreen()
        process.dieWithReason(UNKNOWN)

        val report = FakeProcess().store.onApplicationStart()

        assertEquals(1, report.storedAttempts)
        assertEquals(0, report.countedAttempts)
        assertEquals(1, report.discardedAttempts)
    }

    @Test
    fun anAttemptWhoseOnlyExitIsAForcedCloseIsDiscarded() {
        val process = FakeProcess()
        process.openScreen()
        process.dieWithReason(USER_REQUESTED)

        val report = FakeProcess().store.onApplicationStart()

        assertEquals(1, report.storedAttempts)
        assertEquals(0, report.countedAttempts)
        assertEquals(1, report.discardedAttempts)
    }

    @Test
    fun brokenLinesCountAsFailedAttempts() {
        File(folder.root, BootAttemptStore.FILE_NAME).writeText("garbage\n\n12 oops\n7 99\n")

        val report = FakeProcess().store.onApplicationStart()

        assertEquals(3, report.storedAttempts)
        assertTrue(report.safeMode)
    }

    @Test
    fun theFileHoldsOnlyNumbers() {
        FakeProcess().openScreen()

        val text = File(folder.root, BootAttemptStore.FILE_NAME).readText()

        assertTrue(Regex("[0-9 \n]*").matches(text))
        assertEquals(1, text.lines().count { it.isNotBlank() })
    }

    private companion object {
        // Códigos de motivo de la referencia pública de ApplicationExitInfo.
        const val UNKNOWN = 0
        const val CRASH = 4
        const val CRASH_NATIVE = 5
        const val ANR = 6
        const val INITIALIZATION_FAILURE = 7
        const val USER_REQUESTED = 10
    }
}
