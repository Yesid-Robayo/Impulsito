package io.github.yesidrobayo.shopledger.domain.usecase.region

import io.github.yesidrobayo.shopledger.domain.model.audit.AuditEventType
import io.github.yesidrobayo.shopledger.domain.model.audit.AuditResult
import io.github.yesidrobayo.shopledger.domain.model.backup.SafetyCopyFailure
import io.github.yesidrobayo.shopledger.domain.model.settings.AppSettings
import io.github.yesidrobayo.shopledger.domain.model.settings.BusinessCurrency
import io.github.yesidrobayo.shopledger.domain.model.settings.CurrencyCode
import io.github.yesidrobayo.shopledger.domain.model.settings.CurrencyCorrection
import io.github.yesidrobayo.shopledger.domain.model.settings.ReferenceCurrency
import io.github.yesidrobayo.shopledger.domain.port.AuditRecorder
import io.github.yesidrobayo.shopledger.domain.port.CurrencyCorrectionRepository
import io.github.yesidrobayo.shopledger.domain.port.SafetyCopyPort
import io.github.yesidrobayo.shopledger.domain.port.StorageFailure
import io.github.yesidrobayo.shopledger.domain.port.StorageFailureException
import io.github.yesidrobayo.shopledger.domain.rule.region.CurrencyCorrectionProblem
import io.github.yesidrobayo.shopledger.domain.testing.FakeAppSettingsRepository
import io.github.yesidrobayo.shopledger.domain.testing.FakeTransactionRunner
import io.github.yesidrobayo.shopledger.domain.testing.FixedClock
import io.github.yesidrobayo.shopledger.domain.testing.InMemoryDatabase
import io.github.yesidrobayo.shopledger.domain.testing.appSettings
import io.github.yesidrobayo.shopledger.domain.testing.regionalSettings
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.DayOfWeek
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Corregir la moneda del negocio: revisa sin escribir, guarda la copia de seguridad, y solo
 * entonces cambia la etiqueta en una sola transacción que vuelve a revisar. Ningún monto se toca,
 * y la auditoría anota el éxito o la falla, nunca un rechazo. Las monedas son códigos de prueba.
 */
class CorrectBusinessCurrencyUseCaseTest {
    private val lockedAt = Instant.fromEpochMilliseconds(1_793_000_000_000)
    private val now = Instant.fromEpochMilliseconds(1_793_593_800_000)
    private val xts = code("XTS")
    private val xtt = code("XTT")

    // El negocio en XTS, con dos decimales, un símbolo propio y ya con registros con dinero.
    private val repository =
        FakeAppSettingsRepository(
            appSettings(
                regionalSettings("Etc/GMT+5", DayOfWeek.MONDAY).let {
                    it.copy(currency = BusinessCurrency(xts, 2, "¤", lockedAt))
                },
            ),
        )
    private val transactions = FakeTransactionRunner(InMemoryDatabase())
    private val events = mutableListOf<String>()
    private val corrections = FakeCorrections()
    private val audits = mutableListOf<Pair<AuditEventType, AuditResult>>()
    private var copyFailure: SafetyCopyFailure? = null

    /** Lo que pasa mientras se guarda la copia, para simular un cambio en ese momento. */
    private var duringCopy: () -> Unit = {}

    private val correct =
        CorrectBusinessCurrencyUseCase(
            transactions = transactions,
            settings = repository,
            corrections = corrections,
            safetyCopy =
                SafetyCopyPort {
                    events += "copy"
                    duringCopy()
                    copyFailure
                },
            audit = AuditRecorder { type, result -> audits += type to result },
            clock = FixedClock(now),
        )

