package io.github.yesidrobayo.shopledger.ui.backup

import io.github.yesidrobayo.shopledger.domain.model.backup.BackupCopyList
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupDestinationInfo

/**
 * Lo que muestra la pantalla del respaldo. El bloque de estado es local y llega enseguida; la lista
 * de copias puede tardar, porque con WebDAV la pide al servicio.
 *
 * @param wording el estado y lo que hace falta para decirlo, o nulo mientras se lee.
 * @param destination el destino conectado, o nulo sin destino.
 * @param copies la lista de copias, o nula mientras se pide.
 * @param requesting si `Respaldar ahora` está pidiendo la copia.
 * @param message un aviso breve pendiente de mostrar.
 */
data class BackupUiState(
    val wording: BackupWording? = null,
    val destination: BackupDestinationInfo? = null,
    val copies: BackupCopyList? = null,
    val requesting: Boolean = false,
    val message: BackupMessage? = null,
)

/** Los avisos breves de la pantalla. */
enum class BackupMessage {
    /** Se pidió la copia sin internet: queda encolada y sale cuando vuelva la conexión. */
    NO_INTERNET,
}

/** Lo que la persona puede hacer en la pantalla del respaldo. */
sealed interface BackupAction {
    data object Back : BackupAction

    data object BackUpNow : BackupAction

    /** Sin destino, o para cambiarlo: lleva a elegir dónde guardar. */
    data object ChooseDestination : BackupAction

    data object RetryCopies : BackupAction

    /** Copias en pausa por la política: la muestra para aceptarla. */
    data object ReviewPolicy : BackupAction

    data object MessageShown : BackupAction
}
