package io.github.yesidrobayo.shopledger.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yesidrobayo.shopledger.data.backup.InvariantQueries
import io.github.yesidrobayo.shopledger.data.transaction.RestoreLock
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.VoidReason
import io.github.yesidrobayo.shopledger.domain.model.Voiding
import io.github.yesidrobayo.shopledger.domain.model.money.AccountCurrency
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyAccountDetails
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyAccountKind
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyDocument
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyMovementType
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyOrigin
import io.github.yesidrobayo.shopledger.domain.model.money.NewMoneyAccount
import io.github.yesidrobayo.shopledger.domain.model.money.NewMoneyMovement
import io.github.yesidrobayo.shopledger.domain.model.sale.NewSale
import io.github.yesidrobayo.shopledger.domain.model.sale.NewSaleLine
import io.github.yesidrobayo.shopledger.domain.model.sale.NewSalePayment
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleLineRequest
import io.github.yesidrobayo.shopledger.domain.model.sale.SalePaymentRequest
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleRequest
import io.github.yesidrobayo.shopledger.domain.model.settings.CurrencyCode
import io.github.yesidrobayo.shopledger.domain.model.stock.NewStockMovement
import io.github.yesidrobayo.shopledger.domain.port.Clock
import io.github.yesidrobayo.shopledger.domain.port.MoneyAccountRepository
import io.github.yesidrobayo.shopledger.domain.port.MoneyLedgerRepository
import io.github.yesidrobayo.shopledger.domain.port.PaymentMethodNames
import io.github.yesidrobayo.shopledger.domain.port.SalePaymentRepository
import io.github.yesidrobayo.shopledger.domain.port.SaleRepository
import io.github.yesidrobayo.shopledger.domain.port.StockLedgerRepository
import io.github.yesidrobayo.shopledger.domain.usecase.cash.OpenCashSessionResult
import io.github.yesidrobayo.shopledger.domain.usecase.cash.OpenCashSessionUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.sale.RegisterSaleResult
import io.github.yesidrobayo.shopledger.domain.usecase.sale.RegisterSaleUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.sale.VoidSaleResult
import io.github.yesidrobayo.shopledger.domain.usecase.sale.VoidSaleUseCase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * La venta que mueve su cuenta de dinero, sobre la base real y sus guardas: la venta, sus pagos, el
 * `SALE_RECEIPT`, el `FEE` y el saldo de la cuenta entran en una sola transacción, y las consultas
 * de invariantes, también la del saldo igual a la suma del libro, quedan en cero filas. Una falla
 * inyectada en cada escritura de la venta y de su anulación no deja nada a medias.
 *
 * Las cuentas, los productos y los montos son inventados.
 */
@RunWith(AndroidJUnit4::class)
internal class SaleMoneyOnDatabaseTest : RepositoryTest() {
    private val products by lazy { RoomProductRepository(database) }
    private val stock by lazy { RoomStockLedgerRepository(database) }
    private val salePayments by lazy { RoomSalePaymentRepository(database) }
    private val settings by lazy { RoomAppSettingsRepository(database, RestoreLock { false }) }
    private val sales by lazy { RoomSaleRepository(database) }
    private val accounts by lazy { RoomMoneyAccountRepository(database) }
    private val money by lazy { RoomMoneyLedgerRepository(database) }
    private val sessions by lazy { RoomCashSessionRepository(database) }

    /** La escritura número N falla; `null` no falla nunca. */
    private var failAt: Int? = null
    private var writes = 0

    /** Cuántas veces se leyó una cuenta o su caja. */
    private var accountReads = 0

    private var bankId = 0L
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

