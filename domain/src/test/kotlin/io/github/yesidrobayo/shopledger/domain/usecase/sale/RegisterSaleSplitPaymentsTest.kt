package io.github.yesidrobayo.shopledger.domain.usecase.sale

import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.Limit
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductStockMode
import io.github.yesidrobayo.shopledger.domain.model.payment.PaymentMethodKind
import io.github.yesidrobayo.shopledger.domain.model.sale.PaymentRejection
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleLineRequest
import io.github.yesidrobayo.shopledger.domain.model.sale.SalePaymentRequest
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleRequest
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cobrar una venta con varios métodos: un pago por método, que suman exactamente lo que se cobra,
 * con los ejemplos de las reglas escritos a mano, y que cualquier rechazo o falla no deje nada.
 */
class RegisterSaleSplitPaymentsTest {
    private val world = SaleWorld().apply {
        addMethod(method())
        addMethod(
            method(
                id = CARD,
                kind = PaymentMethodKind.CARD,
                systemKey = null,
                name = "Invented card",
                feeBp = 275,
                settlementDays = 2,
                moneyAccountId = 4,
            ),
        )
    }

    private fun sale(price: Long, vararg payments: Pair<Long, Long>) = SaleRequest(
        lines = listOf(SaleLineRequest(PRODUCT, QuantityMilli.ofUnits(1L), Money(price))),
        paymentMethodId = SaleWorld.CASH,
        payments = payments.map { (method, amount) -> SalePaymentRequest(method, Money(amount)) },
    )

    private suspend fun rejectionOf(request: SaleRequest): RegisterSaleResult {
        val result = world.registerSale()(request)
        assertEquals("Un rechazo no confirma nada", 0, world.commits)
        assertTrue(world.tables.sales.isEmpty())
        assertTrue(world.tables.lines.isEmpty())
        assertTrue(world.tables.movements.isEmpty())
        assertTrue(world.tables.payments.isEmpty())
        return result
    }

    private fun stockedProduct() = world.addProduct(
        product(PRODUCT, onHandMilli = 5_000, averageCost = 500, stockValue = 2_500),
    )

    @Test
    fun theWorkedExampleWritesOnePaymentPerMethodThatAddUpToTheAmountDue() = runTest {
        stockedProduct()

        // 5281 por cobrar: 3001 con tarjeta al 2,75 % y 2280 en efectivo.
        val result = world.registerSale()(sale(5_281, CARD to 3_001, SaleWorld.CASH to 2_280))

        val saleId = (result as RegisterSaleResult.Registered).saleId
        val sale = world.tables.sales.getValue(saleId).content
        assertEquals(Money(5_281), sale.amountDue)
        val payments = world.tables.payments.values.sortedBy { it.id }
        assertTrue(payments.all { it.saleId == saleId })
        assertEquals(listOf(CARD, SaleWorld.CASH), payments.map { it.content.paymentMethodId })
        assertEquals(
            listOf("Invented card", "name of cash"),
            payments.map { it.content.methodNameSnapshot },
        )
        assertEquals(
            listOf(PaymentMethodKind.CARD, PaymentMethodKind.CASH),
            payments.map { it.content.methodKind },
        )
        assertEquals(listOf(3_001L, 2_280L), payments.map { it.content.amount.minor })
        // 3001 al 2,75 % son 82,53, que redondea a 83; el efectivo no paga comisión.
        assertEquals(listOf(83L, 0L), payments.map { it.content.feeAmount.minor })
        assertEquals(
            listOf(LocalDate(2026, 11, 4), LocalDate(2026, 11, 2)).map(::BusinessDate),
            payments.map { it.content.settlementDate },
        )
        assertEquals(listOf(4L, null), payments.map { it.content.moneyAccountId })
        // La venta cumple su invariante: al menos un pago, y los pagos suman lo que se cobra.
        assertEquals(
            sale.amountDue,
            payments.fold(Money.ZERO) { sum, it ->
                sum + it.content.amount
            },
        )
        assertEquals(sale.total + sale.tipAmount + sale.cashRoundingAmount, sale.amountDue)
        assertEquals(1, world.commits)
        assertEquals(1L, world.tables.changeSeq)
    }

    @Test
    fun withWrittenPaymentsTheMethodOfTheSaleIsNotUsed() = runTest {
        stockedProduct()

        val result = world.registerSale()(
            sale(1_000, CARD to 1_000).copy(paymentMethodId = MISSING),
        )

        assertTrue(result is RegisterSaleResult.Registered)
        assertEquals(CARD, world.tables.payments.values.single().content.paymentMethodId)
    }

