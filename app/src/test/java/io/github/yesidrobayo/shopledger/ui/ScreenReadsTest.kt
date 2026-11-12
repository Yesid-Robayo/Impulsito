package io.github.yesidrobayo.shopledger.ui

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Las lecturas de una pantalla: una falla se marca sin romper nada, y reintentar vuelve a leer. */
@OptIn(ExperimentalCoroutinesApi::class)
class ScreenReadsTest {
    @Test
    fun `a failing flow marks the failure instead of throwing, and retry reads again`() =
        runTest(UnconfinedTestDispatcher()) {
            val reads = ScreenReads()
            var failing = 1
            val values = MutableStateFlow(7)
            val broken = flow<Int> { throw IllegalStateException("prueba") }
            val seen = mutableListOf<Int>()
            val collecting =
                launch {
                    reads.guard { if (failing-- > 0) broken else values }.collect { seen += it }
                }

            assertTrue(reads.failed.value)
            assertEquals(emptyList<Int>(), seen)

            reads.retry()

            assertFalse(reads.failed.value)
            assertEquals(listOf(7), seen)
            collecting.cancel()
        }

    @Test
    fun `a single read says whether it failed`() = runTest {
        val reads = ScreenReads()

        assertEquals(ReadOutcome.Read(null), reads.once { null })
        assertFalse(reads.failed.value)
        assertEquals(ReadOutcome.Failed, reads.once { error("prueba") })
        assertTrue(reads.failed.value)
    }

    @Test(expected = CancellationException::class)
    fun `a cancellation is not a failure`() = runTest {
        readOutcome { throw CancellationException("la pantalla se cerró") }
    }
}
