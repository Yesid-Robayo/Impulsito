package io.github.yesidrobayo.shopledger.domain.usecase.customer

import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.customer.CustomerEntryDraft
import io.github.yesidrobayo.shopledger.domain.model.customer.CustomerEntrySource
import io.github.yesidrobayo.shopledger.domain.model.customer.CustomerLedgerEntryType
import io.github.yesidrobayo.shopledger.domain.port.AppSettingsRepository
import io.github.yesidrobayo.shopledger.domain.port.Clock
import io.github.yesidrobayo.shopledger.domain.port.CustomerLedgerRepository
import io.github.yesidrobayo.shopledger.domain.port.CustomerRepository
import io.github.yesidrobayo.shopledger.domain.port.StorageFailure
import io.github.yesidrobayo.shopledger.domain.port.TransactionRunner
import io.github.yesidrobayo.shopledger.domain.port.TxResult
import io.github.yesidrobayo.shopledger.domain.rule.customer.CustomerEntryIssue
import io.github.yesidrobayo.shopledger.domain.rule.region.BusinessCalendar
import io.github.yesidrobayo.shopledger.domain.rule.region.BusinessCalendarResult
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.storageSafe
import io.github.yesidrobayo.shopledger.domain.usecase.region.CurrencyLock
import io.github.yesidrobayo.shopledger.domain.usecase.region.CurrencyLockResult

/** Cómo termina ajustar a mano el saldo de un cliente. Salvo [Recorded], no se escribió nada. */
sealed interface AdjustCustomerBalanceResult {
    /** El ajuste quedó con el id [entryId], y el cliente debe [balanceAfter]. */
    data class Recorded(val entryId: Long, val balanceAfter: Money) : AdjustCustomerBalanceResult

    /** El cliente ya no existe. */
    data object CustomerNotFound : AdjustCustomerBalanceResult

    /** El ajuste no entra, por [issue]: sin nota, en cero o con un saldo que desborda. */
    data class Invalid(val issue: CustomerEntryIssue) : AdjustCustomerBalanceResult

    /** Este teléfono no conoce la zona del negocio [storedZoneId], así que no hay cómo fecharlo. */
    data class UnknownTimeZone(val storedZoneId: String) : AdjustCustomerBalanceResult

    /** La moneda sigue sin confirmar: ningún monto se guarda antes. */
    data object CurrencyNotConfirmed : AdjustCustomerBalanceResult

    data class StorageFailed(val failure: StorageFailure) : AdjustCustomerBalanceResult
}

/**
 * Corrige a mano lo que debe un cliente, con un `ADJUSTMENT` de [amount] con signo y una nota que
 * dice por qué: positivo aumenta la deuda, negativo la baja. El saldo nunca se edita: el ajuste es
 * una fila más del libro, y no mueve dinero de ninguna cuenta. Un cliente archivado o anonimizado
 * también se ajusta, porque su deuda sigue contando.
 */
class AdjustCustomerBalanceUseCase(
    private val transactions: TransactionRunner,
    private val settings: AppSettingsRepository,
    private val customers: CustomerRepository,
    ledger: CustomerLedgerRepository,
    private val clock: Clock,
) {
    private val writer = CustomerLedgerWriter(ledger)
    private val currencyLock = CurrencyLock(settings)

    suspend operator fun invoke(
        customerId: Long,
        amount: Money,
        note: String,
    ): AdjustCustomerBalanceResult = storageSafe(AdjustCustomerBalanceResult::StorageFailed) {
        transactions.write { adjust(customerId, amount, note) }
    }

    private suspend fun adjust(
        customerId: Long,
        amount: Money,
        note: String,
    ): TxResult<AdjustCustomerBalanceResult> {
        if (customers.findById(customerId) == null) {
            return TxResult.Rollback(AdjustCustomerBalanceResult.CustomerNotFound)
        }
        val calendar = when (val result = BusinessCalendar.of(settings.get().regional)) {
            is BusinessCalendarResult.Ready -> result.calendar

            is BusinessCalendarResult.UnknownZone -> return TxResult.Rollback(
                AdjustCustomerBalanceResult.UnknownTimeZone(result.storedZoneId),
            )
        }
        val now = clock.now()
        when (currencyLock.lockForMoneyRecord(now)) {
            is CurrencyLockResult.Locked -> Unit

            CurrencyLockResult.Unconfirmed ->
                return TxResult.Rollback(AdjustCustomerBalanceResult.CurrencyNotConfirmed)
        }
        val draft = CustomerEntryDraft(
            customerId = customerId,
            type = CustomerLedgerEntryType.ADJUSTMENT,
            amount = amount,
            source = CustomerEntrySource.None,
            dueDate = null,
            note = note,
        )
        return when (val outcome = writer.append(draft, calendar.stamp(now))) {
            is CustomerEntryOutcome.Appended -> TxResult.Commit(
                AdjustCustomerBalanceResult.Recorded(outcome.entryId, outcome.balanceAfter),
            )

            is CustomerEntryOutcome.Rejected ->
                TxResult.Rollback(AdjustCustomerBalanceResult.Invalid(outcome.issue))

            // Un ajuste no compensa ninguna fila: estos tres no pueden salir de agregarlo.
            CustomerEntryOutcome.EntryNotFound,
            CustomerEntryOutcome.AlreadyCompensated,
            CustomerEntryOutcome.NotReversible,
            -> error("Un ajuste no compensa ninguna fila")
        }
    }
}
