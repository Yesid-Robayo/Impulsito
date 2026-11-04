package io.github.yesidrobayo.shopledger.domain.port

import io.github.yesidrobayo.shopledger.domain.testing.RecordingEventLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

// Los valores centinela son inventados: si alguno aparece en lo registrado, el registro dejó
// pasar un dato del negocio.
private const val SENTINEL_PRODUCT = "SENTINEL_PRODUCT_7F3A"
private const val SENTINEL_CUSTOMER = "SENTINEL_CUSTOMER_9B2C"
private const val SENTINEL_AMOUNT = "987654321"
private const val SENTINEL_URL = "https://sentinel-8a61.example.com/dav/"
private val SENTINELS = listOf(SENTINEL_PRODUCT, SENTINEL_CUSTOMER, SENTINEL_AMOUNT, SENTINEL_URL)

/** Un mensaje como el de SQLite o el de una conexión: con datos que nunca deben registrarse. */
private const val LEAKY_MESSAGE =
    "INSERT INTO products VALUES ('$SENTINEL_PRODUCT', $SENTINEL_AMOUNT) para $SENTINEL_CUSTOMER " +
        "en $SENTINEL_URL"

private enum class TestEvent(override val text: String) : LogEvent {
    OPENED("Base abierta"),
    FAILED("Falló un trabajo"),
}

private enum class SampleState { PERMISSION_LOST }

private class LeakyFailure : RuntimeException(LEAKY_MESSAGE, IllegalStateException(LEAKY_MESSAGE))

class EventLogTest {
    private val log = RecordingEventLog()

    private fun assertNoSentinel() {
        for (sentinel in SENTINELS) {
            assertFalse("Se registró un centinela", log.allText.contains(sentinel))
        }
    }

    @Test
    fun `each function writes its level and the fixed text of the event`() {
        log.info(TestEvent.OPENED)
        log.warn(TestEvent.OPENED)
        log.error(TestEvent.FAILED)

        assertEquals(
            listOf(
                LogLevel.INFO to "Base abierta",
                LogLevel.WARN to "Base abierta",
                LogLevel.ERROR to "Falló un trabajo",
            ),
            log.entries,
        )
    }

    @Test
    fun `fields only carry numbers, codes and states`() {
        log.info(
            TestEvent.OPENED,
            LogField.count("intentos", 3),
            LogField.durationMillis("duración", 12),
            LogField.id("venta", 42),
            LogField.code("sqlite", 13),
            LogField.httpStatus(404),
            LogField.state("estado", SampleState.PERMISSION_LOST),
        )

        assertEquals(
            "Base abierta: intentos=3, duración=12 ms, venta=42, sqlite=13, http=404, " +
                "estado=PERMISSION_LOST",
            log.entries.single().second,
        )
    }

    @Test
    fun `a field name that is not lowercase letters and underscores is not registered`() {
        val names =
            listOf(
                SENTINEL_PRODUCT,
                "con espacio",
                "nombre2",
                "",
                "a".repeat(LogField.MAX_NAME_LENGTH + 1),
            )

        for (name in names) {
            log.info(TestEvent.OPENED, LogField.count(name, 1))
            assertEquals(LogField.INVALID_NAME, LogField.count(name, 1).name)
        }
        assertNoSentinel()
    }

    @Test
    fun `lowercase names with accents and underscores up to the limit are kept`() {
        val longest = "a".repeat(LogField.MAX_NAME_LENGTH)

        assertEquals("tamaño_copia", LogField.count("tamaño_copia", 1).name)
        assertEquals(longest, LogField.count(longest, 1).name)
    }

    @Test
    fun `a failure is registered by its class, never by its message or cause`() {
        log.error(TestEvent.FAILED, LeakyFailure(), LogField.count("intentos", 2))

        assertEquals(
            "Falló un trabajo: intentos=2, error=" + LeakyFailure::class.qualifiedName,
            log.entries.single().second,
        )
        assertNoSentinel()
    }

    @Test
    fun `a warning keeps the class of its failure too`() {
        log.warn(TestEvent.FAILED, LeakyFailure())

        assertEquals(
            listOf(LogLevel.WARN to "Falló un trabajo: error=" + LeakyFailure::class.qualifiedName),
            log.entries,
        )
        assertNoSentinel()
    }

    @Test
    fun `a storage failure adds its kind, which is a code without data`() {
        log.error(TestEvent.FAILED, StorageFailureException(StorageFailure.DISK_FULL))

        assertEquals(
            "Falló un trabajo: " +
                "error=io.github.yesidrobayo.shopledger.domain.port.StorageFailureException, " +
                "tipo=DISK_FULL",
            log.entries.single().second,
        )
    }

    @Test
    fun `a failure of a class without a name is registered with a generic name`() {
        val anonymous = object : RuntimeException(LEAKY_MESSAGE) {}

        log.error(TestEvent.FAILED, anonymous)

        assertEquals(
            "Falló un trabajo: error=${LogField.UNNAMED_FAILURE}",
            log.entries.single().second,
        )
        assertNoSentinel()
    }

    @Test
    fun `a line prints as its text`() {
        val line = LogLine(TestEvent.OPENED, listOf(LogField.count("intentos", 1)), failure = null)

        assertEquals("Base abierta: intentos=1", line.toString())
    }

    @Test
    fun `a contained block returns its value and registers nothing`() = runTest {
        val result = log.contain(TestEvent.FAILED) { 7 }

        assertEquals(Contained.Done(7), result)
        assertTrue(log.entries.isEmpty())
    }

    @Test
    fun `a contained failure is registered by its class and does not get out`() = runTest {
        val result = log.contain<TestEvent, Int>(TestEvent.FAILED) { throw LeakyFailure() }

        assertEquals(Contained.Failed(LeakyFailure::class.qualifiedName.orEmpty()), result)
        assertEquals(LogLevel.ERROR, log.entries.single().first)
        assertNoSentinel()
    }

    @Test
    fun `a contained serious error does not get out either`() = runTest {
        val result =
            log.contain<TestEvent, Int>(TestEvent.FAILED) {
                throw StackOverflowError(LEAKY_MESSAGE)
            }

        assertEquals(Contained.Failed("java.lang.StackOverflowError"), result)
        assertNoSentinel()
    }

    @Test
    fun `cancellation is not contained`() = runTest {
        val started = CompletableDeferred<Unit>()
        val work =
            async {
                log.contain(TestEvent.FAILED) {
                    started.complete(Unit)
                    CompletableDeferred<Unit>().await()
                }
            }
        started.await()

        work.cancel()

        try {
            work.await()
            fail("El trabajo cancelado no debía terminar con un valor")
        } catch (_: CancellationException) {
            // Esperado: la cancelación sigue su camino.
        }
        assertTrue(log.entries.isEmpty())
    }

    @Test
    fun `the background handler registers an uncaught failure by its class`() {
        val dispatcher = StandardTestDispatcher()
        val scope =
            CoroutineScope(SupervisorJob() + dispatcher + log.failureHandler(TestEvent.FAILED))
        var sibling = false

        scope.launch { throw LeakyFailure() }
        scope.launch { sibling = true }
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(
            listOf(
                LogLevel.ERROR to "Falló un trabajo: error=" + LeakyFailure::class.qualifiedName,
            ),
            log.entries,
        )
        assertTrue("La falla de un trabajo no detiene a los demás", sibling)
        assertNoSentinel()
        scope.cancel()
    }
}
