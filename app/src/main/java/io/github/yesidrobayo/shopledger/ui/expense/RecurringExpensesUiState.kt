package io.github.yesidrobayo.shopledger.ui.expense

import androidx.compose.runtime.Immutable
import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.expense.ExpenseCategory
import io.github.yesidrobayo.shopledger.domain.model.expense.Recurrence
import io.github.yesidrobayo.shopledger.domain.model.expense.RecurringExpense
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyAccount
import kotlinx.datetime.DayOfWeek

/**
 * El estado de los gastos recurrentes: los que esperan confirmación, los programados o los
 * archivados, y lo que se abre encima: la hoja de crear o editar y las confirmaciones de omitir y
 * de archivar.
 *
 * [today] falta hasta conocer el calendario del negocio. [categories] son todas, vigentes y
 * archivadas: un gasto recurrente puede conservar una archivada.
 */
@Immutable
data class RecurringExpensesUiState(
    val content: RecurringContent = RecurringContent.Loading,
    val showArchived: Boolean = false,
    val today: BusinessDate? = null,
    val weekStartDay: DayOfWeek? = null,
    val categories: List<ExpenseCategory> = emptyList(),
    val accounts: List<MoneyAccount> = emptyList(),
    val form: RecurringForm? = null,
    val skipping: RecurringExpense? = null,
    val archiving: RecurringExpense? = null,
    val working: Boolean = false,
    val message: RecurringMessage? = null,
) {
    /** Las categorías que se ofrecen: las vigentes, y la del que se edita aunque se archivó. */
    val formCategories: List<ExpenseCategory>
        get() {
            val kept = form?.keptCategoryId
            return categories.filter { !it.isArchived || it.id == kept }
        }
}

/** Lo que muestra la lista. */
sealed interface RecurringContent {
    /** Leyendo. El esqueleto solo aparece si tarda. */
    data object Loading : RecurringContent

    /** No hay ninguno vigente: se invita a crear el primero. */
    data object Empty : RecurringContent

    /** No se pudo leer. Se puede reintentar. */
    data object ReadError : RecurringContent

    /**
     * El teléfono no conoce la zona horaria del negocio, así que no sabe qué día es ni qué vence:
     * se ofrece elegirla.
     */
    data object UnknownZone : RecurringContent

    /** Los vigentes: [due] esperan confirmación, del más atrasado al más reciente. */
    data class Active(
        val due: List<RecurringExpense>,
        val scheduled: List<RecurringExpense>,
    ) : RecurringContent

    /** Los archivados, que ya no se proponen. Vacía si no hay ninguno. */
    data class Archived(val items: List<RecurringExpense>) : RecurringContent
}

/**
 * Un gasto recurrente tal como lo escribe la persona. [day] es el día de la semana, del mes o del
 * año según [recurrence]; cada mes se escribe en [dayText], y cada año sale de [nextDate].
 * [errors] solo se muestran después de intentar guardar. [keptCategoryId] es la categoría que ya
 * tenía al editarlo: se conserva aunque esté archivada.
 */
@Immutable
data class RecurringForm(
    val editingId: Long? = null,
    val amountText: String = "",
    val categoryId: Long? = null,
    val description: String = "",
    val recurrence: Recurrence = Recurrence.MONTHLY,
    val day: Int,
    val dayText: String = day.toString(),
    val nextDate: BusinessDate,
    val accountId: Long? = null,
    val reminder: Boolean = false,
    val keptCategoryId: Long? = null,
    val errors: Set<RecurringFormError> = emptySet(),
    val status: ExpenseSheetStatus = ExpenseSheetStatus.IDLE,
    val notice: RecurringFormNotice? = null,
) {
    val descriptionTooLong: Boolean
        get() = description.trim().codePointLength() > ExpenseForm.MAX_DESCRIPTION_LENGTH

    /** Si el día puede no existir en algún mes: 29, 30 o 31 cada mes, o el 29 de febrero. */
    val mayFallOnMonthEnd: Boolean
        get() = when (recurrence) {
            Recurrence.WEEKLY -> false
            Recurrence.MONTHLY -> day >= SHORTEST_MONTH_END
            Recurrence.YEARLY -> day == LEAP_DAY
        }

    private companion object {
        /** El primer día que falta en algún mes: el 29, en un febrero de 28 días. */
        const val SHORTEST_MONTH_END = 29

        /** El 29 de febrero, contado sobre un año bisiesto. */
        const val LEAP_DAY = 60
    }
}

