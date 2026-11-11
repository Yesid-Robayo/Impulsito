package io.github.yesidrobayo.shopledger.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yesidrobayo.shopledger.data.backup.InvariantQueries
import io.github.yesidrobayo.shopledger.data.local.payment.paymentMethod
import io.github.yesidrobayo.shopledger.data.transaction.RestoreLock
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.payment.PaymentMethodKind
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleLineRequest
import io.github.yesidrobayo.shopledger.domain.model.sale.SalePaymentRequest
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleRequest
import io.github.yesidrobayo.shopledger.domain.port.Clock
import io.github.yesidrobayo.shopledger.domain.port.PaymentMethodNames
import io.github.yesidrobayo.shopledger.domain.usecase.sale.RegisterSaleResult
import io.github.yesidrobayo.shopledger.domain.usecase.sale.RegisterSaleUseCase
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Una venta cobrada con varios métodos sobre la base real, con sus guardas: un pago por método, que
 * suman lo que la venta cobra, y cero filas en cada consulta de invariantes. Un cobro que no suma
 * no deja nada, ni siquiera el cambio de la cuenta de cambios. El recargo o descuento de un método
 * entra en el ajuste de la venta y en sus líneas, y las guardas lo aceptan. Una comisión escrita en
 * la venta se guarda tal cual, y el costo de cobrar del período sale de las comisiones guardadas.
 *
 * Los productos y montos son inventados.
 */
@RunWith(AndroidJUnit4::class)
internal class RegisterSplitSaleOnDatabaseTest : RepositoryTest() {
    private val products by lazy { RoomProductRepository(database) }
    private val ledger by lazy { RoomStockLedgerRepository(database) }
    private val salePayments by lazy { RoomSalePaymentRepository(database) }

    private val registerSale by lazy {
        RegisterSaleUseCase(
            transactions = runner,
            settings = RoomAppSettingsRepository(database, RestoreLock { false }),
            products = products,
            sales = RoomSaleRepository(database),
            payments = salePayments,
            units = RoomUnitRepository(database),
            systemRows = RoomSystemRowRepository(database),
            ledger = ledger,
            methodNames = PaymentMethodNames { key -> "Initial ${key.lowercase()}" },
            clock = Clock { AT },
        )
    }

    private suspend fun methodId(systemKey: String): Long =
        count("SELECT id FROM payment_methods WHERE system_key = '$systemKey'")

    private suspend fun soldProduct(name: String = "Jabón inventado"): Long = commit {
        val id = products.insert(newProduct(name).copy(salePrice = Money(5_281)))
        ledger.append(openingRow(id, 10))
        id
    }

    private suspend fun request(product: Long, card: Long, cash: Long) = SaleRequest(
        lines = listOf(SaleLineRequest(product, QuantityMilli.ofUnits(1L))),
        paymentMethodId = methodId("CASH"),
        payments = listOf(
            SalePaymentRequest(methodId("CARD"), Money(card)),
            SalePaymentRequest(methodId("CASH"), Money(cash)),
        ),
    )

    @Test
    fun aSaleCollectedWithTwoMethodsPassesEveryGuardAndInvariant() = runBlocking {
        val product = soldProduct()
        val before = changeSeq()

        val result = registerSale(request(product, card = 3_001, cash = 2_280))

        val saleId = (result as RegisterSaleResult.Registered).saleId
        assertEquals(before + 1, changeSeq())
        val payments = salePayments.findBySale(saleId).map { it.content }
        assertEquals(
            listOf(PaymentMethodKind.CARD, PaymentMethodKind.CASH),
            payments.map { it.methodKind },
        )
        assertEquals(listOf(3_001L, 2_280L), payments.map { it.amount.minor })
        assertEquals(
            5_281L,
            count("SELECT amount_due FROM sales WHERE id = $saleId"),
        )
        assertInvariantsHold()
    }

