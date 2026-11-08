package io.github.yesidrobayo.shopledger.domain.usecase.payment

import io.github.yesidrobayo.shopledger.domain.model.catalog.StoredImage
import io.github.yesidrobayo.shopledger.domain.model.payment.PaymentMethodRecord
import io.github.yesidrobayo.shopledger.domain.port.MediaAssetRepository
import io.github.yesidrobayo.shopledger.domain.port.TransactionRunner

/**
 * Las imágenes de cobro de unos métodos, por el id de cada método, para mostrar su miniatura en la
 * lista. Un método sin imagen, o cuya fila ya no existe, no aparece.
 */
class FindPaymentImagesUseCase(
    private val transactions: TransactionRunner,
    private val media: MediaAssetRepository,
) {
    suspend operator fun invoke(methods: List<PaymentMethodRecord>): Map<Long, StoredImage> =
        transactions.read {
            methods
                .mapNotNull { method ->
                    method.details.instructionsMediaId
                        ?.let { media.findImage(it) }
                        ?.let { method.id to it }
                }.toMap()
        }
}
