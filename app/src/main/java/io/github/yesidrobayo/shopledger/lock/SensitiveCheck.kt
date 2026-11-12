package io.github.yesidrobayo.shopledger.lock

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import io.github.yesidrobayo.shopledger.ShopLedgerApp

/**
 * Con el bloqueo encendido, una operación sensible, como restaurar, pide de nuevo la verificación
 * del sistema aunque la app ya esté desbloqueada: quien encuentra el teléfono abierto no la hace sin
 * el PIN. Sin el bloqueo, basta la confirmación normal de la pantalla.
 *
 * Pide la verificación más fuerte que el teléfono ofrezca, y la credencial del teléfono siempre
 * vale. Si el teléfono ya no tiene bloqueo de pantalla, no hay con qué pedirla y la operación sigue:
 * nunca deja a nadie fuera de sus datos.
 */
class SensitiveCheck(private val lock: AppLock, private val authenticator: DeviceAuthenticator) {
    /** `true` si la operación puede seguir. [title] dice en el diálogo del sistema qué se confirma. */
    suspend fun confirm(title: String): Boolean {
        val lockEnabled =
            when (val state = lock.state.value) {
                AppLockState.Unknown -> false
                is AppLockState.Open -> state.lockEnabled
                AppLockState.Locked -> true
            }
        if (!lockEnabled) return true
        return when (authenticator.authenticate(AuthPurpose.SENSITIVE, title, null)) {
            AuthOutcome.Success, AuthOutcome.NoDeviceCredential -> true
            AuthOutcome.Canceled, is AuthOutcome.Failed -> false
        }
    }
}

/** La verificación de las operaciones sensibles, con el bloqueo de la app y la del sistema. */
@Composable
fun rememberSensitiveCheck(): SensitiveCheck {
    val lock = (LocalContext.current.applicationContext as ShopLedgerApp).container.appLock
    val authenticator = LocalDeviceAuthenticator.current
    return remember(lock, authenticator) { SensitiveCheck(lock, authenticator) }
}