    @Test
    fun `the label changes after the copy, in one counted transaction`() = runTest {
        corrections.followingAccounts = 2

        val result = correct(CurrencyCorrection(xtt, 2))

        val corrected = BusinessCurrency(xtt, 2, "¤", lockedAt)
        assertEquals(CorrectCurrencyResult.Applied(corrected, accounts = 2), result)
        assertEquals(listOf("copy", "relabel"), events)
        assertEquals(listOf(Relabel(xts, xtt, 2, now)), corrections.relabels)
        assertEquals(1, transactions.writeCalls)
        assertEquals(1, transactions.commits)
        assertEquals(0, transactions.rollbacks)
        // Ni los decimales, ni el bloqueo, ni el símbolo propio cambian.
        assertEquals(corrected, repository.settings.value.currency())
        assertEquals(listOf(AuditEventType.CURRENCY_CORRECTED to AuditResult.SUCCESS), audits)
    }

    @Test
    fun `a correction the rules reject spends no copy and writes nothing`() = runTest {
        val rejected =
            listOf(
                CurrencyCorrection(xts, 2) to CurrencyCorrectionProblem.SAME_AS_CURRENT,
                CurrencyCorrection(xtt, 3) to CurrencyCorrectionProblem.DECIMALS_DIFFER,
                CurrencyCorrection(CurrencyCode.UNCONFIRMED, 2) to
                    CurrencyCorrectionProblem.UNCONFIRMED,
            )
        for ((request, problem) in rejected) {
            assertEquals(CorrectCurrencyResult.Rejected(problem), correct(request))
        }

        repository.settings.value = repository.settings.value.withReference(xtt)
        assertEquals(
            CorrectCurrencyResult.Rejected(CurrencyCorrectionProblem.SAME_AS_REFERENCE),
            correct(CurrencyCorrection(xtt, 2)),
        )
        assertNothingHappened()
    }

    @Test
    fun `before the lock the currency changes in the form, not here`() = runTest {
        val settings = repository.settings.value
        repository.settings.value =
            settings.copy(
                regional =
                    settings.regional.copy(
                        currency = settings.regional.currency.copy(lockedAt = null),
                    ),
            )

        assertEquals(
            CorrectCurrencyResult.Rejected(CurrencyCorrectionProblem.NOT_LOCKED),
            correct(CurrencyCorrection(xtt, 2)),
        )
        assertNothingHappened()
    }

    @Test
    fun `a currency an own account already uses is rejected`() = runTest {
        corrections.accounts["XTT"] = 1

        assertEquals(
            CorrectCurrencyResult.Rejected(CurrencyCorrectionProblem.USED_BY_ACCOUNT),
            correct(CurrencyCorrection(xtt, 2)),
        )
        assertNothingHappened()
    }

    @Test
    fun `a currency saved as another currency in some record is rejected`() = runTest {
        corrections.otherCurrencies += "XTT"

        assertEquals(
            CorrectCurrencyResult.Rejected(CurrencyCorrectionProblem.USED_AS_OTHER_CURRENCY),
            correct(CurrencyCorrection(xtt, 2)),
        )
        assertNothingHappened()
    }

    @Test
    fun `without the safety copy nothing changes and the failure is audited`() = runTest {
        for (failure in SafetyCopyFailure.entries) {
            copyFailure = failure

            assertEquals(
                CorrectCurrencyResult.SafetyCopyFailed(failure),
                correct(CurrencyCorrection(xtt, 2)),
            )
        }

        assertEquals(0, transactions.writeCalls)
        assertTrue(corrections.relabels.isEmpty())
        assertEquals(xts, repository.settings.value.currency().code)
        assertEquals(
            List(SafetyCopyFailure.entries.size) {
                AuditEventType.CURRENCY_CORRECTED to AuditResult.FAILURE
            },
            audits,
        )
    }

