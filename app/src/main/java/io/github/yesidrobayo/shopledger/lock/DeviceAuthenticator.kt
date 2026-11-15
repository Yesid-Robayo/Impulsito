package io.github.yesidrobayo.shopledger.lock

import android.app.KeyguardManager
import android.os.Build
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/** Para qué se pide la verificación, que decide qué tan fuerte debe ser. */
enum class AuthPurpose {
    /** Abrir la app bloqueada, o encender el bloqueo: biometría débil o la credencial del teléfono. */
    UNLOCK,

    /**
     * Una operación sensible, como apagar el bloqueo: desde Android 11, biometría fuerte o la
     * credencial. Antes, esa combinación no existe y vale la misma de [UNLOCK].
     */
    SENSITIVE,
}

/** Cómo terminó la verificación del sistema. */
sealed interface AuthOutcome {
    data object Success : AuthOutcome

    /** La persona la cerró, o el sistema la cortó al salir de la app: se puede volver a intentar. */
    data object Canceled : AuthOutcome

    /** El teléfono no tiene bloqueo de pantalla: no hay con qué autenticar. */
    data object NoDeviceCredential : AuthOutcome

    /**
     * Falló por otra causa, como demasiados intentos. [systemMessage] es el texto del sistema, que la
     * pantalla muestra tal cual y nunca se registra.
     */
    data class Failed(val systemMessage: String) : AuthOutcome
}

/** La verificación con la huella, el rostro o el PIN del teléfono, sin PIN propio de la app. */
interface DeviceAuthenticator {
    /** Si el teléfono tiene bloqueo de pantalla. Sin él, el bloqueo de la app no se puede encender. */
    fun hasDeviceCredential(): Boolean

    /** Abre la verificación del sistema con [title] y [subtitle], y espera su resultado. */
    suspend fun authenticate(purpose: AuthPurpose, title: String, subtitle: String?): AuthOutcome
}

/**
 * Sin actividad no hay verificación: es el valor de las vistas previas y de las pruebas que no la
 * proveen. Dice que no hay bloqueo de pantalla y nunca autentica.
 */
object NoDeviceAuthenticator : DeviceAuthenticator {
    override fun hasDeviceCredential(): Boolean = false

    override suspend fun authenticate(
        purpose: AuthPurpose,
        title: String,
        subtitle: String?,
    ): AuthOutcome = AuthOutcome.NoDeviceCredential
}

/** La verificación del sistema que provee la actividad principal a sus pantallas. */
val LocalDeviceAuthenticator = staticCompositionLocalOf<DeviceAuthenticator> {
    NoDeviceAuthenticator
}

/**
 * La verificación con `BiometricPrompt`. Es un bloqueo de interfaz, sin `CryptoObject`: los trabajos
 * en segundo plano abren la base y lo sellado sin nadie presente, y una clave atada a la verificación
 * los rompería.
 *
 * Con la credencial del teléfono permitida, el diálogo no lleva botón negativo, y quien no tiene
 * huella ni rostro usa su PIN. Mientras se verifica, [lock] no cuenta la salida a la pantalla del PIN
 * como segundo plano.
 *
 * Una verificación que el sistema da por fallida, como tras demasiados intentos, avisa con
 * [onFailed], que la anota en la auditoría sin el texto del sistema. Cancelar no es un fallo.
 */
class BiometricAuthenticator(
    private val activity: FragmentActivity,
    private val lock: AppLock,
    private val onFailed: () -> Unit,
) : DeviceAuthenticator {
    override fun hasDeviceCredential(): Boolean =
        activity.getSystemService(KeyguardManager::class.java)?.isDeviceSecure == true

    override suspend fun authenticate(
        purpose: AuthPurpose,
        title: String,
        subtitle: String?,
    ): AuthOutcome {
        if (!hasDeviceCredential()) return AuthOutcome.NoDeviceCredential
        // Con el estado ya guardado, el diálogo no se abre ni responde: se trata como cancelado
        // para no quedar esperando un resultado que nunca llega.
        val canShow =
            activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) &&
                !activity.supportFragmentManager.isStateSaved
        if (!canShow) return AuthOutcome.Canceled
        return lock.authenticating { prompt(purpose, title, subtitle) }.also { outcome ->
            if (outcome is AuthOutcome.Failed) onFailed()
        }
    }

    private suspend fun prompt(
        purpose: AuthPurpose,
        title: String,
        subtitle: String?,
    ): AuthOutcome = suspendCancellableCoroutine { continuation ->
        val callback =
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(
                    result: BiometricPrompt.AuthenticationResult,
                ) {
                    if (continuation.isActive) continuation.resume(AuthOutcome.Success)
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    if (continuation.isActive) {
                        continuation.resume(outcomeOf(errorCode, errString))
                    }
                }
            }
        val prompt =
            BiometricPrompt(activity, ContextCompat.getMainExecutor(activity), callback)
        val info =
            BiometricPrompt.PromptInfo
                .Builder()
                .setTitle(title)
                .setSubtitle(subtitle)
                .setAllowedAuthenticators(authenticatorsFor(purpose))
                .setConfirmationRequired(false)
                .build()
        continuation.invokeOnCancellation { prompt.cancelAuthentication() }
        prompt.authenticate(info)
    }

    private fun outcomeOf(errorCode: Int, errString: CharSequence): AuthOutcome = when (errorCode) {
        BiometricPrompt.ERROR_USER_CANCELED,
        BiometricPrompt.ERROR_CANCELED,
        BiometricPrompt.ERROR_NEGATIVE_BUTTON,
        BiometricPrompt.ERROR_TIMEOUT,
        -> AuthOutcome.Canceled

        BiometricPrompt.ERROR_NO_DEVICE_CREDENTIAL -> AuthOutcome.NoDeviceCredential

        // Otra causa con el teléfono ya sin bloqueo también es falta de credencial: nunca deja a
        // nadie fuera.
        else ->
            if (hasDeviceCredential()) {
                AuthOutcome.Failed(errString.toString())
            } else {
                AuthOutcome.NoDeviceCredential
            }
    }

    private companion object {
        /**
         * Biometría fuerte con la credencial no existe en Android 9 y 10, y la débil con la credencial
         * vale en todo el rango de la app.
         */
        fun authenticatorsFor(purpose: AuthPurpose): Int = when (purpose) {
            AuthPurpose.UNLOCK -> BIOMETRIC_WEAK or DEVICE_CREDENTIAL

            AuthPurpose.SENSITIVE ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    BIOMETRIC_STRONG or DEVICE_CREDENTIAL
                } else {
                    BIOMETRIC_WEAK or DEVICE_CREDENTIAL
                }
        }
    }
}