            override suspend fun markVoided(id: Long, voiding: Voiding) {
                tick()
                sales.markVoided(id, voiding)
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

    private val countedMoney by lazy {
        object : MoneyLedgerRepository by money {
            override suspend fun append(movement: NewMoneyMovement): Long {
                tick()
                return money.append(movement)
            }
        }
    }

    private val countedAccounts by lazy {
        object : MoneyAccountRepository by accounts {
            override suspend fun findById(id: Long) = accounts.findById(id).also { accountReads++ }

            override suspend fun openCashSessionId(accountId: Long): Long? =
                accounts.openCashSessionId(accountId).also { accountReads++ }
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
            moneyAccounts = countedAccounts,
            moneyLedger = countedMoney,
        )
    }

    private val voidSale by lazy {
        VoidSaleUseCase(
            transactions = runner,
            settings = settings,
            products = products,
            sales = countedSales,
            ledger = countedStock,
            clock = Clock { AT },
            payments = salePayments,
            moneyAccounts = countedAccounts,
            moneyLedger = countedMoney,
        )
    }

    @Before
    fun createAccountAndProduct() = runBlocking {
        bankId = account("Banco inventado", MoneyAccountKind.BANK)
        soapId = commit {
            val id = products.insert(newProduct("Jabón inventado").copy(salePrice = Money(3_500)))
            stock.append(openingRow(id, 10))
            id
        }
        // La tarjeta cobra en el banco, con 2,75 % de comisión y dos días para acreditar.
        execute(
            "UPDATE payment_methods SET money_account_id = $bankId, fee_bp = 275, " +
                "settlement_days = 2 WHERE system_key = 'CARD'",
        )
    }

    private suspend fun account(
        name: String,
        kind: MoneyAccountKind,
        currency: AccountCurrency? = null,
    ): Long = commit {
        accounts.insert(
            NewMoneyAccount(
                MoneyAccountDetails(name, name.lowercase(), kind),
                currency ?: AccountCurrency.of(settings.get().regional.currency),
                AT,
            ),
        )
    }

    private suspend fun methodId(key: String): Long =
        count("SELECT id FROM payment_methods WHERE system_key = '$key'")

    /** Dos jabones, 7.000: 4.000 con tarjeta y 3.000 en efectivo sin cuenta. */
    private suspend fun cardSale(): SaleRequest = SaleRequest(
        lines = listOf(SaleLineRequest(soapId, QuantityMilli(2_000))),
        paymentMethodId = methodId("CARD"),
        payments = listOf(
            SalePaymentRequest(methodId("CARD"), Money(4_000)),
            SalePaymentRequest(methodId("CASH"), Money(3_000)),
        ),
    )

    private suspend fun cashSale(): SaleRequest =
        SaleRequest(listOf(SaleLineRequest(soapId, QuantityMilli(1_000))), methodId("CASH"))

    @Test
    fun aSaleWithACardAccountWritesItsReceiptAndFeeAndPassesEveryInvariant() = runBlocking {
        val before = changeSeq()

        val registered = registerSale(cardSale()) as RegisterSaleResult.Registered

        assertEquals(before + 1, changeSeq())
        assertFalse(registered.moneyAccountSkipped)
        val card = salePayments.findBySale(registered.saleId).first()
        val (receipt, fee) = money.findBySalePayment(card.id)
        assertEquals(MoneyMovementType.SALE_RECEIPT, receipt.type)
        assertEquals(Money(4_000), receipt.amount)
        assertEquals(MoneyMovementType.FEE, fee.type)
        // 4.000 al 2,75 % son 110.
        assertEquals(Money(-110), fee.amount)
        assertEquals(MoneyOrigin.SalePayment(card.id), receipt.draft.origin)
        assertEquals(card.content.settlementDate, receipt.draft.availableDate)
        assertEquals(Money(3_890), accounts.findById(bankId)?.balance)
        assertEquals(bankId, card.content.moneyAccountId)
        // Lo cobrado y su comisión llevan a la venta, y faltan por llegar hasta su acreditación.
        val sale = MoneyDocument.Sale(registered.saleId)
        assertEquals(sale, money.findDocument(receipt.id))
        assertEquals(sale, money.findDocument(fee.id))
        val settles = requireNotNull(card.content.settlementDate)
        assertEquals(
            mapOf(bankId to Money(3_890)),
            money.observePendingSettlement(settles.plusDays(-1)).first(),
        )
        assertEquals(emptyMap<Long, Money>(), money.observePendingSettlement(settles).first())
        assertInvariantsHold()
    }

    @Test
    fun aSaleWithoutAccountsWritesNoMovementAndReadsNoAccount() = runBlocking {
        val registered = registerSale(cashSale()) as RegisterSaleResult.Registered

        assertFalse(registered.moneyAccountSkipped)
        assertEquals(0L, count("SELECT COUNT(*) FROM money_movements"))
        assertEquals(0, accountReads)
        assertInvariantsHold()
    }

    @Test
    fun anAccountInAnotherCurrencyKeepsTheSaleAndMovesNothing() = runBlocking {
        val other = requireNotNull(CurrencyCode.of("XAU"))
        val foreign = account("Cuenta inventada", MoneyAccountKind.BANK, AccountCurrency(other, 0))
        execute("UPDATE payment_methods SET money_account_id = $foreign WHERE system_key = 'CARD'")

        val registered = registerSale(cardSale()) as RegisterSaleResult.Registered

        assertTrue(registered.moneyAccountSkipped)
        assertEquals(0L, count("SELECT COUNT(*) FROM money_movements"))
        assertEquals(
            0L,
            count("SELECT COUNT(*) FROM sale_payments WHERE money_account_id IS NOT NULL"),
        )
        assertInvariantsHold()
    }

    @Test
    fun withACashSessionOpenTheSaleRecordsIt() = runBlocking {
        val drawer = account("Caja inventada", MoneyAccountKind.CASH)
        execute("UPDATE payment_methods SET money_account_id = $drawer WHERE system_key = 'CASH'")
        val open = OpenCashSessionUseCase(runner, settings, accounts, money, sessions, Clock { AT })
        val session = (open(drawer) as OpenCashSessionResult.Opened).sessionId

        val registered = registerSale(cashSale()) as RegisterSaleResult.Registered

        assertEquals(
            session,
            count("SELECT cash_session_id FROM sales WHERE id = ${registered.saleId}"),
        )
        assertEquals(Money(3_500), sessions.movementsTotal(session))
        assertInvariantsHold()
    }

    @Test
    fun aFailureAtEachWriteLeavesNoSaleWithoutItsMoney() = runBlocking {
        val before = tableCounts()
        val seq = changeSeq()
        var at = 1
        while (true) {
            failAt = at
            writes = 0
            val failed = runCatching { registerSale(cardSale()) }.exceptionOrNull() ?: break
            assertTrue(
                "Escritura $at: ${failed.javaClass.simpleName}",
                failed is InjectedWriteFailure,
            )
            assertEquals("Escritura $at", before, tableCounts())
            assertEquals(Money.ZERO, accounts.findById(bankId)?.balance)
            assertEquals(seq, changeSeq())
            at++
        }
        // Cabecera, línea, su fila de stock, dos pagos, lo cobrado y la comisión: siete escrituras.
        assertEquals(REGISTER_WRITES + 1, at)
        assertEquals(Money(3_890), accounts.findById(bankId)?.balance)
        assertInvariantsHold()
    }

    @Test
    fun voidingTheSaleCompensatesEachMovement() = runBlocking {
        val saleId = (registerSale(cardSale()) as RegisterSaleResult.Registered).saleId
        val card = salePayments.findBySale(saleId).first()
        val original = money.findBySalePayment(card.id)

        val result = voidSale(saleId, VoidReason.ENTRY_ERROR, null)

        assertEquals(VoidSaleResult.Voided, result)
        val voids = money.findByAccount(bankId).drop(original.size)
        assertEquals(original.map { MoneyOrigin.Void(it.id) }, voids.map { it.draft.origin })
        assertEquals(original.map { -it.amount }, voids.map { it.amount })
        assertTrue(voids.all { it.type == MoneyMovementType.MOVEMENT_VOID })
        assertEquals(Money.ZERO, accounts.findById(bankId)?.balance)
        // La anulación no lleva el pago: solo apunta al movimiento que compensa.
        assertEquals(original, money.findBySalePayment(card.id))
        // Nada queda por acreditar, y la anulación lleva a la misma venta.
        assertEquals(MoneyDocument.Sale(saleId), money.findDocument(voids.first().id))
        val settles = requireNotNull(card.content.settlementDate)
        assertEquals(
            emptyMap<Long, Money>(),
            money.observePendingSettlement(settles.plusDays(-1)).first(),
        )
        assertInvariantsHold()
    }

    @Test
    fun aFailureAtEachWriteOfTheVoidLeavesTheSaleAndItsMoney() = runBlocking {
        val saleId = (registerSale(cardSale()) as RegisterSaleResult.Registered).saleId
        val movements = count("SELECT COUNT(*) FROM money_movements")
        var at = 1
        while (true) {
            failAt = at
            writes = 0
            val failed = runCatching {
                voidSale(saleId, VoidReason.ENTRY_ERROR, null)
            }.exceptionOrNull() ?: break
            assertTrue(
                "Escritura $at: ${failed.javaClass.simpleName}",
                failed is InjectedWriteFailure,
            )
            assertEquals(movements, count("SELECT COUNT(*) FROM money_movements"))
            assertEquals(Money(3_890), accounts.findById(bankId)?.balance)
            assertEquals(
                1L,
                count("SELECT COUNT(*) FROM sales WHERE id = $saleId AND voided_at IS NULL"),
            )
            at++
        }
        // La marca de anulada, la fila de stock que vuelve y dos compensaciones de dinero.
        assertEquals(VOID_WRITES + 1, at)
        assertEquals(Money.ZERO, accounts.findById(bankId)?.balance)
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
        const val REGISTER_WRITES = 7
        const val VOID_WRITES = 4
        val TABLES = listOf(
            "sales",
            "sale_lines",
            "sale_payments",
            "stock_movements",
            "money_movements",
        )
    }
}
