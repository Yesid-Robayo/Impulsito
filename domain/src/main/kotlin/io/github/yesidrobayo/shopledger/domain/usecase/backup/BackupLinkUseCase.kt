package io.github.yesidrobayo.shopledger.domain.usecase.backup

import io.github.yesidrobayo.shopledger.domain.model.backup.ConflictLookup
import io.github.yesidrobayo.shopledger.domain.model.backup.DisconnectResult
import io.github.yesidrobayo.shopledger.domain.model.backup.KeepPhoneResult
import io.github.yesidrobayo.shopledger.domain.port.BackupLinkPort

/**
 * El enlace con el destino desde la pantalla del respaldo: desconectar, volver tras iniciar sesión
 * en la nube del teléfono y decidir un conflicto. `Decidir después` no necesita nada: no cambia el
 * estado.
 */
class BackupLinkUseCase(private val port: BackupLinkPort) {
    /** Desconecta, siempre en el teléfono y sin red. Las copias y los datos no se tocan. */
    suspend fun disconnect(): DisconnectResult = port.disconnect()

    /** Con la sesión de la nube cerrada, comprueba si volvió. Devuelve si las copias siguen. */
    suspend fun resumeAfterSignIn(): Boolean = port.resumeAfterSignIn()

    /** Los dos lados del conflicto. */
    suspend fun describeConflict(): ConflictLookup = port.describeConflict()

    /** `Conservar este teléfono`, con la confirmación reforzada ya marcada o no. */
    suspend fun keepThisPhone(fewerRecordsConfirmed: Boolean): KeepPhoneResult =
        port.keepThisPhone(fewerRecordsConfirmed)
}
