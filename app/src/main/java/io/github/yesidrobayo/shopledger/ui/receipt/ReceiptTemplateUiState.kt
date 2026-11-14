package io.github.yesidrobayo.shopledger.ui.receipt

import io.github.yesidrobayo.shopledger.domain.model.catalog.StoredImage
import io.github.yesidrobayo.shopledger.domain.model.settings.ReceiptTemplateField

/** Lo que muestra la plantilla del comprobante. */
sealed interface ReceiptTemplateUiState {
    /** Todavía se leen los ajustes. */
    data object Loading : ReceiptTemplateUiState

    /** Los ajustes no se pudieron leer: se puede reintentar. */
    data object ReadFailed : ReceiptTemplateUiState

    /**
     * La plantilla mientras se edita. Lo escrito se conserva tal cual: un error, del campo o de la
     * base, nunca lo borra.
     *
     * - [businessName] es el nombre del negocio, que se cambia en los ajustes, no aquí.
     * - [logo] es el que se ve, guardado o recién elegido.
     * - [tooLong] marca los textos que pasan del largo permitido.
     * - [changed] dice si hay algo distinto de lo guardado: salir entonces pregunta.
     */
    data class Editing(
        val businessName: String?,
        val logo: StoredImage?,
        val details: String,
        val footer: String,
        val tooLong: Set<ReceiptTemplateField> = emptySet(),
        val importingLogo: Boolean = false,
        val logoProblem: LogoProblem? = null,
        val saving: Boolean = false,
        val saveFailed: Boolean = false,
        val changed: Boolean = false,
        val confirmLeave: Boolean = false,
        val saved: Boolean = false,
        val left: Boolean = false,
    ) : ReceiptTemplateUiState
}

/** Por qué no se pudo usar la imagen elegida como logo. Se conserva la anterior. */
enum class LogoProblem {
    /** No es una imagen que se pueda leer. */
    UNREADABLE,

    /** El teléfono no tiene espacio para guardarla. */
    OUT_OF_SPACE,
}

/** Lo que la persona hace en la plantilla. */
sealed interface ReceiptTemplateAction {
    data class DetailsChanged(val text: String) : ReceiptTemplateAction

    data class FooterChanged(val text: String) : ReceiptTemplateAction

    /** Eligió una imagen en el selector del sistema; [source] es su dirección opaca. */
    data class PickLogo(val source: String) : ReceiptTemplateAction

    data object RemoveLogo : ReceiptTemplateAction

    data object Save : ReceiptTemplateAction

    /** Atrás o cerrar: con cambios sin guardar, pregunta antes. */
    data object Leave : ReceiptTemplateAction

    data object ConfirmLeave : ReceiptTemplateAction

    data object DismissLeave : ReceiptTemplateAction

    data object Retry : ReceiptTemplateAction

    /** El aviso de guardado ya se mostró. */
    data object SavedShown : ReceiptTemplateAction

    /** Abrir los ajustes, donde se cambia el nombre del negocio. */
    data object OpenBusinessName : ReceiptTemplateAction
}
