package io.github.yesidrobayo.shopledger.domain.usecase.sale

import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductStockMode
import io.github.yesidrobayo.shopledger.domain.model.payment.PaymentMethodKind
import io.github.yesidrobayo.shopledger.domain.model.sale.ForeignTender
import io.github.yesidrobayo.shopledger.domain.model.sale.PaymentRejection
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleLineRequest
import io.github.yesidrobayo.shopledger.domain.model.sale.SalePaymentRequest
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleRequest
import io.github.yesidrobayo.shopledger.domain.model.settings.CurrencyCode
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Un pago recibido en otra moneda: la venta y el pago siguen en la moneda del negocio, que en las
 * pruebas no tiene decimales, y lo recibido queda aparte con su cotización. En efectivo, lo
 * recibido se pasa a la moneda del negocio y el cambio se da en ella. Montos inventados.
 */
class RegisterSaleForeignTenderTest {
    private val world = SaleWorld().apply {
        addMethod(method())
        addMethod(method(id = CARD, kind = PaymentMethodKind.CARD))
        addProduct(product(1L, salePrice = 60_000, stockMode = ProductStockMode.UNTRACKED))
    }
    private val other = requireNotNull(CurrencyCode.of("XXX"))

    /** 2000 de una moneda de 2 decimales a 4150,50: 83010 en el libro. */
    private val twenty = ForeignTender(other, 2, 2_000, 4_150_500_000)

    private fun sale(vararg payments: SalePaymentRequest) = SaleRequest(
        lines = listOf(SaleLineRequest(1L, QuantityMilli.ofUnits(1L))),
        paymentMethodId = SaleWorld.CASH,
        payments = payments.toList(),
    )

    private fun payments() = world.tables.payments.values.sortedBy { it.id }.map { it.content }

    @Test
    fun `cash in another currency is received in the book currency and gives change in it`() =
        runTest {
            val result = world.registerSale()(
                sale(SalePaymentRequest(SaleWorld.CASH, Money(60_000), foreign = twenty)),
            )

            assertTrue(result is RegisterSaleResult.Registered)
            val payment = payments().single()
            assertEquals(Money(60_000), payment.amount)
            assertEquals(Money(83_010), payment.tenderedAmount)
            assertEquals(Money(23_010), payment.changeAmount)
            assertEquals(twenty, payment.foreignTender)
            assertEquals(Money(60_000), world.tables.sales.values.single().content.amountDue)
        }

    @Test
    fun `another method takes exactly the converted amount, and no other`() = runTest {
        world.addProduct(product(2L, salePrice = 83_010, stockMode = ProductStockMode.UNTRACKED))
        val line = SaleLineRequest(2L, QuantityMilli.ofUnits(1L))

        val wrong = world.registerSale()(
            SaleRequest(
                listOf(line),
                CARD,
                payments = listOf(SalePaymentRequest(CARD, Money(83_000), foreign = twenty)),
            ),
        )
        val right = world.registerSale()(
            SaleRequest(
                listOf(line),
                CARD,
                payments = listOf(SalePaymentRequest(CARD, Money(83_010), foreign = twenty)),
            ),
        )

        assertEquals(
            RegisterSaleResult.PaymentRejected(0, PaymentRejection.FOREIGN_MISMATCH),
            wrong,
        )
        assertTrue(right is RegisterSaleResult.Registered)
        val payment = payments().single()
        assertEquals(Money(83_010), payment.amount)
        assertNull(payment.tenderedAmount)
        assertEquals(twenty, payment.foreignTender)
    }

    @Test
    fun `a written tendered amount must match the converted one`() = runTest {
        val result = world.registerSale()(
            sale(
                SalePaymentRequest(
                    SaleWorld.CASH,
                    Money(60_000),
                    tenderedAmount = Money(83_000),
                    foreign = twenty,
                ),
            ),
        )

        assertEquals(
            RegisterSaleResult.PaymentRejected(0, PaymentRejection.FOREIGN_MISMATCH),
            result,
        )
        assertTrue(world.tables.sales.isEmpty())
    }

    @Test
    fun `the business currency, a bad shape or too little cash are rejected without writing`() =
        runTest {
            val cases = listOf(
                twenty.copy(currency = requireNotNull(CurrencyCode.of("XTS"))) to
                    PaymentRejection.FOREIGN_SAME_CURRENCY,
                twenty.copy(rateMicro = 0) to PaymentRejection.INVALID_FOREIGN,
                twenty.copy(decimals = 5) to PaymentRejection.INVALID_FOREIGN,
                twenty.copy(amountMinor = 0) to PaymentRejection.INVALID_FOREIGN,
                twenty.copy(
                    amountMinor = 1_000_000_000_000_000,
                    rateMicro = 1_000_000_000_000_000,
                ) to
                    PaymentRejection.INVALID_FOREIGN,
                twenty.copy(amountMinor = 1_000) to PaymentRejection.TENDERED_BELOW_AMOUNT,
            )
            for ((tender, reason) in cases) {
                val result = world.registerSale()(
                    sale(SalePaymentRequest(SaleWorld.CASH, Money(60_000), foreign = tender)),
                )
                assertEquals(
                    tender.toString(),
                    RegisterSaleResult.PaymentRejected(0, reason),
                    result,
                )
            }
            assertTrue(world.tables.sales.isEmpty())
        }

    private companion object {
        const val CARD = 2L
    }
}
