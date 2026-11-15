package io.github.yesidrobayo.shopledger.ui.privacy

import androidx.compose.runtime.Immutable
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupDestinationInfo
import io.github.yesidrobayo.shopledger.domain.model.safety.CloudAccountCheck
import io.github.yesidrobayo.shopledger.domain.model.safety.CloudAccountKind

/** Una guía de la cuenta de la nube del teléfono, que abre el navegador. */
enum class AccountGuide { TWO_STEP, INACTIVITY, QUOTA }

/**
 * `Seguridad de la cuenta de la nube`.
 *
 * @param loading todavía se lee: las casillas muestran su esqueleto, y la guía ya está.
 * @param destination el destino conectado, o nulo sin destino.
 * @param checked los puntos que la persona marcó para este destino.
 * @param accessLost se perdió el acceso a la nube: `Reconectar la nube` va sobre la lista.
 * @param saveFailed una marca no se pudo guardar y volvió a su estado anterior.
 */
@Immutable
data class CloudAccountUiState(
    val loading: Boolean = true,
    val destination: Fact<BackupDestinationInfo?> = Fact.Unavailable,
    val checked: Set<CloudAccountCheck> = emptySet(),
    val accessLost: Boolean = false,
    val saveFailed: Boolean = false,
) {
    /** El tipo del destino conectado, que decide la lista y para qué valen las marcas. */
    val kind: CloudAccountKind? get() = destination.knownOrNull()?.kind()

    /** Las casillas solo cambian con un destino conectado: sin cuenta no hay nada que marcar. */
    val canMark: Boolean get() = !loading && kind != null
}

/** Lo que la persona hace en `Seguridad de la cuenta de la nube`. */
sealed interface CloudAccountAction {
    data object Back : CloudAccountAction

    /** Marca o desmarca un punto de la lista. */
    data class Mark(val check: CloudAccountCheck, val checked: Boolean) : CloudAccountAction

    /** `Abrir {provider}`, solo con la nube del teléfono. Lo resuelve la pantalla. */
    data object OpenProvider : CloudAccountAction

    /** Una guía de la cuenta, en el navegador. Lo resuelve la pantalla. */
    data class OpenGuide(val guide: AccountGuide) : CloudAccountAction

    /** `Elegir dónde guardar`, sin destino. Lo resuelve la navegación. */
    data object ChooseDestination : CloudAccountAction

    /** `Reconectar la nube`. Lo resuelve la navegación. */
    data object Reconnect : CloudAccountAction

    /** Leer otra vez, al volver a primer plano o tras un error de lectura. */
    data object Refresh : CloudAccountAction

    data object MessageShown : CloudAccountAction
}

/** El tipo de destino de [this]. */
fun BackupDestinationInfo.kind(): CloudAccountKind = when (this) {
    is BackupDestinationInfo.WebDav -> CloudAccountKind.WEBDAV
    is BackupDestinationInfo.Slots -> CloudAccountKind.DOCUMENT_SLOTS
}
