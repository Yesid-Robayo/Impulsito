package io.github.yesidrobayo.shopledger.domain.rule.sale

import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.rule.roundDiv
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** El recargo o descuento de cada método y lo que cubre cada pago, con valores a mano. */
class PaymentSurchargesTest {
    /** Una venta sin impuesto: el ajuste sube o baja el total en lo mismo. */
    private val withoutTax: (Money) -> Money? = { adjustment -> Money(10_000) + adjustment }

    /**
     * Una venta de 10000 cuyo ajuste paga además un impuesto agregado del 19 %, redondeado: el
     * total sube más que el recargo.
     */
    private val withAddedTax: (Money) -> Money? = { adjustment ->
        Money(10_000 + adjustment.minor + roundDiv(adjustment.minor * 1_900, 10_000))
    }

    @Test
    fun theSurchargeIsAShareOfThePaymentBeforeTheSurcharge() {
        // Un pago de 10000 con +5,00 % suma 500.
        assertEquals(Money(500), surcharge(10_000, 500))
        assertEquals(Money(-500), surcharge(10_000, -500))
        // 999 al 2,50 % son 24,975: redondea a 25, y con signo contrario a −25.
        assertEquals(Money(25), surcharge(999, 250))
        assertEquals(Money(-25), surcharge(999, -250))
        assertEquals(Money(-999), surcharge(999, -10_000))
        assertEquals(Money(999), surcharge(999, 10_000))
        assertEquals(Money.ZERO, surcharge(0, 500))
    }

    @Test
    fun aSinglePaymentCoversItsAmountAndItsSurcharge() {
        assertEquals(
            SurchargeSettlement.Settled(Money(500), listOf(Money(10_500))),
            settle(withoutTax, 10_000 to 500),
        )
        assertEquals(
            SurchargeSettlement.Settled(Money.ZERO, listOf(Money(10_000))),
            settle(withoutTax, 10_000 to 0),
        )
    }

    @Test
    fun eachPaymentCoversWhatItsOwnSurchargeAddsToTheTotal() {
        // 6000 con tarjeta a +5 % y 4000 con billetera a −2 %. Recargos 300 y −80.
        // Total con 300: 10000 + 300 + 57 = 10357. Total con 220: 10000 + 220 + 42 = 10262.
        // Tarjeta: 6000 + 10357 − 10000 = 6357. Billetera: 4000 + 10262 − 10357 = 3905.
        assertEquals(
            SurchargeSettlement.Settled(Money(220), listOf(Money(6_357), Money(3_905))),
            settle(withAddedTax, 6_000 to 500, 4_000 to -200),
        )
        // Sin impuesto, cada pago lleva justo su recargo.
        assertEquals(
            SurchargeSettlement.Settled(Money(220), listOf(Money(6_300), Money(3_920))),
            settle(withoutTax, 6_000 to 500, 4_000 to -200),
        )
        // Un método sin recargo, en cualquier lugar, cubre lo mismo que escribió.
        assertEquals(
            SurchargeSettlement.Settled(Money(300), listOf(Money(4_000), Money(6_357))),
            settle(withAddedTax, 4_000 to 0, 6_000 to 500),
        )
    }

    @Test
    fun aSaleThatCannotTakeTheAdjustmentIsNotAllowed() {
        assertEquals(SurchargeSettlement.NotAllowed, settle({ null }, 10_000 to 0))
        val onlyWithoutAdjustment: (Money) -> Money? = {
            if (it ==
                Money.ZERO
            ) {
                Money(10_000)
            } else {
                null
            }
        }
        assertEquals(SurchargeSettlement.NotAllowed, settle(onlyWithoutAdjustment, 10_000 to 500))
    }

    @Test
    fun aDiscountThatTakesMoreThanItsPaymentNamesIt() {
        // Un total que baja el doble del ajuste: −100 % sobre 3000 baja el total 6000.
        val doubled: (Money) -> Money? = { adjustment -> Money(10_000) + adjustment + adjustment }

        assertEquals(
            SurchargeSettlement.BelowZero(1),
            settle(doubled, 7_000 to 0, 3_000 to -10_000),
        )
    }

    @Test
    fun anImpossibleInputIsAProgrammingError() {
        val wrong = listOf<() -> Unit>(
            { surcharge(-1, 0) },
            { surcharge(100, 10_001) },
            { surcharge(100, -10_001) },
            { PaymentSurcharges.settle(listOf(Money(1)), emptyList(), withoutTax) },
        )
        wrong.forEach { assertThrows(IllegalArgumentException::class.java) { it() } }
    }

    private fun surcharge(amount: Long, rateBp: Long): Money =
        PaymentSurcharges.surchargeOf(Money(amount), BasisPoints(rateBp))

    private fun settle(
        totalWith: (Money) -> Money?,
        vararg payments: Pair<Int, Int>,
    ): SurchargeSettlement = PaymentSurcharges.settle(
        amounts = payments.map { Money(it.first.toLong()) },
        rates = payments.map { BasisPoints(it.second.toLong()) },
        totalWith = totalWith,
    )
}
