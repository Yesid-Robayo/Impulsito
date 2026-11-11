package io.github.yesidrobayo.shopledger.domain.usecase.sale

import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductStockMode
import io.github.yesidrobayo.shopledger.domain.model.payment.PaymentMethodKind
import io.github.yesidrobayo.shopledger.domain.model.sale.PaymentRejection
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleLineRequest
import io.github.yesidrobayo.shopledger.domain.model.sale.SalePaymentRequest
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleRequest
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * El efectivo al confirmar: cada pago en efectivo se redondea al paso del negocio, ya con su
 * recargo, la venta guarda la suma de los redondeos y cobra el total más ella, y lo recibido y el
 * cambio quedan en el pago. Los ejemplos de las reglas de negocio, escritos a mano.
 */
class RegisterSaleCashTest {
    private val world = SaleWorld().apply {
        addMethod(method())
        addMethod(method(id = CARD, kind = PaymentMethodKind.CARD))
        addMethod(method(id = SECOND_CASH, systemKey = null, name = "Second drawer"))
        addMethod(
            method(
                id = CASH_DISCOUNT,
                systemKey = null,
                name = "Cash promo",
                priceAdjustmentBp = -500,
            ),
        )
    }

    private fun step(minor: Long) {
        world.settings.settings.value = world.settings.settings.value.let {
            it.copy(regional = it.regional.copy(cashRounding = Money(minor)))
        }
    }

    private fun priced(price: Long) = world.addProduct(
        product(1L, salePrice = price, stockMode = ProductStockMode.UNTRACKED),
    )

    private fun sale(methodId: Long = SaleWorld.CASH, vararg payments: SalePaymentRequest) =
        SaleRequest(
            lines = listOf(SaleLineRequest(1L, QuantityMilli.ofUnits(1L))),
            paymentMethodId = methodId,
            payments = payments.toList(),
        )

    private suspend fun register(request: SaleRequest): Long {
        val result = world.registerSale()(request)
        return (result as RegisterSaleResult.Registered).saleId
    }

    private fun header(saleId: Long) = world.tables.sales.getValue(saleId).content

    private fun payments(saleId: Long) = world.tables.payments.values.filter {
        it.saleId == saleId
    }.sortedBy { it.id }.map { it.content }

    @Test
    fun `the cash rounding table of the business rules, in a usual cash sale`() = runTest {
        // Paso, parte en efectivo y lo que se cobra, de la tabla del redondeo de efectivo.
        val cases = listOf(
            Triple(5L, 1_232L, 1_230L),
            Triple(5L, 1_233L, 1_235L),
            Triple(5L, 1_237L, 1_235L),
            Triple(10L, 1_235L, 1_240L),
            Triple(50L, 1_234L, 1_250L),
            Triple(0L, 1_234L, 1_234L),
        )
        for ((minor, price, charged) in cases) {
            val world = SaleWorld().apply { addMethod(method()) }
            world.settings.settings.value = world.settings.settings.value.let {
                it.copy(regional = it.regional.copy(cashRounding = Money(minor)))
            }
            world.addProduct(product(1L, salePrice = price, stockMode = ProductStockMode.UNTRACKED))

            val result = world.registerSale()(sale())

            val id = (result as RegisterSaleResult.Registered).saleId
            val sale = world.tables.sales.getValue(id).content
            assertEquals("paso $minor y $price", Money(price), sale.total)
            assertEquals("paso $minor y $price", Money(charged), sale.amountDue)
            assertEquals("paso $minor y $price", Money(charged - price), sale.cashRoundingAmount)
            assertEquals(
                Money(charged),
                world.tables.payments.values.single().content.amount,
            )
        }
    }

    @Test
    fun `a card pays the exact amount and is never rounded`() = runTest {
        step(50L)
        priced(1_234L)

        val id = register(sale(methodId = CARD))

        assertEquals(Money(1_234L), header(id).amountDue)
        assertEquals(Money.ZERO, header(id).cashRoundingAmount)
    }

    @Test
    fun `two cash payments are each rounded on their own`() = runTest {
        step(5L)
        priced(2_464L)

        // 1232 baja a 1230 y 1232 también: el redondeo de la venta es −4.
        val id = register(
            sale(
                SaleWorld.CASH,
                SalePaymentRequest(SaleWorld.CASH, Money(1_232L)),
                SalePaymentRequest(SECOND_CASH, Money(1_232L)),
            ),
        )

        assertEquals(listOf(1_230L, 1_230L), payments(id).map { it.amount.minor })
        assertEquals(Money(-4L), header(id).cashRoundingAmount)
        assertEquals(Money(2_460L), header(id).amountDue)
    }

