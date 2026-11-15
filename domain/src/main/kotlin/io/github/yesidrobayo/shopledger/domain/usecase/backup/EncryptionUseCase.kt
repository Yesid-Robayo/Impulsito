package io.github.yesidrobayo.shopledger.domain.usecase.backup

import io.github.yesidrobayo.shopledger.domain.model.audit.AuditEventType
import io.github.yesidrobayo.shopledger.domain.model.audit.AuditResult
import io.github.yesidrobayo.shopledger.domain.model.backup.ActivationOutcome
import io.github.yesidrobayo.shopledger.domain.model.backup.CodeCheckOutcome
import io.github.yesidrobayo.shopledger.domain.model.backup.DeactivationOutcome
import io.github.yesidrobayo.shopledger.domain.model.backup.EncryptionStatus
import io.github.yesidrobayo.shopledger.domain.model.backup.RecoveryCodeDraft
import io.github.yesidrobayo.shopledger.domain.model.backup.RecoveryCodeView
import io.github.yesidrobayo.shopledger.domain.model.backup.ResealOutcome
import io.github.yesidrobayo.shopledger.domain.model.backup.TypedCodeCheck
import io.github.yesidrobayo.shopledger.domain.port.AuditRecorder
import io.github.yesidrobayo.shopledger.domain.port.EncryptionPort

/**
 * El cifrado de las copias para las pantallas: leer su estado y activarlo con la compuerta de cuatro
 * pasos. El código nuevo vive solo en memoria hasta [activate], y nada de esto usa la red.
 *
 * @param audit anota en la auditoría la activación o el cambio del código, y cada vez que se ve el
 *   código. Apagar el cifrado lo anota la capa de datos, que es quien lo confirma.
 */
class EncryptionUseCase(private val port: EncryptionPort, private val audit: AuditRecorder) {
    suspend fun status(): EncryptionStatus = port.status()

    fun newCode(): RecoveryCodeDraft = port.newCode()

    fun checkTyped(draft: RecoveryCodeDraft, typed: String): TypedCodeCheck =
        port.checkTyped(draft, typed)

    suspend fun roundTrip(draft: RecoveryCodeDraft): Boolean = port.roundTrip(draft)

    /**
     * Activa el cifrado con [draft], o cambia el código. Queda en la auditoría como un cambio del
     * cifrado, con su resultado, después de la operación.
     */
    suspend fun activate(draft: RecoveryCodeDraft): ActivationOutcome =
        port.activate(draft).also { outcome ->
            val result =
                when (outcome) {
                    ActivationOutcome.ACTIVATED -> AuditResult.SUCCESS
                    ActivationOutcome.SAVE_FAILED -> AuditResult.FAILURE
                }
            audit.record(AuditEventType.ENCRYPTION_CHANGED, result)
        }

    suspend fun reseal(typed: String): ResealOutcome = port.reseal(typed)

    /**
     * El código vigente, para `Ver mi código`, ya con la verificación del teléfono pasada. Verlo
     * queda en la auditoría, sin el código, y también si no se pudo abrir.
     */
    suspend fun viewCode(): RecoveryCodeView = port.viewCode().also { view ->
        val result =
            when (view) {
                is RecoveryCodeView.Shown -> AuditResult.SUCCESS
                RecoveryCodeView.Unavailable -> AuditResult.FAILURE
            }
        audit.record(AuditEventType.RECOVERY_CODE_VIEWED, result)
    }

    /** La prueba del código: periódica o pedida con `Probar el código`. */
    suspend fun checkCode(typed: String): CodeCheckOutcome = port.checkCode(typed)

    suspend fun deactivate(): DeactivationOutcome = port.deactivate()
}
