package io.github.yesidrobayo.shopledger.data.backup

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupDrillResult
import kotlin.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `Salud de mis datos` lee la revisión, la lectura completa y el simulacro de la fila del respaldo,
 * sin escribir nada en ella. Datos inventados.
 */
@RunWith(AndroidJUnit4::class)
class DataHealthReaderTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val fixture = BackupFixture(context, "data-health-test")

    @Before
    fun open() = runBlocking { fixture.open() }

    @After
    fun close() = fixture.close()

    private fun reader(locked: Boolean = true, space: Boolean = false) = DataHealthReader(
        state = { fixture.ports.backupState.get() },
        deviceLocked = { locked },
        serviceSpaceKnown = { space },
    )

    @Test
    fun theRowIsReadAsItIsAndNothingIsWritten() = runBlocking<Unit> {
        fixture.connect()
        fixture.sql(
            "UPDATE backup_state SET last_human_check_at = 1000, last_full_verify_at = 2000, " +
                "last_drill_at = 3000, last_drill_result = 'FAILED'",
        )
        val before = fixture.state()

        val facts = requireNotNull(reader(space = true).facts())

        assertEquals(Instant.fromEpochMilliseconds(1000), facts.lastHumanCheckAt)
        assertEquals(Instant.fromEpochMilliseconds(2000), facts.lastFullVerifyAt)
        assertEquals(Instant.fromEpochMilliseconds(3000), facts.lastDrillAt)
        assertEquals(BackupDrillResult.FAILED, facts.lastDrillResult)
        assertEquals(true, facts.deviceLocked)
        assertEquals(true, facts.serviceSpaceKnown)
        assertEquals(before, fixture.state())
    }

    @Test
    fun withoutADrillThereIsNoResult() = runBlocking<Unit> {
        // La guarda de la tabla solo acepta los nombres del resultado, o ninguno.
        val facts = requireNotNull(reader(locked = false).facts())

        assertNull(facts.lastDrillResult)
        assertNull(facts.lastDrillAt)
        assertEquals(false, facts.deviceLocked)
    }
}
