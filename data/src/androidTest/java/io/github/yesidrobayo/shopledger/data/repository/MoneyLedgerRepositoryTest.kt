package io.github.yesidrobayo.shopledger.data.repository

import androidx.room3.useReaderConnection
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yesidrobayo.shopledger.data.backup.InvariantQueries
import io.github.yesidrobayo.shopledger.data.local.texts
import io.github.yesidrobayo.shopledger.data.transaction.RestoreLock
import io.github.yesidrobayo.shopledger.domain.model.ArchiveFilter
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.money.AccountCurrency
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyAccountDraft
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyAccountKind
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyMovementDraft
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyMovementType
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyOrigin
import io.github.yesidrobayo.shopledger.domain.model.money.NewMoneyMovement
import io.github.yesidrobayo.shopledger.domain.model.region.RecordTime
import io.github.yesidrobayo.shopledger.domain.model.settings.CurrencyCode
import io.github.yesidrobayo.shopledger.domain.port.Clock
import io.github.yesidrobayo.shopledger.domain.port.StorageFailure
import io.github.yesidrobayo.shopledger.domain.port.TxResult
import io.github.yesidrobayo.shopledger.domain.rule.money.MoneyLedger
import io.github.yesidrobayo.shopledger.domain.usecase.money.ArchiveMoneyAccountUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.money.MoneyAccountLifecycleResult
import io.github.yesidrobayo.shopledger.domain.usecase.money.MoneyAppendResult
import io.github.yesidrobayo.shopledger.domain.usecase.money.MoneyLedgerWriter
import io.github.yesidrobayo.shopledger.domain.usecase.money.ObserveMoneyAccountsUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.money.SaveMoneyAccountResult
import io.github.yesidrobayo.shopledger.domain.usecase.money.SaveMoneyAccountUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.money.VoidMoneyMovementResult
import io.github.yesidrobayo.shopledger.domain.usecase.money.VoidMoneyMovementUseCase
import kotlin.random.Random
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * El libro de dinero sobre la base real, con sus casos de uso: el saldo inicial entra como movimiento,
 * cada saldo es la suma de su libro y sigue su cadena, una anulación compensa una sola vez, un saldo
 * mal calculado no entra, y una cuenta en otra moneda nunca se suma a la del negocio. Las consultas
 * de los invariantes de la copia confirman lo mismo. Nombres y montos inventados.
 */
@RunWith(AndroidJUnit4::class)
internal class MoneyLedgerRepositoryTest : RepositoryTest() {
    private val accounts by lazy { RoomMoneyAccountRepository(database) }
    private val ledger by lazy { RoomMoneyLedgerRepository(database) }
    private val settings by lazy { RoomAppSettingsRepository(database, RestoreLock { false }) }
    private val writer by lazy { MoneyLedgerWriter(accounts, ledger) }
    private val clock = Clock { LATER_AT }
    private val save by lazy { SaveMoneyAccountUseCase(runner, settings, accounts, writer, clock) }
    private val void by lazy { VoidMoneyMovementUseCase(runner, settings, ledger, writer, clock) }
    private val time = RecordTime(AT, DATE)

    private suspend fun created(
        name: String,
        opening: Long,
        currency: AccountCurrency? = null,
    ): Long = (
        save(
            null,
            MoneyAccountDraft(name, MoneyAccountKind.BANK, currency, Money(opening)),
        ) as SaveMoneyAccountResult.Saved
        )
        .accountId

    private suspend fun append(draft: MoneyMovementDraft): MoneyAppendResult = runner.write {
        when (val result = writer.append(draft, time, AT)) {
            is MoneyAppendResult.Appended -> TxResult.Commit(result)
            else -> TxResult.Rollback(result)
        }
    }

    private suspend fun manual(account: Long, type: MoneyMovementType, amount: Long) =
        append(MoneyMovementDraft(account, type, Money(amount), MoneyOrigin.None))

    private suspend fun violations(): Map<String, List<String>> = InvariantQueries.MODEL
        .filter { it.label.startsWith("I-16") || it.label.startsWith("I-17") }
        .associate { query ->
            query.label to
                database.useReaderConnection { it.texts(query.sql) }
        }
        .filterValues { it.isNotEmpty() }

    @Test
    fun theLedgerExampleLeaves49000WithItsChainAndCountsEachWrite() = runBlocking {
        val before = changeSeq()
        val bank = created("Banco inventado", 50_000)
        manual(bank, MoneyMovementType.OWNER_CONTRIBUTION, 12_000)
        manual(bank, MoneyMovementType.OTHER_INCOME, 1)
        manual(bank, MoneyMovementType.OWNER_DRAW, -3_001)
        manual(bank, MoneyMovementType.OWNER_DRAW, -10_000)

        val rows = ledger.findByAccount(bank)
        assertEquals(
            listOf(50_000L, 62_000L, 62_001L, 59_000L, 49_000L),
            rows.map {
                it.balanceAfter.minor
            },
        )
        assertEquals(MoneyMovementType.OPENING_BALANCE, rows.first().type)
        assertEquals(Money(49_000), accounts.findById(bank)?.balance)
        assertEquals(before + 5, changeSeq())
        assertEquals(emptyMap<String, List<String>>(), violations())
    }

