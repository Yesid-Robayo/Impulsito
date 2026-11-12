package io.github.yesidrobayo.shopledger.lock

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * La ventana queda protegida mientras alguna razón lo pida: salir de una pantalla protegida no quita
 * la protección del bloqueo, y apagar el bloqueo no la quita a una pantalla que sigue a la vista.
 */
class WindowProtectionTest {
    private val applied = mutableListOf<Boolean>()
    private val protection = WindowProtection { applied += it }

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
}
