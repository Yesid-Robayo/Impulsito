package io.github.yesidrobayo.shopledger.domain.usecase.payment

import io.github.yesidrobayo.shopledger.domain.model.ArchiveFilter
import io.github.yesidrobayo.shopledger.domain.model.payment.PaymentMethodRecord
import io.github.yesidrobayo.shopledger.domain.port.PaymentMethodRepository
import kotlinx.coroutines.flow.Flow

/**
 * La lista de métodos de pago, vigentes o archivados, en el orden que eligió la persona. Vuelve a
 * emitir con cada cambio confirmado.
 */
class ObservePaymentMethodsUseCase(private val methods: PaymentMethodRepository) {
    operator fun invoke(filter: ArchiveFilter): Flow<List<PaymentMethodRecord>> =
        methods.observe(filter)
}
