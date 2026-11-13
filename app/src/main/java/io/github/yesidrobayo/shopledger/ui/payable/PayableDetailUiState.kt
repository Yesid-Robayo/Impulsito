package io.github.yesidrobayo.shopledger.ui.payable

import androidx.compose.runtime.Immutable
import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.catalog.Supplier
import io.github.yesidrobayo.shopledger.domain.model.payable.SupplierLedgerEntry
import io.github.yesidrobayo.shopledger.domain.model.payable.SupplierLedgerEntryType
import io.github.yesidrobayo.shopledger.domain.usecase.customer.PaymentChoices
import io.github.yesidrobayo.shopledger.domain.usecase.payable.SupplierStatement
import kotlinx.datetime.DayOfWeek

/**
 * Una fila del libro del proveedor, tal como se guardó, con lo que dice de ella el resto del libro:
 * [voided] si otra fila ya la compensó, y [voidable] si la persona puede anularla desde aquí, que
 * solo pasa con un pago sin anular.
 */
@Immutable
data class PayableLine(val entry: SupplierLedgerEntry, val voided: Boolean, val voidable: Boolean)

/** Cómo está la cuenta con el proveedor en cuanto a vencimientos, para la línea bajo el saldo. */
@Immutable
sealed interface PayableDue {
    /** No se le debe nada: `Al día`. */
    data object Clear : PayableDue

    /** El negocio tiene saldo a favor: la insignia ya lo dice. */
    data object Credit : PayableDue

    /** Se le debe, y lo más antiguo venció en [since]. */
    data class Overdue(val since: BusinessDate) : PayableDue

    /** Se le debe, y lo próximo vence en [on]. */
    data class DueOn(val on: BusinessDate) : PayableDue

    /** Se le debe, sin fecha de vencimiento. */
    data object NoDue : PayableDue
}

/**
 * El detalle listo para mostrar: el proveedor, su libro del más reciente al más antiguo y su
 * vencimiento. [voidedPurchaseLeftCredit] dice que una compra a plazo con pagos se anuló y el
 * saldo quedó a favor, para explicarlo.
 */
@Immutable
data class PayableDetailView(
    val supplier: Supplier,
    val lines: List<PayableLine>,
    val due: PayableDue,
    val voidedPurchaseLeftCredit: Boolean,
) {
    val overdue: Boolean get() = due is PayableDue.Overdue
}

/** Lo que muestra el detalle de un proveedor en cuentas por pagar. */
@Immutable
sealed interface PayableDetailContent {
    data object Loading : PayableDetailContent

    /** El proveedor no existe. */
    data object Missing : PayableDetailContent

    /** La lectura falló, o el libro tiene cifras que no caben. */
    data object ReadError : PayableDetailContent

    /** Este teléfono no conoce la zona del negocio: no hay cómo saber qué está vencido. */
    data object UnknownZone : PayableDetailContent

    data class Ready(val view: PayableDetailView) : PayableDetailContent
}

/** Lo que impide guardar el pago, cada uno junto a su campo. */
enum class PayableFormError {
    AMOUNT_ZERO,
    AMOUNT_NOT_A_NUMBER,
    AMOUNT_DECIMALS,
    AMOUNT_TOO_HIGH,
    NOTE_TOO_LONG,
    DATE_FUTURE,
}

/** Un aviso dentro de la hoja, que no borra lo escrito. */
@Immutable
sealed interface PayableFormNotice {
    data class SaveFailed(val outOfSpace: Boolean) : PayableFormNotice

    /** La moneda sigue sin confirmar: ningún monto se guarda antes. */
    data object CurrencyUnconfirmed : PayableFormNotice

    /** La cuenta elegida ya no paga, o lleva otra moneda: hay que elegir otra o ninguna. */
    data object AccountUnavailable : PayableFormNotice

    /** Este teléfono no conoce la zona del negocio. */
    data object UnknownZone : PayableFormNotice
}

/** Lo que dejaría el pago escrito, en vivo: lo que queda debiendo o el saldo a favor. */
@Immutable
sealed interface PayablePreview {
    data class Remaining(val balance: Money) : PayablePreview

    data class CreditLeft(val credit: Money) : PayablePreview
}

