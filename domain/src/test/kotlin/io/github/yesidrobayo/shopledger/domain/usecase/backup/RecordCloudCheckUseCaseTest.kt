package io.github.yesidrobayo.shopledger.domain.usecase.backup

import io.github.yesidrobayo.shopledger.domain.port.Clock
import io.github.yesidrobayo.shopledger.domain.port.HumanCheckRecorder
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** `Ya la vi en {provider}` anota la hora de ahora con la generación en pantalla, y nada más. */
class RecordCloudCheckUseCaseTest {
    private val now = Instant.parse("2026-11-07T15:00:00Z")
    private val noted = mutableListOf<Pair<Long, Instant>>()
    private var accepts = true

    private val recorder = HumanCheckRecorder { generation, at ->
        if (accepts) noted += generation to at
        accepts
    }

    private val record = RecordCloudCheckUseCase(recorder, Clock { now })

    @Test
    fun `it notes the shown generation with the current time`() = runTest {
        assertTrue(record(42))

        assertEquals(listOf(42L to now), noted)
    }

    @Test
    fun `without the cloud of the phone nothing is noted`() = runTest {
        accepts = false

        assertFalse(record(42))
        assertEquals(emptyList<Pair<Long, Instant>>(), noted)
    }
}
