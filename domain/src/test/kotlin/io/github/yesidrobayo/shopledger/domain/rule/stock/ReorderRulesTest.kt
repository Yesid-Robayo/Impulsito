package io.github.yesidrobayo.shopledger.domain.rule.stock

import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

// Las cifras son inventadas, salvo el ejemplo de la regla de reposición, escrito a mano.
class ReorderRulesTest {
    @Test
    fun `the example of the rule leaves 8 days and suggests 38 units`() {
        val result = suggest(out = 45_000, onHand = 12_000, min = 5_000)

        val expected = ReorderSuggestion(QuantityMilli(1_500), 8L, QuantityMilli(38_000), true)
        assertEquals(expected, result)
    }

    @Test
    fun `a purchase unit rounds the suggestion up to whole boxes`() {
        val result = suggest(out = 45_000, onHand = 12_000, min = 5_000, factor = 24_000)

        // 38 unidades piden 2 cajas de 24: 48.
        assertEquals(QuantityMilli(48_000), result.suggested)
    }

    @Test
    fun `a factor of zero is ignored`() {
        assertEquals(QuantityMilli(38_000), suggest(45_000, 12_000, 5_000, factor = 0).suggested)
    }

    @Test
    fun `without recent sales nothing is suggested`() {
        val result = suggest(out = 0, onHand = 2_000, min = 5_000)

        val expected = ReorderSuggestion(QuantityMilli.ZERO, null, QuantityMilli.ZERO, false)
        assertEquals(expected, result)
    }

    @Test
    fun `sales too small for a daily rate count as sold and only ask for the minimum`() {
        val none = suggest(out = 10, onHand = 0, min = 0)
        assertEquals(ReorderSuggestion(QuantityMilli.ZERO, null, QuantityMilli.ZERO, true), none)

        // Velocidad cero: no hay días que contar, y la fórmula pide 5 − 2 = 3 unidades.
        val toMinimum = suggest(out = 10, onHand = 2_000, min = 5_000)
        val expected = ReorderSuggestion(QuantityMilli.ZERO, null, QuantityMilli(3_000), true)
        assertEquals(expected, toMinimum)
    }

    @Test
    fun `enough stock suggests nothing, and negative stock lasts zero days`() {
        assertEquals(QuantityMilli.ZERO, suggest(out = 3_000, onHand = 50_000, min = 0).suggested)
        val short = suggest(out = 30_000, onHand = -2_000, min = 0)
        assertEquals(0L, short.daysLeft)
        assertEquals(QuantityMilli(32_000), short.suggested)
    }

    @Test
    fun `a window of no days or a negative cover is a programming error`() {
        assertThrows(IllegalArgumentException::class.java) {
            ReorderRules.suggest(QuantityMilli(1), 0L, 30L, QuantityMilli.ZERO, QuantityMilli.ZERO)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ReorderRules.suggest(
                QuantityMilli(1),
                30L,
                -1L,
                QuantityMilli.ZERO,
                QuantityMilli.ZERO,
            )
        }
    }

    private fun suggest(out: Long, onHand: Long, min: Long, factor: Long? = null) =
        ReorderRules.suggest(
            unitsOut = QuantityMilli(out),
            windowDays = ReorderRules.DEFAULT_WINDOW_DAYS,
            coverDays = ReorderRules.DEFAULT_COVER_DAYS,
            onHand = QuantityMilli(onHand),
            minStock = QuantityMilli(min),
            purchaseFactor = factor?.let { QuantityMilli(it) },
        )
}
