package io.github.yesidrobayo.shopledger.data.repository

import androidx.room3.useReaderConnection
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yesidrobayo.shopledger.data.backup.InvariantQueries
import io.github.yesidrobayo.shopledger.data.local.texts
import io.github.yesidrobayo.shopledger.data.transaction.RestoreLock
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.money.AccountCurrency
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyAccountDraft
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyAccountKind
import io.github.yesidrobayo.shopledger.domain.model.money.NewMoneyMovement
import io.github.yesidrobayo.shopledger.domain.model.money.TransferRequest
import io.github.yesidrobayo.shopledger.domain.model.settings.CurrencyCode
import io.github.yesidrobayo.shopledger.domain.port.Clock
import io.github.yesidrobayo.shopledger.domain.port.MoneyLedgerRepository
import io.github.yesidrobayo.shopledger.domain.usecase.money.MoneyLedgerWriter
import io.github.yesidrobayo.shopledger.domain.usecase.money.SaveMoneyAccountResult
import io.github.yesidrobayo.shopledger.domain.usecase.money.SaveMoneyAccountUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.money.TransferMoneyResult
import io.github.yesidrobayo.shopledger.domain.usecase.money.TransferMoneyUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.money.VoidMoneyMovementResult
import io.github.yesidrobayo.shopledger.domain.usecase.money.VoidMoneyMovementUseCase
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Transferencias sobre la base real: dos partes de un grupo en una transacción, también entre monedas,
 * y su anulación con un compensatorio por parte, que la guarda admite. Si una escritura falla a mitad,
 * no queda ninguna parte, ni al transferir ni al anular. Las consultas de los invariantes de la copia
 * quedan en cero. Nombres y montos inventados.
 */
@RunWith(AndroidJUnit4::class)
internal class MoneyTransferOnDatabaseTest : RepositoryTest() {
    private val accounts by lazy { RoomMoneyAccountRepository(database) }
    private val ledger by lazy { RoomMoneyLedgerRepository(database) }
    private val settings by lazy { RoomAppSettingsRepository(database, RestoreLock { false }) }
    private val clock = Clock { LATER_AT }

    private fun writer(ledger: MoneyLedgerRepository = this.ledger) =
        MoneyLedgerWriter(accounts, ledger)

    private fun transfer(ledger: MoneyLedgerRepository = this.ledger) =
        TransferMoneyUseCase(runner, settings, accounts, writer(ledger), clock)

    private fun void(ledger: MoneyLedgerRepository = this.ledger) =
        VoidMoneyMovementUseCase(runner, settings, ledger, writer(ledger), clock)

    private suspend fun created(
        name: String,
        opening: Long,
        currency: AccountCurrency? = null,
    ): Long = (
        SaveMoneyAccountUseCase(runner, settings, accounts, writer(), clock)(
            null,
            MoneyAccountDraft(name, MoneyAccountKind.BANK, currency, Money(opening)),
        ) as SaveMoneyAccountResult.Saved
        ).accountId

    private suspend fun balance(id: Long): Money = requireNotNull(accounts.findById(id)).balance

    private suspend fun movementCount(): Long = count("SELECT COUNT(*) FROM money_movements")

    private suspend fun violations(): Map<String, List<String>> = InvariantQueries.MODEL
        .filter { it.label.startsWith("I-16") || it.label.startsWith("I-17") }
        .associate { query ->
            query.label to
                database.useReaderConnection { it.texts(query.sql) }
        }
        .filterValues { it.isNotEmpty() }

    /** El libro real, salvo que el agregado número [failAt] falla antes de escribir. */
    private fun failingAt(failAt: Int): MoneyLedgerRepository =
        object : MoneyLedgerRepository by ledger {
            private var appends = 0

            override suspend fun append(movement: NewMoneyMovement): Long {
                appends++
                check(appends != failAt) { "Falla inventada" }
                return ledger.append(movement)
            }
        }

    /** Corre [block] y exige que termine con la falla inventada, que deshace la transacción. */
    private suspend fun failsHalfway(block: suspend () -> Unit) {
        try {
            block()
            throw AssertionError("La falla inventada no llegó")
        } catch (expected: IllegalStateException) {
            assertEquals("Falla inventada", expected.message)
        }
    }

    @Test
    fun aTransferAndItsVoidKeepBothBalancesAndTheGroupWhole() = runBlocking {
        val bank = created("Banco inventado", 50_000)
        val box = created("Caja inventada", 0)

        val done = transfer()(
            TransferRequest(bank, box, Money(30_000)),
        ) as TransferMoneyResult.Transferred

        assertEquals(Money(20_000), balance(bank))
        assertEquals(Money(30_000), balance(box))
        assertEquals(
            listOf(done.outId, done.inId),
            ledger.findByTransferGroup(done.groupId).map {
                it.id
            },
        )
        assertEquals(emptyMap<String, List<String>>(), violations())

        val voided = void()(done.outId) as VoidMoneyMovementResult.Voided
        assertEquals(Money(50_000), balance(bank))
        assertEquals(Money.ZERO, balance(box))
        assertTrue(ledger.isCompensated(done.inId))
        assertEquals(VoidMoneyMovementResult.AlreadyVoided, void()(done.inId))
        assertEquals(VoidMoneyMovementResult.FromDocument, void()(voided.voidMovementId))
        assertEquals(emptyMap<String, List<String>>(), violations())
    }

    @Test
    fun betweenCurrenciesEachPartGoesInItsOwnCurrency() = runBlocking {
        val bank = created("Banco inventado", 10_000)
        val foreign =
            created(
                "Divisas inventadas",
                0,
                AccountCurrency(requireNotNull(CurrencyCode.of("XBA")), 0),
            )

        val done = transfer()(
            TransferRequest(bank, foreign, Money(10_000), fxRateMicro = 4_150_000_000),
        ) as TransferMoneyResult.Transferred

        assertEquals(Money.ZERO, balance(bank))
        assertEquals(Money(415_000), balance(foreign))
        assertEquals(Money(415_000), ledger.findById(done.inId)?.amount)
        void()(done.inId)
        assertEquals(Money(10_000), balance(bank))
        assertEquals(Money.ZERO, balance(foreign))
        assertEquals(emptyMap<String, List<String>>(), violations())
    }

    @Test
    fun aFailureHalfwayLeavesNoPartWhenTransferringOrVoiding() = runBlocking {
        val bank = created("Banco inventado", 50_000)
        val box = created("Caja inventada", 0)
        val before = movementCount()
        val seq = changeSeq()

        failsHalfway { transfer(failingAt(2))(TransferRequest(bank, box, Money(30_000))) }
        assertEquals(before, movementCount())
        assertEquals(Money(50_000), balance(bank))
        assertEquals(seq, changeSeq())

        val done = transfer()(
            TransferRequest(bank, box, Money(30_000)),
        ) as TransferMoneyResult.Transferred
        val afterTransfer = movementCount()
        failsHalfway { void(failingAt(2))(done.outId) }
        assertEquals(afterTransfer, movementCount())
        assertTrue(!ledger.isCompensated(done.outId) && !ledger.isCompensated(done.inId))
        assertEquals(Money(20_000), balance(bank))
        assertEquals(emptyMap<String, List<String>>(), violations())
    }
}