    @Test
    fun aVoidCompensatesOnceAndAWrongBalanceNeverEnters() = runBlocking {
        val bank = created("Banco inventado", 7_000)
        val opening = ledger.findByAccount(bank).single()

        val voidId = (
            void(
                opening.id,
                "Mal escrito",
            ) as VoidMoneyMovementResult.Voided
            ).voidMovementId
        assertEquals(VoidMoneyMovementResult.AlreadyVoided, void(opening.id))
        assertEquals(MoneyOrigin.Void(opening.id), ledger.findById(voidId)?.draft?.origin)
        assertEquals("Mal escrito", ledger.findById(voidId)?.draft?.note)
        assertTrue(ledger.isCompensated(opening.id))
        assertEquals(Money.ZERO, accounts.findById(bank)?.balance)
        // Lo registrado a mano no lleva a ningún documento, ni su anulación.
        assertNull(ledger.findDocument(opening.id))
        assertNull(ledger.findDocument(voidId))
        assertNull(ledger.findDocument(Long.MAX_VALUE))

        val wrong = NewMoneyMovement(
            MoneyMovementDraft(bank, MoneyMovementType.OTHER_INCOME, Money(10), MoneyOrigin.None),
            balanceAfter = Money(11),
            time = time,
            createdAt = AT,
        )
        assertEquals(
            StorageFailure.INVARIANT_VIOLATION,
            storageFailureOf {
                commit { ledger.append(wrong) }
            },
        )
        assertEquals(2, ledger.findByAccount(bank).size)
        assertEquals(emptyMap<String, List<String>>(), violations())
    }

    @Test
    fun aTransferPartAndItsRateReadBackFromTheirColumns() = runBlocking {
        val bank = created("Banco inventado", 0)
        val foreign =
            created(
                "Divisas inventadas",
                0,
                AccountCurrency(requireNotNull(CurrencyCode.of("XBA")), 0),
            )
        val group = "00000000-0000-4000-8000-000000000002"
        append(
            MoneyMovementDraft(
                bank,
                MoneyMovementType.TRANSFER_OUT,
                Money(-10_000),
                MoneyOrigin.Transfer(group, null),
            ),
        )
        append(
            MoneyMovementDraft(
                foreign,
                MoneyMovementType.TRANSFER_IN,
                Money(415_000),
                MoneyOrigin.Transfer(group, 4_150_000_000),
                availableDate = DATE,
                note = "Cambio inventado",
            ),
        )

        val parts = ledger.findByTransferGroup(group)
        assertEquals(listOf(bank, foreign), parts.map { it.accountId })
        assertEquals(MoneyOrigin.Transfer(group, 4_150_000_000), parts.last().draft.origin)
        assertEquals(DATE, parts.last().draft.availableDate)
        assertEquals(time, parts.last().time)
        assertEquals(emptyMap<String, List<String>>(), violations())
    }

    @Test
    fun anAccountInAnotherCurrencyIsNeverAddedToTheBusinessMoney() = runBlocking {
        created("Banco inventado", 12_000)
        val foreign =
            created(
                "Divisas inventadas",
                9_999,
                AccountCurrency(requireNotNull(CurrencyCode.of("XBA")), 0),
            )
        val archived = created("Vieja inventada", 5)
        assertEquals(
            MoneyAccountLifecycleResult.Done,
            ArchiveMoneyAccountUseCase(runner, accounts, clock)(archived),
        )

        val overview = ObserveMoneyAccountsUseCase(settings, accounts)().first()
        assertEquals(Money(12_000), overview.available)
        // La cuenta inicial de estas pruebas conserva la moneda sin confirmar: tampoco se suma.
        val cash = requireNotNull(database.moneyAccountDao().findBySystemKey("CASH")).id
        assertEquals(listOf(cash, foreign), overview.inOtherCurrencies.map { it.id })
        assertEquals(
            listOf(archived),
            accounts.observe(ArchiveFilter.ARCHIVED).first().map {
                it.id
            },
        )
        assertEquals(archived, accounts.findById(archived)?.id)
        assertEquals(null, accounts.findActiveByNormalizedName("vieja inventada"))
    }

    @Test
    fun aRandomSequenceKeepsEveryBalanceEqualToItsLedger() = runBlocking {
        val random = Random(SEED)
        val ids = List(3) { created("Cuenta inventada $it", random.nextLong(-50_000, 50_000)) }
        repeat(STEPS) {
            val account = ids.random(random)
            when (random.nextInt(4)) {
                0 -> manual(account, MoneyMovementType.OWNER_DRAW, -random.nextLong(1, 100_000))

                1 -> manual(
                    account,
                    MoneyMovementType.OWNER_CONTRIBUTION,
                    random.nextLong(1, 100_000),
                )

                2 -> manual(account, MoneyMovementType.OTHER_INCOME, random.nextLong(1, 100_000))

                else -> ledger.findByAccount(account).randomOrNull(random)?.let { void(it.id) }
            }
        }

        val rows = ids.flatMap { ledger.findByAccount(it) }
        val balances = ids.associateWith { requireNotNull(accounts.findById(it)).balance }
        assertTrue(MoneyLedger.brokenAccounts(balances, rows).isEmpty())
        assertEquals(emptyMap<String, List<String>>(), violations())
    }

    private companion object {
        const val SEED = 20_261_112L
        const val STEPS = 80
    }
}
