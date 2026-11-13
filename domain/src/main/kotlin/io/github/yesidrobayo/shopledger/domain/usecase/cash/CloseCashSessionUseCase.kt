package io.github.yesidrobayo.shopledger.domain.usecase.cash

import io.github.yesidrobayo.shopledger.domain.model.Limit
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.cash.CashClosing
import io.github.yesidrobayo.shopledger.domain.model.cash.CashCountPhase
import io.github.yesidrobayo.shopledger.domain.model.cash.CashCounted
import io.github.yesidrobayo.shopledger.domain.model.cash.CashSessionStatus
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
import io.github.yesidrobayo.shopledger.domain.rule.cash.CashCount
import io.github.yesidrobayo.shopledger.domain.rule.region.BusinessCalendar
import io.github.yesidrobayo.shopledger.domain.rule.region.BusinessCalendarResult
import io.github.yesidrobayo.shopledger.domain.usecase.money.MoneyAppendResult
import io.github.yesidrobayo.shopledger.domain.usecase.money.MoneyLedgerWriter
import io.github.yesidrobayo.shopledger.domain.usecase.region.CurrencyLock
import io.github.yesidrobayo.shopledger.domain.usecase.region.CurrencyLockResult

/**
 * Cierra una caja abierta, en una sola transacción: compara lo esperado con lo contado y, si no
 * coinciden, escribe una `CASH_DIFFERENCE` de la sesión por la diferencia, para que la cuenta quede
 * igual a lo contado. La sesión pasa a cerrada con lo esperado, lo contado y la diferencia, y ya no
 * se edita: una corrección es un movimiento nuevo.
 *
 * - **Lo esperado** es la apertura más todo lo que movió la cuenta durante la sesión: ventas,
 *   gastos, pagos, retiros, aportes, transferencias y anulaciones, sin las diferencias de caja.
 * - **La diferencia no es ganancia ni gasto:** no entra en la ganancia y se muestra aparte.
 * - **Se cierra cuando la persona quiera**, también una sesión que quedó abierta desde ayer: la
 *   fecha de la diferencia es la del cierre.
 */
class CloseCashSessionUseCase(
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

    /** Cierra la sesión [sessionId] con [counted], lo que la persona contó, y una nota opcional. */
    suspend operator fun invoke(
        sessionId: Long,
        counted: Money,
        note: String? = null,
    ): CloseCashSessionResult = invoke(sessionId, CashCounted.Amount(counted), note)

    /**
     * Cierra la sesión [sessionId] con lo contado, escrito o por denominación. Un conteo por
     * denominación queda guardado con la sesión.
     */
    suspend operator fun invoke(
        sessionId: Long,
        counted: CashCounted,
        note: String? = null,
    ): CloseCashSessionResult = try {
        transactions.write { close(sessionId, counted, note?.trim()?.takeIf { it.isNotEmpty() }) }
    } catch (failure: StorageFailureException) {
        CloseCashSessionResult.StorageFailed(failure.failure)
    }

    private suspend fun close(
        sessionId: Long,
        countedAs: CashCounted,
        note: String?,
    ): TxResult<CloseCashSessionResult> {
        val reading = when (val read = readCount(countedAs, denominations)) {
            is CountReading.Read -> read

            CountReading.OutOfRange ->
                return TxResult.Rollback(CloseCashSessionResult.CountedOutOfRange)

            CountReading.UnknownDenomination ->
                return TxResult.Rollback(CloseCashSessionResult.UnknownDenomination)
        }
        val counted = reading.amount
        if ((note?.length ?: 0) > MAX_NOTE_LENGTH) {
            return TxResult.Rollback(CloseCashSessionResult.NoteTooLong)
        }
        val session = sessions.findById(sessionId)
            ?: return TxResult.Rollback(CloseCashSessionResult.SessionNotFound)
        if (session.status == CashSessionStatus.CLOSED) {
            return TxResult.Rollback(CloseCashSessionResult.AlreadyClosed)
        }
        val calendar = when (val result = BusinessCalendar.of(settings.get().regional)) {
            is BusinessCalendarResult.Ready -> result.calendar

            is BusinessCalendarResult.UnknownZone ->
                return TxResult.Rollback(
                    CloseCashSessionResult.UnknownTimeZone(result.storedZoneId),
                )
        }
        val expected =
            CashCount.expected(session.opening.openingAmount, sessions.movementsTotal(sessionId))
                ?: return TxResult.Rollback(CloseCashSessionResult.AmountTooLarge)
        val difference = CashCount.difference(counted, expected)
            ?: return TxResult.Rollback(CloseCashSessionResult.AmountTooLarge)
        val now = clock.now()
        if (difference != Money.ZERO) {
            when (currencyLock.lockForMoneyRecord(now)) {
                is CurrencyLockResult.Locked -> Unit

                CurrencyLockResult.Unconfirmed ->
                    return TxResult.Rollback(CloseCashSessionResult.CurrencyNotConfirmed)
            }
            val draft = MoneyMovementDraft(
                accountId = session.opening.accountId,
                type = MoneyMovementType.CASH_DIFFERENCE,
                amount = difference,
                origin = MoneyOrigin.None,
                cashSessionId = sessionId,
            )
            when (writer.append(draft, calendar.stamp(now), now)) {
                is MoneyAppendResult.Appended -> Unit

                MoneyAppendResult.AccountNotFound, MoneyAppendResult.Overflow ->
                    return TxResult.Rollback(CloseCashSessionResult.AmountTooLarge)
            }
        }
        val closing = CashClosing(expected, counted, difference, now, note)
        if (!sessions.close(sessionId, closing)) {
            return TxResult.Rollback(CloseCashSessionResult.AlreadyClosed)
        }
        sessions.insertCounts(sessionId, CashCountPhase.CLOSING, reading.counts)
        return TxResult.Commit(CloseCashSessionResult.Closed(closing))
    }

    private companion object {
        /** Largo máximo de la nota del cierre. */
        const val MAX_NOTE_LENGTH = 200
    }
}

/** Cómo termina cerrar caja. Salvo [Closed], no se escribió nada. */
sealed interface CloseCashSessionResult {
    /** Quedó cerrada con [closing]: lo esperado, lo contado y la diferencia. */
    data class Closed(val closing: CashClosing) : CloseCashSessionResult

    /** La sesión no existe. */
    data object SessionNotFound : CloseCashSessionResult

    /** La sesión ya estaba cerrada: una cerrada no se edita. */
    data object AlreadyClosed : CloseCashSessionResult

    /** Lo contado es negativo o pasa del máximo de un documento. */
    data object CountedOutOfRange : CloseCashSessionResult

    /** Una denominación del conteo no existe, está inactiva o se repite. */
    data object UnknownDenomination : CloseCashSessionResult

    /** La nota pasa de su largo máximo. */
    data object NoteTooLong : CloseCashSessionResult

    /** La moneda del negocio sigue sin confirmar, y una diferencia de caja es un registro con dinero. */
    data object CurrencyNotConfirmed : CloseCashSessionResult

    /** Lo esperado, la diferencia o el saldo no caben en un monto. Solo sale con cifras absurdas. */
    data object AmountTooLarge : CloseCashSessionResult

    /** Este teléfono no conoce la zona del negocio [storedZoneId]: no hay cómo fechar el cierre. */
    data class UnknownTimeZone(val storedZoneId: String) : CloseCashSessionResult

    /** La base no pudo escribir, por [failure]. No quedó nada guardado. */
    data class StorageFailed(val failure: StorageFailure) : CloseCashSessionResult
}
