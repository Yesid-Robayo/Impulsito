package io.github.yesidrobayo.shopledger.ui.backup

import io.github.yesidrobayo.shopledger.domain.model.backup.BackupCopyList
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupDestinationInfo
import io.github.yesidrobayo.shopledger.domain.model.backup.HandoffCopy
import io.github.yesidrobayo.shopledger.domain.model.backup.RestoreAnswer

/**
 * Lo que muestra la pantalla del respaldo. El bloque de estado es local y llega enseguida; la lista
 * de copias puede tardar, porque con WebDAV la pide al servicio.
 *
 * @param wording el estado y lo que hace falta para decirlo, o nulo mientras se lee.
 * @param destination el destino conectado, o nulo sin destino.
 * @param copies la lista de copias, o nula mientras se pide.
 * @param requesting si `Respaldar ahora` está pidiendo la copia.
 * @param message un aviso breve pendiente de mostrar.
 * @param handoff con la nube del teléfono, la última copia entregada, para revisarla allí.
 * @param missingHelp si se muestra la hoja `Si no aparece con esa hora`.
 * @param restore en qué va una restauración pedida desde esta pantalla.
 */
data class BackupUiState(
    val wording: BackupWording? = null,
    val destination: BackupDestinationInfo? = null,
    val copies: BackupCopyList? = null,
    val requesting: Boolean = false,
    val message: BackupMessage? = null,
    val handoff: HandoffCopy? = null,
    val missingHelp: Boolean = false,
    val restore: RestoreUi = RestoreUi.Idle,
)

/** Los avisos breves de la pantalla. */
enum class BackupMessage {
    /** Se pidió la copia sin internet: queda encolada y sale cuando vuelva la conexión. */
    NO_INTERNET,

    /** Quedó anotada la revisión: lo que vio la persona, no una comprobación. */
    CHECK_SAVED,
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

    /** `Abrir {provider}`: lo resuelve la navegación, con la app de esa nube. */
    data object OpenProvider : BackupAction

    /** `Ya la vi en {provider}`: anota lo que dijo la persona, con la generación en pantalla. */
    data object MarkSeen : BackupAction

    /** `No aparece`: muestra qué hacer, sin anotar nada. */
    data object ShowMissingHelp : BackupAction

    data object CloseMissingHelp : BackupAction

    /** Toca una copia de la lista: abre su hoja. */
    data class OpenCopy(val key: String) : BackupAction

    /** `Restaurar` en la hoja de una copia: pide confirmar. */
    data class ConfirmCopy(val key: String) : BackupAction

    /** `Buscar un archivo`: lo resuelve la navegación, con el selector del sistema. */
    data object FindFile : BackupAction

    /** El selector devolvió [uri]: pide confirmar, porque no viene de la lista. */
    data class FilePicked(val uri: String) : BackupAction

    /** Confirmó restaurar lo que estaba en pantalla. */
    data object StartRestore : BackupAction

    /** Contestó la pregunta de la restauración. */
    data class Answer(val answer: RestoreAnswer) : BackupAction

    /** Cancela la restauración: los datos no cambian. */
    data object CancelRestore : BackupAction

    /** Cierra la hoja, el diálogo o el aviso de la restauración. */
    data object CloseRestore : BackupAction
}
