package io.github.yesidrobayo.shopledger.domain.usecase.payment

import io.github.yesidrobayo.shopledger.domain.model.payment.PaymentMethodDraft
import io.github.yesidrobayo.shopledger.domain.port.Clock
import io.github.yesidrobayo.shopledger.domain.port.PaymentMethodRepository
import io.github.yesidrobayo.shopledger.domain.port.StorageFailureException
import io.github.yesidrobayo.shopledger.domain.port.TransactionRunner
import io.github.yesidrobayo.shopledger.domain.port.TxResult
import io.github.yesidrobayo.shopledger.domain.rule.payment.PaymentMethodCheck

/**
 * Guarda lo que la persona cambió de un método de pago, inicial o propio, vigente o archivado.
 *
 * Las ventas pasadas no cambian: guardan su propia copia del nombre y del tipo. Con ventas, el tipo
 * ya no cambia. Un método inicial al que no se le toca el nombre lo conserva traducido. Guardar sin
 * cambios no escribe nada ni cuenta un cambio para la copia.
 */
class EditPaymentMethodUseCase(
    private val transactions: TransactionRunner,
    private val methods: PaymentMethodRepository,
    private val clock: Clock,
) {
    suspend operator fun invoke(id: Long, draft: PaymentMethodDraft): SavePaymentMethodResult =
        try {
            transactions.write { edit(id, draft) }
        } catch (failure: StorageFailureException) {
            SavePaymentMethodResult.StorageFailed(failure.failure)
        }

    private suspend fun edit(
        id: Long,
        draft: PaymentMethodDraft,
    ): TxResult<SavePaymentMethodResult> {
        val method = methods.findById(id)
            ?: return TxResult.Rollback(SavePaymentMethodResult.NotFound)
        val details = when (val checked = checkDraft(methods, draft, editing = method)) {
            is PaymentMethodCheck.Invalid ->
                return TxResult.Rollback(SavePaymentMethodResult.Invalid(checked.errors))

            is PaymentMethodCheck.Valid -> checked.details
        }
        if (details == method.details) return TxResult.Rollback(SavePaymentMethodResult.Saved(id))
        methods.update(id, details, clock.now())
        return TxResult.Commit(SavePaymentMethodResult.Saved(id))
    }
}
