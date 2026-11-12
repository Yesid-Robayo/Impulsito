package io.github.yesidrobayo.shopledger.ui.unit

import androidx.compose.runtime.Immutable
import io.github.yesidrobayo.shopledger.domain.model.catalog.ConversionEntry
import io.github.yesidrobayo.shopledger.domain.model.catalog.MeasureUnit
import io.github.yesidrobayo.shopledger.domain.model.catalog.Product

/** Los dos segmentos de la pantalla. */
enum class UnitsTab {
    UNITS,
    CONVERSIONS,
}

/** Lo que se confirma antes de hacerlo. [name] es el de la unidad, para el título. */
sealed interface UnitsDialog {
    val unitId: Long
    val name: String

    data class Archive(override val unitId: Long, override val name: String) : UnitsDialog

    data class Delete(override val unitId: Long, override val name: String) : UnitsDialog
}

/** Un aviso breve, abajo, tras una acción. */
sealed interface UnitsMessage {
    data object UnitSaved : UnitsMessage

    data object ConversionSaved : UnitsMessage

    /** Reactivar no se pudo: una vigente ya usa su nombre. */
    data object NameTaken : UnitsMessage

    data class Failed(val outOfSpace: Boolean) : UnitsMessage
}

/**
 * El estado de la pantalla de unidades y conversiones.
 *
 * [loading] hasta que llegan las unidades, y [readFailed] si una lectura falló: se dice, con
 * `Reintentar`. [active] y [archived] van en el orden en que se crearon; las vigentes en [inUse] no
 * se eliminan, solo se archivan. [conversions] son las de los productos
 * vigentes, y [products] los productos vigentes que se pueden elegir al crear una. Las hojas abiertas
 * llegan aparte, con su propio estado.
 */
@Immutable
data class UnitsUiState(
    val loading: Boolean = true,
    val readFailed: Boolean = false,
    val tab: UnitsTab = UnitsTab.UNITS,
    val active: List<MeasureUnit> = emptyList(),
    val archived: List<MeasureUnit> = emptyList(),
    val inUse: Set<Long> = emptySet(),
    val conversions: List<ConversionEntry> = emptyList(),
    val products: List<Product> = emptyList(),
    val dialog: UnitsDialog? = null,
    val message: UnitsMessage? = null,
) {
    /** Todas las unidades, vigentes y archivadas: un producto puede seguir en una archivada. */
    val allUnits: List<MeasureUnit> get() = active + archived
}

/** Lo que la persona hace en la pantalla, fuera de las hojas. */
sealed interface UnitsAction {
    data object Back : UnitsAction

    /** Vuelve a leer tras una falla de lectura. */
    data object Retry : UnitsAction

    data class SelectTab(val tab: UnitsTab) : UnitsAction

    data object NewUnit : UnitsAction

    data class EditUnit(val unitId: Long) : UnitsAction

    data class AskArchive(val unitId: Long) : UnitsAction

    data class AskDelete(val unitId: Long) : UnitsAction

    data class Reactivate(val unitId: Long) : UnitsAction

    data object ConfirmDialog : UnitsAction

    data object DismissDialog : UnitsAction

    data object NewConversion : UnitsAction

    data class EditConversion(val conversionId: Long) : UnitsAction

    data object MessageShown : UnitsAction
}
