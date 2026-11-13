package io.github.yesidrobayo.shopledger.domain.usecase.cash

import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.cash.CashCountPhase
import io.github.yesidrobayo.shopledger.domain.model.cash.CashCounted
import io.github.yesidrobayo.shopledger.domain.model.cash.NewCashSession
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyAccountKind
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyMovementDraft
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyMovementType
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyOrigin
import io.github.yesidrobayo.shopledger.domain.port.AppSettingsRepository
import io.github.yesidrobayo.shopledger.domain.port.CashDenominationRepository
import io.github.yesidrobayo.shopledger.domain.port.CashSessionRepository
import io.github.yesidrobayo.shopledger.domain.port.Clock
import io.github.yesidrobayo.shopledger.domain.port.MoneyAccountRepository
import io.github.yesidrobayo.shopledger.domain.port.MoneyLedgerRepository
import io.github.yesidrobayo.shopledger.domain.port.StorageFailure
import io.github.yesidrobayo.shopledger.domain.port.StorageFailureException
import io.github.yesidrobayo.shopledger.domain.port.TransactionRunner
import io.github.yesidrobayo.shopledger.domain.port.TxResult
import io.github.yesidrobayo.shopledger.domain.rule.region.BusinessCalendar
import io.github.yesidrobayo.shopledger.domain.rule.region.BusinessCalendarResult
import io.github.yesidrobayo.shopledger.domain.usecase.money.MoneyAppendResult
import io.github.yesidrobayo.shopledger.domain.usecase.money.MoneyLedgerWriter
import io.github.yesidrobayo.shopledger.domain.usecase.region.CurrencyLock
import io.github.yesidrobayo.shopledger.domain.usecase.region.CurrencyLockResult

/**
 * Abre una sesión de caja en una cuenta de efectivo, en una sola transacción. **Abrir caja es
 * opcional:** sin sesión nada se bloquea ni se pide, y una venta se registra igual.
 *
 * - **Lo que había al abrir** nace del saldo de la cuenta. La persona puede corregirlo con lo que
 *   contó: si difiere, una `CASH_DIFFERENCE` de la sesión lleva la cuenta a lo contado, y la apertura
 *   queda en lo contado. Esa diferencia no cuenta en lo esperado al cerrar.
 * - **Una abierta por cuenta.** Si la cuenta ya tiene una, se rechaza.
 * - **Solo cuentas de efectivo vigentes**: un banco no se cuenta a mano.
 * - Desde que abre, cada movimiento de la cuenta cae en la sesión, por el escritor del libro.
 */
