package io.github.yesidrobayo.shopledger.domain.rule.sale

import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.sale.ForeignTender
import io.github.yesidrobayo.shopledger.domain.model.sale.PaymentRejection
import io.github.yesidrobayo.shopledger.domain.model.settings.CurrencyCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Lo recibido en otra moneda, con la tabla de la regla del cobro en otra moneda. */
class ForeignTenderRulesTest {
    private val book = requireNotNull(CurrencyCode.of("XTS"))
    private val other = requireNotNull(CurrencyCode.of("XXX"))

    private fun tender(decimals: Int, amount: Long, rate: Long) =
        ForeignTender(other, decimals, amount, rate)

    @Test
    fun `the table of the rule, with books of 0, 3 and 2 decimals`() {
        assertEquals(
            Money(83_010),
            ForeignTenderRules.toBook(tender(2, 2_000, 4_150_500_000), book, 0),
        )
        assertEquals(Money(3_813), ForeignTenderRules.toBook(tender(2, 1_250, 305_000), book, 3))
        assertEquals(Money(3_100), ForeignTenderRules.toBook(tender(0, 5_000, 6_200), book, 2))
    }

    @Test
    fun `the business currency or an amount over the limit gives nothing`() {
        assertNull(
            ForeignTenderRules.toBook(
                tender(2, 2_000, 4_150_500_000).copy(currency = book),
                book,
                0,
            ),
        )
        assertNull(
            ForeignTenderRules.toBook(
                tender(0, 1_000_000_000_000_000, 1_000_000_000_000_000),
                book,
                4,
            ),
        )
    }

    @Test
    fun `decimals, amount and rate must be in range`() {
        val good = tender(2, 2_000, 4_150_500_000)
        assertNull(ForeignTenderRules.shapeRejection(good))
        for (bad in listOf(
            good.copy(decimals = -1),
            good.copy(decimals = 5),
            good.copy(amountMinor = 0),
            good.copy(amountMinor = 1_000_000_000_000_001),
            good.copy(rateMicro = 0),
        )) {
            assertEquals(
                bad.toString(),
                PaymentRejection.INVALID_FOREIGN,
                ForeignTenderRules.shapeRejection(bad),
            )
        }
    }
}
