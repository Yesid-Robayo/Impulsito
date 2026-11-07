package io.github.yesidrobayo.shopledger.data.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * El código de error que el SQLite empaquetado pone al comienzo de su mensaje. Solo se lee ese
 * número: el resto del mensaje puede llevar rutas y nunca decide nada.
 */
class SqliteFailureTest {
    @Test
    fun `the primary code comes from the start of the message`() {
        assertEquals(11, SqliteFailure.primaryCode(failure("Error code: 11, message: dañada")))
        assertEquals(26, SqliteFailure.primaryCode(failure("Error code: 26, message: x")))
        // Un código extendido guarda el principal en su byte bajo: 267 es una tabla virtual dañada.
        assertEquals(11, SqliteFailure.primaryCode(failure("Error code: 267, message: x")))
    }

    @Test
    fun `a message without the code has no code`() {
        assertNull(SqliteFailure.primaryCode(RuntimeException()))
        assertNull(SqliteFailure.primaryCode(failure("database disk image is malformed")))
        assertNull(SqliteFailure.primaryCode(failure("Error code: , message: x")))
        // El número tiene que ir al comienzo: uno dentro del texto, quizá de una ruta, no cuenta.
        assertNull(SqliteFailure.primaryCode(failure("/data/Error code: 11")))
    }

    @Test
    fun `damage and a file that is not a database are corruption`() {
        assertTrue(SqliteFailure.isCorruption(failure("Error code: 11, message: x")))
        assertTrue(SqliteFailure.isCorruption(failure("Error code: 26, message: x")))
        assertFalse(SqliteFailure.isCorruption(failure("Error code: 13, message: x")))
        assertFalse(SqliteFailure.isCorruption(failure("Error code: 14, message: x")))
        assertFalse(SqliteFailure.isCorruption(RuntimeException()))
    }

    @Test
    fun `a full disk is only the full code`() {
        assertTrue(SqliteFailure.isStorageFull(failure("Error code: 13, message: x")))
        assertFalse(SqliteFailure.isStorageFull(failure("Error code: 11, message: x")))
        assertFalse(SqliteFailure.isStorageFull(RuntimeException()))
    }

    private fun failure(message: String) = RuntimeException(message)
}
