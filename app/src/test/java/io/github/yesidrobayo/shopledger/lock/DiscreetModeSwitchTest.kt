package io.github.yesidrobayo.shopledger.lock

import io.github.yesidrobayo.shopledger.domain.model.settings.PreferenceChange
import io.github.yesidrobayo.shopledger.domain.port.StorageFailure
import io.github.yesidrobayo.shopledger.domain.usecase.settings.ChangePreferenceResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * El modo discreto: ocultar es inmediato y sin verificación; mostrar pide la verificación, y si se
 * cancela las cifras siguen ocultas. Una falla al guardar se informa.
 */
class DiscreetModeSwitchTest {
    private val checks = mutableListOf<String>()
    private val saved = mutableListOf<PreferenceChange>()
    private var allowed = true
    private var result: ChangePreferenceResult = ChangePreferenceResult.Saved

    private val switch =
        DiscreetModeSwitch(
            check = { reason ->
                checks += reason
                allowed
            },
            save = { change ->
                saved += change
                result
            },
            reason = REASON,
        )

    @Test
    fun `hiding is immediate and asks for nothing`() = runTest {
        assertTrue(switch.set(true))

        assertEquals(emptyList<String>(), checks)
        assertEquals(listOf<PreferenceChange>(PreferenceChange.DiscreetMode(true)), saved)
    }

    @Test
    fun `showing again asks for the check with its reason`() = runTest {
        assertTrue(switch.set(false))

        assertEquals(listOf(REASON), checks)
        assertEquals(listOf<PreferenceChange>(PreferenceChange.DiscreetMode(false)), saved)
    }

    @Test
    fun `a cancelled check keeps the figures hidden`() = runTest {
        allowed = false

        assertFalse(switch.set(false))
        assertEquals(emptyList<PreferenceChange>(), saved)
    }

    @Test
    fun `a save failure is reported`() = runTest {
        result = ChangePreferenceResult.Failed(StorageFailure.RESTORE_IN_PROGRESS)

        assertFalse(switch.set(true))
    }

    private companion object {
        const val REASON = "Mostrar cifras ocultas"
    }
}
