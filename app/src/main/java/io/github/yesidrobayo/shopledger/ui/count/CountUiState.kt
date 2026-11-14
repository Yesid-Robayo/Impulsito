package io.github.yesidrobayo.shopledger.ui.count

import androidx.compose.runtime.Immutable
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.catalog.Category
import io.github.yesidrobayo.shopledger.domain.model.catalog.MeasureUnit
import io.github.yesidrobayo.shopledger.domain.model.stock.CountSession
import io.github.yesidrobayo.shopledger.domain.model.stock.Location
import io.github.yesidrobayo.shopledger.domain.usecase.stock.CountOverview

/** Lo que muestra el inicio del conteo. */
@Immutable
sealed interface CountHomeContent {
    data object Loading : CountHomeContent

    data object ReadError : CountHomeContent

    data class Ready(val overview: CountOverview) : CountHomeContent
}

/** Por qué no se pudo empezar un conteo. La hoja elige el texto. */
enum class NewCountError {
    /** En ese alcance no hay productos con stock propio. */
    SCOPE_EMPTY,

    /** La nota pasa de su largo. */
    NOTE_TOO_LONG,

    /** La ubicación ya no se puede elegir. */
    LOCATION_UNAVAILABLE,

    /** Este teléfono no conoce la zona del negocio. */
    UNKNOWN_ZONE,
}

/**
 * La hoja de un conteo nuevo: dónde, qué categoría si se quiere, a ciegas por defecto y una nota.
 * [categories] son las que se pueden elegir; [choosingCategory] abre su lista.
 */
@Immutable
data class NewCountSheet(
    val locationId: Long?,
    val categoryId: Long? = null,
    val blind: Boolean = true,
    val note: String = "",
    val categories: List<Category> = emptyList(),
    val choosingCategory: Boolean = false,
    val error: NewCountError? = null,
    val failedOutOfSpace: Boolean? = null,
    val starting: Boolean = false,
)

/** El estado del inicio del conteo. [openedId] lleva a la sesión recién abierta, una vez. */
@Immutable
data class CountHomeUiState(
    val content: CountHomeContent = CountHomeContent.Loading,
    val sheet: NewCountSheet? = null,
    val openedId: Long? = null,
)

/** Lo que la persona hace en el inicio del conteo. */
sealed interface CountHomeAction {
    data object Back : CountHomeAction

    data object Retry : CountHomeAction

    data object NewCount : CountHomeAction

    data class SheetLocation(val locationId: Long) : CountHomeAction

    data object OpenCategories : CountHomeAction

    /** Elige una categoría, o ninguna con `null`. */
    data class SheetCategory(val categoryId: Long?) : CountHomeAction

    data class SheetBlind(val blind: Boolean) : CountHomeAction

    data class SheetNote(val text: String) : CountHomeAction

    data object Start : CountHomeAction

    data object DismissSheet : CountHomeAction

    data class OpenSession(val sessionId: Long) : CountHomeAction

    /** La navegación ya abrió la sesión nueva. */
    data object OpenedShown : CountHomeAction

    data object ChooseZone : CountHomeAction

    data object CreateProduct : CountHomeAction
}

/** Por qué una cantidad contada no se guardó. La pantalla elige el texto. */
enum class CountRowError {
    NOT_A_NUMBER,
    NEGATIVE,
    DECIMALS,
    TOO_HIGH,
}

/**
 * Un producto de la sesión. [text] es lo escrito tal cual, que se guarda al ser válido; [counted]
 * es lo guardado. [expected] solo se muestra sin conteo a ciegas, o en la revisión. [moved] dice
 * que tuvo movimientos después de abrir la sesión.
 */
@Immutable
data class CountRowUi(
    val lineId: Long,
    val name: String,
    val unit: MeasureUnit?,
    val expected: QuantityMilli,
    val counted: QuantityMilli?,
    val text: String,
    val error: CountRowError? = null,
    val moved: Boolean = false,
) {
    val difference: QuantityMilli? get() = counted?.let { it - expected }
}

/** Qué filas se ven en la sesión: todas, las que faltan o las ya contadas. */
enum class CountFilter {
    PENDING,
    DONE,
}

/** La revisión antes de aplicar: el resumen, lo que se movió y su valor. */
@Immutable
data class CountReviewUi(
    val adjustments: Int,
    val surplus: Money?,
    val shortage: Money?,
    val movedCount: Int,
)

/** Lo que pide confirmación en la sesión. */
enum class CountDialog {
    APPLY,
    CANCEL,
}

/** Un aviso de la sesión, que no borra nada de lo contado. */
@Immutable
sealed interface CountNotice {
    /** No se pudo guardar lo contado: lo escrito sigue en la fila. */
    data class SaveFailed(val outOfSpace: Boolean) : CountNotice

    /** No se pudo aplicar: la sesión sigue abierta y nada se aplicó. */
    data class ApplyFailed(val outOfSpace: Boolean) : CountNotice

    /** Este teléfono no conoce la zona del negocio: no hay fecha que darle a los ajustes. */
    data object UnknownZone : CountNotice

    /** La moneda del negocio sigue sin confirmar: un ajuste con valor la necesita. */
    data object CurrencyUnconfirmed : CountNotice
}

/** Cómo terminó la sesión, para que la navegación vuelva con su aviso. */
sealed interface CountFinished {
    data class Applied(val adjustments: Int) : CountFinished

    data object Cancelled : CountFinished
}

/**
 * El estado de una sesión de conteo, mientras se cuenta o en la revisión.
 *
 * @param reviewing se están revisando las diferencias antes de aplicar.
 * @param readOnly la sesión ya terminó: se ve, pero no se cuenta ni se aplica.
 */
@Immutable
data class CountSessionUiState(
    val loading: Boolean = true,
    val missing: Boolean = false,
    val readFailed: Boolean = false,
    val session: CountSession? = null,
    val location: Location? = null,
    val categoryName: String? = null,
    val showsLocation: Boolean = false,
    val rows: List<CountRowUi> = emptyList(),
    val counted: Int = 0,
    val total: Int = 0,
    val query: String = "",
    val filter: CountFilter? = null,
    val reviewing: Boolean = false,
    val review: CountReviewUi? = null,
    val dialog: CountDialog? = null,
    val notice: CountNotice? = null,
    val working: Boolean = false,
    val finished: CountFinished? = null,
) {
    val readOnly: Boolean get() = session?.finishedAt != null
    val blind: Boolean get() = session?.isBlind ?: true
}

/** Lo que la persona hace en una sesión. */
sealed interface CountSessionAction {
    data object Back : CountSessionAction

    data object Retry : CountSessionAction

    data class Query(val text: String) : CountSessionAction

    data class Filter(val filter: CountFilter?) : CountSessionAction

    data class Count(val lineId: Long, val text: String) : CountSessionAction

    data class Step(val lineId: Long, val up: Boolean) : CountSessionAction

    data object Review : CountSessionAction

    data object AskApply : CountSessionAction

    data object AskCancel : CountSessionAction

    data object Confirm : CountSessionAction

    data object DismissDialog : CountSessionAction

    data object ChooseZone : CountSessionAction

    /** La navegación ya volvió con el aviso de cómo terminó. */
    data object FinishedShown : CountSessionAction
}
