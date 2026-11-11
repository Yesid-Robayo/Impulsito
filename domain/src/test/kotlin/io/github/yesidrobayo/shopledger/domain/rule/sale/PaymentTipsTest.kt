package io.github.yesidrobayo.shopledger.domain.rule.sale

import io.github.yesidrobayo.shopledger.domain.model.Money
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * La parte de la propina de cada pago: en proporción a lo que cubre, con el residuo en el pago
 * mayor, y nunca más que el pago. Montos inventados, escritos a mano.
 */
class PaymentTipsTest {
    private fun money(vararg minor: Long) = minor.map(::Money)

    @Test
    fun `a single payment carries all of the tip`() {
        assertEquals(money(1_000L), PaymentTips.shares(Money(1_000L), money(11_000L)))
    }

    @Test
    fun `two payments share the tip in proportion, and the larger takes the remainder`() {
        // 1000 sobre 3000 y 8000: 272,7 sube a 273, y el mayor absorbe 727.
        assertEquals(money(273L, 727L), PaymentTips.shares(Money(1_000L), money(3_000L, 8_000L)))
        // Mitades exactas.
        assertEquals(money(500L, 500L), PaymentTips.shares(Money(1_000L), money(5_500L, 5_500L)))
    }

    @Test
    fun `without a tip or without payments every share is zero`() {
        assertEquals(money(0L, 0L), PaymentTips.shares(Money.ZERO, money(3_000L, 8_000L)))
        assertEquals(emptyList<Money>(), PaymentTips.shares(Money(1_000L), emptyList()))
    }

    @Test
    fun `a payment never carries more tip than it covers`() {
        // Lo escrito todavía no alcanza: con pagos en cero, la propina iría entera al último.
        assertEquals(money(0L, 0L), PaymentTips.shares(Money(1_000L), money(0L, 0L)))
        assertEquals(money(300L), PaymentTips.shares(Money(1_000L), money(300L)))
    }

    @Test
    fun `a negative tip is a programming error`() {
        assertThrows(IllegalArgumentException::class.java) {
            PaymentTips.shares(Money(-1L), money(1_000L))
        }
    }
}
