package io.github.yesidrobayo.shopledger.ui.backup

import io.github.yesidrobayo.shopledger.domain.model.backup.BackupCopyList
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupCopySummary
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupDestinationInfo
import io.github.yesidrobayo.shopledger.domain.model.backup.ConflictLookup
import io.github.yesidrobayo.shopledger.domain.model.backup.ConflictSides
import io.github.yesidrobayo.shopledger.domain.model.backup.HandoffCopy
import io.github.yesidrobayo.shopledger.domain.model.backup.RestoreAnswer
import io.github.yesidrobayo.shopledger.domain.model.settings.MeteredBackupPolicy

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
 * @param uploadedThisMonth con WebDAV, los bytes subidos en el mes, o nulo mientras se leen.
 * @param metered en qué va `Copias con datos móviles`: la hoja o la advertencia de `Solo con Wi-Fi`.
 * @param release en qué va `Liberar copia`.
 * @param copiesEncrypted con un destino, si las copias salen cifradas; nulo mientras se lee.
 * @param disconnect en qué va `Desconectar`.
 * @param conflict la hoja del conflicto, o nula si está cerrada.
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
    val uploadedThisMonth: Long? = null,
    val metered: MeteredUi = MeteredUi.Closed,
    val release: ReleaseUi = ReleaseUi.Idle,
    val copiesEncrypted: Boolean? = null,
    val disconnect: DisconnectUi = DisconnectUi.HIDDEN,
    val conflict: ConflictUi? = null,
)

/** `Copias con datos móviles`: cerrada, la hoja con las tres opciones o la advertencia. */
sealed interface MeteredUi {
    data object Closed : MeteredUi

    data object Choosing : MeteredUi

    /** `¿Copiar solo con Wi-Fi?`: elegirla pide confirmar, porque deja de haber copias solas. */
    data object ConfirmWifiOnly : MeteredUi
}

/** `Liberar copia`: nada, la pregunta o la liberación en curso. */
sealed interface ReleaseUi {
    data object Idle : ReleaseUi

    /** `¿Liberar esta copia?`. */
    data class Confirm(val copy: BackupCopySummary) : ReleaseUi

    /** Quitando la marca en el destino. */
    data class Working(val copy: BackupCopySummary) : ReleaseUi

    /** No se pudo: la copia sigue fijada, y el aviso ofrece reintentar con ella. */
    data class Failed(val copy: BackupCopySummary) : ReleaseUi
}

/** En qué va `Desconectar`. */
enum class DisconnectUi {
    HIDDEN,

    /** El diálogo pide confirmar. */
    ASKING,

    /** Desconectando: es local y breve, pero el diálogo no se repite. */
    WORKING,
}

/**
 * La hoja del conflicto.
 *
 * @param lookup los dos lados, o nulo mientras se leen: con WebDAV hace falta el destino.
 * @param confirm la confirmación de `Conservar este teléfono` a la vista, o nula.
 * @param working si se está decidiendo: los botones no se repiten.
 * @param offline si el destino no respondió porque no hay internet.
 */
data class ConflictUi(
    val lookup: ConflictLookup? = null,
    val confirm: KeepConfirm? = null,
    val working: Boolean = false,
    val offline: Boolean = false,
)

/** La confirmación de `Conservar este teléfono`. */
sealed interface KeepConfirm {
    /**
     * Con los registros de cada lado. Con [reinforced], este teléfono tiene menos: el botón se
     * activa solo al marcar que se entiende.
     */
    data class Sides(val sides: ConflictSides, val reinforced: Boolean) : KeepConfirm

    /** Con la nube del teléfono, hace falta un archivo nuevo para la ranura que no se usa. */
    data class NewFile(val need: ConflictLookup.NewSlotFileRequired) : KeepConfirm
}

/** Los avisos breves de la pantalla. */
enum class BackupMessage {
    /** Se pidió la copia sin internet: queda encolada y sale cuando vuelva la conexión. */
    NO_INTERNET,

    /** Quedó anotada la revisión: lo que vio la persona, no una comprobación. */
    CHECK_SAVED,

    /** La copia quedó liberada: la retención la puede borrar cuando corresponda. */
    RELEASED,

    /** No se pudo liberar: la copia sigue fijada. */
    RELEASE_FAILED,

    /** El destino quedó desconectado. */
    DISCONNECTED,

    /** No se pudo desconectar: todo sigue como estaba. */
    DISCONNECT_FAILED,

    /** Para conservar este teléfono hay que fijar otra copia, y ya hay tres fijadas. */
    PINNED_MAX,

    /** El destino no respondió al decidir el conflicto: sin él no se decide. */
    CONFLICT_UNREACHABLE,

    /** Sin internet, el destino no se puede leer para decidir el conflicto. */
    CONFLICT_NO_INTERNET,
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

    /** `Copias con datos móviles`: abre la hoja con las tres opciones. */
    data object OpenMeteredPolicy : BackupAction

    /** Eligió [policy] en la hoja. `Solo con Wi-Fi` pide confirmar antes de guardarse. */
    data class PickMeteredPolicy(val policy: MeteredBackupPolicy) : BackupAction

    /** Confirmó `Copiar solo con Wi-Fi`. */
    data object ConfirmWifiOnly : BackupAction

    /** Cierra la hoja o la advertencia sin cambiar nada. */
    data object CloseMeteredPolicy : BackupAction

    /** `Liberar copia` en la hoja de una copia fijada: pide confirmar. */
    data class ReleaseCopy(val key: String) : BackupAction

    /** Confirmó liberar la copia en pantalla. */
    data object ConfirmRelease : BackupAction

    /** Cierra la pregunta sin liberar nada. */
    data object CloseRelease : BackupAction

    /** `Exportar copia`: lo resuelve la navegación, con `Copias locales`. */
    data object OpenLocalCopies : BackupAction

    /** `Desconectar`: pide confirmar. */
    data object Disconnect : BackupAction

    data object ConfirmDisconnect : BackupAction

    data object DismissDisconnect : BackupAction

    /**
     * `Reconectar`: con WebDAV lleva a escribir la contraseña otra vez, y con la nube del teléfono
     * a sus archivos. Lo resuelve la navegación.
     */
    data object Reconnect : BackupAction

    /**
     * Con la sesión de la nube del teléfono cerrada, `Abrir {provider}` para iniciar sesión allí.
     * Lo resuelve la navegación; nunca lleva a crear archivos nuevos.
     */
    data object SignInToProvider : BackupAction

    /** `Revisar`: abre la hoja del conflicto y lee los dos lados. */
    data object ReviewConflict : BackupAction

    /** Vuelve a leer los dos lados, tras un destino que no respondió. */
    data object RetryConflict : BackupAction

    /** `Decidir después`: cierra la hoja sin cambiar nada. */
    data object DecideLater : BackupAction

    /**
     * `Restaurar la copia de la nube`: cierra la hoja y abre la restauración de la copia [key], o,
     * sin ella, `Buscar un archivo`.
     */
    data class RestoreCloud(val key: String?) : BackupAction

    /** `Conservar este teléfono`: pide confirmar con los registros de cada lado. */
    data object KeepPhone : BackupAction

    /** Confirmó conservar este teléfono. */
    data object ConfirmKeep : BackupAction

    data object DismissKeep : BackupAction

    /**
     * Confirmó crear el archivo nuevo para la ranura que no se usa: lleva a preparar los archivos.
     * Lo resuelve la navegación, y la hoja se cierra.
     */
    data object CreateConflictFile : BackupAction
}
