package io.github.yesidrobayo.shopledger.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yesidrobayo.shopledger.data.backup.InvariantQueries
import io.github.yesidrobayo.shopledger.data.transaction.RestoreLock
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.VoidReason
import io.github.yesidrobayo.shopledger.domain.model.customer.CustomerContact
import io.github.yesidrobayo.shopledger.domain.model.customer.CustomerDetails
import io.github.yesidrobayo.shopledger.domain.model.customer.LoyaltyEntryDraft
import io.github.yesidrobayo.shopledger.domain.model.customer.LoyaltyEntrySource
import io.github.yesidrobayo.shopledger.domain.model.customer.LoyaltyEntryType
import io.github.yesidrobayo.shopledger.domain.model.customer.NewLoyaltyLedgerEntry
import io.github.yesidrobayo.shopledger.domain.model.sale.NewSale
import io.github.yesidrobayo.shopledger.domain.model.sale.NewSaleLine
import io.github.yesidrobayo.shopledger.domain.model.sale.NewSalePayment
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleLineRequest
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleRequest
import io.github.yesidrobayo.shopledger.domain.model.settings.LoyaltySettings
import io.github.yesidrobayo.shopledger.domain.model.stock.NewStockMovement
import io.github.yesidrobayo.shopledger.domain.port.Clock
import io.github.yesidrobayo.shopledger.domain.port.LoyaltyLedgerRepository
import io.github.yesidrobayo.shopledger.domain.port.PaymentMethodNames
import io.github.yesidrobayo.shopledger.domain.port.SalePaymentRepository
import io.github.yesidrobayo.shopledger.domain.port.SaleRepository
import io.github.yesidrobayo.shopledger.domain.port.StockLedgerRepository
import io.github.yesidrobayo.shopledger.domain.usecase.sale.RegisterSaleResult
import io.github.yesidrobayo.shopledger.domain.usecase.sale.RegisterSaleUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.sale.VoidSaleResult
import io.github.yesidrobayo.shopledger.domain.usecase.sale.VoidSaleUseCase
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Ganar y canjear puntos sobre la base real, con sus guardas: la venta, sus líneas, el libro de
 * stock, el pago, el canje y lo ganado entran juntos, en una transacción, y las consultas de
 * invariantes, también la del saldo de puntos y su cadena, quedan en cero filas. Una falla
 * inyectada en cada escritura no deja nada. Anular la venta compensa lo ganado y lo canjeado sin
 * llevar la venta.
 *
 * Los puntos no son dinero: el canje entra en el ajuste de la venta y ningún pago lo cubre. El
 * cliente, los productos y los montos son inventados.
 */
@RunWith(AndroidJUnit4::class)
internal class LoyaltySaleOnDatabaseTest : RepositoryTest() {
    private val products by lazy { RoomProductRepository(database) }
    private val stock by lazy { RoomStockLedgerRepository(database) }
    private val salePayments by lazy { RoomSalePaymentRepository(database) }
    private val settings by lazy { RoomAppSettingsRepository(database, RestoreLock { false }) }
    private val sales by lazy { RoomSaleRepository(database) }
    private val customers by lazy { RoomCustomerRepository(database) }
    private val loyaltyLedger by lazy { RoomLoyaltyLedgerRepository(database) }

    /** La escritura número N de la venta falla; `null` no falla nunca. */
    private var failAt: Int? = null
    private var writes = 0

    private var customerId = 0L
    private var lampId = 0L

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

