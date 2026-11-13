package io.github.yesidrobayo.shopledger.domain.usecase.money

import io.github.yesidrobayo.shopledger.domain.model.money.MoneyMovementDraft
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyMovementType
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyOrigin
import io.github.yesidrobayo.shopledger.domain.model.money.TransferInputError
import io.github.yesidrobayo.shopledger.domain.model.money.TransferRequest
import io.github.yesidrobayo.shopledger.domain.port.AppSettingsRepository
import io.github.yesidrobayo.shopledger.domain.port.Clock
import io.github.yesidrobayo.shopledger.domain.port.MoneyAccountRepository
import io.github.yesidrobayo.shopledger.domain.port.StorageFailure
import io.github.yesidrobayo.shopledger.domain.port.TransactionRunner
import io.github.yesidrobayo.shopledger.domain.port.TxResult
import io.github.yesidrobayo.shopledger.domain.rule.money.TransferCheck
import io.github.yesidrobayo.shopledger.domain.rule.money.TransferRules
import io.github.yesidrobayo.shopledger.domain.rule.region.BusinessCalendar
import io.github.yesidrobayo.shopledger.domain.rule.region.BusinessCalendarResult
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.storageSafe
import io.github.yesidrobayo.shopledger.domain.usecase.region.CurrencyLock
import io.github.yesidrobayo.shopledger.domain.usecase.region.CurrencyLockResult
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/** Cómo termina transferir entre cuentas. Salvo [Transferred], no se escribió nada. */
sealed interface TransferMoneyResult {
    /** Quedaron las dos partes, unidas por [groupId]. */
    data class Transferred(val groupId: String, val outId: Long, val inId: Long) :
        TransferMoneyResult

    /** Cada error va en su campo, y lo escrito se conserva. */
    data class Invalid(val errors: Set<TransferInputError>) : TransferMoneyResult

    /** Alguna de las dos cuentas ya no existe. */
    data object AccountNotFound : TransferMoneyResult

    /** Alguna de las dos cuentas está archivada: no recibe ni entrega dinero nuevo. */
    data object AccountArchived : TransferMoneyResult

    /** La moneda del negocio sigue sin confirmar: ningún monto se guarda antes. */
    data object CurrencyNotConfirmed : TransferMoneyResult

    /** El saldo de alguna cuenta no cabría en `Long`. */
    data object Overflow : TransferMoneyResult

    /** Este teléfono no conoce la zona del negocio [storedZoneId]: no hay cómo fecharla. */
    data class UnknownTimeZone(val storedZoneId: String) : TransferMoneyResult

    /** La base no pudo escribir, por [failure]. No quedó nada guardado. */
    data class StorageFailed(val failure: StorageFailure) : TransferMoneyResult
}

/** Da el id que une las dos partes de una transferencia: un UUID de 36 caracteres. */
fun interface TransferGroupIds {
    fun next(): String

    companion object {
        /** Un UUID al azar, de la biblioteca estándar de Kotlin. */
        @OptIn(ExperimentalUuidApi::class)
        val RANDOM: TransferGroupIds = TransferGroupIds { Uuid.random().toString() }
    }
}

/**
 * Mueve dinero entre dos cuentas de la persona: una parte que sale y una que entra, con el mismo
 * grupo, en una sola transacción. Si cualquiera falla no queda ninguna.
 *
 * - **No toca la ganancia:** el dinero solo cambia de lugar.
 * - **Entre monedas distintas** la persona escribe la cotización, y cada parte va en la moneda de su
 *   cuenta. Entre cuentas de la misma moneda entra lo mismo que sale.
 * - **Ocurre hoy.** El origen puede quedar en negativo: se avisa antes, no se bloquea.
 */
class TransferMoneyUseCase(
    private val transactions: TransactionRunner,
    private val settings: AppSettingsRepository,
    private val accounts: MoneyAccountRepository,
    private val writer: MoneyLedgerWriter,
    private val clock: Clock,
    private val groupIds: TransferGroupIds = TransferGroupIds.RANDOM,
) {
    private val currencyLock = CurrencyLock(settings)

    suspend operator fun invoke(request: TransferRequest): TransferMoneyResult =
        storageSafe(TransferMoneyResult::StorageFailed) {
            transactions.write { transfer(request) }
        }

    private suspend fun transfer(request: TransferRequest): TxResult<TransferMoneyResult> {
        val from = accounts.findById(request.fromAccountId)
        val to = accounts.findById(request.toAccountId)
        if (from == null || to == null) return reject(TransferMoneyResult.AccountNotFound)
        val valid = when (
            val check = TransferRules.check(
                from = from.currency,
                to = to.currency,
                sameAccount = from.id == to.id,
                amount = request.amount,
                fxRateMicro = request.fxRateMicro,
                note = request.note,
            )
        ) {
            is TransferCheck.Invalid -> return reject(TransferMoneyResult.Invalid(check.errors))
            is TransferCheck.Valid -> check
        }
        if (from.isArchived || to.isArchived) return reject(TransferMoneyResult.AccountArchived)
        val calendar = when (val result = BusinessCalendar.of(settings.get().regional)) {
            is BusinessCalendarResult.Ready -> result.calendar

            is BusinessCalendarResult.UnknownZone ->
                return reject(TransferMoneyResult.UnknownTimeZone(result.storedZoneId))
        }
        val now = clock.now()
        when (currencyLock.lockForMoneyRecord(now)) {
            is CurrencyLockResult.Locked -> Unit

            CurrencyLockResult.Unconfirmed -> return reject(
                TransferMoneyResult.CurrencyNotConfirmed,
            )
        }
        val time = calendar.stamp(now)
        val origin = MoneyOrigin.Transfer(groupIds.next(), valid.fxRateMicro)
        val out = writer.append(
            MoneyMovementDraft(
                from.id,
                MoneyMovementType.TRANSFER_OUT,
                -valid.sent,
                origin,
                note = valid.note,
            ),
            time,
            now,
        )
        val outId = when (out) {
            is MoneyAppendResult.Appended -> out.movementId
            MoneyAppendResult.AccountNotFound -> return reject(TransferMoneyResult.AccountNotFound)
            MoneyAppendResult.Overflow -> return reject(TransferMoneyResult.Overflow)
        }
        val inbound = writer.append(
            MoneyMovementDraft(
                to.id,
                MoneyMovementType.TRANSFER_IN,
                valid.received,
                origin,
                note = valid.note,
            ),
            time,
            now,
        )
        return when (inbound) {
            is MoneyAppendResult.Appended ->
                TxResult.Commit(
                    TransferMoneyResult.Transferred(origin.groupId, outId, inbound.movementId),
                )

            MoneyAppendResult.AccountNotFound -> reject(TransferMoneyResult.AccountNotFound)

            MoneyAppendResult.Overflow -> reject(TransferMoneyResult.Overflow)
        }
    }

    private fun reject(result: TransferMoneyResult): TxResult<TransferMoneyResult> =
        TxResult.Rollback(result)
}
