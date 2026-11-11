package io.github.yesidrobayo.shopledger.domain.usecase.sale

import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.Checked
import io.github.yesidrobayo.shopledger.domain.model.DocumentStatus
import io.github.yesidrobayo.shopledger.domain.model.Limit
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.ProfitTotals
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.VoidReason
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductStockMode
import io.github.yesidrobayo.shopledger.domain.model.payment.PaymentMethodKind
import io.github.yesidrobayo.shopledger.domain.model.sale.PaymentRejection
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleLineRequest
import io.github.yesidrobayo.shopledger.domain.model.sale.SalePaymentRequest
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleRequest
import io.github.yesidrobayo.shopledger.domain.rule.MarginCalculator
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * La comisión y lo que de verdad llega de cada pago: calculada con los datos del método o escrita
 * en la venta, con su acreditación, y cómo baja la ganancia final y no el margen. Valores a mano.
 */
class RegisterSalePaymentFeesTest {
    private val world = SaleWorld().apply {
        addMethod(method())
        addMethod(
            method(
                id = CARD,
                kind = PaymentMethodKind.CARD,
                feeBp = 299,
                feeFixed = 15,
                settlementDays = 2,
            ),
        )
        addMethod(method(id = TERMINAL, kind = PaymentMethodKind.CARD, feeBp = 275))
        addProduct(
            product(SOAP, onHandMilli = 10_000, averageCost = 2_500, stockValue = 25_000),
        )
        addProduct(product(SERVICE, stockMode = ProductStockMode.UNTRACKED, fixedUnitCost = 400))
    }

    private fun line(productId: Long, price: Long) =
        SaleLineRequest(productId, QuantityMilli.ofUnits(1L), Money(price))

    private suspend fun sell(request: SaleRequest): Long =
        (world.registerSale()(request) as RegisterSaleResult.Registered).saleId

    private fun paymentsOf(saleId: Long) = world.tables.payments.values.filter {
        it.saleId == saleId
    }.sortedBy { it.id }.map { it.content }

    @Test
    fun theFeeOfTheMethodGivesItsNetAndItsSettlementDate() = runTest {
        // Venta del 2026-11-01 de 10000 con tarjeta a 2,99 % más 15, que se acredita a 2 días.
        val saleId = sell(
            SaleRequest(
                listOf(line(SERVICE, 10_000)),
                paymentMethodId = CARD,
                date = BusinessDate(LocalDate(2026, 11, 1)),
            ),
        )

        val payment = paymentsOf(saleId).single()
        assertEquals(Money(314), payment.feeAmount)
        assertEquals(Money(9_686), payment.netReceived)
        assertEquals(BusinessDate(LocalDate(2026, 11, 3)), payment.settlementDate)
    }

    @Test
    fun aFeeWrittenInTheSaleIsKeptAsWritten() = runTest {
        val saleId = sell(
            SaleRequest(
                listOf(line(SERVICE, 10_000)),
                paymentMethodId = SaleWorld.CASH,
                payments = listOf(
                    SalePaymentRequest(CARD, Money(6_000), feeAmount = Money(200)),
                    SalePaymentRequest(TERMINAL, Money(3_000), feeAmount = Money.ZERO),
                    SalePaymentRequest(SaleWorld.CASH, Money(1_000)),
                ),
            ),
        )

        val payments = paymentsOf(saleId)
        // Sin escribirla, la tarjeta habría dado 194 y la terminal 83: se guarda lo escrito.
        assertEquals(listOf(200L, 0L, 0L), payments.map { it.feeAmount.minor })
        assertEquals(listOf(5_800L, 3_000L, 1_000L), payments.map { it.netReceived.minor })
    }

    @Test
    fun aPaymentOfZeroPaysNoFeeNotEvenTheFixedOne() = runTest {
        world.addMethod(
            method(
                id = FREE,
                kind = PaymentMethodKind.OTHER,
                feeFixed = 15,
                priceAdjustmentBp = -10_000,
            ),
        )
        // Una venta de total 0 con tarjeta: sin la regla habría pagado los 15 fijos.
        val free = sell(SaleRequest(listOf(line(SERVICE, 0)), paymentMethodId = CARD))
        // Un pago de 0 con tarjeta dentro de una división.
        val split = sell(
            SaleRequest(
                listOf(line(SERVICE, 1_000)),
                paymentMethodId = SaleWorld.CASH,
                payments = listOf(
                    SalePaymentRequest(CARD, Money.ZERO),
                    SalePaymentRequest(SaleWorld.CASH, Money(1_000)),
                ),
            ),
        )
        // Un pago que el descuento de 100 % de su método deja en 0.
        val discounted = sell(SaleRequest(listOf(line(SERVICE, 2_000)), paymentMethodId = FREE))

        assertEquals(listOf(0L), paymentsOf(free).map { it.feeAmount.minor })
        assertEquals(listOf(0L, 0L), paymentsOf(split).map { it.feeAmount.minor })
        assertEquals(listOf(0L), paymentsOf(discounted).map { it.amount.minor })
        assertEquals(listOf(0L), paymentsOf(discounted).map { it.feeAmount.minor })
        val summary = (MarginCalculator.profit(totalsOfTheDay()) as Checked.Value).value
        assertEquals(Money.ZERO, summary.paymentCosts)
    }

