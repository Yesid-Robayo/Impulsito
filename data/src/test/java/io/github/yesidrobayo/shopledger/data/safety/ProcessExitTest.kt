package io.github.yesidrobayo.shopledger.data.safety

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Qué códigos de motivo del sistema cuentan como fallo al abrir. */
class ProcessExitTest {
    private fun isFailure(reason: Int) =
        ProcessExit(42, 1_000L, exitKindOf(reason), reason, status = 0, importance = 100).isFailure

    @Test
    fun crashesAnrAndInitializationFailuresCount() {
        assertEquals(ExitKind.JAVA_CRASH, exitKindOf(4))
        assertEquals(ExitKind.NATIVE_CRASH, exitKindOf(5))
        assertEquals(ExitKind.ANR, exitKindOf(6))
        assertEquals(ExitKind.INITIALIZATION_FAILURE, exitKindOf(7))
        listOf(4, 5, 6, 7).forEach { assertTrue("El motivo $it debe contar", isFailure(it)) }
    }

    @Test
    fun unknownForcedCloseAndMemoryExitsDoNotCount() {
        // Desconocido, salida propia, señal, memoria baja y cierre a pedido de la persona.
        listOf(0, 1, 2, 3, 10).forEach {
            assertEquals(ExitKind.OTHER, exitKindOf(it))
            assertFalse("El motivo $it no debe contar", isFailure(it))
        }
    }
}
