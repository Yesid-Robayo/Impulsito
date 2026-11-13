package io.github.yesidrobayo.shopledger.domain.usecase.customer

import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.customer.CustomerLedgerEntryType
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyMovementType
import io.github.yesidrobayo.shopledger.domain.model.region.RecordTime
import io.github.yesidrobayo.shopledger.domain.port.AppSettingsRepository
import io.github.yesidrobayo.shopledger.domain.port.Clock
import io.github.yesidrobayo.shopledger.domain.port.CustomerLedgerRepository
import io.github.yesidrobayo.shopledger.domain.port.MoneyLedgerRepository
import io.github.yesidrobayo.shopledger.domain.port.StorageFailure
import io.github.yesidrobayo.shopledger.domain.port.TransactionRunner
import io.github.yesidrobayo.shopledger.domain.port.TxResult
import io.github.yesidrobayo.shopledger.domain.rule.customer.CustomerEntryIssue
import io.github.yesidrobayo.shopledger.domain.rule.region.BusinessCalendar
import io.github.yesidrobayo.shopledger.domain.rule.region.BusinessCalendarResult
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.storageSafe
import io.github.yesidrobayo.shopledger.domain.usecase.money.MoneyAppendResult
import io.github.yesidrobayo.shopledger.domain.usecase.money.MoneyLedgerWriter
import kotlin.time.Instant

/** Cómo termina anular un abono. Salvo [Voided], no se escribió nada. */
sealed interface VoidCustomerPaymentResult {
    /**
     * El abono quedó compensado por la fila [voidEntryId], y el cliente debe [balanceAfter].
     * [voidMovementIds] son las anulaciones de su dinero, vacía si el abono no entró a una cuenta.
     */
    data class Voided(
        val voidEntryId: Long,
        val voidMovementIds: List<Long>,
        val balanceAfter: Money,
    ) : VoidCustomerPaymentResult

    /** El abono no existe, o es de otro cliente. */
    data object EntryNotFound : VoidCustomerPaymentResult

    /**
     * La fila no es un abono. Un cargo de venta se anula con su venta, y un ajuste o un saldo
     * inicial se corrigen con otro ajuste.
     */
    data object NotAPayment : VoidCustomerPaymentResult

    /** El abono ya tiene su anulación: se anula una sola vez. */
    data object AlreadyVoided : VoidCustomerPaymentResult

    /** La anulación no entra en el libro, por [issue]: una nota muy larga o un saldo que desborda. */
    data class Invalid(val issue: CustomerEntryIssue) : VoidCustomerPaymentResult

    /** La cuenta donde entró el dinero ya no existe. */
    data object AccountNotFound : VoidCustomerPaymentResult

    /** El saldo de la cuenta no cabría en un entero de 64 bits con la anulación. */
    data object AccountOverflow : VoidCustomerPaymentResult

    /** Este teléfono no conoce la zona del negocio [storedZoneId]: no hay cómo fechar la anulación. */
    data class UnknownTimeZone(val storedZoneId: String) : VoidCustomerPaymentResult

    /** La base no pudo escribir, por [failure]. No quedó nada guardado. */
    data class StorageFailed(val failure: StorageFailure) : VoidCustomerPaymentResult
}

/**
 * Anula un abono registrado por error, en una sola transacción: un `ENTRY_VOID` con el monto
 * opuesto en el libro del cliente, que vuelve a subir su saldo, y un `MOVEMENT_VOID` por cada
 * entrada de ese abono en una cuenta de dinero. El abono queda en el libro, marcado como anulado,
 * y deja de cubrir cargos. Nada se borra.
 *
 * La anulación ocurre hoy, no en la fecha del abono: lo cobrado en los días pasados no cambia. Si
 * el cliente ya había gastado lo que el abono dejaba a favor, el saldo puede quedar en deuda otra
 * vez, y no se bloquea por eso.
 */