    @Test
    fun aWrittenFeeOutOfRangeNamesItsPayment() = runTest {
        val limit = Limit.DOCUMENT_TOTAL.max
        for (fee in listOf(-1L, limit + 1)) {
            val result = world.registerSale()(
                SaleRequest(
                    listOf(line(SERVICE, 1_000)),
                    paymentMethodId = SaleWorld.CASH,
                    payments = listOf(
                        SalePaymentRequest(SaleWorld.CASH, Money(400)),
                        SalePaymentRequest(CARD, Money(600), feeAmount = Money(fee)),
                    ),
                ),
            )

            assertEquals(
                RegisterSaleResult.PaymentRejected(1, PaymentRejection.INVALID_FEE),
                result,
            )
        }
        assertEquals(0, world.commits)
        assertTrue(world.tables.payments.isEmpty())
    }

    @Test
    fun paymentCostsLowerTheFinalProfitAndNeverTheMargin() = runTest {
        // Venta 1: 5281 de un producto que costó 2500, con 3001 en tarjeta al 2,75 % y 2280 en
        // efectivo. Comisión 83.
        sell(
            SaleRequest(
                listOf(line(SOAP, 5_281)),
                paymentMethodId = SaleWorld.CASH,
                payments = listOf(
                    SalePaymentRequest(TERMINAL, Money(3_001)),
                    SalePaymentRequest(SaleWorld.CASH, Money(2_280)),
                ),
            ),
        )
        // Venta 2: 1000 de un servicio que cuesta 400, en efectivo, sin comisión.
        sell(SaleRequest(listOf(line(SERVICE, 1_000)), paymentMethodId = SaleWorld.CASH))
        // Venta 3: con comisión escrita de 120, y anulada: su comisión ya no cuenta.
        val voided = sell(
            SaleRequest(
                listOf(line(SERVICE, 4_000)),
                paymentMethodId = SaleWorld.CASH,
                payments = listOf(SalePaymentRequest(CARD, Money(4_000), Money(120))),
            ),
        )
        world.voidSale()(voided, VoidReason.OTHER, null)

        val summary = (MarginCalculator.profit(totalsOfTheDay()) as Checked.Value).value

        // Ventas 5281 + 1000 = 6281 y costo 2500 + 400 = 2900: ganancia en ventas 3381.
        assertEquals(Money(6_281), summary.netSales)
        assertEquals(Money(3_381), summary.grossProfit)
        // Solo la comisión de la venta activa: 83. Ganancia final 3381 − 83 = 3298.
        assertEquals(Money(83), summary.paymentCosts)
        assertEquals(Money(83), summary.totalExpenses)
        assertEquals(Money(3_298), summary.netProfit)
        // El margen sale de ventas y costo: la comisión no lo toca.
        val withoutFees = MarginCalculator.profit(totalsOfTheDay().copy(paymentFees = Money.ZERO))
        assertEquals(summary.grossMargin, (withoutFees as Checked.Value).value.grossMargin)
    }

    /**
     * Las sumas del día con la fórmula del mapa: solo ventas activas, y la comisión de sus pagos
     * menos su redondeo de efectivo.
     */
    private fun totalsOfTheDay(): ProfitTotals {
        val active = world.tables.sales.values.filter { it.status == DocumentStatus.ACTIVE }
        val activeIds = active.map { it.id }.toSet()
        val fees = world.tables.payments.values.filter { it.saleId in activeIds }
        return ProfitTotals(
            salesNetTotal = active.fold(Money.ZERO) { sum, it -> sum + it.content.netTotal },
            returnsNetRefund = Money.ZERO,
            salesTotalCost = active.fold(Money.ZERO) { sum, it -> sum + it.content.totalCost },
            returnsCostReturned = Money.ZERO,
            recordedExpenses = Money.ZERO,
            netShrinkage = Money.ZERO,
            paymentFees = fees.fold(Money.ZERO) { sum, it -> sum + it.content.feeAmount },
            cashRoundingTotal =
                active.fold(Money.ZERO) { sum, it -> sum + it.content.cashRoundingAmount },
        )
    }

    private companion object {
        const val CARD = 2L
        const val TERMINAL = 3L
        const val FREE = 4L
        const val SOAP = 1L
        const val SERVICE = 2L
    }
}
