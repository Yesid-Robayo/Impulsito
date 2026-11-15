package io.github.yesidrobayo.shopledger.ui.privacy

import androidx.compose.runtime.Immutable
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupDestinationInfo
import io.github.yesidrobayo.shopledger.domain.model.safety.PolicyConsentStatus

/**
 * Un dato del estado real: leído, o que no se pudo leer ahora. Nunca se muestra uno anterior en su
 * lugar, ni se inventa uno.
 */
@Immutable
sealed interface Fact<out T> {
    data object Unavailable : Fact<Nothing>

    data class Known<out T>(val value: T) : Fact<T>
}

/** El valor de [this], o nulo si no se pudo leer. */
fun <T> Fact<T>.knownOrNull(): T? = when (this) {
    is Fact.Known -> value
    Fact.Unavailable -> null
}

/**
 * El segundo destino como lo dice `Qué datos hay y adónde van`: ninguno, o la carpeta elegida.
 *
 * @property folderName el nombre de la carpeta, o nulo si no se pudo leer su nombre.
 */
@Immutable
sealed interface SecondFact {
    data object None : SecondFact

    data class Folder(val folderName: String?) : SecondFact
}

/**
 * `Qué datos hay y adónde van`, con el estado real de este momento.
 *
 * @param loading todavía se lee: `Ahora mismo` muestra su esqueleto, y los textos fijos ya están.
 * @param consent cómo está la aceptación de la política y los términos.
 * @param destination el destino conectado, o nulo si no hay ninguno.
 * @param encryptionOn si las copias salen cifradas.
 * @param second el segundo destino.
 * @param appLock si el bloqueo de la app está encendido.
 * @param updateCheck si la búsqueda de versión está encendida.
 * @param noticeDismissed la persona eligió `Decidir después`: el aviso no vuelve en esta visita.
 * @param accepting la aceptación se está guardando.
 * @param acceptFailed el teléfono no pudo guardar la aceptación: nada cambió.
 */
@Immutable
data class DataTransparencyUiState(
    val loading: Boolean = true,
    val consent: Fact<PolicyConsentStatus> = Fact.Unavailable,
    val destination: Fact<BackupDestinationInfo?> = Fact.Unavailable,
    val encryptionOn: Fact<Boolean> = Fact.Unavailable,
    val second: Fact<SecondFact> = Fact.Unavailable,
    val appLock: Fact<Boolean> = Fact.Unavailable,
    val updateCheck: Fact<Boolean> = Fact.Unavailable,
    val noticeDismissed: Boolean = false,
    val accepting: Boolean = false,
    val acceptFailed: Boolean = false,
) {
    /**
     * `Hay cambios en la política`: la persona aceptó una versión anterior y la de ahora espera su
     * respuesta. Quien nunca aceptó lo decide al conectar un destino.
     */
    val showPolicyNotice: Boolean
        get() = consent.knownOrNull() == PolicyConsentStatus.OUTDATED && !noticeDismissed

    /**
     * Las copias en la nube esperan la aceptación: hay un destino conectado y la versión vigente
     * no está aceptada. Los datos, las ventas y exportar siguen igual.
     */
    val uploadsPaused: Boolean
        get() {
            val status = consent.knownOrNull() ?: return false
            return status != PolicyConsentStatus.ACCEPTED && destination.knownOrNull() != null
        }
}

/** Lo que la persona hace en `Qué datos hay y adónde van`. */
sealed interface DataTransparencyAction {
    data object Back : DataTransparencyAction

    /** `Aceptar la política nueva`. */
    data object AcceptPolicy : DataTransparencyAction

    /** `Decidir después`: no registra nada, y el aviso vuelve en la próxima visita. */
    data object DecideLater : DataTransparencyAction

    /** `Política de privacidad`: la abre el navegador. Lo resuelve la pantalla. */
    data object OpenPrivacy : DataTransparencyAction

    /** `Términos de uso`, desde los recursos. Lo resuelve la navegación. */
    data object OpenTerms : DataTransparencyAction

    /** La pantalla volvió a primer plano: el estado se lee de nuevo. */
    data object Resumed : DataTransparencyAction

    /** El aviso de la aceptación que no se guardó ya se mostró. */
    data object MessageShown : DataTransparencyAction
}
