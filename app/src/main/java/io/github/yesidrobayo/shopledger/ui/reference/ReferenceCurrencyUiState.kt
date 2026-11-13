package io.github.yesidrobayo.shopledger.ui.reference

import androidx.compose.runtime.Immutable
import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.pricing.FxRate
import io.github.yesidrobayo.shopledger.domain.model.settings.ReferenceCurrency
import kotlinx.datetime.DayOfWeek

/** Una cotización del historial, con su variación frente a la anterior, si la hay. */
@Immutable
data class RateRow(val rate: FxRate, val variation: BasisPoints?)

/** Por qué no se puede guardar la cotización escrita. La hoja lo dice debajo del campo. */
enum class RateProblem {
    /** Vacía, en cero o negativa: debe ser mayor que cero. */
    NOT_POSITIVE,

    /** No es un número, o tiene más de seis decimales. */
    NOT_A_NUMBER,

    /** Pasa del tope de una cotización. */
    TOO_HIGH,
}

/**
 * La hoja de `Registrar cotización`: lo escrito tal cual, la fecha elegida, el problema del campo y
 * si quedó guardando o falló. [jumpWarning] avisa, sin bloquear, que se aleja más de la mitad de la
 * anterior: casi siempre un cero de más o de menos. [variation] es frente a la anterior.
 */
@Immutable
data class RateSheet(
    val rateText: String = "",
    val date: BusinessDate? = null,
    val note: String = "",
    val problem: RateProblem? = null,
    val noteTooLong: Boolean = false,
    val futureDate: Boolean = false,
    val variation: BasisPoints? = null,
    val jumpWarning: Boolean = false,
    val saving: Boolean = false,
    val saveFailed: SaveFailure? = null,
)

/** Una falla al guardar; [outOfSpace] si fue por falta de espacio. Lo escrito se conserva. */
@Immutable
data class SaveFailure(val outOfSpace: Boolean)

/** Lo que se avisa con un mensaje de la navegación. */
sealed interface ReferenceMessage {
    data object RateSaved : ReferenceMessage

    data class Failed(val outOfSpace: Boolean) : ReferenceMessage
}

/** El calendario del negocio para la fecha de una cotización: hoy y el primer día de la semana. */
@Immutable
data class RateCalendar(val today: BusinessDate, val weekStartDay: DayOfWeek)

/** El estado de la moneda de referencia. */
sealed interface ReferenceCurrencyUiState {
    data object Loading : ReferenceCurrencyUiState

    /** Una lectura falló: se dice con `Reintentar`. */
    data object ReadFailed : ReferenceCurrencyUiState

    /**
     * La moneda de referencia leída. [reference] es `null` sin moneda elegida. [latest] es la
     * cotización vigente y [history] todas, de la más reciente a la más vieja. [choosing] abre la
     * lista de monedas, [pendingChange] pide confirmar el cambio de moneda, [sheet] es la hoja de
     * registrar, [calendar] la fecha de hoy o `null` mientras se lee, y [message] lo que se avisa.
     */
    @Immutable
    data class Ready(
        val reference: ReferenceCurrency?,
        val latest: RateRow?,
        val history: List<RateRow>,
        val choosing: Boolean = false,
        val query: String = "",
        val pendingChange: ReferenceCurrency? = null,
        val sheet: RateSheet? = null,
        val calendar: RateCalendar? = null,
        val message: ReferenceMessage? = null,
    ) : ReferenceCurrencyUiState
}

/** Lo que la persona hace en la moneda de referencia. */
sealed interface ReferenceCurrencyAction {
    data object Back : ReferenceCurrencyAction

    data object Retry : ReferenceCurrencyAction

    /** Abre la lista de monedas. */
    data object ChooseCurrency : ReferenceCurrencyAction

    data class Query(val text: String) : ReferenceCurrencyAction

    /** Eligió la moneda [code], con los decimales que da la norma, [isoDecimals]. */
    data class PickCurrency(val code: String, val isoDecimals: Int) : ReferenceCurrencyAction

    /** Cambió los decimales de la moneda elegida. */
    data class PickDecimals(val decimals: Int) : ReferenceCurrencyAction

    data object ConfirmChange : ReferenceCurrencyAction

    data object DismissDialog : ReferenceCurrencyAction

    data object StartRate : ReferenceCurrencyAction

    data class RateText(val text: String) : ReferenceCurrencyAction

    data class RateDate(val date: BusinessDate) : ReferenceCurrencyAction

    data class RateNote(val text: String) : ReferenceCurrencyAction

    data object SaveRate : ReferenceCurrencyAction

    data object MessageShown : ReferenceCurrencyAction
}