/** La hoja de registrar un pago, con lo escrito. [date] nulo es hoy. */
@Immutable
data class SupplierPaymentSheet(
    val amountText: String = "",
    val accountId: Long? = null,
    val date: BusinessDate? = null,
    val note: String = "",
    val preview: PayablePreview? = null,
    val errors: Set<PayableFormError> = emptySet(),
    val notice: PayableFormNotice? = null,
    val working: Boolean = false,
)

/** Un aviso breve, abajo, tras una acción del detalle. */
sealed interface PayableDetailMessage {
    data object PaymentRecorded : PayableDetailMessage

    data object PaymentVoided : PayableDetailMessage

    data class Failed(val outOfSpace: Boolean) : PayableDetailMessage
}

/** El estado completo del detalle. [voidingEntryId] es el pago que se pide confirmar anular. */
@Immutable
data class PayableDetailUiState(
    val content: PayableDetailContent = PayableDetailContent.Loading,
    val today: BusinessDate? = null,
    val weekStartDay: DayOfWeek? = null,
    val choices: PaymentChoices = PaymentChoices(),
    val payment: SupplierPaymentSheet? = null,
    val voidingEntryId: Long? = null,
    val message: PayableDetailMessage? = null,
)

/** Lo que la persona hace en el detalle de un proveedor en cuentas por pagar. */
sealed interface PayableDetailAction {
    data object Back : PayableDetailAction

    data object Retry : PayableDetailAction

    /** Abre la ficha del proveedor: lo resuelve la navegación. */
    data object OpenSupplier : PayableDetailAction

    /** Abre la compra de un cargo: lo resuelve la navegación. */
    data class OpenPurchase(val purchaseId: Long) : PayableDetailAction

    data object ChooseZone : PayableDetailAction

    data object ConfirmCurrency : PayableDetailAction

    data object OpenPayment : PayableDetailAction

    data class PaymentAmount(val text: String) : PayableDetailAction

    /** Elige la cuenta de la que sale el pago, o ninguna con `null`. */
    data class PaymentAccount(val accountId: Long?) : PayableDetailAction

    data class PaymentDate(val date: BusinessDate) : PayableDetailAction

    data class PaymentNote(val text: String) : PayableDetailAction

    data object SavePayment : PayableDetailAction

    data object DismissSheet : PayableDetailAction

    data class AskVoidPayment(val entryId: Long) : PayableDetailAction

    data object ConfirmVoid : PayableDetailAction

    data object DismissDialog : PayableDetailAction

    data object MessageShown : PayableDetailAction
}

/** Arma el detalle de [statement] en [today], o `null` si el libro tiene cifras que no caben. */
internal fun payableDetailView(
    statement: SupplierStatement,
    today: BusinessDate,
): PayableDetailView? {
    val debt = statement.debt ?: return null
    val byId = statement.entries.associateBy { it.id }
    val compensated = statement.entries.mapNotNull { it.content.reversesEntryId }.toSet()
    val lines = statement.entries.reversed().map { entry ->
        val voided = entry.id in compensated
        PayableLine(
            entry = entry,
            voided = voided,
            voidable = !voided && entry.content.type == SupplierLedgerEntryType.PAYMENT,
        )
    }
    val balance = statement.supplier.balance
    val due = when {
        balance == Money.ZERO -> PayableDue.Clear

        balance < Money.ZERO -> PayableDue.Credit

        else -> debt.overdueSince(today)?.let { PayableDue.Overdue(it) }
            ?: debt.nextDue(today)?.let { PayableDue.DueOn(it) }
            ?: PayableDue.NoDue
    }
    // La última fila anuló un cargo de compra y dejó saldo a favor: la compra ya tenía pagos.
    val last = statement.entries.lastOrNull()?.content
    val reversed = last?.reversesEntryId?.let { byId[it]?.content?.type }
    val voidedPurchaseLeftCredit = balance < Money.ZERO &&
        last?.type == SupplierLedgerEntryType.ENTRY_VOID &&
        reversed == SupplierLedgerEntryType.CREDIT_PURCHASE
    return PayableDetailView(statement.supplier, lines, due, voidedPurchaseLeftCredit)
}
