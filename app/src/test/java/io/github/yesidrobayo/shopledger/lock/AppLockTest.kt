package io.github.yesidrobayo.shopledger.lock

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import io.github.yesidrobayo.shopledger.domain.model.settings.AppLockTimeout
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Cuándo se bloquea la app: al arrancar con el bloqueo encendido, y al volver de segundo plano
 * después del tiempo elegido. Rotar, autenticarse o encender el bloqueo no bloquean, y apagarlo o
 * perder el bloqueo de pantalla del teléfono nunca dejan a nadie fuera.
 */
class AppLockTest {
    private var now = 1_000_000L
    private val lock = AppLock { now }

    private val process =
        object : LifecycleOwner {
            override val lifecycle: Lifecycle get() = error("El bloqueo no lee el ciclo de vida")
        }

    private fun away(millis: Long) {
        lock.onStop(process)
        now += millis
        lock.onStart(process)
    }

    @Test
    fun `nothing is shown until the setting is read`() {
        assertEquals(AppLockState.Unknown, lock.state.value)
    }

    @Test
    fun `without the lock the app opens and stays open`() {
        lock.apply(enabled = false, AppLockTimeout.IMMEDIATELY)
        away(DAY)

        assertEquals(AppLockState.Open(lockEnabled = false), lock.state.value)
        assertEquals(0, lock.promptRequests.value)
    }

    @Test
    fun `with the lock on, a new process starts locked and asks for the prompt`() {
        lock.apply(enabled = true, AppLockTimeout.ONE_MINUTE)

        assertEquals(AppLockState.Locked, lock.state.value)
        assertEquals(1, lock.promptRequests.value)
    }

    @Test
    fun `one minute by default locks only from the full minute`() {
        open(AppLockTimeout.ONE_MINUTE)

        away(59_999L)
        assertEquals(AppLockState.Open(lockEnabled = true), lock.state.value)

        away(60_000L)
        assertEquals(AppLockState.Locked, lock.state.value)
    }

    @Test
    fun `immediately locks on any return`() {
        open(AppLockTimeout.IMMEDIATELY)

        away(0L)

        assertEquals(AppLockState.Locked, lock.state.value)
    }

    @Test
    fun `five minutes locks only from the fifth minute`() {
        open(AppLockTimeout.FIVE_MINUTES)

        away(299_999L)
        assertEquals(AppLockState.Open(lockEnabled = true), lock.state.value)

        away(300_000L)
        assertEquals(AppLockState.Locked, lock.state.value)
    }

    @Test
    fun `coming back without having left, like a rotation, does not lock`() {
        open(AppLockTimeout.IMMEDIATELY)

        lock.onStart(process)

        assertEquals(AppLockState.Open(lockEnabled = true), lock.state.value)
    }

    @Test
    fun `leaving for the system pin screen while authenticating does not lock`() = runTest {
        open(AppLockTimeout.IMMEDIATELY)

        lock.authenticating { away(DAY) }
        // El resultado llega antes de que el proceso vuelva a primer plano: tampoco bloquea.
        lock.authenticating { lock.onStop(process) }
        lock.onStart(process)

        assertEquals(AppLockState.Open(lockEnabled = true), lock.state.value)
    }

    @Test
    fun `turning the lock on while open does not lock right away`() {
        lock.apply(enabled = false, AppLockTimeout.ONE_MINUTE)

        lock.apply(enabled = true, AppLockTimeout.ONE_MINUTE)

        assertEquals(AppLockState.Open(lockEnabled = true), lock.state.value)
        assertEquals(0, lock.promptRequests.value)
    }

    @Test
    fun `a changed delay applies to the next return`() {
        open(AppLockTimeout.FIVE_MINUTES)

        lock.apply(enabled = true, AppLockTimeout.IMMEDIATELY)
        away(1L)

        assertEquals(AppLockState.Locked, lock.state.value)
    }

    @Test
    fun `turning the lock off opens a locked app`() {
        lock.apply(enabled = true, AppLockTimeout.ONE_MINUTE)

        lock.apply(enabled = false, AppLockTimeout.ONE_MINUTE)

        assertEquals(AppLockState.Open(lockEnabled = false), lock.state.value)
    }

    @Test
    fun `a locked app stays locked with the lock still on`() {
        lock.apply(enabled = true, AppLockTimeout.ONE_MINUTE)

        lock.apply(enabled = true, AppLockTimeout.FIVE_MINUTES)

        assertEquals(AppLockState.Locked, lock.state.value)
    }

    @Test
    fun `coming back to a locked app asks for the prompt again`() {
        lock.apply(enabled = true, AppLockTimeout.ONE_MINUTE)

        away(1L)

        assertEquals(AppLockState.Locked, lock.state.value)
        assertEquals(2, lock.promptRequests.value)
    }

    @Test
    fun `authenticating opens the business with the lock still on`() {
        lock.apply(enabled = true, AppLockTimeout.ONE_MINUTE)

        lock.unlocked()

        assertEquals(AppLockState.Open(lockEnabled = true), lock.state.value)
    }

    @Test
    fun `a phone that lost its screen lock opens without the lock`() {
        lock.apply(enabled = true, AppLockTimeout.ONE_MINUTE)

        lock.noDeviceCredential()
        away(DAY)

        assertEquals(AppLockState.Open(lockEnabled = false), lock.state.value)
    }

    @Test
    fun `without a database the rescue is never locked`() {
        lock.withoutSettings()

        assertEquals(AppLockState.Open(lockEnabled = false), lock.state.value)
    }

    @Test
    fun `a missing database later does not open a locked app`() {
        lock.apply(enabled = true, AppLockTimeout.ONE_MINUTE)

        lock.withoutSettings()

        assertEquals(AppLockState.Locked, lock.state.value)
    }

    @Test
    fun `returning before the setting is read changes nothing`() {
        away(DAY)

        assertEquals(AppLockState.Unknown, lock.state.value)
        assertEquals(0, lock.promptRequests.value)
    }

    private fun open(timeout: AppLockTimeout) {
        lock.apply(enabled = true, timeout)
        lock.unlocked()
    }

    private companion object {
        const val DAY = 86_400_000L
    }
}
