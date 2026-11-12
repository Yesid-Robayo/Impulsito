package io.github.yesidrobayo.shopledger.lock

import android.view.MotionEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * La ventana queda protegida mientras alguna razón lo pida: salir de una pantalla protegida no quita
 * la protección del bloqueo, y apagar el bloqueo no la quita a una pantalla que sigue a la vista.
 */
class WindowProtectionTest {
    private val applied = mutableListOf<Boolean>()
    private val hidden = mutableListOf<Boolean>()
    private val protection =
        WindowProtection(applySecure = { applied += it }, applyHideOverlays = { hidden += it })

    @Test
    fun `a protected screen secures the window only while it is shown`() {
        val screen = Any()

        protection.add(screen)
        protection.remove(screen)

        assertEquals(listOf(true, false), applied)
    }

    @Test
    fun `leaving a protected screen keeps the lock protection`() {
        val screen = Any()
        protection.lockEnabled(true)

        protection.add(screen)
        protection.remove(screen)

        assertEquals(listOf(true), applied)
    }

    @Test
    fun `turning the lock off keeps a shown protected screen secure`() {
        val screen = Any()
        protection.lockEnabled(true)
        protection.add(screen)

        protection.lockEnabled(false)
        assertEquals(listOf(true), applied)

        protection.remove(screen)
        assertEquals(listOf(true, false), applied)
    }

    @Test
    fun `two screens at once need both to leave`() {
        val first = Any()
        val second = Any()

        protection.add(first)
        protection.add(second)
        protection.remove(first)
        assertEquals(listOf(true), applied)

        protection.remove(second)
        assertEquals(listOf(true, false), applied)
    }

    @Test
    fun `overlays are hidden while a sensitive action is shown`() {
        val dialog = Any()

        protection.hideOverlays(dialog)
        protection.showOverlays(dialog)

        assertEquals(listOf(true, false), hidden)
        assertEquals(emptyList<Boolean>(), applied)
    }

    @Test
    fun `a sensitive screen is secured, hides overlays and lets them back when it leaves`() {
        val screen = Any()

        protection.addSensitiveScreen(screen) {}
        protection.removeSensitiveScreen(screen)

        assertEquals(listOf(true, false), applied)
        assertEquals(listOf(true, false), hidden)
    }

    @Test
    fun `without a sensitive screen no touch is ever rejected`() {
        assertFalse(protection.rejects(MotionEvent.ACTION_DOWN, true))
    }

    @Test
    fun `a gesture that starts covered is rejected whole and warns once`() {
        var warnings = 0
        protection.addSensitiveScreen(Any()) { warnings += 1 }

        assertTrue(protection.rejects(MotionEvent.ACTION_DOWN, true))
        assertTrue(protection.rejects(MotionEvent.ACTION_MOVE, false))
        assertTrue(protection.rejects(MotionEvent.ACTION_UP, false))
        assertFalse(protection.rejects(MotionEvent.ACTION_DOWN, false))
        assertEquals(1, warnings)
    }
}