    @Test
    fun aCollectionThatDoesNotAddUpLeavesNothing() = runBlocking {
        val product = soldProduct()
        val before = changeSeq()
        val onHand = requireNotNull(products.findById(product)).onHand

        val result = registerSale(request(product, card = 3_000, cash = 2_280))

        assertEquals(RegisterSaleResult.PaymentsDoNotMatch(Money(5_281), Money(5_280)), result)
        assertEquals(before, changeSeq())
        assertEquals(0L, count("SELECT COUNT(*) FROM sales"))
        assertEquals(0L, count("SELECT COUNT(*) FROM sale_payments"))
        assertEquals(onHand, requireNotNull(products.findById(product)).onHand)
        assertInvariantsHold()
    }

    @Test
    fun surchargesAndDiscountsOfTheMethodsPassEveryGuardAndInvariant() = runBlocking {
        val first = soldProduct()
        val second = soldProduct("Café inventado")
        val surcharged = commit {
            database.paymentMethodDao().insert(
                paymentMethod("Tarjeta inventada", kind = "CARD").copy(priceAdjustmentBp = 500),
            )
        }
        val discounted = commit {
            database.paymentMethodDao().insert(
                paymentMethod("Billetera inventada").copy(priceAdjustmentBp = -333),
            )
        }

        // 10562 por cobrar: 6000 con +5 % suman 300, y 4562 con −3,33 % restan 152.
        val result = registerSale(
            SaleRequest(
                lines = listOf(
                    SaleLineRequest(first, QuantityMilli.ofUnits(1L)),
                    SaleLineRequest(second, QuantityMilli.ofUnits(1L), unitPrice = Money(5_281)),
                ),
                paymentMethodId = methodId("CASH"),
                payments = listOf(
                    SalePaymentRequest(surcharged, Money(6_000)),
                    SalePaymentRequest(discounted, Money(4_562)),
                ),
            ),
        )

        val saleId = (result as RegisterSaleResult.Registered).saleId
        assertEquals(148L, count("SELECT order_adjustment FROM sales WHERE id = $saleId"))
        assertEquals(10_710L, count("SELECT amount_due FROM sales WHERE id = $saleId"))
        assertEquals(
            listOf(6_300L, 4_410L),
            salePayments.findBySale(saleId).map { it.content.amount.minor },
        )
        assertEquals(
            148L,
            count("SELECT SUM(order_adjustment_share) FROM sale_lines WHERE sale_id = $saleId"),
        )
        assertInvariantsHold()
    }

    @Test
    fun aWrittenFeeIsSavedAndCountsInThePaymentCostsOfThePeriod() = runBlocking {
        val product = soldProduct()
        val card = methodId("CARD")

        // 3001 con tarjeta, con una comisión de 83 escrita en la venta, y 2280 en efectivo.
        val result = registerSale(
            SaleRequest(
                lines = listOf(SaleLineRequest(product, QuantityMilli.ofUnits(1L))),
                paymentMethodId = methodId("CASH"),
                payments = listOf(
                    SalePaymentRequest(card, Money(3_001), feeAmount = Money(83)),
                    SalePaymentRequest(methodId("CASH"), Money(2_280)),
                ),
            ),
        )

        val saleId = (result as RegisterSaleResult.Registered).saleId
        val payments = salePayments.findBySale(saleId).map { it.content }
        assertEquals(listOf(83L, 0L), payments.map { it.feeAmount.minor })
        assertEquals(listOf(2_918L, 2_280L), payments.map { it.netReceived.minor })
        // El costo de cobrar del mapa: comisiones de ventas activas menos su redondeo de efectivo.
        assertEquals(
            83L,
            count(
                "SELECT (SELECT COALESCE(SUM(p.fee_amount), 0) FROM sale_payments p " +
                    "JOIN sales s ON s.id = p.sale_id WHERE s.status = 'ACTIVE') - " +
                    "(SELECT COALESCE(SUM(cash_rounding_amount), 0) FROM sales " +
                    "WHERE status = 'ACTIVE')",
            ),
        )
        assertInvariantsHold()
    }

    private suspend fun assertInvariantsHold() {
        for (query in InvariantQueries.MODEL) {
            val rows = query.sql.trimEnd().removeSuffix(";")
            assertEquals(query.label, 0L, count("SELECT COUNT(*) FROM ($rows)"))
        }
    }
}
