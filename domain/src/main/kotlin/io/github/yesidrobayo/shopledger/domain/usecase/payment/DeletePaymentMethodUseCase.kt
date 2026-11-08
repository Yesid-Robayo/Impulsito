package io.github.yesidrobayo.shopledger.domain.usecase.payment

import io.github.yesidrobayo.shopledger.domain.port.PaymentMethodRepository
import io.github.yesidrobayo.shopledger.domain.port.StorageFailureException
import io.github.yesidrobayo.shopledger.domain.port.TransactionRunner
import io.github.yesidrobayo.shopledger.domain.port.TxResult

/**
 * Elimina un método de pago que nunca se usó en una venta, como uno creado por error.
 *
 * Con ventas no se elimina, aunque estén anuladas: se archiva, para que sus ventas sigan apuntando
 * a él. Tampoco el único vigente.
 */
class DeletePaymentMethodUseCase(
    private val transactions: TransactionRunner,
    private val methods: PaymentMethodRepository,
) {
    suspend operator fun invoke(id: Long): DeletePaymentMethodResult = try {
        transactions.write { delete(id) }
    } catch (failure: StorageFailureException) {
        DeletePaymentMethodResult.StorageFailed(failure.failure)
    }

    private suspend fun delete(id: Long): TxResult<DeletePaymentMethodResult> {
        val method = methods.findById(id)
            ?: return TxResult.Rollback(DeletePaymentMethodResult.NotFound)
        val refused = when {
            methods.hasSalePayments(id) -> DeletePaymentMethodResult.HasSalePayments

            !method.isArchived && methods.findActive().size <= 1 ->
                DeletePaymentMethodResult.LastActive

            else -> null
        }
        if (refused != null) return TxResult.Rollback(refused)
        methods.delete(id)
        return TxResult.Commit(DeletePaymentMethodResult.Deleted)
    }
}
