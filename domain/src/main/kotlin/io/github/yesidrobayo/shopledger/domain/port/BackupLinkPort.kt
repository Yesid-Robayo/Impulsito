package io.github.yesidrobayo.shopledger.domain.port

import io.github.yesidrobayo.shopledger.domain.model.backup.ConflictLookup
import io.github.yesidrobayo.shopledger.domain.model.backup.DisconnectResult
import io.github.yesidrobayo.shopledger.domain.model.backup.KeepPhoneResult

/**
 * Lo que la persona hace con el enlace al destino desde la pantalla del respaldo: desconectar,
 * volver a la nube del teléfono tras iniciar sesión y decidir un conflicto.
 *
 * Todo corre bajo el cerrojo del respaldo: nunca a la vez que una copia.
 */
interface BackupLinkPort {
    /**
     * Desconecta el destino, siempre en el teléfono y sin red: cancela los trabajos, borra la
     * credencial o suelta los archivos, y conserva lo que reconoce el mismo destino al volver. Las
     * copias del destino y los datos no se tocan, y los cambios siguen contándose.
     */
    suspend fun disconnect(): DisconnectResult

    /**
     * Con la nube del teléfono detenida porque su sesión se cerró, comprueba los tres archivos. Si
     * responden, las copias siguen solas, sin crear archivos nuevos. Devuelve si siguieron.
     */
    suspend fun resumeAfterSignIn(): Boolean

    /** Los dos lados del conflicto. Con WebDAV lee el destino, así que necesita internet. */
    suspend fun describeConflict(): ConflictLookup

    /**
     * `Conservar este teléfono`. Con [fewerRecordsConfirmed], la persona ya marcó que entiende que
     * este teléfono tiene menos registros que la cabeza.
     */
    suspend fun keepThisPhone(fewerRecordsConfirmed: Boolean): KeepPhoneResult
}
