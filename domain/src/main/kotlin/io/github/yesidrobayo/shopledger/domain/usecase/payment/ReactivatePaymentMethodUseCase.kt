package io.github.yesidrobayo.shopledger.domain.usecase.payment

import io.github.yesidrobayo.shopledger.domain.port.Clock
import io.github.yesidrobayo.shopledger.domain.port.PaymentMethodRepository
import io.github.yesidrobayo.shopledger.domain.port.StorageFailureException
import io.github.yesidrobayo.shopledger.domain.port.TransactionRunner
import io.github.yesidrobayo.shopledger.domain.port.TxResult

/**
 * Reactiva un método de pago archivado: vuelve a ofrecerse al cobrar.
 *
 * Un archivado no reserva su nombre, así que otro vigente pudo tomarlo mientras tanto. En ese caso
 * no se reactiva, en vez de dejar dos métodos que se ven iguales al cobrar. Un método inicial sin
 * nombre propio nunca choca: su texto sale de su clave.
 */
class ReactivatePaymentMethodUseCase(
    private val transactions: TransactionRunner,
    private val methods: PaymentMethodRepository,
    private val clock: Clock,
) {
    suspend operator fun invoke(id: Long): ReactivatePaymentMethodResult = try {
        transactions.write { reactivate(id) }
    } catch (failure: StorageFailureException) {
        ReactivatePaymentMethodResult.StorageFailed(failure.failure)
    }

    private suspend fun reactivate(id: Long): TxResult<ReactivatePaymentMethodResult> {
        val method = methods.findById(id)
            ?: return TxResult.Rollback(ReactivatePaymentMethodResult.NotFound)
        if (!method.isArchived) return TxResult.Rollback(ReactivatePaymentMethodResult.NotArchived)
        val name = method.details.normalizedName
        if (name != null && methods.findActiveByNormalizedName(name) != null) {
            return TxResult.Rollback(ReactivatePaymentMethodResult.NameTaken)
        }
        methods.setArchivedAt(id, archivedAt = null, updatedAt = clock.now())
        return TxResult.Commit(ReactivatePaymentMethodResult.Reactivated)
    }
}
