package io.github.yesidrobayo.shopledger.ui.goal

import androidx.compose.runtime.Immutable
import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.region.NumberSymbols
import io.github.yesidrobayo.shopledger.domain.model.report.RevenueLimitView
import io.github.yesidrobayo.shopledger.domain.usecase.report.MonthGoalView
import kotlinx.datetime.YearMonth

/** Los símbolos y los decimales con que la persona escribe los montos de las metas. */
@Immutable
data class GoalNumbers(val symbols: NumberSymbols, val currencyDecimals: Int)

/** Por qué no se puede guardar un monto escrito. La hoja lo dice debajo del campo. */
enum class GoalFieldProblem {
    /** En cero: una meta mide algo mayor que cero. */
    NOT_POSITIVE,

    /** No es un número, o tiene más decimales que la moneda. */
    NOT_A_NUMBER,

    /** Pasa del tope de una meta. */
    TOO_HIGH,
}

/** Una falla al guardar; [outOfSpace] si fue por falta de espacio. Lo escrito se conserva. */
@Immutable
data class GoalSaveFailure(val outOfSpace: Boolean)

/**
 * La hoja de metas de [month]: lo escrito tal cual, el problema de cada campo y si quedó guardando
 * o falló. Los dos campos son opcionales, y dejarlos vacíos quita las metas del mes.
 */
@Immutable
data class GoalSheet(
    val month: YearMonth,
    val editing: Boolean,
    val salesText: String = "",
    val profitText: String = "",
    val salesProblem: GoalFieldProblem? = null,
    val profitProblem: GoalFieldProblem? = null,
    val saving: Boolean = false,
    val saveFailed: GoalSaveFailure? = null,
)

/**
 * La hoja del límite de ingresos: el monto y los meses tal como se escribieron, el problema de
 * cada campo y si quedó guardando o falló. [editing] si ya hay un límite, que entonces se puede
 * quitar.
 */
@Immutable
data class LimitSheet(
    val editing: Boolean,
    val amountText: String = "",
    val monthsText: String = "",
    val amountProblem: GoalFieldProblem? = null,
    val monthsInvalid: Boolean = false,
    val saving: Boolean = false,
    val saveFailed: GoalSaveFailure? = null,
)

/** Lo que se avisa con un mensaje de la navegación. */
sealed interface GoalMessage {
    data object Saved : GoalMessage

    data object Removed : GoalMessage

    data object LimitSaved : GoalMessage

    data object LimitRemoved : GoalMessage
}

/** El estado de `Metas y límites`. */
sealed interface GoalsUiState {
    /** Leyendo. El esqueleto solo aparece si tarda. */
    data object Loading : GoalsUiState

    /** Una lectura falló: se dice con `Reintentar`. Nada se perdió. */
    data object ReadFailed : GoalsUiState

    /** Este teléfono no conoce la zona del negocio: sin ella no se sabe qué mes es. */
    data object UnknownZone : GoalsUiState

    /**
     * Las metas de [month] y su avance.
     *
     * @param today hoy en la zona del negocio, para nombrar el mes y saber hasta dónde avanzar.
     * @param canGoNext si se puede pasar al mes siguiente: aquí sí se avanza a meses futuros, para
     *   dejarlos definidos, hasta un año después del mes en curso.
     * @param sheet la hoja de metas abierta, o `null`.
     * @param limit el límite de ingresos y lo acumulado en su ventana, que siempre termina en el
     *   mes en curso, o `null` si la persona no escribió uno.
     * @param limitSheet la hoja del límite abierta, o `null`.
     */
    @Immutable
    data class Ready(
        val month: YearMonth,
        val today: BusinessDate,
        val canGoNext: Boolean,
        val goals: MonthGoalView,
        val sheet: GoalSheet? = null,
        val message: GoalMessage? = null,
        val limit: RevenueLimitView? = null,
        val limitSheet: LimitSheet? = null,
    ) : GoalsUiState
}

/** Lo que la persona hace en `Metas y límites`. */
sealed interface GoalsAction {
    data object Back : GoalsAction

    data object Retry : GoalsAction

    /** Sin zona conocida: lleva a elegirla. */
    data object ChooseZone : GoalsAction

    data object PreviousMonth : GoalsAction

    data object NextMonth : GoalsAction

    /** `Definir metas` o `Editar metas`: abre la hoja del mes que se ve. */
    data object StartGoals : GoalsAction

    data class SalesText(val text: String) : GoalsAction

    data class ProfitText(val text: String) : GoalsAction

    data object SaveGoals : GoalsAction

    /** Cierra la hoja abierta, de metas o de límite, sin guardar. */
    data object DismissSheet : GoalsAction

    data object MessageShown : GoalsAction

    /** `Definir límite` o `Editar límite`: abre la hoja del límite. */
    data object StartLimit : GoalsAction

    data class LimitAmountText(val text: String) : GoalsAction

    data class LimitMonthsText(val text: String) : GoalsAction

    data object SaveLimit : GoalsAction

    /** `Quitar límite`, desde la hoja de un límite guardado. */
    data object RemoveLimit : GoalsAction
}