    @Test
    fun `cash is rounded after the discount of its method`() = runTest {
        step(10L)
        priced(1_000L)

        // −5 % sobre 1000 deja 950, que ya es múltiplo de 10. Sin el descuento, 1000.
        val id = register(sale(CASH_DISCOUNT))

        assertEquals(Money(950L), header(id).amountDue)
        assertEquals(Money.ZERO, header(id).cashRoundingAmount)
    }

    @Test
    fun `the change is what was received minus the rounded cash payment`() = runTest {
        step(50L)
        priced(1_234L)

        val id = register(
            sale(
                SaleWorld.CASH,
                SalePaymentRequest(SaleWorld.CASH, Money(1_234L), tenderedAmount = Money(2_000L)),
            ),
        )

        val cash = payments(id).single()
        assertEquals(Money(1_250L), cash.amount)
        assertEquals(Money(2_000L), cash.tenderedAmount)
        assertEquals(Money(750L), cash.changeAmount)
    }

    @Test
    fun `exactly what is charged gives no change, and nothing received gives none either`() =
        runTest {
            priced(1_000L)

            val exact = register(
                sale(
                    SaleWorld.CASH,
                    SalePaymentRequest(
                        SaleWorld.CASH,
                        Money(1_000L),
                        tenderedAmount = Money(1_000L),
                    ),
                ),
            )
            val none = register(sale())

            assertEquals(Money.ZERO, payments(exact).single().changeAmount)
            assertNull(payments(none).single().tenderedAmount)
            assertNull(payments(none).single().changeAmount)
        }

    @Test
    fun `what was received below the rounded cash payment is rejected and nothing is written`() =
        runTest {
            step(50L)
            priced(1_234L)

            // Se cobran 1250: 1240 recibidos no alcanzan, aunque pasan de 1234.
            val result = world.registerSale()(
                sale(
                    SaleWorld.CASH,
                    SalePaymentRequest(
                        SaleWorld.CASH,
                        Money(1_234L),
                        tenderedAmount = Money(1_240L),
                    ),
                ),
            )

            assertEquals(
                RegisterSaleResult.PaymentRejected(0, PaymentRejection.TENDERED_BELOW_AMOUNT),
                result,
            )
            assertTrue(world.tables.sales.isEmpty())
            assertTrue(world.tables.payments.isEmpty())
        }

    @Test
    fun `what was received only exists in cash`() = runTest {
        priced(1_000L)

        val result = world.registerSale()(
            sale(
                SaleWorld.CASH,
                SalePaymentRequest(CARD, Money(1_000L), tenderedAmount = Money(1_000L)),
            ),
        )

        assertEquals(
            RegisterSaleResult.PaymentRejected(0, PaymentRejection.TENDER_NOT_CASH),
            result,
        )
        assertTrue(world.tables.sales.isEmpty())
    }

    @Test
    fun `what was received out of range is rejected before reading anything`() = runTest {
        priced(1_000L)

        val result = world.registerSale()(
            sale(
                SaleWorld.CASH,
                SalePaymentRequest(SaleWorld.CASH, Money(1_000L), tenderedAmount = Money(-1L)),
            ),
        )

        assertEquals(
            RegisterSaleResult.PaymentRejected(0, PaymentRejection.INVALID_TENDERED),
            result,
        )
    }

    @Test
    fun `a rounding that passes the document limit is too large`() = runTest {
        // 999999999999999 con un paso de 6 × 10^11 sube a 1667 pasos: más que el tope.
        step(600_000_000_000L)
        world.addProduct(
            product(1L, salePrice = 1_000_000_000_000L, stockMode = ProductStockMode.UNTRACKED),
        )
        world.addProduct(
            product(2L, salePrice = 999_999_999_999L, stockMode = ProductStockMode.UNTRACKED),
        )

        val result = world.registerSale()(
            SaleRequest(
                lines = listOf(
                    SaleLineRequest(1L, QuantityMilli.ofUnits(999L)),
                    SaleLineRequest(2L, QuantityMilli.ofUnits(1L)),
                ),
                paymentMethodId = SaleWorld.CASH,
            ),
        )

        assertEquals(RegisterSaleResult.SaleTotalTooLarge, result)
    }

    private companion object {
        const val CARD = 2L
        const val SECOND_CASH = 3L
        const val CASH_DISCOUNT = 4L
    }
}
