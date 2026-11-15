package io.github.yesidrobayo.shopledger.ui.expense

import androidx.compose.runtime.Immutable
import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.VoidReason
import io.github.yesidrobayo.shopledger.domain.model.expense.ExpenseCategory
import io.github.yesidrobayo.shopledger.domain.model.expense.RecurringOccurrence
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyAccount
import io.github.yesidrobayo.shopledger.domain.model.region.NumberSymbols
import io.github.yesidrobayo.shopledger.domain.usecase.expense.ExpenseListDay
import io.github.yesidrobayo.shopledger.domain.usecase.expense.ExpenseListRow
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.YearMonth

/**
 * El estado de la pantalla de gastos: el mes y la categoría elegidos, los gastos del mes por día,
 * su total, y las dos hojas que se abren encima: el detalle de un gasto y el gasto nuevo.
 *
 * [month] y [today] faltan hasta conocer el calendario del negocio. [categories] son todas, vigentes
 * y archivadas: un gasto viejo puede estar en una archivada, y el filtro también la ofrece.
 * [dueRecurring] es cuántos gastos recurrentes esperan confirmación.
 */
@Immutable
data class ExpensesUiState(
    val month: YearMonth? = null,
    val today: BusinessDate? = null,
    val weekStartDay: DayOfWeek? = null,
    val categoryId: Long? = null,
    val categories: List<ExpenseCategory> = emptyList(),
    val content: ExpensesContent = ExpensesContent.Loading,
    val total: Money? = null,
    val categorySheet: Boolean = false,
    val detail: ExpenseDetailSheet? = null,
    val form: ExpenseForm? = null,
    val selectedExpenseId: Long? = null,
    val message: ExpenseMessage? = null,
    val newCategory: NewExpenseCategory? = null,
    val accounts: List<MoneyAccount> = emptyList(),
    val dueRecurring: Int = 0,
) {
    /** Las categorías que se ofrecen en un gasto nuevo: solo las vigentes. */
    val activeCategories: List<ExpenseCategory> get() = categories.filter { !it.isArchived }

    /** La categoría del filtro, o `null` para todas. */
    val filterCategory: ExpenseCategory? get() = categories.firstOrNull { it.id == categoryId }
}

/** Lo que muestra la lista del mes. */
sealed interface ExpensesContent {
    /** Leyendo. El esqueleto solo aparece si tarda. */
    data object Loading : ExpensesContent

    /** El mes no tiene gastos: se invita a registrar el primero. */
    data object Empty : ExpensesContent

    /** El mes tiene gastos, pero ninguno de la categoría elegida. */
    data object EmptyFiltered : ExpensesContent

    /** No se pudo leer. Se puede reintentar. */
    data object ReadError : ExpensesContent

    /**
     * El teléfono no conoce la zona horaria del negocio, así que no sabe qué mes es hoy: se ofrece
     * elegirla, porque reintentar no lo cambia.
     */
    data object UnknownZone : ExpensesContent

    /** Los gastos del mes por día, del más nuevo al más antiguo. */
    data class Days(val days: List<ExpenseListDay>) : ExpensesContent
}

/**
 * La hoja de un gasto: sus datos, sin campos editables, y su anulación. Con [voiding] la hoja pide el
 * motivo y una nota antes de anular. Con [confirmCorrect] pregunta antes de anular y corregir.
 */
@Immutable
data class ExpenseDetailSheet(
    val row: ExpenseListRow,
    val voiding: ExpenseVoidDraft? = null,
    val status: ExpenseSheetStatus = ExpenseSheetStatus.IDLE,
    val confirmCorrect: Boolean = false,
)

/** El motivo y la nota de una anulación, tal como los elige la persona. */
data class ExpenseVoidDraft(
    val reason: VoidReason = VoidReason.ENTRY_ERROR,
    val note: String = "",
) {
    val noteTooLong: Boolean get() = note.trim().codePointLength() > MAX_NOTE_LENGTH

    companion object {
        /** Largo máximo de la nota, el mismo que guarda la base. */
        const val MAX_NOTE_LENGTH = 200

        /** Los motivos que valen para un gasto: un gasto no se devuelve. */
        val REASONS: List<VoidReason> = listOf(VoidReason.ENTRY_ERROR, VoidReason.OTHER)
    }
}

