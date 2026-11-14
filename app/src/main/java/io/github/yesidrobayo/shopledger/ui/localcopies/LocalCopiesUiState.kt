package io.github.yesidrobayo.shopledger.ui.localcopies

import io.github.yesidrobayo.shopledger.domain.model.BusinessTimeZone
import io.github.yesidrobayo.shopledger.domain.model.backup.SecondDestinationView

/**
 * Lo que muestra `Copias locales`.
 *
 * @param encrypted si la copia exportada sale cifrada; nulo mientras se lee.
 * @param export en qué va `Guardar en un archivo`.
 * @param message un aviso breve pendiente de mostrar.
 * @param zone la zona del negocio, para la hora del nombre sugerido; nula si no se sabe.
 * @param second el segundo destino, o nulo mientras se lee.
 * @param folderRejected si la última carpeta elegida no era del almacenamiento del teléfono.
 * @param confirmRemove si se pregunta `¿Quitar este destino?`.
 */
data class LocalCopiesUiState(
    val encrypted: Boolean? = null,
    val export: ExportUi = ExportUi.Idle,
    val message: LocalCopiesMessage? = null,
    val zone: BusinessTimeZone? = null,
    val second: SecondDestinationView? = null,
    val folderRejected: Boolean = false,
    val confirmRemove: Boolean = false,
    val photos: PhotosUi = PhotosUi(),
)

/**
 * `Fotos`: cuántas hay para exportar y en qué va `Exportar fotos`. Exportarlas nunca cambia los
 * datos ni el estado del respaldo.
 *
 * @property exportable fotos con su archivo en el teléfono, o `null` mientras se lee.
 */
data class PhotosUi(
    val exportable: Int? = null,
    val working: Boolean = false,
    val failure: PhotosFailure? = null,
)

/** Por qué no se exportaron las fotos. */
enum class PhotosFailure {
    /** Cifrado activo sin su clave: nada sale en claro. */
    KEY_MISSING,

    /** Otra falla: se puede reintentar. */
    OTHER,
}

/** En qué va la copia exportada. Ninguno cambia los datos. */
sealed interface ExportUi {
    data object Idle : ExportUi

    /** Creando la copia y escribiéndola en el archivo elegido. */
    data object Working : ExportUi

    /** Quedó guardada, pero la app no podría restaurarla: se dice aparte, y queda a la vista. */
    data object SavedNotRestorable : ExportUi

    /** No se pudo, por [reason]. El archivo elegido no quedó a medias. */
    data class Failed(val reason: ExportFailure) : ExportUi
}

/** Por qué no se guardó la copia. Cada uno tiene su frase. */
enum class ExportFailure {
    /** Sin espacio en el teléfono o en el lugar elegido. */
    NO_SPACE,

    /** La base del teléfono está dañada: no se exporta. */
    DAMAGED,

    /** Otra falla: se puede reintentar. */
    OTHER,
}

/** Los avisos breves de la pantalla. */
enum class LocalCopiesMessage {
    /** La copia quedó guardada y comprobada en el archivo. */
    SAVED,

    /** No se pudo guardar la carpeta elegida, `Mantener al día` o quitarla: nada quedó a medias. */
    FOLDER_FAILED,

    /** Un toque llegó a través de otra app dibujada encima: no se guardó nada. */
    OBSCURED_TOUCH,

    /** Las fotos quedaron exportadas y comprobadas en el archivo. */
    PHOTOS_SAVED,
}

/** Lo que la persona puede hacer en `Copias locales`. */
sealed interface LocalCopiesAction {
    data object Back : LocalCopiesAction

    /** `Guardar en un archivo`: lo resuelve la navegación, con el selector del sistema. */
    data object SaveToFile : LocalCopiesAction

    /** El selector devolvió el documento [uri], ya creado con su nombre. */
    data class FileCreated(val uri: String) : LocalCopiesAction

    /** Cierra el aviso de un fallo o de una copia que no se podría restaurar. */
    data object DismissResult : LocalCopiesAction

    data object MessageShown : LocalCopiesAction

    /** `Elegir carpeta` o `Cambiar carpeta`: lo resuelve la navegación, con el selector. */
    data object ChooseFolder : LocalCopiesAction

    /** El selector devolvió el árbol [treeUri]. */
    data class FolderPicked(val treeUri: String) : LocalCopiesAction

    /** `Mantener al día esta carpeta`. */
    data class SetFollow(val follow: Boolean) : LocalCopiesAction

    /** `Quitar destino`: pide confirmar. */
    data object AskRemove : LocalCopiesAction

    data object ConfirmRemove : LocalCopiesAction

    data object CloseRemove : LocalCopiesAction

    /** La pantalla volvió a primer plano: el segundo destino se lee de nuevo. */
    data object Resumed : LocalCopiesAction

    /** `Guardar en un archivo` se tocó a través de otra app encima: se rechaza y se avisa. */
    data object ObscuredTouch : LocalCopiesAction

    /** `Exportar fotos`: lo resuelve la navegación, con el selector del sistema. */
    data object ExportPhotos : LocalCopiesAction

    /** El selector devolvió el documento [uri] para el ZIP de las fotos. */
    data class PhotosFileCreated(val uri: String) : LocalCopiesAction

    /** Cierra el aviso de que las fotos no se exportaron. */
    data object DismissPhotosFailure : LocalCopiesAction
}