    @Test
    fun paymentsThatDoNotAddUpAreRejectedWithTheirDifference() = runTest {
        stockedProduct()

        assertEquals(
            RegisterSaleResult.PaymentsDoNotMatch(amountDue = Money(5_281), paid = Money(5_280)),
            rejectionOf(sale(5_281, CARD to 3_000, SaleWorld.CASH to 2_280)),
        )
        assertEquals(
            RegisterSaleResult.PaymentsDoNotMatch(amountDue = Money(5_281), paid = Money(5_282)),
            rejectionOf(sale(5_281, CARD to 3_002, SaleWorld.CASH to 2_280)),
        )
    }

    @Test
    fun aSaleOfZeroKeepsItsPaymentOfZero() = runTest {
        world.addProduct(product(PRODUCT, stockMode = ProductStockMode.UNTRACKED))

        val result = world.registerSale()(sale(0, SaleWorld.CASH to 0))

        assertTrue(result is RegisterSaleResult.Registered)
        val payment = world.tables.payments.values.single().content
        assertEquals(Money.ZERO, payment.amount)
        assertEquals(Money.ZERO, world.tables.sales.values.single().content.amountDue)
    }

    @Test
    fun aRepeatedMethodOrAnAmountOutOfRangeNamesItsPayment() = runTest {
        stockedProduct()

        assertEquals(
            RegisterSaleResult.PaymentRejected(1, PaymentRejection.DUPLICATE_METHOD),
            rejectionOf(sale(1_000, CARD to 500, CARD to 500)),
        )
        assertEquals(
            RegisterSaleResult.PaymentRejected(0, PaymentRejection.INVALID_AMOUNT),
            rejectionOf(sale(1_000, CARD to -1, SaleWorld.CASH to 1_001)),
        )
        // Se rechaza antes de leer la base: ni siquiera importa que el método exista.
        assertEquals(
            RegisterSaleResult.PaymentRejected(1, PaymentRejection.DUPLICATE_METHOD),
            rejectionOf(sale(1_000, MISSING to 500, MISSING to 500)),
        )
    }

    @Test
    fun eachMethodOfTheSplitMustBeAbleToCharge() = runTest {
        stockedProduct()
        world.addMethod(method(id = ARCHIVED, kind = PaymentMethodKind.WALLET, archived = true))
        world.addMethod(method(id = CREDIT, kind = PaymentMethodKind.ON_ACCOUNT))

        assertEquals(
            RegisterSaleResult.PaymentMethodNotFound,
            rejectionOf(sale(1_000, SaleWorld.CASH to 500, MISSING to 500)),
        )
        assertEquals(
            RegisterSaleResult.PaymentMethodArchived,
            rejectionOf(sale(1_000, SaleWorld.CASH to 500, ARCHIVED to 500)),
        )
        assertEquals(
            RegisterSaleResult.PaymentNeedsCustomer,
            rejectionOf(sale(1_000, SaleWorld.CASH to 500, CREDIT to 500)),
        )
    }

    @Test
    fun aSumOrAFeeAboveTheDocumentLimitIsRejected() = runTest {
        stockedProduct()
        val limit = Limit.DOCUMENT_TOTAL.max
        world.addMethod(
            method(id = COSTLY, kind = PaymentMethodKind.OTHER, feeBp = 10_000, feeFixed = limit),
        )

        assertEquals(
            RegisterSaleResult.SaleTotalTooLarge,
            rejectionOf(sale(1_000, SaleWorld.CASH to limit, CARD to limit)),
        )
        assertEquals(
            RegisterSaleResult.SaleTotalTooLarge,
            rejectionOf(sale(1_000, SaleWorld.CASH to 999, COSTLY to 1)),
        )
    }

    @Test
    fun aFailureAtAnyWriteLeavesNothing() = runTest {
        stockedProduct()
        val request = sale(1_000, CARD to 600, SaleWorld.CASH to 400)
        // Cabecera, línea, fila del libro con su saldo y los dos pagos: cada uno con su id.
        val writes = 11
        for (failAt in 1..writes) {
            world.failAtWrite = failAt
            val failed = runCatching { world.registerSale()(request) }
            assertTrue("Escritura $failAt", failed.exceptionOrNull() is InjectedSaleFailure)
            assertTrue(world.tables.sales.isEmpty())
            assertTrue(world.tables.lines.isEmpty())
            assertTrue(world.tables.movements.isEmpty())
            assertTrue(world.tables.payments.isEmpty())
            assertEquals(QuantityMilli(5_000), world.tables.products.getValue(PRODUCT).onHand)
            assertEquals(0L, world.tables.changeSeq)
        }
        world.failAtWrite = null

        assertTrue(world.registerSale()(request) is RegisterSaleResult.Registered)
        assertEquals(2, world.tables.payments.size)
        assertEquals(1L, world.tables.changeSeq)
    }

    private companion object {
        const val PRODUCT = 1L
        const val CARD = 2L
        const val ARCHIVED = 3L
        const val CREDIT = 4L
        const val COSTLY = 5L
        const val MISSING = 99L
    }
}
