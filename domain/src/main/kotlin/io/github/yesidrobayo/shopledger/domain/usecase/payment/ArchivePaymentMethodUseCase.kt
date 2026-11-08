package io.github.yesidrobayo.shopledger.domain.usecase.payment

import io.github.yesidrobayo.shopledger.domain.port.Clock
import io.github.yesidrobayo.shopledger.domain.port.PaymentMethodRepository
import io.github.yesidrobayo.shopledger.domain.port.StorageFailureException
import io.github.yesidrobayo.shopledger.domain.port.TransactionRunner
import io.github.yesidrobayo.shopledger.domain.port.TxResult

/**
 * Archiva un método de pago, tenga ventas o no: deja de ofrecerse al cobrar, sigue en los reportes
 * y se puede reactivar. Sus ventas conservan el nombre y el tipo con que se cobraron.
 *
 * Siempre queda al menos un vigente, porque toda venta lleva al menos un pago.
 */
class ArchivePaymentMethodUseCase(
    private val transactions: TransactionRunner,
    private val methods: PaymentMethodRepository,
    private val clock: Clock,
) {
    suspend operator fun invoke(id: Long): ArchivePaymentMethodResult = try {
        transactions.write { archive(id) }
    } catch (failure: StorageFailureException) {
        ArchivePaymentMethodResult.StorageFailed(failure.failure)
    }

    private suspend fun archive(id: Long): TxResult<ArchivePaymentMethodResult> {
        val method = methods.findById(id)
            ?: return TxResult.Rollback(ArchivePaymentMethodResult.NotFound)
        val refused = when {
            method.isArchived -> ArchivePaymentMethodResult.AlreadyArchived
            methods.findActive().size <= 1 -> ArchivePaymentMethodResult.LastActive
            else -> null
        }
        if (refused != null) return TxResult.Rollback(refused)
        val now = clock.now()
        methods.setArchivedAt(id, archivedAt = now, updatedAt = now)
        return TxResult.Commit(ArchivePaymentMethodResult.Archived)
    }
}