class VoidCustomerPaymentUseCase(
    private val transactions: TransactionRunner,
    private val settings: AppSettingsRepository,
    private val ledger: CustomerLedgerRepository,
    private val movements: MoneyLedgerRepository,
    private val money: MoneyLedgerWriter,
    private val clock: Clock,
) {
    private val writer = CustomerLedgerWriter(ledger)

    suspend operator fun invoke(
        customerId: Long,
        entryId: Long,
        note: String? = null,
    ): VoidCustomerPaymentResult = storageSafe(VoidCustomerPaymentResult::StorageFailed) {
        transactions.write { void(customerId, entryId, note) }
    }

    private suspend fun void(
        customerId: Long,
        entryId: Long,
        note: String?,
    ): TxResult<VoidCustomerPaymentResult> {
        val entry = ledger.findById(entryId)?.takeIf { it.customerId == customerId }
            ?: return reject(VoidCustomerPaymentResult.EntryNotFound)
        if (entry.type != CustomerLedgerEntryType.PAYMENT) {
            return reject(VoidCustomerPaymentResult.NotAPayment)
        }
        if (ledger.isCompensated(entryId)) return reject(VoidCustomerPaymentResult.AlreadyVoided)
        val calendar = when (val result = BusinessCalendar.of(settings.get().regional)) {
            is BusinessCalendarResult.Ready -> result.calendar

            is BusinessCalendarResult.UnknownZone ->
                return reject(VoidCustomerPaymentResult.UnknownTimeZone(result.storedZoneId))
        }
        val now = clock.now()
        val time = calendar.stamp(now)
        val reversal = when (val outcome = writer.reverse(customerId, entryId, note, time)) {
            is CustomerEntryOutcome.Appended -> outcome

            is CustomerEntryOutcome.Rejected ->
                return reject(VoidCustomerPaymentResult.Invalid(outcome.issue))

            // Ya se comprobaron en esta transacción: la fila existe, es un abono y no tiene
            // compensatorio.
            CustomerEntryOutcome.EntryNotFound,
            CustomerEntryOutcome.AlreadyCompensated,
            CustomerEntryOutcome.NotReversible,
            -> error("El abono se comprobó antes de anularlo")
        }
        val voids = when (val result = voidMoney(entryId, time, now)) {
            is MoneyVoids.Done -> result.movementIds
            is MoneyVoids.Failed -> return reject(result.result)
        }
        return TxResult.Commit(
            VoidCustomerPaymentResult.Voided(reversal.entryId, voids, reversal.balanceAfter),
        )
    }

    /**
     * Anula el dinero que entró con el abono [entryId], en cada cuenta donde haya entrado. La nota
     * queda solo en el libro del cliente: el movimiento ya apunta a su abono.
     */
    private suspend fun voidMoney(entryId: Long, time: RecordTime, now: Instant): MoneyVoids {
        val ids = mutableListOf<Long>()
        val received = movements.findByCustomerLedgerEntry(entryId)
            .filter { it.type == MoneyMovementType.CUSTOMER_PAYMENT }
            .filterNot { movements.isCompensated(it.id) }
        for (movement in received) {
            when (val result = money.void(movement, time, now)) {
                is MoneyAppendResult.Appended -> ids += result.movementId

                MoneyAppendResult.AccountNotFound ->
                    return MoneyVoids.Failed(VoidCustomerPaymentResult.AccountNotFound)

                MoneyAppendResult.Overflow ->
                    return MoneyVoids.Failed(VoidCustomerPaymentResult.AccountOverflow)
            }
        }
        return MoneyVoids.Done(ids)
    }

    private sealed interface MoneyVoids {
        data class Done(val movementIds: List<Long>) : MoneyVoids

        data class Failed(val result: VoidCustomerPaymentResult) : MoneyVoids
    }

    private fun reject(result: VoidCustomerPaymentResult): TxResult<VoidCustomerPaymentResult> =
        TxResult.Rollback(result)
}
