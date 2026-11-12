package io.github.yesidrobayo.shopledger.data.safety

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CrashRecorderTest {
    @get:Rule
    val folder = TemporaryFolder()

    private var now = 1_000_000L

    private fun recorder(pid: Int = 42) =
        CrashRecorder(folder.root, { AppVersions("1.0.0-test", 7, 31) }, pid) { now }

    private fun records(): List<File> = File(folder.root, CrashRecorder.DIRECTORY_NAME)
        .listFiles()
        ?.filter { it.name.endsWith(".txt") }
        .orEmpty()

    @Test
    fun theRecordNeverContainsExceptionMessages() {
        val marked = MARKED_VALUES.joinToString(" ")
        val cause = IllegalStateException(marked)
        // Android copia el mensaje de la causa en el de la excepción que la envuelve.
        val error = RuntimeException("Unable to create application: $cause", cause)
        error.addSuppressed(IllegalArgumentException(marked))
        val recorder = recorder()
        recorder.prepare()

        recorder.recordUncaught(onMainThread = true, error = error)

        val text = records().single().readText()
        MARKED_VALUES.forEach {
            assertFalse("El registro contiene $it", text.contains(it, ignoreCase = true))
        }
        assertTrue(text.contains("\njava.lang.RuntimeException\n"))
        assertTrue(text.contains("Caused by: java.lang.IllegalStateException"))
        assertTrue(text.contains("\tSuppressed: java.lang.IllegalArgumentException"))
        assertTrue(text.contains("\tat ${CrashRecorderTest::class.java.name}."))
        assertTrue(text.contains("main_thread=true"))
        assertTrue(text.contains("app_version_name=1.0.0-test"))
    }

    @Test
    fun aHugeTraceStaysWithinTheSizeLimit() {
        val error = IllegalStateException()
        val longClassName = "a".repeat(600) + ".B"
        error.stackTrace = Array(1_000) { StackTraceElement(longClassName, "m$it", "B.kt", it) }
        val recorder = recorder()
        recorder.prepare()

        recorder.recordUncaught(onMainThread = false, error = error)

        val file = records().single()
        assertTrue(file.length() <= CrashRecorder.MAX_FILE_BYTES)
        assertTrue(file.readText().endsWith("truncated=true\n"))
    }

    @Test
    fun aCycleOfCausesEnds() {
        val first = IllegalStateException()
        val second = IllegalArgumentException(first)
        first.initCause(second)
        val recorder = recorder()
        recorder.prepare()

        recorder.recordUncaught(onMainThread = true, error = first)

        val text = records().single().readText()
        assertTrue(text.contains("[CIRCULAR REFERENCE: java.lang.IllegalStateException]"))
    }

    @Test
    fun onlyTheNewestFiveRecordsAreKept() {
        val recorder = recorder()
        recorder.prepare()

        repeat(7) {
            now += 1_000
            recorder.recordUncaught(onMainThread = true, error = IllegalStateException())
        }

        val names = records().map { it.name }.sorted()
        assertEquals(CrashRecorder.MAX_FILES, names.size)
        assertEquals("crash-1003000-42.txt", names.first())
        assertTrue(names.none { it.endsWith(".tmp") })
    }

    @Test
    fun theExitReasonJoinsTheRecordOfTheSameProcess() {
        val crashed = recorder(pid = 42)
        crashed.prepare()
        now += 1_000
        crashed.recordUncaught(onMainThread = true, error = IllegalStateException())
        now += 1_000

        val next = recorder(pid = 43)
        next.prepare()
        next.recordExits(listOf(ProcessExit(42, now - 500, ExitKind.JAVA_CRASH, 4, 0, 100)))

        val text = records().single().readText()
        assertTrue(text.contains("kind=java_crash"))
        assertTrue(text.contains("exit_reason=4\nexit_status=0\nexit_importance=100\n"))
    }

    @Test
    fun aNativeCrashGetsItsOwnRecord() {
        val recorder = recorder(pid = 43)
        recorder.prepare()
        now += 1_000

        recorder.recordExits(listOf(ProcessExit(42, now, ExitKind.NATIVE_CRASH, 5, 11, 100)))

        val text = records().single().readText()
        assertTrue(text.contains("kind=exit"))
        assertTrue(text.contains("pid=42"))
        assertTrue(text.contains("exit_reason=5\nexit_status=11\n"))
    }

    @Test
    fun exitsAreRecordedOnceAndOrdinaryExitsAreIgnored() {
        val recorder = recorder(pid = 43)
        recorder.prepare()
        now += 1_000
        val exits =
            listOf(
                ProcessExit(41, now, ExitKind.OTHER, 10, 0, 100),
                ProcessExit(42, now - 10, ExitKind.ANR, 6, 0, 100),
            )

        recorder.recordExits(exits)
        recorder.recordExits(exits)

        assertEquals(1, records().size)
    }

    @Test
    fun exitsFromBeforeTheFirstStartAreIgnored() {
        val recorder = recorder(pid = 43)
        recorder.prepare()

        recorder.recordExits(listOf(ProcessExit(42, now - 1, ExitKind.JAVA_CRASH, 4, 0, 100)))

        assertTrue(records().isEmpty())
    }

    @Test
    fun aCrashAndALaterForcedCloseOfTheSameProcessLeaveOneRecord() {
        val crashed = recorder(pid = 42)
        crashed.prepare()
        now += 1_000
        crashed.recordUncaught(onMainThread = true, error = IllegalStateException())

        val next = recorder(pid = 43)
        next.recordExits(
            listOf(exit(42, now + 100, reason = 4), exit(42, now + 6_000, reason = 10)),
        )

        val text = records().single().readText()
        assertEquals(1, Regex("exit_reason=").findAll(text).count())
        assertTrue(text.contains("exit_reason=4\n"))
    }

    @Test
    fun aSecondCrashExitOfTheSameProcessDoesNotAddARecord() {
        val crashed = recorder(pid = 42)
        crashed.prepare()
        now += 1_000
        crashed.recordUncaught(onMainThread = true, error = IllegalStateException())

        // Como en Android 17: la segunda salida llega después, y la lee otro arranque.
        recorder(pid = 43).recordExits(listOf(exit(42, now + 100, reason = 4)))
        recorder(pid = 44).recordExits(listOf(exit(42, now + 6_000, reason = 4)))

        val text = records().single().readText()
        assertEquals(1, Regex("exit_reason=").findAll(text).count())
    }

    @Test
    fun twoNativeExitsOfTheSameProcessLeaveOneRecord() {
        val recorder = recorder(pid = 43)
        recorder.prepare()

        recorder.recordExits(
            listOf(exit(42, now + 6_000, reason = 5), exit(42, now + 100, reason = 5)),
        )

        val text = records().single().readText()
        assertTrue(text.contains("kind=exit"))
        assertEquals(1, Regex("exit_reason=").findAll(text).count())
    }

    private fun exit(pid: Int, timestampMillis: Long, reason: Int) =
        ProcessExit(pid, timestampMillis, exitKindOf(reason), reason, status = 0, importance = 100)

    private companion object {
        // Valores marcados de la prueba de registros limpios: producto, cliente, monto, contraseña,
        // URL y usuario de WebDAV, URI de ranura y código de recuperación. Todos inventados.
        val MARKED_VALUES =
            listOf(
                "SENTINEL_PRODUCT_7F3A",
                "SENTINEL_CUSTOMER_9B2C",
                "987654321",
                "SENTINEL_PASS_4D1E",
                "https://webdav.sentinel.example.com/SENTINEL_FOLDER_5A6B/",
                "sentinel_user_8c3d",
                "content://com.example.sentinel.documents/tree/SENTINEL_SLOT_2E7F",
                "SENTINEL_RECOVERY_3K5M",
            )
    }
}
