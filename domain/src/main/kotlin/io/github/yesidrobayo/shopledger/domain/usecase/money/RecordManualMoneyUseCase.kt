package io.github.yesidrobayo.shopledger.domain.usecase.money

import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.money.ManualMoneyInputError
import io.github.yesidrobayo.shopledger.domain.model.money.ManualMoneyRequest
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyMovementDraft
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyOrigin
import io.github.yesidrobayo.shopledger.domain.port.AppSettingsRepository
import io.github.yesidrobayo.shopledger.domain.port.Clock
import io.github.yesidrobayo.shopledger.domain.port.MoneyAccountRepository
import io.github.yesidrobayo.shopledger.domain.port.StorageFailure
import io.github.yesidrobayo.shopledger.domain.port.TransactionRunner
import io.github.yesidrobayo.shopledger.domain.port.TxResult
import io.github.yesidrobayo.shopledger.domain.rule.money.ManualMoneyCheck
import io.github.yesidrobayo.shopledger.domain.rule.money.ManualMoneyRules
import io.github.yesidrobayo.shopledger.domain.rule.region.BusinessCalendar
import io.github.yesidrobayo.shopledger.domain.rule.region.BusinessCalendarResult
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.storageSafe
import io.github.yesidrobayo.shopledger.domain.usecase.region.CurrencyLock
import io.github.yesidrobayo.shopledger.domain.usecase.region.CurrencyLockResult

/** Cómo termina registrar dinero a mano en una cuenta. Salvo [Recorded], no se escribió nada. */
sealed interface RecordManualMoneyResult {
    /**
     * Quedó en el libro con el id [movementId], y la cuenta con el saldo [balanceAfter], que puede
     * ser negativo: un saldo insuficiente se avisa, no se bloquea.
     */
    data class Recorded(val movementId: Long, val balanceAfter: Money) : RecordManualMoneyResult

    /** Cada error va en su campo, y lo escrito se conserva. */
    data class Invalid(val errors: Set<ManualMoneyInputError>) : RecordManualMoneyResult

    /** La cuenta ya no existe. */
    data object AccountNotFound : RecordManualMoneyResult

    /** La cuenta está archivada: no recibe dinero nuevo hasta reactivarla. */
    data object AccountArchived : RecordManualMoneyResult

    /** La moneda del negocio sigue sin confirmar: ningún monto se guarda antes. */
    data object CurrencyNotConfirmed : RecordManualMoneyResult

    /** El saldo de la cuenta no cabría en `Long` con este monto. */
    data object Overflow : RecordManualMoneyResult

    /** Este teléfono no conoce la zona del negocio [storedZoneId]: no hay cómo fecharlo. */
    data class UnknownTimeZone(val storedZoneId: String) : RecordManualMoneyResult

    /** La base no pudo escribir, por [failure]. No quedó nada guardado. */
    data class StorageFailed(val failure: StorageFailure) : RecordManualMoneyResult
}

/**
 * Registra en una cuenta, en una sola transacción, dinero que la persona saca o pone sin documento.
 *
 * - **Un retiro no es un gasto, y un aporte no es una venta.** Mueven dinero entre la persona y el
 *   negocio: la cuenta cambia, la ganancia no. Ninguna suma de ganancia lee el libro de dinero.
 * - **Ocurre hoy**, con el instante actual en la zona del negocio.
 * - **Un saldo insuficiente no bloquea:** la cuenta puede quedar en negativo, y la pantalla lo avisa
 *   antes de confirmar.
 * - **La cuenta tiene que estar vigente.** El monto va en la moneda de la cuenta, sea o no la del
 *   negocio.
 */
class RecordManualMoneyUseCase(
    private val transactions: TransactionRunner,
    private val settings: AppSettingsRepository,
    private val accounts: MoneyAccountRepository,
    private val writer: MoneyLedgerWriter,
    private val clock: Clock,
) {
    private val currencyLock = CurrencyLock(settings)

    suspend operator fun invoke(request: ManualMoneyRequest): RecordManualMoneyResult =
        storageSafe(RecordManualMoneyResult::StorageFailed) {
            transactions.write { record(request) }
        }

    private suspend fun record(request: ManualMoneyRequest): TxResult<RecordManualMoneyResult> {
        val valid = when (val check = ManualMoneyRules.check(request.amount, request.note)) {
            is ManualMoneyCheck.Invalid -> return reject(
                RecordManualMoneyResult.Invalid(check.errors),
            )

            is ManualMoneyCheck.Valid -> check
        }
        val account = accounts.findById(request.accountId)
            ?: return reject(RecordManualMoneyResult.AccountNotFound)
        if (account.isArchived) return reject(RecordManualMoneyResult.AccountArchived)
        val calendar = when (val result = BusinessCalendar.of(settings.get().regional)) {
            is BusinessCalendarResult.Ready -> result.calendar

            is BusinessCalendarResult.UnknownZone ->
                return reject(RecordManualMoneyResult.UnknownTimeZone(result.storedZoneId))
        }
        val now = clock.now()
        when (currencyLock.lockForMoneyRecord(now)) {
            is CurrencyLockResult.Locked -> Unit

            CurrencyLockResult.Unconfirmed -> return reject(
                RecordManualMoneyResult.CurrencyNotConfirmed,
            )
        }
        val type = request.kind.type
        val signed = if (type.allows(valid.amount.minor)) valid.amount else -valid.amount
        val draft = MoneyMovementDraft(
            accountId = account.id,
            type = type,
            amount = signed,
            origin = MoneyOrigin.None,
            note = valid.note,
        )
        return when (val appended = writer.append(draft, calendar.stamp(now), now)) {
            is MoneyAppendResult.Appended -> TxResult.Commit(
                RecordManualMoneyResult.Recorded(appended.movementId, appended.balanceAfter),
            )

            MoneyAppendResult.AccountNotFound -> reject(RecordManualMoneyResult.AccountNotFound)

            MoneyAppendResult.Overflow -> reject(RecordManualMoneyResult.Overflow)
        }
    }

    private fun reject(result: RecordManualMoneyResult): TxResult<RecordManualMoneyResult> =
        TxResult.Rollback(result)
}
