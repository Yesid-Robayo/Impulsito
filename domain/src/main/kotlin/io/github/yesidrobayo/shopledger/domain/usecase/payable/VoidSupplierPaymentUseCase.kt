package io.github.yesidrobayo.shopledger.domain.usecase.payable

import io.github.yesidrobayo.shopledger.domain.model.payable.NewSupplierLedgerEntry
import io.github.yesidrobayo.shopledger.domain.model.payable.SupplierLedgerEntryType
import io.github.yesidrobayo.shopledger.domain.port.AppSettingsRepository
import io.github.yesidrobayo.shopledger.domain.port.Clock
import io.github.yesidrobayo.shopledger.domain.port.MoneyAccountRepository
import io.github.yesidrobayo.shopledger.domain.port.MoneyLedgerRepository
import io.github.yesidrobayo.shopledger.domain.port.StorageFailure
import io.github.yesidrobayo.shopledger.domain.port.StorageFailureException
import io.github.yesidrobayo.shopledger.domain.port.SupplierLedgerRepository
import io.github.yesidrobayo.shopledger.domain.port.TransactionRunner
import io.github.yesidrobayo.shopledger.domain.port.TxResult
import io.github.yesidrobayo.shopledger.domain.rule.payable.SupplierCompensation
import io.github.yesidrobayo.shopledger.domain.rule.payable.SupplierLedgerRules
import io.github.yesidrobayo.shopledger.domain.rule.region.BusinessCalendar
import io.github.yesidrobayo.shopledger.domain.rule.region.BusinessCalendarResult
import io.github.yesidrobayo.shopledger.domain.usecase.money.MoneyAppendResult
import io.github.yesidrobayo.shopledger.domain.usecase.money.MoneyLedgerWriter

/**
 * Anula un pago a proveedor que se registró por error, en una sola transacción: el pago no se
 * edita ni se borra, recibe su compensación, un `ENTRY_VOID` con el monto opuesto y la fecha de la
 * anulación, y lo que se le debe vuelve a subir.
 *
 * Si el pago salió de una cuenta, su `SUPPLIER_PAYMENT` recibe en la misma transacción su
 * `MOVEMENT_VOID`, y la cuenta vuelve a subir. Solo anula pagos: el cargo de una compra se compensa
 * al anular la compra, que es el documento. Un pago se anula una sola vez.
 */
class VoidSupplierPaymentUseCase(
    private val transactions: TransactionRunner,
    private val settings: AppSettingsRepository,
    private val ledger: SupplierLedgerRepository,
    private val clock: Clock,
    private val accounts: MoneyAccountRepository? = null,
    private val moneyLedger: MoneyLedgerRepository? = null,
) {
    suspend operator fun invoke(entryId: Long): VoidSupplierPaymentResult = try {
        transactions.write { void(entryId) }
    } catch (failure: StorageFailureException) {
        VoidSupplierPaymentResult.StorageFailed(failure.failure)
    }

    private suspend fun void(entryId: Long): TxResult<VoidSupplierPaymentResult> {
        val calendar = when (val result = BusinessCalendar.of(settings.get().regional)) {
            is BusinessCalendarResult.Ready -> result.calendar

            is BusinessCalendarResult.UnknownZone -> return TxResult.Rollback(
                VoidSupplierPaymentResult.UnknownTimeZone(result.storedZoneId),
            )
        }
        val payment = ledger.findById(entryId)
            ?: return TxResult.Rollback(VoidSupplierPaymentResult.PaymentNotFound)
        if (payment.content.type != SupplierLedgerEntryType.PAYMENT) {
            return TxResult.Rollback(VoidSupplierPaymentResult.NotAPayment)
        }
        val amount = when (
            val compensation = SupplierLedgerRules.compensationOf(
                payment,
                ledger.isCompensated(entryId),
            )
        ) {
            is SupplierCompensation.Ready -> compensation.amount

            SupplierCompensation.AlreadyCompensated ->
                return TxResult.Rollback(VoidSupplierPaymentResult.AlreadyVoided)

            // Un pago nunca es una compensación: ya se comprobó su tipo.
            SupplierCompensation.NotCompensable ->
                return TxResult.Rollback(VoidSupplierPaymentResult.NotAPayment)
        }
        val now = clock.now()
        val today = calendar.stamp(now)
        val supplierId = payment.content.supplierId
        ledger.appendChained(supplierId, amount) { balanceAfter ->
            NewSupplierLedgerEntry(
                supplierId = supplierId,
                type = SupplierLedgerEntryType.ENTRY_VOID,
                amount = amount,
                balanceAfter = balanceAfter,
                reversesEntryId = entryId,
                occurredAt = now,
                businessDate = today.businessDate,
                createdAt = now,
            )
        } ?: return TxResult.Rollback(VoidSupplierPaymentResult.BalanceTooLarge)
        val money = moneyLedger ?: return TxResult.Commit(VoidSupplierPaymentResult.Voided)
        for (movement in money.findBySupplierLedgerEntry(entryId)) {
            // El pago estaba vigente, así que su movimiento tampoco tiene anulación.
            val writer = MoneyLedgerWriter(
                requireNotNull(accounts) { "Anular un pago con cuenta pide las cuentas" },
                money,
            )
            when (writer.void(movement, today, now)) {
                is MoneyAppendResult.Appended -> Unit

                MoneyAppendResult.AccountNotFound, MoneyAppendResult.Overflow ->
                    return TxResult.Rollback(VoidSupplierPaymentResult.AccountBalanceTooLarge)
            }
        }
        return TxResult.Commit(VoidSupplierPaymentResult.Voided)
    }
}

/** Resultado de [VoidSupplierPaymentUseCase]. Salvo [Voided], no se escribió nada. */
sealed interface VoidSupplierPaymentResult {
    /** El pago quedó compensado, y lo que se le debe al proveedor volvió a subir. */
    data object Voided : VoidSupplierPaymentResult

    /** La fila no existe. */
    data object PaymentNotFound : VoidSupplierPaymentResult

    /** La fila no es un pago: un cargo se compensa al anular su compra. */
    data object NotAPayment : VoidSupplierPaymentResult

    /** El pago ya estaba anulado: se anula una sola vez. */
    data object AlreadyVoided : VoidSupplierPaymentResult

    /** Este teléfono no conoce la zona del negocio [storedZoneId]: no hay cómo fechar la anulación. */
    data class UnknownTimeZone(val storedZoneId: String) : VoidSupplierPaymentResult

    /** El saldo que dejaría no cabe en un monto. Solo sale con cifras absurdas. */
    data object BalanceTooLarge : VoidSupplierPaymentResult

    /**
     * El movimiento de la cuenta no se puede compensar: su saldo no cabría, o la cuenta falta. Solo
     * sale de una copia dañada.
     */
    data object AccountBalanceTooLarge : VoidSupplierPaymentResult

    /** La base no pudo escribir, por [failure]. No quedó nada guardado. */
    data class StorageFailed(val failure: StorageFailure) : VoidSupplierPaymentResult
}
