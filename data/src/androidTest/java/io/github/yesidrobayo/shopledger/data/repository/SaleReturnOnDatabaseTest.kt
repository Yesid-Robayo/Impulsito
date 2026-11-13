package io.github.yesidrobayo.shopledger.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yesidrobayo.shopledger.data.backup.InvariantQueries
import io.github.yesidrobayo.shopledger.data.transaction.RestoreLock
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.VoidReason
import io.github.yesidrobayo.shopledger.domain.model.money.AccountCurrency
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyAccountDetails
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyAccountKind
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyMovementType
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyOrigin
import io.github.yesidrobayo.shopledger.domain.model.money.NewMoneyAccount
import io.github.yesidrobayo.shopledger.domain.model.money.NewMoneyMovement
import io.github.yesidrobayo.shopledger.domain.model.returns.LineAmounts
import io.github.yesidrobayo.shopledger.domain.model.returns.NewSaleReturn
import io.github.yesidrobayo.shopledger.domain.model.returns.NewSaleReturnLine
import io.github.yesidrobayo.shopledger.domain.model.returns.ReturnLineRequest
import io.github.yesidrobayo.shopledger.domain.model.returns.ReturnResolution
import io.github.yesidrobayo.shopledger.domain.model.returns.ReturnSettlement
import io.github.yesidrobayo.shopledger.domain.model.returns.SaleReturnRequest
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleLineRequest
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleRequest
import io.github.yesidrobayo.shopledger.domain.model.stock.NewStockMovement
import io.github.yesidrobayo.shopledger.domain.model.stock.StockMovementType
import io.github.yesidrobayo.shopledger.domain.port.Clock
import io.github.yesidrobayo.shopledger.domain.port.MoneyLedgerRepository
import io.github.yesidrobayo.shopledger.domain.port.PaymentMethodNames
import io.github.yesidrobayo.shopledger.domain.port.SaleReturnRepository
import io.github.yesidrobayo.shopledger.domain.port.StockLedgerRepository
import io.github.yesidrobayo.shopledger.domain.usecase.returns.RegisterSaleReturnResult
import io.github.yesidrobayo.shopledger.domain.usecase.returns.RegisterSaleReturnUseCase
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
 * La devolución de venta sobre la base real y sus guardas: la devolución, sus líneas, la entrada al
 * libro de stock y el `REFUND` de la cuenta entran en una sola transacción, la venta con una
 * devolución activa no se anula, y las consultas de invariantes quedan en cero filas, también la de
 * no devolver más de lo vendido. Una falla inyectada en cada escritura no deja nada a medias.
 *
 * Los productos y los montos son inventados.
 */
@RunWith(AndroidJUnit4::class)
internal class SaleReturnOnDatabaseTest : RepositoryTest() {
    private val products by lazy { RoomProductRepository(database) }
    private val stock by lazy { RoomStockLedgerRepository(database) }
    private val settings by lazy { RoomAppSettingsRepository(database, RestoreLock { false }) }
    private val sales by lazy { RoomSaleRepository(database) }
    private val returns by lazy { RoomSaleReturnRepository(database) }
    private val accounts by lazy { RoomMoneyAccountRepository(database) }
    private val money by lazy { RoomMoneyLedgerRepository(database) }

    /** La escritura número N falla; `null` no falla nunca. */
    private var failAt: Int? = null
    private var writes = 0

    private var cashId = 0L
    private var soapId = 0L

    private fun tick() {
        writes++
        if (writes == failAt) throw InjectedWriteFailure()
    }

