package io.github.yesidrobayo.shopledger.domain.port

import io.github.yesidrobayo.shopledger.domain.model.backup.FullVerifyOutcome

/**
 * `Comprobar ahora`: baja la copia más reciente entera y compara su suma, solo cuando la persona lo
 * pide, porque gasta datos. Es la única escritura de `Salud de mis datos`.
 */
fun interface FullVerifyPort {
    suspend fun runFullVerify(): FullVerifyOutcome
}
