package io.github.yesidrobayo.shopledger.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yesidrobayo.shopledger.data.local.TEST_BUSINESS_CURRENCY
import io.github.yesidrobayo.shopledger.data.local.TEST_BUSINESS_DECIMALS
import io.github.yesidrobayo.shopledger.data.local.catalog.CREATED_AT
import io.github.yesidrobayo.shopledger.data.local.catalog.CatalogDatabaseTest
import io.github.yesidrobayo.shopledger.data.local.catalog.MUCH_LATER
import io.github.yesidrobayo.shopledger.data.local.money.appendMoneyMovement
import io.github.yesidrobayo.shopledger.data.local.money.moneyAccountRow
import io.github.yesidrobayo.shopledger.data.local.money.moneyMovement
import io.github.yesidrobayo.shopledger.data.local.purchaseRow
import io.github.yesidrobayo.shopledger.data.local.saleRow
import io.github.yesidrobayo.shopledger.data.local.update
import io.github.yesidrobayo.shopledger.data.transaction.BackupStateChangeCounter
import io.github.yesidrobayo.shopledger.data.transaction.RestoreLock
import io.github.yesidrobayo.shopledger.data.transaction.RoomTransactionRunner
import io.github.yesidrobayo.shopledger.data.transaction.runnerFor
import io.github.yesidrobayo.shopledger.domain.model.audit.AuditEventType
import io.github.yesidrobayo.shopledger.domain.model.audit.AuditResult
import io.github.yesidrobayo.shopledger.domain.model.settings.BusinessCurrency
import io.github.yesidrobayo.shopledger.domain.model.settings.CurrencyCode
import io.github.yesidrobayo.shopledger.domain.model.settings.CurrencyCorrection
import io.github.yesidrobayo.shopledger.domain.port.AuditRecorder
import io.github.yesidrobayo.shopledger.domain.port.Clock
import io.github.yesidrobayo.shopledger.domain.port.CurrencyCorrectionRepository
import io.github.yesidrobayo.shopledger.domain.port.SafetyCopyPort
import io.github.yesidrobayo.shopledger.domain.port.StorageFailure
import io.github.yesidrobayo.shopledger.domain.port.StorageFailureException
import io.github.yesidrobayo.shopledger.domain.rule.region.CurrencyCorrectionProblem
import io.github.yesidrobayo.shopledger.domain.usecase.region.CorrectBusinessCurrencyUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.region.CorrectCurrencyResult
import kotlin.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Corregir la moneda del negocio sobre una base real con guardas: solo cambia la etiqueta de los
 * ajustes y de las cuentas que la seguían, ningún monto cambia, todo va en una sola transacción
 * que cuenta un cambio, y lo que la dejaría incoherente se rechaza sin copia. Las monedas son
 * códigos de prueba y los montos, inventados.
 */
@RunWith(AndroidJUnit4::class)
internal class CurrencyCorrectionOnDatabaseTest : CatalogDatabaseTest() {
    private lateinit var runner: RoomTransactionRunner
    private val settings by lazy { RoomAppSettingsRepository(database, RestoreLock { false }) }
    private val corrections by lazy { RoomCurrencyCorrectionRepository(database) }
    private val audits = mutableListOf<Pair<AuditEventType, AuditResult>>()
    private var copies = 0
    private val xtt = requireNotNull(CurrencyCode.of("XTT"))

    @Before
    fun createRunner() {
        runner = runnerFor(database, BackupStateChangeCounter(database.backupStateDao()))
    }

    private fun correction(
        repository: CurrencyCorrectionRepository = corrections,
    ): CorrectBusinessCurrencyUseCase = CorrectBusinessCurrencyUseCase(
        transactions = runner,
        settings = settings,
        corrections = repository,
        safetyCopy =
            SafetyCopyPort {
                copies += 1
                null
            },
        audit = AuditRecorder { type, result -> audits += type to result },
        clock = Clock { Instant.fromEpochMilliseconds(MUCH_LATER) },
    )

    @Test
    fun onlyTheLabelChangesAndEveryAmountStaysTheSame() = runBlocking {
        val bank = insertAccount("banco", TEST_BUSINESS_CURRENCY)
        val foreign = insertAccount("ahorro", "XTU")
        database.appendMoneyMovement(moneyMovement(bank, "OPENING_BALANCE", 50_000, 50_000))
        database.appendMoneyMovement(moneyMovement(foreign, "OPENING_BALANCE", 7_000, 7_000))
        val priceListId = count("SELECT id FROM price_lists WHERE system_key = 'DEFAULT'")
        execute(saleRow(priceListId).insertInto("sales"))
        execute(purchaseRow().insertInto("purchases"))
        val amountsBefore = amounts()
        val seq = changeSeq()

        val result = correction()(CurrencyCorrection(xtt, TEST_BUSINESS_DECIMALS.toInt()))

        val corrected =
            BusinessCurrency(
                code = xtt,
                decimals = TEST_BUSINESS_DECIMALS.toInt(),
                symbol = null,
                lockedAt = Instant.fromEpochMilliseconds(CREATED_AT),
            )
        assertEquals(CorrectCurrencyResult.Applied(corrected, accounts = 1), result)
        assertEquals(corrected, settings.get().regional.currency)
        // La cuenta que seguía a la moneda del negocio la sigue; la de otra moneda no cambia.
        assertEquals("XTT", accountCode(bank))
        assertEquals("XTU", accountCode(foreign))
        assertEquals(amountsBefore, amounts())
        // Una sola transacción, contada una vez, después de una copia.
        assertEquals(seq + 1, changeSeq())
        assertEquals(1, copies)
        assertEquals(listOf(AuditEventType.CURRENCY_CORRECTED to AuditResult.SUCCESS), audits)
    }

