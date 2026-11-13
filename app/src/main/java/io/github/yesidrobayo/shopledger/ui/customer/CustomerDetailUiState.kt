package io.github.yesidrobayo.shopledger.ui.customer

import androidx.compose.runtime.Immutable
import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.BusinessTimeZone
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.customer.Customer
import io.github.yesidrobayo.shopledger.domain.model.customer.CustomerEntrySource
import io.github.yesidrobayo.shopledger.domain.model.customer.CustomerLedgerEntry
import io.github.yesidrobayo.shopledger.domain.model.customer.CustomerLedgerEntryType
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleHistoryRow
import io.github.yesidrobayo.shopledger.domain.rule.customer.PaymentPreview
import io.github.yesidrobayo.shopledger.domain.usecase.customer.CustomerStatement
import io.github.yesidrobayo.shopledger.domain.usecase.customer.PaymentChoices
import kotlinx.datetime.DayOfWeek

/**
 * Una fila del extracto, tal como la guardó el libro, con lo que dice de ella el resto del libro:
 * [voided] si otra fila ya la compensó, y [voidable] si la persona puede anularla desde aquí, que
 * solo pasa con un abono sin anular.
 */
@Immutable
data class StatementLine(
    val entry: CustomerLedgerEntry,
    val voided: Boolean,
    val voidable: Boolean,
)

/** Cómo está la cuenta del cliente en cuanto a vencimientos, para la línea bajo el saldo. */
@Immutable
sealed interface DueStatus {
    /** No debe nada: `Al día`. */
    data object Clear : DueStatus

    /** Tiene saldo a favor: la insignia ya lo dice, y no hay vencimiento que mostrar. */
    data object Credit : DueStatus

    /** Debe, y lo más antiguo venció en [since]. */
    data class Overdue(val since: BusinessDate) : DueStatus

    /** Debe, y lo próximo vence en [on]. */
    data class DueOn(val on: BusinessDate) : DueStatus

    /** Debe, sin fecha de vencimiento. */
    data object NoDue : DueStatus
}

/**
 * El detalle listo para mostrar: el cliente, su extracto del más reciente al más antiguo y su
 * vencimiento.
 */
@Immutable
data class CustomerDetailView(
    val customer: Customer,
    val lines: List<StatementLine>,
    val due: DueStatus,
    val loyaltyEnabled: Boolean,
) {
    val overdue: Boolean get() = due is DueStatus.Overdue
}

/** Lo que muestra el detalle de un cliente. */
@Immutable
sealed interface CustomerDetailContent {
    data object Loading : CustomerDetailContent

    /** El cliente no existe. */
    data object Missing : CustomerDetailContent

    data object ReadError : CustomerDetailContent

    /** Este teléfono no conoce la zona del negocio: no hay cómo saber qué está vencido. */
    data object UnknownZone : CustomerDetailContent

    data class Ready(val view: CustomerDetailView) : CustomerDetailContent
}

/** Los segmentos del detalle. */
enum class CustomerDetailTab { MOVEMENTS, SALES }

/** Las ventas del cliente, leídas por tramos al abrir su segmento. */
@Immutable
data class CustomerSales(
    val rows: List<SaleHistoryRow> = emptyList(),
    val loading: Boolean = false,
    val complete: Boolean = false,
    val failed: Boolean = false,
    val started: Boolean = false,
)

/** Lo que impide guardar el abono o el ajuste, cada uno junto a su campo. */
enum class CustomerFormError {
    AMOUNT_ZERO,
    AMOUNT_NOT_A_NUMBER,
    AMOUNT_DECIMALS,
    AMOUNT_TOO_HIGH,
    NOTE_REQUIRED,
    NOTE_TOO_LONG,
    DATE_FUTURE,
}

/** Un aviso dentro de la hoja, que no borra lo escrito. */
@Immutable
sealed interface CustomerFormNotice {
    data class SaveFailed(val outOfSpace: Boolean) : CustomerFormNotice

    /** La moneda sigue sin confirmar: ningún monto se guarda antes. */
    data object CurrencyUnconfirmed : CustomerFormNotice

