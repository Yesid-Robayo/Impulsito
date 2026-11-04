package io.github.yesidrobayo.shopledger.domain.model.purchase

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PurchaseForeignCurrencyTest {
    @Test
    fun `accepts a three letter code and a positive rate`() {
        // Un código cualquiera de tres letras: la regla no conoce ninguna moneda en particular.
        assertEquals("XTS", PurchaseForeignCurrency("XTS", rateMicro = 1L).currencyCode)
    }

    @Test
    fun `rejects a code that is not three capital letters`() {
        for (code in listOf("", "XT", "XTSA", "xts", "XT1", "X[S")) {
            assertThrows(code, IllegalArgumentException::class.java) {
                PurchaseForeignCurrency(code, rateMicro = 1_000_000L)
            }
        }
    }

    @Test
    fun `rejects a rate that is not positive`() {
        for (rate in listOf(0L, -1L)) {
            assertThrows(IllegalArgumentException::class.java) {
                PurchaseForeignCurrency("XTS", rateMicro = rate)
            }
        }
    }
}
