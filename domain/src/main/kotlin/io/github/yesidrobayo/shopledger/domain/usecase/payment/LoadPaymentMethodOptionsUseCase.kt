package io.github.yesidrobayo.shopledger.domain.usecase.payment

import io.github.yesidrobayo.shopledger.domain.model.payment.PaymentMethodOptions
import io.github.yesidrobayo.shopledger.domain.port.MediaAssetRepository
import io.github.yesidrobayo.shopledger.domain.port.PaymentMethodRepository
import io.github.yesidrobayo.shopledger.domain.port.TransactionRunner

/**
 * Un método de pago con lo que se puede hacer con él: si se archiva o se elimina, y si su tipo se
 * puede cambiar. Lo lee de una sola foto de la base, para que el método, sus ventas y la cuenta de
 * vigentes digan lo mismo.
 *
 * Es lo que se ofrece en la pantalla; cada caso de uso lo vuelve a comprobar al escribir.
 */
class LoadPaymentMethodOptionsUseCase(
    private val transactions: TransactionRunner,
    private val methods: PaymentMethodRepository,
    private val media: MediaAssetRepository,
) {
    /** Las opciones del método [id], o `null` si ya no existe. */
    suspend operator fun invoke(id: Long): PaymentMethodOptions? = transactions.read {
        methods.findById(id)?.let { method ->
            PaymentMethodOptions(
                method = method,
                hasSalePayments = methods.hasSalePayments(id),
                activeCount = methods.findActive().size,
                image = method.details.instructionsMediaId?.let { media.findImage(it) },
            )
        }
    }
}