    /** La cuenta elegida ya no recibe dinero, o lleva otra moneda: hay que elegir otra. */
    data object AccountUnavailable : CustomerFormNotice

    /** Este teléfono no conoce la zona del negocio. */
    data object UnknownZone : CustomerFormNotice
}

/** La hoja de registrar un abono, con lo escrito. [date] nulo es hoy. */
@Immutable
data class PaymentSheet(
    val amountText: String = "",
    val methodId: Long? = null,
    val accountId: Long? = null,
    val date: BusinessDate? = null,
    val note: String = "",
    val preview: PaymentPreview? = null,
    val errors: Set<CustomerFormError> = emptySet(),
    val notice: CustomerFormNotice? = null,
    val working: Boolean = false,
)

/** La hoja de ajustar el saldo a mano: monto con signo y nota obligatoria. */
@Immutable
data class AdjustSheet(
    val amountText: String = "",
    val note: String = "",
    val errors: Set<CustomerFormError> = emptySet(),
    val notice: CustomerFormNotice? = null,
    val working: Boolean = false,
)

/** Una confirmación pendiente del detalle. */
@Immutable
sealed interface CustomerDetailDialog {
    data class VoidPayment(val entryId: Long) : CustomerDetailDialog

    /** Archivar, con el nombre y el saldo, que se avisa si no es cero. */
    data class Archive(val name: String, val balance: Money) : CustomerDetailDialog

    /** Anonimizar, que no se deshace, con el nombre y el saldo. */
    data class Anonymize(val name: String, val balance: Money) : CustomerDetailDialog
}

/** Un aviso breve, abajo, tras una acción del detalle. */
sealed interface CustomerDetailMessage {
    data object Saved : CustomerDetailMessage

    data object PaymentRecorded : CustomerDetailMessage

    data object PaymentVoided : CustomerDetailMessage

    data object Adjusted : CustomerDetailMessage

    data object Archived : CustomerDetailMessage

    data object Reactivated : CustomerDetailMessage

    /** Se anonimizó: el aviso explica que sigue en copias anteriores y ofrece actualizarlas. */
    data object Anonymized : CustomerDetailMessage

    data object StatementFailed : CustomerDetailMessage

    data class Failed(val outOfSpace: Boolean) : CustomerDetailMessage
}

/** Una fila del extracto que se comparte: sin notas, costos ni ganancia. */
@Immutable
data class StatementDocumentLine(
    val date: BusinessDate,
    val type: CustomerLedgerEntryType,
    val amount: Money,
    val balanceAfter: Money,
    val voided: Boolean,
)

/**
 * El extracto que se comparte, armado solo con lo que el cliente puede ver: el negocio, su nombre,
 * la fecha, el saldo y cada fila con su saldo después. Es un modelo propio, sin campos de costo,
 * margen, ganancia ni notas internas, para que ninguno pueda colarse en el documento.
 */
@Immutable
data class StatementDocument(
    val businessName: String?,
    val customerName: String?,
    val issuedOn: BusinessDate,
    val balance: Money,
    val lines: List<StatementDocumentLine>,
)

/** El estado completo del detalle de un cliente. */
@Immutable
data class CustomerDetailUiState(
    val content: CustomerDetailContent = CustomerDetailContent.Loading,
    val tab: CustomerDetailTab = CustomerDetailTab.MOVEMENTS,
    val sales: CustomerSales = CustomerSales(),
    val today: BusinessDate? = null,
    val weekStartDay: DayOfWeek? = null,
    val zone: BusinessTimeZone? = null,
    val choices: PaymentChoices = PaymentChoices(),
    val payment: PaymentSheet? = null,
    val adjust: AdjustSheet? = null,
    val dialog: CustomerDetailDialog? = null,
    val message: CustomerDetailMessage? = null,
    /** El extracto que la pantalla debe compartir ahora; se limpia al compartirlo. */
    val statement: StatementDocument? = null,
)

/** Lo que la persona hace en el detalle de un cliente. */
sealed interface CustomerDetailAction {
    data object Back : CustomerDetailAction

    data object Edit : CustomerDetailAction

    data object Retry : CustomerDetailAction

