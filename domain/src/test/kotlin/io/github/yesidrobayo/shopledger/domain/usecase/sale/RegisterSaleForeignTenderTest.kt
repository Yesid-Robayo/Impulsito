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
        addMethod(method(id = SURCHARGED, kind = PaymentMethodKind.CARD, priceAdjustmentBp = 500))
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

    /** Una venta de una unidad de un producto nuevo de [price], con [payments]. */
    private fun sell(
        productId: Long,
        price: Long,
        vararg payments: SalePaymentRequest,
    ): SaleRequest {
        world.addProduct(
            product(productId, salePrice = price, stockMode = ProductStockMode.UNTRACKED),
        )
        return SaleRequest(
            listOf(SaleLineRequest(productId, QuantityMilli.ofUnits(1L))),
            payments.first().paymentMethodId,
            payments = payments.toList(),
        )
    }

    @Test
    fun `another method takes exactly the converted amount, and no other`() = runTest {
        world.addProduct(product(2L, salePrice = 83_010, stockMode = ProductStockMode.UNTRACKED))
        val line = SaleLineRequest(2L, QuantityMilli.ofUnits(1L))

        // Una venta de 83000 pagada con lo que convertido da 83010.
        val wrong = world.registerSale()(
            sell(3L, 83_000, SalePaymentRequest(CARD, Money(83_000), foreign = twenty)),
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

    @Test
    fun `with a surcharge, what was handed over covers the surcharged payment`() = runTest {
        // 79057 con tarjeta a +5,00 %: el recargo es 3952,85, que da 3953, y el pago cubre
        // 83010, justo los 2000 entregados a 4150,50.
        val result = world.registerSale()(
            sell(2L, 79_057, SalePaymentRequest(SURCHARGED, Money(79_057), foreign = twenty)),
        )

        assertTrue(result is RegisterSaleResult.Registered)
        val payment = payments().single()
        assertEquals(Money(83_010), payment.amount)
        assertNull(payment.tenderedAmount)
        assertEquals(twenty, payment.foreignTender)
        val saved = world.tables.sales.values.single().content
        assertEquals(Money(3_953), saved.orderAdjustment)
        assertEquals(Money(83_010), saved.amountDue)
    }

    @Test
    fun `with a surcharge, the converted amount written before it is rejected without writing`() =
        runTest {
            // Escribir 83010 antes del recargo haría que el pago cubra 87161, no lo entregado.
            val result = world.registerSale()(
                sell(2L, 83_010, SalePaymentRequest(SURCHARGED, Money(83_010), foreign = twenty)),
            )

            assertEquals(
                RegisterSaleResult.PaymentRejected(0, PaymentRejection.FOREIGN_MISMATCH),
                result,
            )
            assertTrue(world.tables.sales.isEmpty())
            assertTrue(world.tables.payments.isEmpty())
        }

    @Test
    fun `with a surcharge and another payment, the rest of the sale goes to the other one`() =
        runTest {
            // 100000: la tarjeta escribe 79057 y cubre 83010; el efectivo, 20943 sin recargo.
            val result = world.registerSale()(
                sell(
                    2L,
                    100_000,
                    SalePaymentRequest(SURCHARGED, Money(79_057), foreign = twenty),
                    SalePaymentRequest(SaleWorld.CASH, Money(20_943)),
                ),
            )

            assertTrue(result is RegisterSaleResult.Registered)
            assertEquals(listOf(Money(83_010), Money(20_943)), payments().map { it.amount })
            assertEquals(Money(103_953), world.tables.sales.values.single().content.amountDue)
        }

    private companion object {
        const val CARD = 2L
        const val SURCHARGED = 3L
    }
}
