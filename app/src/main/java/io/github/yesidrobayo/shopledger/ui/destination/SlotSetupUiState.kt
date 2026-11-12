package io.github.yesidrobayo.shopledger.ui.destination

import io.github.yesidrobayo.shopledger.domain.model.backup.BackupSlot
import io.github.yesidrobayo.shopledger.domain.model.backup.SlotSetupLook

/**
 * Lo que muestra `Preparar archivos en la nube`.
 *
 * @param look los tres archivos y la nube, o nulo mientras se lee `slots.json`, que es local.
 * @param notice por qué no sirvió el último archivo elegido, hasta elegir otro.
 * @param working si se está revisando un archivo o confirmando las ranuras.
 * @param ready si las ranuras quedaron listas: se muestra `Archivos listos`, y la acción es
 *   `Continuar`.
 * @param confirmFailed si la última confirmación no pudo guardar: `Continuar` la repite.
 * @param message un aviso breve pendiente de mostrar.
 * @param target a dónde ir, pendiente de que la navegación lo haga.
 * @param askingChange si se pregunta antes de cambiar de destino: las copias anteriores quedan
 *   donde están.
 */
data class SlotSetupUiState(
    val look: SlotSetupLook? = null,
    val notice: SlotSetupNotice? = null,
    val working: Boolean = false,
    val ready: Boolean = false,
    val confirmFailed: Boolean = false,
    val message: SlotSetupMessage? = null,
    val target: SlotSetupTarget? = null,
    val askingChange: Boolean = false,
) {
    /** El archivo que se crea con `Crear el archivo`, o nulo si ya están los tres. */
    val next: BackupSlot? get() = look?.next
}

/** Por qué no sirvió el archivo elegido, con lo que se puede hacer. */
sealed interface SlotSetupNotice {
    /** Otra nube o una carpeta del teléfono: un servicio WebDAV o las copias locales. */
    data object OutsideProviders : SlotSetupNotice

    /** La nube no deja escribir en él, o no dejó conservar el permiso: otro servicio. */
    data object NotWritable : SlotSetupNotice

    /** La nube no dice cuánto mide: otro servicio. */
    data object SizeUnknown : SlotSetupNotice

    /** Ya tiene datos: la app no escribe encima, y se crea otro. */
    data object NotEmpty : SlotSetupNotice

    /** Es el archivo número [fileNumber]: se crea otro. */
    data class SameFile(val fileNumber: Int) : SlotSetupNotice
}

/** Los avisos breves de la pantalla. */
enum class SlotSetupMessage {
    /** La persona cerró el selector sin crear el archivo. */
    CANCELLED,

    /** El teléfono no pudo guardar. Nada se perdió, y se puede repetir. */
    SAVE_FAILED,
}

/** A dónde lleva la pantalla. */
enum class SlotSetupTarget {
    BACK,

    /** La pantalla del respaldo, con las ranuras ya conectadas. */
    BACKUP,

    /** Conectar un servicio WebDAV. */
    WEBDAV_CONNECT,

    /** Las copias locales, que también exportan las fotos. */
    LOCAL_COPIES,

    /** Volver a elegir dónde guardar las copias. */
    DESTINATION_CHOICE,

    /** La ayuda de los destinos. */
    HELP,
}

/** Lo que la persona puede hacer en la pantalla. */
sealed interface SlotSetupAction {
    data object Back : SlotSetupAction

    data object Help : SlotSetupAction

    /** `Crear el archivo`: abre el selector. Lo resuelve la navegación. */
    data object CreateFile : SlotSetupAction

    /**
     * El selector volvió con el documento [uri], o con nulo si la persona lo cerró sin crear nada.
     */
    data class FilePicked(val uri: String?) : SlotSetupAction

    /** `Continuar`, con los tres archivos. */
    data object Continue : SlotSetupAction

    /** Confirmó cambiar de destino a estos archivos. */
    data object ConfirmChange : SlotSetupAction

    /** No quiso cambiar de destino: los archivos quedan creados para después. */
    data object DismissChange : SlotSetupAction

    /** `Crear otro archivo`, tras un archivo que no sirvió. */
    data object CreateAnotherFile : SlotSetupAction

    data object ConnectService : SlotSetupAction

    data object GoToLocalCopies : SlotSetupAction

    data object ChooseOtherService : SlotSetupAction

    data object ExportPhotos : SlotSetupAction

    data object MessageShown : SlotSetupAction

    data object TargetHandled : SlotSetupAction
}
