package io.github.yesidrobayo.shopledger.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yesidrobayo.shopledger.data.backup.InvariantQueries
import io.github.yesidrobayo.shopledger.data.transaction.RestoreLock
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.VoidReason
import io.github.yesidrobayo.shopledger.domain.model.customer.CustomerContact
import io.github.yesidrobayo.shopledger.domain.model.customer.CustomerDetails
import io.github.yesidrobayo.shopledger.domain.model.customer.CustomerEntryDraft
import io.github.yesidrobayo.shopledger.domain.model.customer.CustomerEntrySource
import io.github.yesidrobayo.shopledger.domain.model.customer.CustomerLedgerEntryType
import io.github.yesidrobayo.shopledger.domain.model.customer.NewCustomerLedgerEntry
import io.github.yesidrobayo.shopledger.domain.model.region.RecordTime
import io.github.yesidrobayo.shopledger.domain.model.sale.NewSale
import io.github.yesidrobayo.shopledger.domain.model.sale.NewSaleLine
import io.github.yesidrobayo.shopledger.domain.model.sale.NewSalePayment
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleLineRequest
import io.github.yesidrobayo.shopledger.domain.model.sale.SalePaymentRequest
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleRequest
import io.github.yesidrobayo.shopledger.domain.model.stock.NewStockMovement
import io.github.yesidrobayo.shopledger.domain.port.Clock
import io.github.yesidrobayo.shopledger.domain.port.CustomerLedgerRepository
import io.github.yesidrobayo.shopledger.domain.port.PaymentMethodNames
import io.github.yesidrobayo.shopledger.domain.port.SalePaymentRepository
import io.github.yesidrobayo.shopledger.domain.port.SaleRepository
import io.github.yesidrobayo.shopledger.domain.port.StockLedgerRepository
import io.github.yesidrobayo.shopledger.domain.usecase.customer.CustomerLedgerWriter
import io.github.yesidrobayo.shopledger.domain.usecase.sale.RegisterSaleResult
import io.github.yesidrobayo.shopledger.domain.usecase.sale.RegisterSaleUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.sale.VoidSaleResult
import io.github.yesidrobayo.shopledger.domain.usecase.sale.VoidSaleUseCase
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * La venta a crédito sobre la base real, con sus guardas: la venta, sus líneas, el libro de stock,
 * los pagos y el cargo en el libro del cliente entran juntos, en una transacción, y las consultas de
 * invariantes, también la de los cargos que suman lo pagado a crédito, quedan en cero filas. Una
 * falla inyectada en cada escritura no deja nada. Anular la venta compensa el cargo.
 *
 * El cliente, los productos y los montos son inventados.
 */
@RunWith(AndroidJUnit4::class)
internal class CreditSaleOnDatabaseTest : RepositoryTest() {
    private val products by lazy { RoomProductRepository(database) }
    private val stock by lazy { RoomStockLedgerRepository(database) }
    private val salePayments by lazy { RoomSalePaymentRepository(database) }
    private val settings by lazy { RoomAppSettingsRepository(database, RestoreLock { false }) }
    private val sales by lazy { RoomSaleRepository(database) }
    private val customers by lazy { RoomCustomerRepository(database) }
    private val customerLedger by lazy { RoomCustomerLedgerRepository(database) }

    /** La escritura número N de la venta falla; `null` no falla nunca. */
    private var failAt: Int? = null
    private var writes = 0

    private var customerId = 0L
    private var soapId = 0L

    private fun tick() {
        writes++
        if (writes == failAt) throw InjectedWriteFailure()
    }

    private val countedSales by lazy {
        object : SaleRepository by sales {
            override suspend fun insert(sale: NewSale): Long {
                tick()
                return sales.insert(sale)
            }

            override suspend fun insertLine(saleId: Long, line: NewSaleLine): Long {
                tick()
                return sales.insertLine(saleId, line)
            }
        }
    }

