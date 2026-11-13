package io.github.yesidrobayo.shopledger.domain.usecase.money

import io.github.yesidrobayo.shopledger.domain.model.money.AccountCurrency
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyAccountDraft
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyAccountInputError
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyMovementDraft
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyMovementType
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyOrigin
import io.github.yesidrobayo.shopledger.domain.model.money.NewMoneyAccount
import io.github.yesidrobayo.shopledger.domain.port.AppSettingsRepository
import io.github.yesidrobayo.shopledger.domain.port.Clock
import io.github.yesidrobayo.shopledger.domain.port.MoneyAccountRepository
import io.github.yesidrobayo.shopledger.domain.port.StorageFailure
import io.github.yesidrobayo.shopledger.domain.port.TransactionRunner
import io.github.yesidrobayo.shopledger.domain.port.TxResult
import io.github.yesidrobayo.shopledger.domain.rule.money.MoneyAccountCheck
import io.github.yesidrobayo.shopledger.domain.rule.money.MoneyAccountRules
import io.github.yesidrobayo.shopledger.domain.rule.region.BusinessCalendar
import io.github.yesidrobayo.shopledger.domain.rule.region.BusinessCalendarResult
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.storageSafe
import io.github.yesidrobayo.shopledger.domain.usecase.region.CurrencyLock
import io.github.yesidrobayo.shopledger.domain.usecase.region.CurrencyLockResult

/** Cómo termina crear o editar una cuenta de dinero. Salvo [Saved], no se escribió nada. */
sealed interface SaveMoneyAccountResult {
    /** Quedó guardada, o ya estaba exactamente así. [accountId] es el de la cuenta. */
    data class Saved(val accountId: Long) : SaveMoneyAccountResult

    /** Cada error va en su campo, y lo escrito se conserva. */
    data class Invalid(val errors: Set<MoneyAccountInputError>) : SaveMoneyAccountResult

    /** Otra cuenta vigente ya se llama así, comparado sin mayúsculas ni tildes. */
    data object DuplicateName : SaveMoneyAccountResult

    /** La cuenta que se editaba ya no existe. */
    data object NotFound : SaveMoneyAccountResult

    /** La moneda del negocio sigue sin confirmar: ninguna cuenta nace antes. */
    data object CurrencyNotConfirmed : SaveMoneyAccountResult

    /** Este teléfono no conoce la zona del negocio [storedZoneId]: no hay cómo fechar el saldo. */
    data class UnknownTimeZone(val storedZoneId: String) : SaveMoneyAccountResult

    /** La base no pudo escribir. No quedó nada a medias, y lo escrito se conserva. */
    data class StorageFailed(val failure: StorageFailure) : SaveMoneyAccountResult
}

/**
 * Crea una cuenta de dinero con su saldo inicial, o edita el nombre y la clase de una existente, en
 * una sola transacción.
 *
 * - **El saldo inicial es un movimiento.** Entra como `OPENING_BALANCE`, en la misma transacción que
 *   la cuenta: así su saldo es, desde el primer momento, la suma de su libro. Con saldo cero la cuenta
 *   nace sin movimientos.
 * - **La moneda se elige al crear.** Por defecto la del negocio; otra moneda lleva sus propios
 *   decimales, y su saldo nunca se suma al del negocio. Una cuenta guardada no cambia de moneda.
 * - **El nombre es único entre las vigentes**, comparado sin mayúsculas ni tildes. La cuenta inicial
 *   de efectivo sin nombre propio sigue el idioma de la interfaz y no reserva nada.
 * - **Antes de la moneda del negocio no hay cuentas nuevas**, y un saldo inicial bloquea la moneda
 *   como cualquier registro con dinero.
 *
 * Una edición que no cambia nada no escribe ni cuenta un cambio sin respaldar.
 */
