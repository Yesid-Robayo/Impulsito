package io.github.yesidrobayo.shopledger.domain.rule

import io.github.yesidrobayo.shopledger.domain.model.Money
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cada caso dice qué se lee con cada cantidad de decimales. La cuenta no los conoce: el mismo
 * entero da el mismo redondeo en cualquier moneda, y solo cambia cómo se muestra.
 */
class CashRoundingTest {
    @Test
    fun `examples of the rounding table`() {
        // Decimales, paso, parte en efectivo, redondeo y lo que se cobra.
        // Dos decimales y paso 5 (0,05): 12,32 se cobra 12,30.
        assertRounding(step = 5L, cashPart = 1232L, amount = -2L, charged = 1230L)
        // 12,33 se cobra 12,35.
        assertRounding(step = 5L, cashPart = 1233L, amount = 2L, charged = 1235L)
        // 12,37 se cobra 12,35.
        assertRounding(step = 5L, cashPart = 1237L, amount = -2L, charged = 1235L)
        // Dos decimales y paso 10 (0,10): 12,35 es un empate y sube a 12,40.
        assertRounding(step = 10L, cashPart = 1235L, amount = 5L, charged = 1240L)
        // Sin decimales y paso 50: 1234 se cobra 1250.
        assertRounding(step = 50L, cashPart = 1234L, amount = 16L, charged = 1250L)
        // Tres decimales y paso 0: 1,234 se cobra igual.
        assertRounding(step = 0L, cashPart = 1234L, amount = 0L, charged = 1234L)
    }

    @Test
    fun `step zero never rounds in a currency without decimals or with two`() {
        // Sin decimales: 98765 se cobra igual.
        assertRounding(step = 0L, cashPart = 98_765L, amount = 0L, charged = 98_765L)
        // Con dos decimales: 19,99 se cobra igual.
        assertRounding(step = 0L, cashPart = 1999L, amount = 0L, charged = 1999L)
    }

    @Test
    fun `step five in a currency without decimals or with two`() {
        // Sin decimales, monedas de 5: 98763 (exacto 19752,6 pasos) se cobra 98765.
        assertRounding(step = 5L, cashPart = 98_763L, amount = 2L, charged = 98_765L)
        // Con dos decimales, monedas de 0,05: 12,32 se cobra 12,30 y 12,38 se cobra 12,40.
        assertRounding(step = 5L, cashPart = 1232L, amount = -2L, charged = 1230L)
        assertRounding(step = 5L, cashPart = 1238L, amount = 2L, charged = 1240L)
    }

    @Test
    fun `step fifty in a currency without decimals or with two`() {
        // Sin decimales, monedas de 50: 1224 baja a 1200, y 1225, un empate, sube a 1250.
        assertRounding(step = 50L, cashPart = 1224L, amount = -24L, charged = 1200L)
        assertRounding(step = 50L, cashPart = 1225L, amount = 25L, charged = 1250L)
        // Con dos decimales, monedas de 0,50: 73,80 sube a 74,00 y 73,24 baja a 73,00.
        assertRounding(step = 50L, cashPart = 7380L, amount = 20L, charged = 7400L)
        assertRounding(step = 50L, cashPart = 7324L, amount = -24L, charged = 7300L)
    }

    @Test
    fun `a sale paid without cash is not rounded`() {
        // Pagada solo con tarjeta: la parte en efectivo es 0.
        assertRounding(step = 5L, cashPart = 0L, amount = 0L, charged = 0L)
        assertRounding(step = 50L, cashPart = 0L, amount = 0L, charged = 0L)
    }

    @Test
    fun `only the cash part of a mixed payment is rounded`() {
        // Total 5280, de los que 3001 van con tarjeta: el efectivo, 2279, sube a 2280. Lo que se
        // cobra en total es 5281, y la tarjeta conserva su monto exacto.
        assertRounding(step = 5L, cashPart = 2279L, amount = 1L, charged = 2280L)
    }

    @Test
    fun `a cash part that is already a multiple is not rounded`() {
        assertRounding(step = 50L, cashPart = 1250L, amount = 0L, charged = 1250L)
    }

    @Test
    fun `rounding at the caps stays within half a step`() {
        val documentCap = 1_000_000_000_000_000L
        val unitMoneyCap = 1_000_000_000_000L

        assertRounding(
            step = unitMoneyCap,
            cashPart = documentCap,
            amount = 0L,
            charged = documentCap,
        )
        assertRounding(
            step = unitMoneyCap,
            cashPart = documentCap - 1L,
            amount = 1L,
            charged = documentCap,
        )
        // Medio paso es un empate: sube y el redondeo vale justo medio paso.
        assertRounding(
            step = unitMoneyCap,
            cashPart = 500_000_000_000L,
            amount = 500_000_000_000L,
            charged = unitMoneyCap,
        )
    }

    @Test
    fun `the charged amount is the nearest multiple on seeded cases`() {
        val random = Random(20_261_103)
        repeat(10_000) {
            val cashPart = random.nextLong(0L, 1_000_000_000_000_001L)
            val step = random.nextLong(1L, 1_000_000_000_001L)
            val amount = CashRounding.amount(Money(cashPart), Money(step)).minor
            val case = "efectivo $cashPart, paso $step"

            assertEquals(case, 0L, (cashPart + amount) % step)
            assertTrue(case, 2 * amount <= step && -2 * amount < step)
        }
    }

    @Test
    fun `a negative step is a programming error`() {
        assertThrows(IllegalArgumentException::class.java) {
            CashRounding.amount(Money(1234L), Money(-5L))
        }
    }

    @Test
    fun `a rounded amount that does not fit in a long fails instead of wrapping`() {
        // 2^63 − 1 con paso 10 sube a 9223372036854775810.
        assertThrows(ArithmeticException::class.java) {
            CashRounding.amount(Money(Long.MAX_VALUE), Money(10L))
        }
    }

    private fun assertRounding(step: Long, cashPart: Long, amount: Long, charged: Long) {
        val rounding = CashRounding.amount(Money(cashPart), Money(step))

        assertEquals("paso $step, efectivo $cashPart", Money(amount), rounding)
        assertEquals("paso $step, efectivo $cashPart", Money(charged), Money(cashPart) + rounding)
    }
}