    @Test
    fun aFailureAfterChangingTheLabelLeavesNothingBehind() = runBlocking {
        val bank = insertAccount("banco", TEST_BUSINESS_CURRENCY)
        database.appendMoneyMovement(moneyMovement(bank, "OPENING_BALANCE", 50_000, 50_000))
        val seq = changeSeq()
        val failing =
            object : CurrencyCorrectionRepository by corrections {
                override suspend fun relabel(
                    from: CurrencyCode,
                    to: CurrencyCode,
                    decimals: Int,
                    updatedAt: Instant,
                ): Int {
                    corrections.relabel(from, to, decimals, updatedAt)
                    throw StorageFailureException(StorageFailure.IO_ERROR)
                }
            }

        val result = correction(failing)(CurrencyCorrection(xtt, TEST_BUSINESS_DECIMALS.toInt()))

        assertEquals(CorrectCurrencyResult.StorageFailed(StorageFailure.IO_ERROR), result)
        assertEquals(TEST_BUSINESS_CURRENCY, settings.get().regional.currency.code.code)
        assertEquals(TEST_BUSINESS_CURRENCY, accountCode(bank))
        assertEquals(seq, changeSeq())
        assertEquals(listOf(AuditEventType.CURRENCY_CORRECTED to AuditResult.FAILURE), audits)
    }

    @Test
    fun whatWouldLeaveSomethingIncoherentIsRejectedWithoutACopy() = runBlocking {
        val decimals = TEST_BUSINESS_DECIMALS.toInt()
        insertAccount("ahorro", "XTU")
        execute(
            "INSERT INTO fx_rates (currency_code, rate_micro, note, occurred_at, " +
                "business_date, created_at) " +
                "VALUES ('XTV', 1000000, NULL, $CREATED_AT, '2026-01-01', $CREATED_AT)",
        )
        execute(
            purchaseRow()
                .with("fx_currency_code" to "XTW", "fx_rate_micro" to 1_000_000L)
                .insertInto("purchases"),
        )
        execute(
            update(
                "app_settings",
                1,
                "reference_currency_code" to "XTR",
                "reference_currency_decimals" to TEST_BUSINESS_DECIMALS,
            ),
        )
        val seq = changeSeq()

        val expected =
            mapOf(
                "XTU" to CurrencyCorrectionProblem.USED_BY_ACCOUNT,
                "XTV" to CurrencyCorrectionProblem.USED_AS_OTHER_CURRENCY,
                "XTW" to CurrencyCorrectionProblem.USED_AS_OTHER_CURRENCY,
                "XTR" to CurrencyCorrectionProblem.SAME_AS_REFERENCE,
            )
        for ((code, problem) in expected) {
            val request = CurrencyCorrection(requireNotNull(CurrencyCode.of(code)), decimals)
            assertEquals(CorrectCurrencyResult.Rejected(problem), correction()(request))
        }

        assertEquals(TEST_BUSINESS_CURRENCY, settings.get().regional.currency.code.code)
        assertEquals(seq, changeSeq())
        assertEquals(0, copies)
        assertEquals(emptyList<Pair<AuditEventType, AuditResult>>(), audits)
    }

    private suspend fun insertAccount(name: String, code: String): Long {
        execute(moneyAccountRow(name).with("currency_code" to code).insertInto("money_accounts"))
        return count("SELECT id FROM money_accounts WHERE normalized_name = '$name'")
    }

    private suspend fun accountCode(id: Long): String? =
        database.moneyAccountDao().findById(id)?.currencyCode

    private suspend fun changeSeq(): Long =
        requireNotNull(database.backupStateDao().get()).changeSeq

    /** Cada monto guardado que una corrección podría tocar, en un orden fijo. */
    private suspend fun amounts(): List<Long> = listOf(
        count("SELECT COALESCE(SUM(amount), 0) FROM money_movements"),
        count("SELECT COALESCE(SUM(balance_after), 0) FROM money_movements"),
        count("SELECT COALESCE(SUM(balance), 0) FROM money_accounts"),
        count("SELECT COALESCE(SUM(total), 0) FROM sales"),
        count("SELECT COALESCE(SUM(amount_due), 0) FROM sales"),
        count("SELECT COALESCE(SUM(total), 0) FROM purchases"),
        count("SELECT cash_rounding FROM app_settings WHERE id = 1"),
    )
}
