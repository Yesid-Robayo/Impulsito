package io.github.yesidrobayo.shopledger.domain.rule.sale

import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
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

    /** Lo que cubre un pago escrito con tarjeta a [bp] puntos básicos, sin impuesto. */
    private fun withRate(bp: Long): (Money) -> Money? = { written ->
        written + PaymentSurcharges.surchargeOf(written, BasisPoints(bp))
    }

    @Test
    fun `the written amount that covers exactly what was handed over, surcharge included`() {
        // 2000 a 4150,50 son 83010. Con +5,00 %, 79057 suma 3952,85: 3953, y cubre 83010.
        assertEquals(Money(79_057), ForeignTenderRules.writtenFor(Money(83_010), withRate(500)))
        // Con −3,00 %, 85577 resta 2567,31: 2567, y cubre 83010.
        assertEquals(Money(85_577), ForeignTenderRules.writtenFor(Money(83_010), withRate(-300)))
        // Sin recargo, el monto es lo convertido.
        assertEquals(Money(83_010), ForeignTenderRules.writtenFor(Money(83_010), withRate(0)))
    }

    @Test
    fun `an amount the surcharge jumps over, or a sale that takes none, gives nothing`() {
        // 79049 cubre 83001 y 79050 cubre 83003: ningún monto cubre 83002.
        assertEquals(Money(83_001), withRate(500)(Money(79_049)))
        assertEquals(Money(83_003), withRate(500)(Money(79_050)))
        assertNull(ForeignTenderRules.writtenFor(Money(83_002), withRate(500)))
        assertNull(ForeignTenderRules.writtenFor(Money(83_010)) { null })
        // Lo que la venta no admite cuenta como demasiado: se busca abajo.
        val capped: (Money) -> Money? = { if (it > Money(100_000)) null else it }
        assertEquals(Money(83_010), ForeignTenderRules.writtenFor(Money(83_010), capped))
    }
}