    @Test
    fun `what changed during the copy is checked again inside the transaction`() = runTest {
        // Mientras se copiaba, esa moneda pasó a ser la de referencia desde otra pantalla.
        duringCopy = { repository.settings.value = repository.settings.value.withReference(xtt) }
        assertEquals(
            CorrectCurrencyResult.Rejected(CurrencyCorrectionProblem.SAME_AS_REFERENCE),
            correct(CurrencyCorrection(xtt, 2)),
        )

        // Y otra vez, ahora con una cuenta nueva en esa moneda.
        repository.settings.value = repository.settings.value.withReference(null)
        duringCopy = { corrections.accounts["XTT"] = 1 }
        assertEquals(
            CorrectCurrencyResult.Rejected(CurrencyCorrectionProblem.USED_BY_ACCOUNT),
            correct(CurrencyCorrection(xtt, 2)),
        )

        assertEquals(2, transactions.writeCalls)
        assertEquals(2, transactions.rollbacks)
        assertEquals(0, transactions.commits)
        assertTrue(corrections.relabels.isEmpty())
        assertTrue(audits.isEmpty())
    }

    @Test
    fun `a storage failure while writing leaves nothing and is audited`() = runTest {
        corrections.failRelabel = StorageFailure.DISK_FULL

        assertEquals(
            CorrectCurrencyResult.StorageFailed(StorageFailure.DISK_FULL),
            correct(CurrencyCorrection(xtt, 2)),
        )
        assertEquals(1, transactions.rollbacks)
        assertEquals(xts, repository.settings.value.currency().code)
        assertEquals(listOf(AuditEventType.CURRENCY_CORRECTED to AuditResult.FAILURE), audits)
    }

    @Test
    fun `a restore that starts after the copy keeps the database untouched`() = runTest {
        duringCopy = { transactions.restoreInProgress = true }

        assertEquals(
            CorrectCurrencyResult.StorageFailed(StorageFailure.RESTORE_IN_PROGRESS),
            correct(CurrencyCorrection(xtt, 2)),
        )
        assertTrue(corrections.relabels.isEmpty())
        assertEquals(0, transactions.commits)
    }

    private fun assertNothingHappened() {
        assertTrue(events.isEmpty())
        assertEquals(0, transactions.writeCalls)
        assertTrue(corrections.relabels.isEmpty())
        assertTrue(audits.isEmpty())
        assertEquals(xts, repository.settings.value.currency().code)
    }

    private fun code(text: String): CurrencyCode = requireNotNull(CurrencyCode.of(text))

    private fun AppSettings.currency(): BusinessCurrency = regional.currency

    private fun AppSettings.withReference(reference: CurrencyCode?): AppSettings = copy(
        regional = regional.copy(referenceCurrency = reference?.let { ReferenceCurrency(it, 2) }),
    )

    /** Un cambio de etiqueta pedido a la base. */
    private data class Relabel(
        val from: CurrencyCode,
        val to: CurrencyCode,
        val decimals: Int,
        val updatedAt: Instant,
    )

    /**
     * La base en memoria para la corrección: las cuentas por moneda, las monedas usadas como otra
     * moneda y cada cambio de etiqueta, que también cambia la moneda de los ajustes.
     */
    private inner class FakeCorrections : CurrencyCorrectionRepository {
        val accounts = mutableMapOf<String, Int>()
        val otherCurrencies = mutableSetOf<String>()
        val relabels = mutableListOf<Relabel>()
        var failRelabel: StorageFailure? = null
        var followingAccounts = 0

        override suspend fun accountsIn(code: CurrencyCode): Int = accounts[code.code] ?: 0

        override suspend fun usedAsOtherCurrency(code: CurrencyCode): Boolean =
            code.code in otherCurrencies

        override suspend fun relabel(
            from: CurrencyCode,
            to: CurrencyCode,
            decimals: Int,
            updatedAt: Instant,
        ): Int {
            failRelabel?.let { throw StorageFailureException(it) }
            events += "relabel"
            relabels += Relabel(from, to, decimals, updatedAt)
            val current = repository.settings.value
            repository.settings.value =
                current.copy(
                    updatedAt = updatedAt,
                    regional =
                        current.regional.copy(
                            currency = current.regional.currency.copy(code = to),
                        ),
                )
            return followingAccounts
        }
    }
}
