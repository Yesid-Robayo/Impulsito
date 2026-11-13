package io.github.yesidrobayo.shopledger.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yesidrobayo.shopledger.data.backup.InvariantQueries
import io.github.yesidrobayo.shopledger.data.transaction.RestoreLock
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.money.AccountCurrency
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyAccountDetails
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyAccountKind
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyMovementDraft
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyMovementType
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyOrigin
import io.github.yesidrobayo.shopledger.domain.model.money.NewMoneyAccount
import io.github.yesidrobayo.shopledger.domain.model.region.RecordTime
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleLineRequest
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleRequest
import io.github.yesidrobayo.shopledger.domain.port.Clock
import io.github.yesidrobayo.shopledger.domain.port.PaymentMethodNames
import io.github.yesidrobayo.shopledger.domain.usecase.cash.OpenCashSessionResult
import io.github.yesidrobayo.shopledger.domain.usecase.cash.OpenCashSessionUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.money.MoneyAppendResult
import io.github.yesidrobayo.shopledger.domain.usecase.money.MoneyLedgerWriter
import io.github.yesidrobayo.shopledger.domain.usecase.sale.RegisterSaleResult
import io.github.yesidrobayo.shopledger.domain.usecase.sale.RegisterSaleUseCase
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * La caja sobre la base real. Es opcional: una venta se registra sin ninguna sesión, sin pedir nada
 * ni dejar rastro en la caja. Abierta, cada movimiento de la cuenta cae en la sesión, y una
 * diferencia al abrir lleva la cuenta a lo contado. Los nombres y los montos son inventados.
 */
@RunWith(AndroidJUnit4::class)
internal class CashSessionOnDatabaseTest : RepositoryTest() {
    private val settings by lazy { RoomAppSettingsRepository(database, RestoreLock { false }) }
    private val accounts by lazy { RoomMoneyAccountRepository(database) }
    private val money by lazy { RoomMoneyLedgerRepository(database) }
    private val sessions by lazy { RoomCashSessionRepository(database) }
    private val products by lazy { RoomProductRepository(database) }
    private val ledger by lazy { RoomStockLedgerRepository(database) }

    private val open by lazy {
        OpenCashSessionUseCase(runner, settings, accounts, money, sessions, Clock { AT })
    }

    private val registerSale by lazy {
        RegisterSaleUseCase(
            transactions = runner,
            settings = settings,
            products = products,
            sales = RoomSaleRepository(database),
            payments = RoomSalePaymentRepository(database),
            units = RoomUnitRepository(database),
            systemRows = RoomSystemRowRepository(database),
            ledger = ledger,
            methodNames = PaymentMethodNames { key -> "Initial ${key.lowercase()}" },
            taxRates = RoomTaxRateRepository(database),
            clock = Clock { AT },
        )
    }

    /** Una caja de efectivo en la moneda del negocio. */
    private suspend fun cashAccount(): Long = commit {
        accounts.insert(
            NewMoneyAccount(
                MoneyAccountDetails("Caja inventada", "caja inventada", MoneyAccountKind.CASH),
                AccountCurrency.of(settings.get().regional.currency),
                AT,
            ),
        )
    }

    private suspend fun sell(): RegisterSaleResult {
        val productId = commit { products.insert(newProduct("Pan inventado")) }
        val method = count("SELECT id FROM payment_methods WHERE system_key = 'CASH'")
        return registerSale(
            SaleRequest(listOf(SaleLineRequest(productId, QuantityMilli(1_000))), method),
        )
    }

    @Test
    fun aSaleWithoutCashSessionAsksForNothing() = runBlocking {
        val result = sell()

        assertTrue(result is RegisterSaleResult.Registered)
        assertEquals(0L, count("SELECT COUNT(*) FROM cash_sessions"))
        assertEquals(0L, count("SELECT COUNT(*) FROM sales WHERE cash_session_id IS NOT NULL"))
        assertInvariantsHold()
    }

    @Test
    fun aSaleStillRegistersWithASessionOpen() = runBlocking {
        val account = cashAccount()
        assertTrue(open(account) is OpenCashSessionResult.Opened)

        assertTrue(sell() is RegisterSaleResult.Registered)
        assertInvariantsHold()
    }

    @Test
    fun aDifferentCountAtOpeningMovesTheAccountAsOneChange() = runBlocking {
        val account = cashAccount()
        val before = changeSeq()

        val id = (open(account, Money(2_500)) as OpenCashSessionResult.Opened).sessionId

        assertEquals(before + 1, changeSeq())
        assertEquals(Money(2_500), requireNotNull(sessions.findById(id)).opening.openingAmount)
        assertEquals(Money(2_500), requireNotNull(accounts.findById(account)).balance)
        assertEquals(
            1L,
            count(
                "SELECT COUNT(*) FROM money_movements WHERE type = 'CASH_DIFFERENCE' " +
                    "AND cash_session_id = $id",
            ),
        )
        // La diferencia de la apertura no cuenta en lo esperado.
        assertEquals(Money.ZERO, sessions.movementsTotal(id))
        assertEquals(OpenCashSessionResult.AlreadyOpen, open(account))
        assertInvariantsHold()
    }

    @Test
    fun whileOpenEveryMovementOfTheAccountFallsInTheSession() = runBlocking {
        val account = cashAccount()
        val id = (open(account) as OpenCashSessionResult.Opened).sessionId

        val movementId = commit {
            val appended = MoneyLedgerWriter(accounts, money).append(
                MoneyMovementDraft(
                    account,
                    MoneyMovementType.OWNER_DRAW,
                    Money(-700),
                    MoneyOrigin.None,
                ),
                RecordTime(AT, DATE),
                AT,
            )
            (appended as MoneyAppendResult.Appended).movementId
        }

        assertEquals(id, requireNotNull(money.findById(movementId)).draft.cashSessionId)
        assertEquals(Money(-700), sessions.movementsTotal(id))
        assertEquals(id, accounts.openCashSessionId(account))
        assertInvariantsHold()
    }

    private suspend fun assertInvariantsHold() {
        for (query in InvariantQueries.MODEL) {
            val rows = query.sql.trimEnd().removeSuffix(";")
            assertEquals(query.label, 0L, count("SELECT COUNT(*) FROM ($rows)"))
        }
    }
}