    private val countedReturns by lazy {
        object : SaleReturnRepository by returns {
            override suspend fun insert(saleReturn: NewSaleReturn): Long {
                tick()
                return returns.insert(saleReturn)
            }

            override suspend fun insertLine(saleReturnId: Long, line: NewSaleReturnLine): Long {
                tick()
                return returns.insertLine(saleReturnId, line)
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

    private val countedMoney by lazy {
        object : MoneyLedgerRepository by money {
            override suspend fun append(movement: NewMoneyMovement): Long {
                tick()
                return money.append(movement)
            }
        }
    }

    private val registerSale by lazy {
        RegisterSaleUseCase(
            transactions = runner,
            settings = settings,
            products = products,
            sales = sales,
            payments = RoomSalePaymentRepository(database),
            units = RoomUnitRepository(database),
            systemRows = RoomSystemRowRepository(database),
            ledger = stock,
            methodNames = PaymentMethodNames { key -> "Initial ${key.lowercase()}" },
            taxRates = RoomTaxRateRepository(database),
            clock = Clock { AT },
        )
    }

    private val giveBack by lazy {
        RegisterSaleReturnUseCase(
            transactions = runner,
            settings = settings,
            products = products,
            sales = sales,
            returns = countedReturns,
            units = RoomUnitRepository(database),
            ledger = countedStock,
            clock = Clock { AT },
            moneyAccounts = accounts,
            moneyLedger = countedMoney,
        )
    }

    private val voidSale by lazy {
        VoidSaleUseCase(runner, settings, products, sales, stock, Clock { AT })
    }

    @Before
    fun createProduct() = runBlocking {
        // Una caja en la moneda del negocio: la inicial nace antes de confirmarla.
        cashId = commit {
            accounts.insert(
                NewMoneyAccount(
                    MoneyAccountDetails("Caja inventada", "caja inventada", MoneyAccountKind.CASH),
                    AccountCurrency.of(settings.get().regional.currency),
                    AT,
                ),
            )
        }
        soapId = commit {
            val id = products.insert(newProduct("Jabón inventado").copy(salePrice = Money(3_500)))
            stock.append(openingRow(id, 10))
            id
        }
    }

    /** Tres jabones en efectivo: 10.500, que entran en la cuenta de efectivo. */
    private suspend fun threeSoaps(): Long {
        val cash = count("SELECT id FROM payment_methods WHERE system_key = 'CASH'")
        val request = SaleRequest(listOf(SaleLineRequest(soapId, QuantityMilli(3_000))), cash)
        return (registerSale(request) as RegisterSaleResult.Registered).saleId
    }

    private suspend fun lineOf(saleId: Long): Long = sales.findLines(saleId).single().id

    private fun request(saleId: Long, lineId: Long, milli: Long, restock: Boolean = true) =
        SaleReturnRequest(
            saleId,
            listOf(ReturnLineRequest(lineId, QuantityMilli(milli), restock)),
            ReturnSettlement.Refund(cashId),
        )

    @Test
    fun aReturnEntersStockAtTheFrozenCostAndRefundsFromTheAccount() = runBlocking {
        val saleId = threeSoaps()
        val lineId = lineOf(saleId)
        val sold = sales.findLines(saleId).single().content
        val before = changeSeq()
        val cashBefore = checkNotNull(accounts.findById(cashId)).balance

        val result = giveBack(request(saleId, lineId, 1_000))

        val returnId = (result as RegisterSaleReturnResult.Registered).saleReturnId
        assertEquals(Money(3_500), result.totalRefund)
        assertEquals(before + 1, changeSeq())
        val header = checkNotNull(returns.findById(returnId)).content
        assertEquals(ReturnResolution.REFUND, header.resolution)
        assertEquals(Money(3_500), header.netRefund)
        assertEquals(cashId, header.moneyAccountId)
        val line = returns.findLines(returnId).single()
        assertEquals(QuantityMilli(1_000), line.content.quantity)
        val entry = stock.findByProduct(soapId).last()
        assertEquals(StockMovementType.SALE_RETURN, entry.movement.type)
        assertEquals(line.id, entry.source.saleReturnLineId)
        assertEquals(sold.unitCost, entry.unitCost)
        assertEquals(QuantityMilli(8_000), checkNotNull(products.findById(soapId)).onHand)
        val refund = money.findByAccount(cashId).last()
        assertEquals(MoneyMovementType.REFUND, refund.type)
        assertEquals(MoneyOrigin.SaleReturn(returnId), refund.draft.origin)
        assertEquals(cashBefore - Money(3_500), checkNotNull(accounts.findById(cashId)).balance)
        assertEquals(
            mapOf(
                lineId to
                    LineAmounts(
                        QuantityMilli(1_000),
                        Money(3_500),
                        Money.ZERO,
                        header.costReturned,
                    ),
            ),
            returns.takenBySale(saleId),
        )
        assertInvariantsHold()
    }

    @Test
    fun withoutRestockNothingEntersAndTheSaleIsNotVoided() = runBlocking {
        val saleId = threeSoaps()
        val rows = stock.findByProduct(soapId).size

        val result = giveBack(request(saleId, lineOf(saleId), 2_000, restock = false))

        val returnId = (result as RegisterSaleReturnResult.Registered).saleReturnId
        assertEquals(rows, stock.findByProduct(soapId).size)
        assertEquals(Money.ZERO, checkNotNull(returns.findById(returnId)).content.costReturned)
        assertEquals(
            VoidSaleResult.HasActiveReturns,
            voidSale(saleId, VoidReason.ENTRY_ERROR, null),
        )
        assertInvariantsHold()
    }

    @Test
    fun theWholeLineReturnedInPartsNeverPassesWhatWasSold() = runBlocking {
        val saleId = threeSoaps()
        val lineId = lineOf(saleId)
        repeat(3) { giveBack(request(saleId, lineId, 1_000)) }

        assertEquals(
            RegisterSaleReturnResult.AboveRemaining(0, QuantityMilli.ZERO),
            giveBack(request(saleId, lineId, 1_000)),
        )
        val refunded = count("SELECT SUM(total_refund) FROM sale_returns WHERE sale_id = $saleId")
        assertEquals(10_500L, refunded)
        assertEquals(QuantityMilli(10_000), checkNotNull(products.findById(soapId)).onHand)
        assertInvariantsHold()
    }

    @Test
    fun aFailureAtEachWriteLeavesNoReturnWithoutItsStockOrMoney() = runBlocking {
        val saleId = threeSoaps()
        val lineId = lineOf(saleId)
        val before = tableCounts()
        val seq = changeSeq()
        val cashBefore = checkNotNull(accounts.findById(cashId)).balance
        var at = 1
        while (true) {
            failAt = at
            writes = 0
            val failed = runCatching { giveBack(request(saleId, lineId, 1_000)) }
                .exceptionOrNull() ?: break
            assertTrue(
                "Escritura $at: ${failed.javaClass.simpleName}",
                failed is InjectedWriteFailure,
            )
            assertEquals("Escritura $at", before, tableCounts())
            assertEquals(cashBefore, checkNotNull(accounts.findById(cashId)).balance)
            assertEquals(seq, changeSeq())
            at++
        }
        // La cabecera, su línea, la entrada al stock y el dinero: cuatro escrituras.
        assertEquals(RETURN_WRITES + 1, at)
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
        const val RETURN_WRITES = 4
        val TABLES = listOf(
            "sale_returns",
            "sale_return_lines",
            "stock_movements",
            "money_movements",
        )
    }
}
