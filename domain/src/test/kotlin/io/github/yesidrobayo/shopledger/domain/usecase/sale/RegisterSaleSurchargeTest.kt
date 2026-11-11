package io.github.yesidrobayo.shopledger.domain.usecase.sale

import io.github.yesidrobayo.shopledger.domain.model.Limit
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductStockMode
import io.github.yesidrobayo.shopledger.domain.model.payment.PaymentMethodKind
import io.github.yesidrobayo.shopledger.domain.model.sale.Sale
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleLine
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleLineRequest
import io.github.yesidrobayo.shopledger.domain.model.sale.SalePaymentRequest
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleRequest
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * El recargo o descuento de cada método de pago: entra en el ajuste de la venta, se reparte entre
 * sus líneas y lo cubre el pago de ese método, con valores escritos a mano.
 */
class RegisterSaleSurchargeTest {
    private val world = SaleWorld().apply {
        addMethod(method())
        addMethod(method(id = CARD, kind = PaymentMethodKind.CARD, priceAdjustmentBp = 500))
        addMethod(method(id = WALLET, kind = PaymentMethodKind.WALLET, priceAdjustmentBp = -333))
        addMethod(method(id = FREE, kind = PaymentMethodKind.OTHER, priceAdjustmentBp = -10_000))
        listOf(1L, 2L, 3L).forEach {
            addProduct(product(it, stockMode = ProductStockMode.UNTRACKED, fixedUnitCost = 100))
        }
    }

    private fun lines(vararg prices: Long) = prices.mapIndexed { index, price ->
        SaleLineRequest(index + 1L, QuantityMilli.ofUnits(1L), Money(price))
    }

    private suspend fun register(request: SaleRequest): Pair<Sale, List<SaleLine>> {
        val result = world.registerSale()(request)
        val saleId = (result as RegisterSaleResult.Registered).saleId
        val lines = world.tables.lines.values.filter { it.saleId == saleId }.sortedBy { it.id }
        return world.tables.sales.getValue(saleId) to lines
    }

    private fun assertSaleAddsUp(sale: Sale, lines: List<SaleLine>) {
        val content = sale.content
        assertEquals(content.orderAdjustment, lines.sumMoney { it.share })
        assertEquals(content.total, lines.sumMoney { it.content.lineTotal })
        assertEquals(content.netTotal, lines.sumMoney { it.content.netAmount })
        val paid = world.tables.payments.values.filter { it.saleId == sale.id }
        assertEquals(
            content.amountDue,
            paid.fold(Money.ZERO) { sum, it ->
                sum + it.content.amount
            },
        )
    }

    @Test
    fun aSurchargeOfTheOnlyMethodRaisesTheSaleAndItsPayment() = runTest {
        // 10000 con +5,00 %: el ajuste suma 500, que va entero a la única línea.
        val (sale, lines) = register(SaleRequest(lines(10_000), paymentMethodId = CARD))

        assertEquals(Money(500), sale.content.orderAdjustment)
        assertEquals(Money(10_500), sale.content.total)
        assertEquals(Money(10_500), sale.content.amountDue)
        assertEquals(Money(500), lines.single().content.orderAdjustmentShare)
        assertEquals(Money(10_500), lines.single().content.lineTotal)
        assertEquals(Money(10_500), world.tables.payments.values.single().content.amount)
        assertSaleAddsUp(sale, lines)
    }

    @Test
    fun aDiscountOfTheOnlyMethodIsSharedWithCorrectRounding() = runTest {
        // 31000 con −3,33 % son −1032,3: −1032. Se reparte 7000, 6000 y 18000: −233,03 da −233,
        // −199,74 da −200, y la de mayor base absorbe −599.
        val (sale, lines) = register(
            SaleRequest(lines(7_000, 6_000, 18_000), paymentMethodId = WALLET),
        )

        assertEquals(Money(-1_032), sale.content.orderAdjustment)
        assertEquals(listOf(-233L, -200L, -599L), lines.map { it.share.minor })
        assertEquals(listOf(6_767L, 5_800L, 17_401L), lines.map { it.content.lineTotal.minor })
        assertEquals(Money(29_968), sale.content.amountDue)
        assertEquals(Money(29_968), world.tables.payments.values.single().content.amount)
        // El costo no cambia: el ajuste mueve lo cobrado, no lo que costó.
        assertEquals(Money(300), sale.content.totalCost)
        assertSaleAddsUp(sale, lines)
    }

    @Test
    fun withSeveralMethodsEachPaymentCarriesItsOwnSurcharge() = runTest {
        // 6000 con tarjeta a +5 % suma 300; 4000 en efectivo no suma nada.
        val (sale, lines) = register(
            SaleRequest(
                lines(4_000, 6_000),
                paymentMethodId = SaleWorld.CASH,
                payments = listOf(
                    SalePaymentRequest(CARD, Money(6_000)),
                    SalePaymentRequest(SaleWorld.CASH, Money(4_000)),
                ),
            ),
        )

        assertEquals(Money(300), sale.content.orderAdjustment)
        assertEquals(listOf(120L, 180L), lines.map { it.share.minor })
        val paid = world.tables.payments.values.sortedBy { it.id }.map { it.content.amount.minor }
        assertEquals(listOf(6_300L, 4_000L), paid)
        assertEquals(Money(10_300), sale.content.amountDue)
        assertSaleAddsUp(sale, lines)
    }

    @Test
    fun aDiscountOfAllLeavesASaleOfZeroWithItsPaymentOfZero() = runTest {
        val (sale, lines) = register(SaleRequest(lines(2_500, 1_500), paymentMethodId = FREE))

        assertEquals(Money(-4_000), sale.content.orderAdjustment)
        assertEquals(Money.ZERO, sale.content.total)
        assertEquals(Money.ZERO, world.tables.payments.values.single().content.amount)
        assertTrue(lines.all { it.content.lineTotal == Money.ZERO })
        assertSaleAddsUp(sale, lines)
    }

    @Test
    fun aSurchargeAboveTheDocumentLimitIsRejectedWithoutWriting() = runTest {
        val limit = Limit.DOCUMENT_TOTAL.max
        world.addProduct(product(4, stockMode = ProductStockMode.UNTRACKED))

        val result = world.registerSale()(
            SaleRequest(
                listOf(SaleLineRequest(4, QuantityMilli.ofUnits(1_000L), Money(limit / 1_000))),
                paymentMethodId = CARD,
            ),
        )

        assertEquals(RegisterSaleResult.SaleTotalTooLarge, result)
        assertEquals(0, world.commits)
        assertTrue(world.tables.sales.isEmpty())
        assertTrue(world.tables.payments.isEmpty())
    }

    private val SaleLine.share: Money get() = content.orderAdjustmentShare

    private fun <T> List<T>.sumMoney(selector: (T) -> Money): Money =
        fold(Money.ZERO) { sum, item -> sum + selector(item) }

    private companion object {
        const val CARD = 2L
        const val WALLET = 3L
        const val FREE = 4L
    }
}
