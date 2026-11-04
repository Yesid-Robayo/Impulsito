package io.github.yesidrobayo.shopledger.domain.model.settings

import kotlin.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BusinessCurrencyTest {
    @Test
    fun `a code is three capital letters from A to Z`() {
        // XTS es el código que ISO 4217 reserva para pruebas: no es la moneda de nadie.
        assertEquals("XTS", CurrencyCode.of("XTS")?.code)
        assertEquals("XTS", CurrencyCode.of("XTS").toString())
        // Lo mismo que rechaza la guarda de la base: otra longitud, minúsculas, cifras o letras con
        // tilde, que `upper` de SQLite no cambia.
        for (broken in listOf("", "XT", "XTSS", "xts", "Xts", "X1S", "XÉS", "X S")) {
            assertNull(broken, CurrencyCode.of(broken))
        }
    }

    @Test
    fun `the business is born without a currency until the person confirms one`() {
        assertEquals("XXX", CurrencyCode.UNCONFIRMED.code)
        assertEquals(CurrencyCode.UNCONFIRMED, CurrencyCode.of("XXX"))
        assertFalse(CurrencyCode.UNCONFIRMED.isConfirmed)
        assertTrue(requireNotNull(CurrencyCode.of("XTS")).isConfirmed)
    }

    @Test
    fun `the currency is locked from the first record with money`() {
        val code = requireNotNull(CurrencyCode.of("XTS"))
        val open = BusinessCurrency(code, decimals = 2, symbol = null, lockedAt = null)

        assertFalse(open.isLocked)
        assertTrue(open.copy(lockedAt = Instant.fromEpochMilliseconds(1L)).isLocked)
    }

    @Test
    fun `the stored tax modes never change`() {
        // Son los valores que admite la guarda de la base: renombrar uno dejaría ajustes sin leer.
        assertEquals(listOf("NONE", "INCLUDED", "ADDED"), TaxMode.entries.map { it.name })
    }
}
