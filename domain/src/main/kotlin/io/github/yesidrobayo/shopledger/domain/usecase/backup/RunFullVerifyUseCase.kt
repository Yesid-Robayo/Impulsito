package io.github.yesidrobayo.shopledger.domain.usecase.backup

import io.github.yesidrobayo.shopledger.domain.model.backup.FullVerifyOutcome
import io.github.yesidrobayo.shopledger.domain.port.FullVerifyPort

/** `Comprobar ahora`: la comprobación completa de la copia más reciente, a pedido de la persona. */
class RunFullVerifyUseCase(private val port: FullVerifyPort) {
    suspend operator fun invoke(): FullVerifyOutcome = port.runFullVerify()
}