class SaveMoneyAccountUseCase(
    private val transactions: TransactionRunner,
    private val settings: AppSettingsRepository,
    private val accounts: MoneyAccountRepository,
    private val writer: MoneyLedgerWriter,
    private val clock: Clock,
) {
    private val currencyLock = CurrencyLock(settings)

    /** Crea la cuenta de [draft] si [editingId] es nulo, o edita esa cuenta. */
    suspend operator fun invoke(
        editingId: Long?,
        draft: MoneyAccountDraft,
    ): SaveMoneyAccountResult = storageSafe(SaveMoneyAccountResult::StorageFailed) {
        transactions.write {
            if (editingId == null) create(draft) else edit(editingId, draft)
        }
    }

    private suspend fun create(draft: MoneyAccountDraft): TxResult<SaveMoneyAccountResult> {
        val valid = when (val check = MoneyAccountRules.check(draft, isInitial = false)) {
            is MoneyAccountCheck.Invalid -> return reject(
                SaveMoneyAccountResult.Invalid(check.errors),
            )

            is MoneyAccountCheck.Valid -> check
        }
        if (isTaken(valid.details.normalizedName, editingId = null)) {
            return reject(SaveMoneyAccountResult.DuplicateName)
        }
        val regional = settings.get().regional
        if (!regional.currency.code.isConfirmed) {
            return reject(SaveMoneyAccountResult.CurrencyNotConfirmed)
        }
        val now = clock.now()
        val opening = valid.openingBalance
        val accountId = accounts.insert(
            NewMoneyAccount(
                details = valid.details,
                currency = draft.currency ?: AccountCurrency.of(regional.currency),
                createdAt = now,
            ),
        )
        if (opening == null) return TxResult.Commit(SaveMoneyAccountResult.Saved(accountId))

        val calendar = when (val result = BusinessCalendar.of(regional)) {
            is BusinessCalendarResult.Ready -> result.calendar

            is BusinessCalendarResult.UnknownZone ->
                return reject(SaveMoneyAccountResult.UnknownTimeZone(result.storedZoneId))
        }
        // El saldo inicial es el primer registro con dinero de la cuenta: bloquea la moneda del
        // negocio en esta misma transacción, como una venta o un gasto.
        when (currencyLock.lockForMoneyRecord(now)) {
            is CurrencyLockResult.Locked -> Unit

            CurrencyLockResult.Unconfirmed -> return reject(
                SaveMoneyAccountResult.CurrencyNotConfirmed,
            )
        }
        val appended = writer.append(
            MoneyMovementDraft(
                accountId = accountId,
                type = MoneyMovementType.OPENING_BALANCE,
                amount = opening,
                origin = MoneyOrigin.None,
            ),
            calendar.stamp(now),
            now,
        )
        // Una cuenta recién creada existe y tiene saldo cero: el saldo inicial, dentro del tope de un
        // documento, siempre cabe.
        check(appended is MoneyAppendResult.Appended) { "El saldo inicial no entró al libro" }
        return TxResult.Commit(SaveMoneyAccountResult.Saved(accountId))
    }

    private suspend fun edit(
        editingId: Long,
        draft: MoneyAccountDraft,
    ): TxResult<SaveMoneyAccountResult> {
        val current = accounts.findById(editingId)
            ?: return reject(SaveMoneyAccountResult.NotFound)
        val valid = when (
            val check = MoneyAccountRules.check(
                draft.copy(openingBalance = null),
                isInitial = current.systemKey != null,
            )
        ) {
            is MoneyAccountCheck.Invalid -> return reject(
                SaveMoneyAccountResult.Invalid(check.errors),
            )

            is MoneyAccountCheck.Valid -> check
        }
        // Una archivada no reserva su nombre, así que no choca al renombrarla: choca al reactivarla.
        if (!current.isArchived && isTaken(valid.details.normalizedName, editingId)) {
            return reject(SaveMoneyAccountResult.DuplicateName)
        }
        if (current.details == valid.details) {
            return TxResult.Rollback(SaveMoneyAccountResult.Saved(current.id))
        }
        accounts.update(current.id, valid.details, clock.now())
        return TxResult.Commit(SaveMoneyAccountResult.Saved(current.id))
    }

    /** Si otra cuenta vigente ya usa [normalizedName]. Una sin nombre propio no choca con nadie. */
    private suspend fun isTaken(normalizedName: String?, editingId: Long?): Boolean {
        val clash = normalizedName?.let { accounts.findActiveByNormalizedName(it) } ?: return false
        return clash.id != editingId
    }

    private fun reject(result: SaveMoneyAccountResult): TxResult<SaveMoneyAccountResult> =
        TxResult.Rollback(result)
}
