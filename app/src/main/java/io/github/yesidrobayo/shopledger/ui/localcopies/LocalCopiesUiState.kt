package io.github.yesidrobayo.shopledger.ui.localcopies

import io.github.yesidrobayo.shopledger.domain.model.BusinessTimeZone

/**
 * Lo que muestra `Copias locales`.
 *
 * @param encrypted si la copia exportada sale cifrada; nulo mientras se lee.
 * @param export en qué va `Guardar en un archivo`.
 * @param message un aviso breve pendiente de mostrar.
 * @param zone la zona del negocio, para la hora del nombre sugerido; nula si no se sabe.
 */
data class LocalCopiesUiState(
    val encrypted: Boolean? = null,
    val export: ExportUi = ExportUi.Idle,
    val message: LocalCopiesMessage? = null,
    val zone: BusinessTimeZone? = null,
)

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
}
