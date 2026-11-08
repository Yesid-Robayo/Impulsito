package io.github.yesidrobayo.shopledger.domain.usecase.payment

import io.github.yesidrobayo.shopledger.domain.model.payment.PaymentMethodDraft
import io.github.yesidrobayo.shopledger.domain.port.Clock
import io.github.yesidrobayo.shopledger.domain.port.MediaAssetRepository
import io.github.yesidrobayo.shopledger.domain.port.PaymentMethodRepository
import io.github.yesidrobayo.shopledger.domain.port.StorageFailureException
import io.github.yesidrobayo.shopledger.domain.port.TransactionRunner
import io.github.yesidrobayo.shopledger.domain.port.TxResult
import io.github.yesidrobayo.shopledger.domain.rule.payment.PaymentMethodCheck

/**
 * Crea un método de pago de la persona, como una billetera o una tarjeta con su propio nombre.
 *
 * Nace vigente y al final de la lista, después también de los archivados: así reactivar uno no lo
 * cruza con el nuevo. Su imagen de cobro, si la trae, se anota en la misma transacción. Es un cambio
 * del negocio: se cuenta para la copia.
 */
class CreatePaymentMethodUseCase(
    private val transactions: TransactionRunner,
    private val methods: PaymentMethodRepository,
    private val media: MediaAssetRepository,
    private val clock: Clock,
) {
    suspend operator fun invoke(draft: PaymentMethodDraft): SavePaymentMethodResult = try {
        transactions.write { create(draft) }
    } catch (failure: StorageFailureException) {
        SavePaymentMethodResult.StorageFailed(failure.failure)
    }

    private suspend fun create(draft: PaymentMethodDraft): TxResult<SavePaymentMethodResult> {
        val details = when (val checked = checkDraft(methods, draft, editing = null)) {
            is PaymentMethodCheck.Invalid ->
                return TxResult.Rollback(SavePaymentMethodResult.Invalid(checked.errors))

            is PaymentMethodCheck.Valid -> checked.details
        }
        val now = clock.now()
        val imageId = imageIdAfter(media, draft.image, current = null, now)
        val last = methods.maxSortOrder() ?: 0L
        val id =
            methods.insert(
                details.copy(instructionsMediaId = imageId),
                Math.addExact(last, 1L),
                now,
            )
        return TxResult.Commit(SavePaymentMethodResult.Saved(id))
    }
}