    private val countedPayments by lazy {
        object : SalePaymentRepository by salePayments {
            override suspend fun insert(saleId: Long, payment: NewSalePayment): Long {
                tick()
                return salePayments.insert(saleId, payment)
            }
        }
    }

    private val countedStock by lazy {
        object : StockLedgerRepository by stock {
            override suspend fun append(movement: NewStockMovement): Long {
                tick()
                return stock.append(movement)
            }
        }
    }

    private val countedCustomerLedger by lazy {
        object : CustomerLedgerRepository by customerLedger {
            override suspend fun append(entry: NewCustomerLedgerEntry): Long {
                tick()
                return customerLedger.append(entry)
            }
        }
    }

    private val registerSale by lazy {
        RegisterSaleUseCase(
            transactions = runner,
            settings = settings,
            products = products,
            sales = countedSales,
            payments = countedPayments,
            units = RoomUnitRepository(database),
            systemRows = RoomSystemRowRepository(database),
            ledger = countedStock,
            methodNames = PaymentMethodNames { key -> "Initial ${key.lowercase()}" },
            taxRates = RoomTaxRateRepository(database),
            clock = Clock { AT },
            customers = customers,
            customerLedger = countedCustomerLedger,
        )
    }

    private val voidSale by lazy {
        VoidSaleUseCase(
            transactions = runner,
            settings = settings,
            products = products,
            sales = sales,
            ledger = stock,
            clock = Clock { AT },
            customerLedger = customerLedger,
        )
    }

    @Before
    fun createCustomerAndProduct() = runBlocking {
        customerId = commit {
            customers.insert(
                CustomerDetails(
                    CustomerContact("Cliente inventado", "cliente inventado", null, null, null),
                    null,
                ),
                AT,
            )
        }
        soapId = commit {
            val id = products.insert(newProduct("Jabón inventado").copy(salePrice = Money(3_500)))
            stock.append(openingRow(id, 10))
            id
        }
    }

    private suspend fun methodId(key: String): Long =
        count("SELECT id FROM payment_methods WHERE system_key = '$key'")

    /** Dos jabones, 7.000: 2.000 en efectivo y 5.000 a crédito. */
    private suspend fun creditSale(): SaleRequest = SaleRequest(
        lines = listOf(SaleLineRequest(soapId, QuantityMilli(2_000))),
        paymentMethodId = methodId("CASH"),
        payments = listOf(
            SalePaymentRequest(methodId("CASH"), Money(2_000)),
            SalePaymentRequest(methodId("ON_ACCOUNT"), Money(5_000)),
        ),
        customerId = customerId,
    )

    @Test
    fun aCreditSaleWritesItsChargeAndPassesEveryGuardAndInvariant() = runBlocking {
        val before = changeSeq()

        val saleId = (registerSale(creditSale()) as RegisterSaleResult.Registered).saleId

        assertEquals(before + 1, changeSeq())
        assertEquals(customerId, count("SELECT customer_id FROM sales WHERE id = $saleId"))
        val charge = customerLedger.findBySale(saleId).single()
        assertEquals(CustomerLedgerEntryType.CREDIT_SALE, charge.type)
        assertEquals(Money(5_000), charge.amount)
        assertEquals(Money(5_000), charge.balanceAfter)
        assertEquals(Money(5_000), customers.findById(customerId)?.balance)
        assertInvariantsHold()
    }

    @Test
    fun aFailureAtEachWriteLeavesNoSaleWithoutItsCharge() = runBlocking {
        val before = tableCounts()
        val seq = changeSeq()
        var at = 1
        while (true) {
            failAt = at
            writes = 0
            val failed = runCatching { registerSale(creditSale()) }.exceptionOrNull()
            if (failed == null) break
            assertTrue(
                "Escritura $at: ${failed.javaClass.simpleName}",
                failed is InjectedWriteFailure,
            )
            assertEquals("Escritura $at", before, tableCounts())
            assertEquals(Money.ZERO, customers.findById(customerId)?.balance)
            assertEquals(seq, changeSeq())
            at++
        }
        // Cabecera, línea, su fila de stock, dos pagos y el cargo: seis escrituras, cada una probada.
        assertEquals(WRITES + 1, at)
        assertEquals(1L, count("SELECT COUNT(*) FROM customer_ledger_entries"))
        assertInvariantsHold()
    }

