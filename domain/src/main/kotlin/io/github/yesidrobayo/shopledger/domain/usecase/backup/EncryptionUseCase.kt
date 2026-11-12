package io.github.yesidrobayo.shopledger.domain.usecase.backup

import io.github.yesidrobayo.shopledger.domain.model.backup.ActivationOutcome
import io.github.yesidrobayo.shopledger.domain.model.backup.CodeCheckOutcome
import io.github.yesidrobayo.shopledger.domain.model.backup.DeactivationOutcome
import io.github.yesidrobayo.shopledger.domain.model.backup.EncryptionStatus
import io.github.yesidrobayo.shopledger.domain.model.backup.RecoveryCodeDraft
import io.github.yesidrobayo.shopledger.domain.model.backup.RecoveryCodeView
import io.github.yesidrobayo.shopledger.domain.model.backup.ResealOutcome
import io.github.yesidrobayo.shopledger.domain.model.backup.TypedCodeCheck
import io.github.yesidrobayo.shopledger.domain.port.EncryptionPort

/**
 * El cifrado de las copias para las pantallas: leer su estado y activarlo con la compuerta de cuatro
 * pasos. El código nuevo vive solo en memoria hasta [activate], y nada de esto usa la red.
 */
class EncryptionUseCase(private val port: EncryptionPort) {
    suspend fun status(): EncryptionStatus = port.status()

    fun newCode(): RecoveryCodeDraft = port.newCode()

    fun checkTyped(draft: RecoveryCodeDraft, typed: String): TypedCodeCheck =
        port.checkTyped(draft, typed)

    suspend fun roundTrip(draft: RecoveryCodeDraft): Boolean = port.roundTrip(draft)

    suspend fun activate(draft: RecoveryCodeDraft): ActivationOutcome = port.activate(draft)

    suspend fun reseal(typed: String): ResealOutcome = port.reseal(typed)

    /** El código vigente, para `Ver mi código`, ya con la verificación del teléfono pasada. */
    suspend fun viewCode(): RecoveryCodeView = port.viewCode()

    /** La prueba del código: periódica o pedida con `Probar el código`. */
    suspend fun checkCode(typed: String): CodeCheckOutcome = port.checkCode(typed)

    suspend fun deactivate(): DeactivationOutcome = port.deactivate()
}
