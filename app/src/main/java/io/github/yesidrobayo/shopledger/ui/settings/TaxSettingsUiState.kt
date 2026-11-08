package io.github.yesidrobayo.shopledger.ui.settings

import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.region.NumberSymbols
import io.github.yesidrobayo.shopledger.domain.model.settings.TaxMode

/** Lo que muestra la pantalla de impuestos. */
sealed interface TaxSettingsUiState {
    /** Leyendo los ajustes. Los datos son locales: casi nunca se ve. */
    data object Loading : TaxSettingsUiState

    /**
     * Con datos. [missingDefaultRate] avisa que se cobra impuesto sin tasa por defecto. [editor] es
     * la hoja de una tasa, si está abierta; [dialog], la confirmación pendiente; [message], el
     * aviso corto que falta mostrar.
     */
    data class Ready(
        val mode: TaxMode,
        val purchaseTaxRecoverable: Boolean,
        val missingDefaultRate: Boolean,
        val active: List<TaxRateRow>,
        val archived: List<TaxRateRow>,
        val editor: TaxRateEditor? = null,
        val dialog: TaxDialog? = null,
        val message: TaxMessage? = null,
    ) : TaxSettingsUiState
}

/**
 * Una fila de tasa. Una tasa en uso, por un producto o por ser la tasa por defecto, solo se
 * archiva; una sin uso, solo se elimina.
 */
data class TaxRateRow(
    val id: Long,
    val name: String,
    val rate: BasisPoints,
    val isDefault: Boolean,
    val inUse: Boolean,
)

/**
 * La hoja de crear o editar una tasa. [editingId] es `null` al crear. Los errores se muestran solo
 * después de intentar guardar, y cada uno se borra al cambiar su campo.
 */
data class TaxRateEditor(
    val editingId: Long?,
    val name: String,
    val percent: String,
    val useAsDefault: Boolean,
    val nameError: TaxNameError? = null,
    val percentError: TaxPercentError? = null,
    val saving: Boolean = false,
    val saveFailure: SaveFailure? = null,
)

/**
 * Un guardado que falló en la base. Nada quedó a medias. [outOfSpace] dice si se arregla liberando
 * espacio, lo único que la persona puede hacer.
 */
data class SaveFailure(val outOfSpace: Boolean)

/** Qué falla en el nombre de una tasa. */
enum class TaxNameError {
    EMPTY,
    TOO_LONG,
    DUPLICATE,
}

/** Qué falla en el porcentaje de una tasa. */
enum class TaxPercentError {
    /** Vacío, mal escrito o fuera de 0 a 100. */
    RANGE,

    /** Más de dos decimales. */
    DECIMALS,
}

/** La confirmación que espera respuesta. */
sealed interface TaxDialog {
    /** Cambiar cómo se cobra el impuesto: las ventas registradas no cambian. */
    data class ChangeMode(val mode: TaxMode) : TaxDialog

    data class Archive(val id: Long, val name: String) : TaxDialog

    data class Delete(val id: Long, val name: String) : TaxDialog
}

/** El aviso corto tras una acción. */
sealed interface TaxMessage {
    /** La tasa quedó guardada. */
    data object RateSaved : TaxMessage

    /** Al reactivar: otra tasa vigente ya usa ese nombre. */
    data object NameTaken : TaxMessage

    /** La base no pudo escribir, o la tasa ya no estaba. Nada quedó a medias. */
    data class Failed(val failure: SaveFailure) : TaxMessage
}

/** Lo que la persona hace en la pantalla de impuestos. */
sealed interface TaxSettingsAction {
    data object Back : TaxSettingsAction

    /** Tocó un modo. Si es otro, se pide confirmar. */
    data class SelectMode(val mode: TaxMode) : TaxSettingsAction

    data class SetPurchaseTaxRecoverable(val recoverable: Boolean) : TaxSettingsAction

    data object NewRate : TaxSettingsAction

    /** Abre la hoja de una tasa. [symbols] escriben su porcentaje como en el formato regional. */
    data class EditRate(val id: Long, val symbols: NumberSymbols) : TaxSettingsAction

    data class AskArchive(val id: Long) : TaxSettingsAction

    data class AskDelete(val id: Long) : TaxSettingsAction

    data class Reactivate(val id: Long) : TaxSettingsAction

    data object ConfirmDialog : TaxSettingsAction

    data object DismissDialog : TaxSettingsAction

    data class EditorName(val name: String) : TaxSettingsAction

    data class EditorPercent(val percent: String) : TaxSettingsAction

    data class EditorDefault(val useAsDefault: Boolean) : TaxSettingsAction

    /** Guardar la hoja. [symbols] leen el porcentaje con el formato regional. */
    data class SaveRate(val symbols: NumberSymbols) : TaxSettingsAction

    data object DismissEditor : TaxSettingsAction

    data object MessageShown : TaxSettingsAction
}
