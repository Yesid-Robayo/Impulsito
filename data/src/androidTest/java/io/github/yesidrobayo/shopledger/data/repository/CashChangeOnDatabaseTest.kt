package io.github.yesidrobayo.shopledger.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yesidrobayo.shopledger.data.backup.InvariantQueries
import io.github.yesidrobayo.shopledger.data.transaction.RestoreLock
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.sale.PaymentRejection
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleLineRequest
import io.github.yesidrobayo.shopledger.domain.model.sale.SalePaymentRequest
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleRequest
import io.github.yesidrobayo.shopledger.domain.port.Clock
import io.github.yesidrobayo.shopledger.domain.port.PaymentMethodNames
import io.github.yesidrobayo.shopledger.domain.usecase.sale.RegisterSaleResult
import io.github.yesidrobayo.shopledger.domain.usecase.sale.RegisterSaleUseCase
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * El efectivo sobre la base real, con sus guardas: el pago en efectivo se guarda ya redondeado, la
 * venta guarda su redondeo y cobra el total más él, y lo recibido y el cambio quedan en el pago, con
 * cero filas en cada consulta de invariantes. Lo recibido que no alcanza no deja nada.
 *
 * Los productos y montos son inventados.
 */
@RunWith(AndroidJUnit4::class)
internal class CashChangeOnDatabaseTest : RepositoryTest() {
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
            taxRates = RoomTaxRateRepository(database),
            clock = Clock { AT },
        )
    }

    private suspend fun methodId(systemKey: String): Long =
        count("SELECT id FROM payment_methods WHERE system_key = '$systemKey'")

    private suspend fun soldProduct(): Long = commit {
        val id = products.insert(newProduct("Jabón inventado").copy(salePrice = Money(5_280)))
        ledger.append(openingRow(id, 10))
        id
    }

    private suspend fun request(product: Long, tendered: Long?) = SaleRequest(
        lines = listOf(SaleLineRequest(product, QuantityMilli.ofUnits(1L))),
        paymentMethodId = methodId("CASH"),
        payments = listOf(
            SalePaymentRequest(methodId("CARD"), Money(3_001)),
            SalePaymentRequest(
                methodId("CASH"),
                Money(2_279),
                tenderedAmount = tendered?.let(::Money),
            ),
        ),
    )

    @Test
    fun theCashPaymentIsRoundedAndKeepsWhatWasReceivedAndTheChange() = runBlocking {
        execute("UPDATE app_settings SET cash_rounding = 5")
        val product = soldProduct()

        // 5280 con 3001 en tarjeta: el efectivo cubre 2279, que sube a 2280. Con 5000 recibidos, el
        // cambio es 2720.
        val result = registerSale(request(product, tendered = 5_000))

        val saleId = (result as RegisterSaleResult.Registered).saleId
        assertEquals(1L, count("SELECT cash_rounding_amount FROM sales WHERE id = $saleId"))
        assertEquals(5_281L, count("SELECT amount_due FROM sales WHERE id = $saleId"))
        val payments = salePayments.findBySale(saleId).map { it.content }
        assertEquals(listOf(3_001L, 2_280L), payments.map { it.amount.minor })
        assertNull(payments.first().tenderedAmount)
        assertEquals(Money(5_000), payments.last().tenderedAmount)
        assertEquals(Money(2_720), payments.last().changeAmount)
        assertInvariantsHold()
    }

    @Test
    fun withoutRoundingOrWhatWasReceivedNothingChanges() = runBlocking {
        val product = soldProduct()

        val result = registerSale(request(product, tendered = null))

        val saleId = (result as RegisterSaleResult.Registered).saleId
        assertEquals(0L, count("SELECT cash_rounding_amount FROM sales WHERE id = $saleId"))
        assertEquals(5_280L, count("SELECT amount_due FROM sales WHERE id = $saleId"))
        assertEquals(
            0L,
            count("SELECT COUNT(*) FROM sale_payments WHERE tendered_amount IS NOT NULL"),
        )
        assertInvariantsHold()
    }

    @Test
    fun whatWasReceivedBelowTheCashPartLeavesNothing() = runBlocking {
        execute("UPDATE app_settings SET cash_rounding = 5")
        val product = soldProduct()
        val before = changeSeq()

        val result = registerSale(request(product, tendered = 2_279))

        assertEquals(
            RegisterSaleResult.PaymentRejected(1, PaymentRejection.TENDERED_BELOW_AMOUNT),
            result,
        )
        assertEquals(before, changeSeq())
        assertEquals(0L, count("SELECT COUNT(*) FROM sales"))
        assertInvariantsHold()
    }

    private suspend fun assertInvariantsHold() {
        for (query in InvariantQueries.MODEL) {
            val rows = query.sql.trimEnd().removeSuffix(";")
            assertEquals(query.label, 0L, count("SELECT COUNT(*) FROM ($rows)"))
        }
    }
}