/** En qué va lo que se confirma en una hoja. */
enum class ExpenseSheetStatus {
    IDLE,

    /** Guardando o anulando: el botón muestra su indicador e ignora más toques. */
    WORKING,

    /** No se pudo. Lo escrito se conserva, y se puede reintentar. */
    FAILED,
}

/**
 * El gasto nuevo, tal como lo escribe la persona. [date] falta mientras sea hoy. [errors] solo se
 * muestran después de intentar guardar, para no marcar en rojo un campo que se está llenando. Con
 * [proposeCategory], sin categoría elegida se propone la de otros gastos; al corregir un gasto cuya
 * categoría se archivó no se propone ninguna: la persona elige. Con [recurring], el gasto confirma
 * ese vencimiento de un gasto recurrente.
 */
@Immutable
data class ExpenseForm(
    val amountText: String = "",
    val categoryId: Long? = null,
    val description: String = "",
    val date: BusinessDate? = null,
    val errors: Set<ExpenseFormError> = emptySet(),
    val status: ExpenseSheetStatus = ExpenseSheetStatus.IDLE,
    val notice: ExpenseFormNotice? = null,
    val proposeCategory: Boolean = true,
    val accountId: Long? = null,
    val recurring: RecurringOccurrence? = null,
) {
    val descriptionTooLong: Boolean
        get() = description.trim().codePointLength() > MAX_DESCRIPTION_LENGTH

    companion object {
        /** Largo máximo de la descripción, el mismo que guarda la base. */
        const val MAX_DESCRIPTION_LENGTH = 200
    }
}

/** Lo que impide guardar el gasto, en su campo. */
enum class ExpenseFormError {
    /** Falta el valor, o es cero. */
    AMOUNT_ZERO,

    /** El valor no se lee como un número del formato del negocio. */
    AMOUNT_NOT_A_NUMBER,

    /** El valor tiene más decimales que la moneda. */
    AMOUNT_DECIMALS,

    /** El valor pasa del tope de un documento. */
    AMOUNT_TOO_HIGH,

    /** La descripción pasa de 200 caracteres. */
    DESCRIPTION_TOO_LONG,

    /** La fecha elegida es posterior a hoy. */
    DATE_FUTURE,
}

/** Un aviso en la hoja del gasto nuevo, sobre algo que no es de un campo. */
sealed interface ExpenseFormNotice {
    /** No se pudo guardar. [outOfSpace] dice si fue por falta de espacio. */
    data class SaveFailed(val outOfSpace: Boolean) : ExpenseFormNotice

    /** La moneda sigue sin confirmar: se confirma primero, y lo escrito se conserva. */
    data object CurrencyUnconfirmed : ExpenseFormNotice

    /** La categoría elegida se archivó o se eliminó mientras tanto: hay que elegir otra. */
    data object CategoryUnavailable : ExpenseFormNotice

    /** Al corregir, la categoría original está archivada y no se trajo: hay que elegir otra. */
    data object CategoryLeftOut : ExpenseFormNotice

    /** La cuenta elegida ya no puede pagar el gasto: se elige otra o ninguna. */
    data object AccountUnavailable : ExpenseFormNotice

    /** La categoría del gasto recurrente está archivada y no se trajo: hay que elegir otra. */
    data object RecurringCategoryLeftOut : ExpenseFormNotice

    /**
     * El vencimiento que se confirmaba ya se registró u omitió, o el gasto recurrente se archivó:
     * no se guardó nada.
     */
    data object RecurringHandled : ExpenseFormNotice
}

/**
 * La hoja de una categoría nueva, abierta desde el gasto nuevo: al guardarla, queda elegida en el
 * gasto, que sigue con lo escrito.
 */
@Immutable
data class NewExpenseCategory(
    val name: String = "",
    val error: ExpenseCategoryNameError? = null,
    val saving: Boolean = false,
    val saveFailed: Boolean = false,
)

