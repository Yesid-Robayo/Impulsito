package io.github.yesidrobayo.shopledger.domain.usecase.sale

import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductStockMode
import io.github.yesidrobayo.shopledger.domain.model.payment.PaymentMethodKind
import io.github.yesidrobayo.shopledger.domain.model.sale.NewSale
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleLineRequest
import io.github.yesidrobayo.shopledger.domain.model.sale.SalePaymentRequest
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleRequest
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * La propina al confirmar: va en lo que se cobra y paga la comisión de su método, pero no su
 * recargo, y nunca entra en lo que cobran las líneas ni en la ganancia. Solo se acepta con las
 * propinas encendidas. Los ejemplos de las reglas de negocio, escritos a mano.
 */
class RegisterSaleTipTest {
    private fun world(tipsEnabled: Boolean = true) = SaleWorld().apply {
        addMethod(method())
        addMethod(method(id = CARD, kind = PaymentMethodKind.CARD, feeBp = 300L))
        addMethod(
            method(
                id = SURCHARGED,
                kind = PaymentMethodKind.CARD,
                systemKey = null,
                name = "Invented card",
                priceAdjustmentBp = 500L,
            ),
        )
        settings.settings.value = settings.settings.value.let {
            it.copy(sales = it.sales.copy(tipsEnabled = tipsEnabled))
        }
        addProduct(
            product(
                1L,
                salePrice = 10_000L,
                stockMode = ProductStockMode.UNTRACKED,
                fixedUnitCost = 4_000L,
            ),
        )
    }

    private fun sale(
        tip: Long,
        methodId: Long = SaleWorld.CASH,
        vararg payments: Pair<Long, Long>,
    ) = SaleRequest(
        lines = listOf(SaleLineRequest(1L, QuantityMilli.ofUnits(1L))),
        paymentMethodId = methodId,
        payments = payments.map { (method, amount) ->
            SalePaymentRequest(method, Money(amount))
        },
        tipAmount = Money(tip),
    )

    private suspend fun SaleWorld.registered(request: SaleRequest): Pair<NewSale, List<Long>> {
        val id = (registerSale()(request) as RegisterSaleResult.Registered).saleId
        val payments = tables.payments.values.filter { it.saleId == id }.sortedBy { it.id }
        return tables.sales.getValue(id).content to payments.map { it.content.amount.minor }
    }

    private fun NewSale.profit(): Money = netTotal - totalCost

    @Test
    fun `the tip example of the business rules, with a card at 3 percent`() = runTest {
        val world = world()

        // Venta de 10000 con propina de 1000 y tarjeta al 3,00 %: se cobran 11000, la comisión es
        // 330 y las ventas netas siguen en 10000.
        val (sale, payments) = world.registered(sale(1_000L, CARD))

        assertEquals(Money(11_000L), sale.amountDue)
        assertEquals(Money(1_000L), sale.tipAmount)
        assertEquals(Money(10_000L), sale.netTotal)
        assertEquals(listOf(11_000L), payments)
        assertEquals(Money(330L), world.tables.payments.values.single().content.feeAmount)
    }

    @Test
    fun `the tip pays no surcharge of its method`() = runTest {
        val world = world()

        // 10000 + propina de 1000 con tarjeta a +5 %: el recargo es 5 % de 10000, 500, y no de
        // 11000. Se cobran 11500, y las ventas netas quedan en 10500.
        val (sale, payments) = world.registered(sale(1_000L, SURCHARGED))

        assertEquals(Money(500L), sale.orderAdjustment)
        assertEquals(Money(10_500L), sale.netTotal)
        assertEquals(Money(11_500L), sale.amountDue)
        assertEquals(listOf(11_500L), payments)
    }

    @Test
    fun `the tip changes neither sales nor profit, also with two payments and a surcharge`() =
        runTest {
            val tipped = world().registered(
                sale(1_000L, SaleWorld.CASH, SURCHARGED to 5_500L, SaleWorld.CASH to 5_500L),
            ).first
            val plain = world().registered(
                sale(0L, SaleWorld.CASH, SURCHARGED to 5_000L, SaleWorld.CASH to 5_000L),
            ).first

            // Cada pago lleva 500 de propina; el recargo, 5 % de 5000, es 250 en las dos.
            assertEquals(Money(10_250L), tipped.netTotal)
            assertEquals(plain.netTotal, tipped.netTotal)
            assertEquals(plain.profit(), tipped.profit())
            assertEquals(plain.orderAdjustment, tipped.orderAdjustment)
            assertEquals(plain.amountDue + Money(1_000L), tipped.amountDue)
        }

    @Test
    fun `written payments cover the sale and the tip, or nothing is written`() = runTest {
        val world = world()

        val result = world.registerSale()(sale(1_000L, SaleWorld.CASH, SaleWorld.CASH to 10_000L))

        assertEquals(RegisterSaleResult.PaymentsDoNotMatch(Money(11_000L), Money(10_000L)), result)
        assertTrue(world.tables.sales.isEmpty())
    }

    @Test
    fun `a tip with tips turned off, negative or past the limit is not accepted`() = runTest {
        val cases = listOf(
            world(tipsEnabled = false) to 1_000L,
            world() to -1L,
            world() to 1_000_000_000_000_001L,
        )
        for ((world, tip) in cases) {
            val result = world.registerSale()(sale(tip))

            assertEquals("propina $tip", RegisterSaleResult.TipNotAccepted, result)
            assertTrue(world.tables.sales.isEmpty())
        }
    }

    @Test
    fun `a zero tip is accepted with tips turned off`() = runTest {
        val (sale, _) = world(tipsEnabled = false).registered(sale(0L))

        assertEquals(Money.ZERO, sale.tipAmount)
        assertEquals(Money(10_000L), sale.amountDue)
    }

    @Test
    fun `a tip that passes the document limit with the sale is too large`() = runTest {
        val world = world()

        val result = world.registerSale()(sale(999_999_999_999_999L))

        assertEquals(RegisterSaleResult.SaleTotalTooLarge, result)
    }

    private companion object {
        const val CARD = 2L
        const val SURCHARGED = 3L
    }
}
