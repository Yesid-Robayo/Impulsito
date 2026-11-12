package io.github.yesidrobayo.shopledger.lock

/**
 * La verificación que el rescate pide antes de restaurar. Sin base no se puede leer si el bloqueo
 * de la app está encendido, así que con bloqueo de pantalla en el teléfono se pide siempre, la más
 * fuerte que el teléfono ofrezca.
 *
 * Sin bloqueo de pantalla no hay con qué pedirla, y restaurar sigue: nunca deja a nadie sin poder
 * recuperar sus datos. Cancelada o fallida, no se restaura.
 */
class DeviceCredentialCheck(private val authenticator: DeviceAuthenticator) {
    /** `true` si se puede restaurar. [title] dice en el diálogo del sistema qué se confirma. */
    suspend fun allows(title: String): Boolean {
        if (!authenticator.hasDeviceCredential()) return true
        return when (authenticator.authenticate(AuthPurpose.SENSITIVE, title, null)) {
            AuthOutcome.Success, AuthOutcome.NoDeviceCredential -> true
            AuthOutcome.Canceled, is AuthOutcome.Failed -> false
        }
    }
}