    /**
     * Un anticipo de 10.000 deja saldo a favor, y la venta de 7.000 lo usa con 5.000 a crédito: el
     * cargo lo consume en la misma transacción, sin deuda ni vencimiento. Una falla en cada escritura
     * deja el saldo a favor intacto.
     */
    @Test
    fun aSaleThatUsesTheCreditBalanceConsumesItInTheSameTransaction() = runBlocking {
        val prepaid = CustomerEntryDraft(
            customerId,
            CustomerLedgerEntryType.PAYMENT,
            Money(-10_000),
            CustomerEntrySource.None,
            null,
            null,
        )
        commit { CustomerLedgerWriter(customerLedger).append(prepaid, RecordTime(AT, DATE)) }
        assertEquals(Money(-10_000), customers.findById(customerId)?.balance)
        val before = tableCounts()
        val seq = changeSeq()
        var at = 1
        while (true) {
            failAt = at
            writes = 0
            val result = runCatching { registerSale(creditSale()) }
            val failed = result.exceptionOrNull() ?: break
            assertTrue("Escritura $at", failed is InjectedWriteFailure)
            assertEquals("Escritura $at", before, tableCounts())
            assertEquals(Money(-10_000), customers.findById(customerId)?.balance)
            assertEquals(seq, changeSeq())
            at++
        }
        assertEquals(WRITES + 1, at)

        val saleId = count("SELECT MAX(id) FROM sales")
        val charge = customerLedger.findBySale(saleId).single()
        assertEquals(CustomerLedgerEntryType.CREDIT_SALE, charge.type)
        assertEquals(Money(5_000), charge.amount)
        assertEquals(Money(-5_000), charge.balanceAfter)
        assertNull(charge.dueDate)
        assertEquals(Money(-5_000), customers.findById(customerId)?.balance)
        assertInvariantsHold()
    }

    @Test
    fun voidingTheSaleCompensatesItsChargeWithoutTheSale() = runBlocking {
        val saleId = (registerSale(creditSale()) as RegisterSaleResult.Registered).saleId
        val charge = customerLedger.findBySale(saleId).single()

        val result = voidSale(saleId, VoidReason.ENTRY_ERROR, null)

        assertEquals(VoidSaleResult.Voided, result)
        val void = customerLedger.findByCustomer(customerId).last()
        assertEquals(CustomerLedgerEntryType.ENTRY_VOID, void.type)
        assertEquals(CustomerEntrySource.Reverses(charge.id), void.source)
        assertEquals(Money(-5_000), void.amount)
        assertNull(customerLedger.findBySale(saleId).firstOrNull { it.id == void.id })
        assertEquals(Money.ZERO, customers.findById(customerId)?.balance)
        assertInvariantsHold()
    }

    private suspend fun tableCounts(): List<Long> = TABLES.map { count("SELECT COUNT(*) FROM $it") }

    private suspend fun assertInvariantsHold() {
        for (query in InvariantQueries.MODEL) {
            val rows = query.sql.trimEnd().removeSuffix(";")
            assertEquals(query.label, 0L, count("SELECT COUNT(*) FROM ($rows)"))
        }
    }

    private class InjectedWriteFailure : RuntimeException("Falla a propósito")

    private companion object {
        const val WRITES = 6
        val TABLES = listOf(
            "sales",
            "sale_lines",
            "sale_payments",
            "stock_movements",
            "customer_ledger_entries",
        )
    }
}