class OpenCashSessionUseCase(
    private val transactions: TransactionRunner,
    private val settings: AppSettingsRepository,
    private val accounts: MoneyAccountRepository,
    private val ledger: MoneyLedgerRepository,
    private val sessions: CashSessionRepository,
    private val clock: Clock,
    private val denominations: CashDenominationRepository? = null,
) {
    private val currencyLock = CurrencyLock(settings)
    private val writer = MoneyLedgerWriter(accounts, ledger)

    /**
     * Abre caja en [accountId] con [counted], lo que la persona contó, o con el saldo de la cuenta
     * si es `null`.
     */
    suspend operator fun invoke(accountId: Long, counted: Money? = null): OpenCashSessionResult =
        invoke(accountId, counted?.let(CashCounted::Amount))

    /**
     * Abre caja en [accountId] con lo contado, escrito o por denominación, o con el saldo de la
     * cuenta si es `null`. Un conteo por denominación queda guardado con la sesión.
     */
    suspend operator fun invoke(accountId: Long, counted: CashCounted?): OpenCashSessionResult =
        try {
            transactions.write { open(accountId, counted) }
        } catch (failure: StorageFailureException) {
            OpenCashSessionResult.StorageFailed(failure.failure)
        }

    private suspend fun open(
        accountId: Long,
        counted: CashCounted?,
    ): TxResult<OpenCashSessionResult> {
        val reading = counted?.let {
            when (val read = readCount(it, denominations)) {
                is CountReading.Read -> read

                CountReading.OutOfRange ->
                    return TxResult.Rollback(OpenCashSessionResult.CountedOutOfRange)

                CountReading.UnknownDenomination ->
                    return TxResult.Rollback(OpenCashSessionResult.UnknownDenomination)
            }
        }
        val account = accounts.findById(accountId)
            ?: return TxResult.Rollback(OpenCashSessionResult.AccountNotFound)
        when {
            account.isArchived -> return TxResult.Rollback(OpenCashSessionResult.AccountArchived)

            account.details.kind != MoneyAccountKind.CASH ->
                return TxResult.Rollback(OpenCashSessionResult.NotCashAccount)

            sessions.findOpen(accountId) != null ->
                return TxResult.Rollback(OpenCashSessionResult.AlreadyOpen)
        }
        val calendar = when (val result = BusinessCalendar.of(settings.get().regional)) {
            is BusinessCalendarResult.Ready -> result.calendar

            is BusinessCalendarResult.UnknownZone ->
                return TxResult.Rollback(OpenCashSessionResult.UnknownTimeZone(result.storedZoneId))
        }
        val now = clock.now()
        val time = calendar.stamp(now)
        val opening = reading?.amount ?: account.balance
        val sessionId = sessions.insert(
            NewCashSession(accountId, opening, now, time.businessDate),
        )
        reading?.let { sessions.insertCounts(sessionId, CashCountPhase.OPENING, it.counts) }
        val difference = try {
            opening - account.balance
        } catch (overflow: ArithmeticException) {
            return TxResult.Rollback(OpenCashSessionResult.BalanceTooLarge)
        }
        if (difference != Money.ZERO) {
            when (currencyLock.lockForMoneyRecord(now)) {
                is CurrencyLockResult.Locked -> Unit

                CurrencyLockResult.Unconfirmed ->
                    return TxResult.Rollback(OpenCashSessionResult.CurrencyNotConfirmed)
            }
            val draft = MoneyMovementDraft(
                accountId = accountId,
                type = MoneyMovementType.CASH_DIFFERENCE,
                amount = difference,
                origin = MoneyOrigin.None,
                cashSessionId = sessionId,
            )
            when (writer.append(draft, time, now)) {
                is MoneyAppendResult.Appended -> Unit

                MoneyAppendResult.AccountNotFound ->
                    return TxResult.Rollback(OpenCashSessionResult.AccountNotFound)

                MoneyAppendResult.Overflow ->
                    return TxResult.Rollback(OpenCashSessionResult.BalanceTooLarge)
            }
        }
        return TxResult.Commit(OpenCashSessionResult.Opened(sessionId))
    }
}

/** Cómo termina abrir caja. Salvo [Opened], no se escribió nada. */
sealed interface OpenCashSessionResult {
    /** Quedó abierta la sesión [sessionId]. */
    data class Opened(val sessionId: Long) : OpenCashSessionResult

    /** La cuenta ya no existe. */
    data object AccountNotFound : OpenCashSessionResult

    /** La cuenta está archivada. */
    data object AccountArchived : OpenCashSessionResult

    /** La cuenta no es de efectivo. */
    data object NotCashAccount : OpenCashSessionResult

    /** La cuenta ya tiene una caja abierta: hay una sola por cuenta. */
    data object AlreadyOpen : OpenCashSessionResult

    /** Lo contado es negativo o pasa del máximo de un documento. */
    data object CountedOutOfRange : OpenCashSessionResult

    /** Una denominación del conteo no existe, está inactiva o se repite. */
    data object UnknownDenomination : OpenCashSessionResult

    /** La moneda del negocio sigue sin confirmar, y una diferencia de caja es un registro con dinero. */
    data object CurrencyNotConfirmed : OpenCashSessionResult

    /** El saldo de la cuenta no cabría en un monto. Solo sale con cifras absurdas. */
    data object BalanceTooLarge : OpenCashSessionResult

    /** Este teléfono no conoce la zona del negocio [storedZoneId]: no hay cómo fechar la apertura. */
    data class UnknownTimeZone(val storedZoneId: String) : OpenCashSessionResult

    /** La base no pudo escribir, por [failure]. No quedó nada guardado. */
    data class StorageFailed(val failure: StorageFailure) : OpenCashSessionResult
}