/** Lo que impide guardar el gasto recurrente, en su campo. */
enum class RecurringFormError {
    /** Falta el valor, o es cero. */
    AMOUNT_ZERO,

    /** El valor no se lee como un número del formato del negocio. */
    AMOUNT_NOT_A_NUMBER,

    /** El valor tiene más decimales que la moneda. */
    AMOUNT_DECIMALS,

    /** El valor pasa del tope de un documento. */
    AMOUNT_TOO_HIGH,

    /** Falta la categoría. */
    CATEGORY_MISSING,

    /** La descripción pasa de 200 caracteres. */
    DESCRIPTION_TOO_LONG,

    /** El día no existe en la repetición, o la próxima fecha no cae en él. */
    DAY_INVALID,
}

/** Un aviso en la hoja, sobre algo que no es de un campo. */
sealed interface RecurringFormNotice {
    /** No se pudo guardar. Lo escrito se conserva. */
    data class SaveFailed(val outOfSpace: Boolean) : RecurringFormNotice

    /** La categoría elegida se archivó o se eliminó mientras tanto: hay que elegir otra. */
    data object CategoryUnavailable : RecurringFormNotice
}

/** El aviso breve que sigue a una acción, para mostrarlo una vez. */
sealed interface RecurringMessage {
    data object Saved : RecurringMessage

    /** Se omitió un vencimiento: la propuesta pasó a [nextDate]. */
    data class Skipped(val nextDate: BusinessDate) : RecurringMessage

    data object Archived : RecurringMessage

    data object Reactivated : RecurringMessage

    /** No se pudo omitir, archivar o reactivar. Nada cambió. */
    data class Failed(val outOfSpace: Boolean) : RecurringMessage

    /** Ese vencimiento ya se resolvió en otra pantalla, o el gasto recurrente ya no se propone. */
    data object AlreadyHandled : RecurringMessage
}

/** Lo que la persona hace en los gastos recurrentes. */
sealed interface RecurringExpensesAction {
    data object Back : RecurringExpensesAction

    data object Retry : RecurringExpensesAction

    /** Abre la configuración regional para elegir la zona: lo resuelve la navegación. */
    data object ChooseZone : RecurringExpensesAction

    /** Muestra los archivados, o vuelve a los vigentes. */
    data class ShowArchived(val archived: Boolean) : RecurringExpensesAction

    data object New : RecurringExpensesAction

    data class Edit(val recurringExpenseId: Long) : RecurringExpensesAction

    /** Registrar el vencimiento actual: lo resuelve la navegación, con el gasto nuevo. */
    data class Register(val recurringExpenseId: Long) : RecurringExpensesAction

    data class StartSkip(val recurringExpenseId: Long) : RecurringExpensesAction

    data object ConfirmSkip : RecurringExpensesAction

    data object DismissSkip : RecurringExpensesAction

    /** Pide confirmar el archivo del que se edita. */
    data object StartArchive : RecurringExpensesAction

    data object ConfirmArchive : RecurringExpensesAction

    data object DismissArchive : RecurringExpensesAction

    data class Reactivate(val recurringExpenseId: Long) : RecurringExpensesAction

    data class AmountChange(val text: String) : RecurringExpensesAction

    data class CategoryChange(val categoryId: Long) : RecurringExpensesAction

    data class DescriptionChange(val text: String) : RecurringExpensesAction

    data class RecurrenceChange(val recurrence: Recurrence) : RecurringExpensesAction

    /** Elige el día de la semana, de 1, lunes, a 7, domingo. */
    data class WeekdayChange(val day: Int) : RecurringExpensesAction

    /** Escribe el día del mes. */
    data class DayOfMonthChange(val text: String) : RecurringExpensesAction

    data class NextDateChange(val date: BusinessDate) : RecurringExpensesAction

    data class AccountChange(val accountId: Long?) : RecurringExpensesAction

    data class ReminderChange(val enabled: Boolean) : RecurringExpensesAction

    data object Save : RecurringExpensesAction

    data object CloseForm : RecurringExpensesAction

    data object MessageShown : RecurringExpensesAction
}