    private val countedLoyalty by lazy {
        object : LoyaltyLedgerRepository by loyaltyLedger {
            override suspend fun append(entry: NewLoyaltyLedgerEntry): Long {
                tick()
                return loyaltyLedger.append(entry)
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
            customerLedger = RoomCustomerLedgerRepository(database),
            loyaltyLedger = countedLoyalty,
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
            customerLedger = RoomCustomerLedgerRepository(database),
            loyaltyLedger = loyaltyLedger,
        )
    }

    /**
     * El programa activo, a 1.000 por punto y 5 por punto canjeado; un cliente con 150 puntos de un
     * ajuste inicial, y una lámpara de 13.250.
     */
    @Before
    fun createProgramCustomerAndProduct() = runBlocking {
        commit {
            settings.updateLoyalty(LoyaltySettings(true, Money(1_000), Money(5)), AT)
        }
        customerId = commit {
            customers.insert(
                CustomerDetails(
                    CustomerContact("Cliente inventado", "cliente inventado", null, null, null),
                    null,
                ),
                AT,
            )
        }
        commit {
            loyaltyLedger.append(
                NewLoyaltyLedgerEntry(
                    LoyaltyEntryDraft(
                        customerId,
                        LoyaltyEntryType.ADJUSTMENT,
                        150L,
                        LoyaltyEntrySource.None,
                        "Inicial",
                    ),
                    balanceAfter = 150L,
                    occurredAt = AT,
                    businessDate = DATE,
                ),
            )
        }
        lampId = commit {
            val id = products.insert(
                newProduct("Lámpara inventada").copy(salePrice = Money(13_250)),
            )
            stock.append(openingRow(id, 10))
            id
        }
    }

    private suspend fun methodId(key: String): Long =
        count("SELECT id FROM payment_methods WHERE system_key = '$key'")

    /** Una lámpara, 13.250, que canjea 100 puntos: se cobran 12.750 en efectivo. */
    private suspend fun pointsSale(): SaleRequest = SaleRequest(
        lines = listOf(SaleLineRequest(lampId, QuantityMilli(1_000))),
        paymentMethodId = methodId("CASH"),
        customerId = customerId,
        pointsRedeemed = 100L,
    )

    private suspend fun pointsBalance(): Long =
        count("SELECT points_balance FROM customers WHERE id = $customerId")

    @Test
    fun aSaleThatRedeemsAndEarnsPassesEveryGuardAndInvariant() = runBlocking {
        val before = changeSeq()

        val registered = registerSale(pointsSale()) as RegisterSaleResult.Registered

        assertEquals(before + 1, changeSeq())
        assertEquals(12L, registered.pointsEarned)
        val saleId = registered.saleId
        assertEquals(-500L, count("SELECT order_adjustment FROM sales WHERE id = $saleId"))
        assertEquals(12_750L, count("SELECT net_total FROM sales WHERE id = $saleId"))
        // Ningún pago cubre los puntos: el único pago es el efectivo por lo que queda.
        assertEquals(
            12_750L,
            count("SELECT SUM(amount) FROM sale_payments WHERE sale_id = $saleId"),
        )
        val rows = loyaltyLedger.findBySale(saleId)
        assertEquals(listOf(LoyaltyEntryType.REDEEM, LoyaltyEntryType.EARN), rows.map { it.type })
        assertEquals(listOf(-100L, 12L), rows.map { it.points })
        assertEquals(listOf(50L, 62L), rows.map { it.balanceAfter })
        assertEquals(62L, pointsBalance())
        assertInvariantsHold()
    }

    @Test
    fun aFailureAtEachWriteLeavesNoSaleWithoutItsPoints() = runBlocking {
        val before = tableCounts()
        val seq = changeSeq()
        var at = 1
        while (true) {
            failAt = at
            writes = 0
            val failed = runCatching { registerSale(pointsSale()) }.exceptionOrNull()
            if (failed == null) break
            assertTrue(
                "Escritura $at: ${failed.javaClass.simpleName}",
                failed is InjectedWriteFailure,
            )
            assertEquals("Escritura $at", before, tableCounts())
            assertEquals(150L, pointsBalance())
            assertEquals(seq, changeSeq())
            at++
        }
        // Cabecera, línea, su fila de stock, el pago, el canje y lo ganado: seis escrituras.
        assertEquals(WRITES + 1, at)
        assertEquals(62L, pointsBalance())
        assertInvariantsHold()
    }

    @Test
    fun voidingTheSaleCompensatesWhatItEarnedAndRedeemedWithoutTheSale() = runBlocking {
        val saleId = (registerSale(pointsSale()) as RegisterSaleResult.Registered).saleId
        val ofSale = loyaltyLedger.findBySale(saleId)

        val result = voidSale(saleId, VoidReason.ENTRY_ERROR, null)

        assertEquals(VoidSaleResult.Voided, result)
        assertEquals(ofSale, loyaltyLedger.findBySale(saleId))
        assertEquals(
            2L,
            count(
                "SELECT COUNT(*) FROM loyalty_ledger_entries WHERE type = 'ENTRY_VOID' " +
                    "AND sale_id IS NULL AND reverses_entry_id IN (${ofSale.joinToString {
                        "${it.id}"
                    }})",
            ),
        )
        assertEquals(150L, pointsBalance())
        assertInvariantsHold()
    }

    @Test
    fun redeemingMoreThanTheBalanceWritesNothing() = runBlocking {
        val before = tableCounts()

        val result = registerSale(pointsSale().copy(pointsRedeemed = 151L))

        assertEquals(RegisterSaleResult.NotEnoughPoints(150L), result)
        assertEquals(before, tableCounts())
        assertEquals(150L, pointsBalance())
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
            "loyalty_ledger_entries",
        )
    }
}