    data class SelectTab(val tab: CustomerDetailTab) : CustomerDetailAction

    data object LoadMoreSales : CustomerDetailAction

    data class OpenSale(val saleId: Long) : CustomerDetailAction

    /** Abre el marcador del sistema con [phone]: lo resuelve la pantalla. */
    data class Dial(val phone: String) : CustomerDetailAction

    data object ChooseZone : CustomerDetailAction

    /** Abre una venta nueva con este cliente ya elegido: lo resuelve la navegación. */
    data object SellToCustomer : CustomerDetailAction

    data object ShareStatement : CustomerDetailAction

    /** La pantalla ya compartió el extracto, o no pudo si [ok] es falso. */
    data class StatementShared(val ok: Boolean) : CustomerDetailAction

    data object OpenPayment : CustomerDetailAction

    data class PaymentAmount(val text: String) : CustomerDetailAction

    data class ChooseMethod(val methodId: Long) : CustomerDetailAction

    /** Elige la cuenta del abono, o ninguna con `null`. */
    data class PaymentAccount(val accountId: Long?) : CustomerDetailAction

    data class PaymentDate(val date: BusinessDate) : CustomerDetailAction

    data class PaymentNote(val text: String) : CustomerDetailAction

    data object SavePayment : CustomerDetailAction

    data object DismissSheet : CustomerDetailAction

    data class AskVoidPayment(val entryId: Long) : CustomerDetailAction

    data object OpenAdjust : CustomerDetailAction

    data class AdjustAmount(val text: String) : CustomerDetailAction

    data class AdjustNote(val text: String) : CustomerDetailAction

    data object SaveAdjust : CustomerDetailAction

    data object AskArchive : CustomerDetailAction

    data object Reactivate : CustomerDetailAction

    data object AskAnonymize : CustomerDetailAction

    /** Confirma el diálogo abierto. Anonimizar llega aquí solo después de la verificación. */
    data object ConfirmDialog : CustomerDetailAction

    data object DismissDialog : CustomerDetailAction

    data object ConfirmCurrency : CustomerDetailAction

    /** Pide una copia nueva tras anonimizar, desde el aviso. */
    data object UpdateCopies : CustomerDetailAction

    data object MessageShown : CustomerDetailAction
}

/** Si [entry] compensa otra fila del libro. */
internal val CustomerLedgerEntry.reversedId: Long?
    get() = (source as? CustomerEntrySource.Reverses)?.entryId

/** Arma el detalle de [statement] en [today]. */
internal fun detailView(
    statement: CustomerStatement,
    today: BusinessDate,
    loyaltyEnabled: Boolean,
): CustomerDetailView {
    val compensated = statement.entries.mapNotNull { it.reversedId }.toSet()
    val lines = statement.entries.map { entry ->
        val voided = entry.id in compensated
        StatementLine(
            entry = entry,
            voided = voided,
            voidable = !voided && entry.type == CustomerLedgerEntryType.PAYMENT,
        )
    }
    val balance = statement.customer.balance
    val due = when {
        balance == Money.ZERO -> DueStatus.Clear

        balance < Money.ZERO -> DueStatus.Credit

        else -> statement.dues.overdueSince(today)?.let { DueStatus.Overdue(it) }
            ?: statement.dues.nextDue(today)?.let { DueStatus.DueOn(it) }
            ?: DueStatus.NoDue
    }
    return CustomerDetailView(statement.customer, lines, due, loyaltyEnabled)
}

/** El extracto para compartir, en [today]: las filas en el orden del libro, de la más antigua. */
internal fun statementDocument(
    view: CustomerDetailView,
    businessName: String?,
    today: BusinessDate,
): StatementDocument = StatementDocument(
    businessName = businessName,
    customerName = view.customer.contact?.name,
    issuedOn = today,
    balance = view.customer.balance,
    lines = view.lines.reversed().map { line ->
        StatementDocumentLine(
            date = line.entry.businessDate,
            type = line.entry.type,
            amount = line.entry.amount,
            balanceAfter = line.entry.balanceAfter,
            voided = line.voided,
        )
    },
)