/** Lo que impide guardar el nombre de la categoría nueva. */
enum class ExpenseCategoryNameError {
    EMPTY,
    TOO_LONG,
    DUPLICATE,
}

/** El aviso breve que sigue a guardar o anular, para mostrarlo una vez. */
sealed interface ExpenseMessage {
    /** Se guardó el gasto [expenseId] por [amount]. */
    data class Saved(val expenseId: Long, val amount: Money) : ExpenseMessage

    /** Se anuló un gasto. */
    data object Voided : ExpenseMessage
}

/** El formato de los montos que se escriben: los separadores y los decimales de la moneda. */
data class ExpenseNumberFormat(val moneySymbols: NumberSymbols, val currencyDecimals: Int)

/** Lo que la persona hace en la pantalla de gastos. */
sealed interface ExpensesAction {
    /** Abre la configuración regional para elegir la zona horaria del negocio. */
    data object ChooseZone : ExpensesAction

    data object PreviousMonth : ExpensesAction

    data object NextMonth : ExpensesAction

    data object OpenCategories : ExpensesAction

    data class CategoryFilterChange(val categoryId: Long?) : ExpensesAction

    data object CloseCategories : ExpensesAction

    data object Retry : ExpensesAction

    data class OpenExpense(val expenseId: Long) : ExpensesAction

    data object StartVoid : ExpensesAction

    data class VoidReasonChange(val reason: VoidReason) : ExpensesAction

    data class VoidNoteChange(val note: String) : ExpensesAction

    data object ConfirmVoid : ExpensesAction

    data object CloseDetail : ExpensesAction

    /** Pedir confirmación de anular y corregir. */
    data object StartCorrect : ExpensesAction

    data object CloseCorrect : ExpensesAction

    /** Anular el gasto y abrir uno nuevo con sus datos. */
    data object ConfirmCorrect : ExpensesAction

    data object NewExpense : ExpensesAction

    /** Abre el gasto nuevo con los datos del vencimiento actual de un gasto recurrente. */
    data class StartRecurring(val recurringExpenseId: Long) : ExpensesAction

    data class AmountChange(val text: String) : ExpensesAction

    data class FormCategoryChange(val categoryId: Long) : ExpensesAction

    data class DescriptionChange(val text: String) : ExpensesAction

    data class DateChange(val date: BusinessDate) : ExpensesAction

    /** Elige la cuenta de la que sale el gasto, o ninguna con `null`. */
    data class AccountChange(val accountId: Long?) : ExpensesAction

    data object Save : ExpensesAction

    data object CloseForm : ExpensesAction

    /** Abrir la hoja de una categoría nueva desde el gasto nuevo. */
    data object NewCategory : ExpensesAction

    data class NewCategoryNameChange(val name: String) : ExpensesAction

    data object SaveCategory : ExpensesAction

    data object CloseCategory : ExpensesAction

    /** El aviso breve ya se mostró. */
    data object MessageShown : ExpensesAction

    /** Ir a confirmar la moneda: lo resuelve la navegación, y la hoja queda como estaba. */
    data object ConfirmCurrency : ExpensesAction

    /** Abrir los gastos recurrentes: lo resuelve la navegación. */
    data object OpenRecurring : ExpensesAction

    /** Volver a las ventas, otro segmento de Registros: lo resuelve la navegación. */
    data object ShowSales : ExpensesAction

    /** Ir a las compras, otro segmento de Registros: lo resuelve la navegación. */
    data object ShowPurchases : ExpensesAction
}

/** Cuántos caracteres tiene el texto, como los cuenta la base: un emoji es uno. */
internal fun String.codePointLength(): Int = length - count { it.isLowSurrogate() }

/** Lo que muestra la lista: los días, o el estado que corresponda. */
internal fun contentOf(days: List<ExpenseListDay>, monthHasAny: Boolean): ExpensesContent = when {
    days.isNotEmpty() -> ExpensesContent.Days(days)
    monthHasAny -> ExpensesContent.EmptyFiltered
    else -> ExpensesContent.Empty
}
