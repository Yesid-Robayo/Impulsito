package io.github.yesidrobayo.shopledger.ui.purchase

import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.catalog.MeasureUnit
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyAccount
import io.github.yesidrobayo.shopledger.domain.model.region.NumberSymbols
import kotlinx.datetime.DayOfWeek

/**
 * Lo que muestra la compra nueva. Todo sale del ViewModel: la pantalla solo dibuja.
 *
 * @param loading el catálogo todavía no llega. Es un formulario local, así que no hay esqueleto:
 *   solo se esperan los productos y los proveedores.
 * @param loadFailed el catálogo no se pudo leer: la pantalla ofrece reintentar.
 * @param supplierName el proveedor elegido, o `null` para `Sin proveedor`.
 * @param date la fecha elegida, o `null` para hoy.
 * @param today hoy y [weekStartDay] para elegir otra fecha; faltan si la zona no se reconoce.
 * @param lines las líneas, en el orden en que se agregaron.
 * @param total lo que se paga por las líneas que ya se pueden calcular, o `null` sin ninguna.
 * @param sheet la hoja abierta, si hay una.
 * @param dialog el diálogo abierto, si hay uno.
 * @param notice el aviso arriba del total, como un error al registrar.
 * @param registering la compra se está guardando: el botón espera.
 * @param registered la compra quedó guardada: la pantalla muestra el resumen del costo.
 * @param closed la pantalla terminó y la navegación vuelve.
 * @param payment cómo se paga la compra: `Pagada` o `A plazo`, con su vencimiento y lo pagado ahora.
 */
data class NewPurchaseUiState(
    val loading: Boolean = true,
    val loadFailed: Boolean = false,
    val supplierName: String? = null,
    val date: BusinessDate? = null,
    val today: BusinessDate? = null,
    val weekStartDay: DayOfWeek? = null,
    val reference: String = "",
    val referenceTooLong: Boolean = false,
    val lines: List<PurchaseLineUi> = emptyList(),
    val total: Money? = null,
    val sheet: PurchaseSheet? = null,
    val dialog: PurchaseDialog? = null,
    val notice: PurchaseNotice? = null,
    val registering: Boolean = false,
    val registered: PurchaseSummary? = null,
    val closed: Boolean = false,
    val payment: PurchasePaymentUi = PurchasePaymentUi(),
) {
    /** Si hay algo escrito que se perdería al salir. */
    val hasLines: Boolean get() = lines.isNotEmpty()
}

/**
 * Una línea de la compra en pantalla.
 *
 * @param key la identidad estable de la línea en la lista, aunque se borre otra antes.
 * @param unit la unidad en que se compra la línea, con la que se escribe la cantidad.
 * @param units las unidades en que se puede comprar el producto: la base y sus conversiones de
 *   compra. Con una sola, no hay nada que elegir.
 * @param byTotal si la persona escribe el total de la línea en vez del costo unitario.
 * @param lineTotal el total de la línea con el impuesto, si ya se puede calcular.
 * @param derived el valor que se deduce del escrito: el total si se escribe el costo unitario, y
 *   el costo unitario si se escribe el total.
 * @param costBefore y [costAfter] el costo promedio antes y después de esta línea, si cambia.
 * @param costDiffersFrom el costo anterior cuando el de esta línea es más del triple o menos de un
 *   tercio: un error de digitación aquí daña el promedio. No bloquea.
 * @param archived el producto se archivó mientras se escribía la compra: se reactiva o se quita.
 */
data class PurchaseLineUi(
    val key: Long,
    val productId: Long,
    val name: String,
    val unit: MeasureUnit?,
    val units: List<MeasureUnit> = emptyList(),
    val quantityText: String,
    val amountText: String,
    val byTotal: Boolean,
    val lineTotal: Money? = null,
    val derived: Money? = null,
    val costBefore: Money? = null,
    val costAfter: Money? = null,
    val costDiffersFrom: Money? = null,
    val quantityError: PurchaseFieldError? = null,
    val amountError: PurchaseFieldError? = null,
    val archived: Boolean = false,
)

/**
 * Cómo se paga la compra en pantalla.
 *
 * @param onCredit `A plazo`: lo que no se paga ahora queda en el libro del proveedor.
 * @param dueDate el vencimiento que se usará: el elegido o el que propone el plazo habitual del
 *   proveedor; `null` es sin vencimiento.
 * @param canClearDue si se puede volver a `Sin vencimiento`: solo con una fecha elegida a mano y un
 *   proveedor sin plazo habitual, porque con plazo el vencimiento se propone siempre.
 * @param purchaseDate la fecha de la compra, desde la que puede vencer.
 * @param paidNowText lo que se paga al registrar, tal como se escribió.
 * @param errors lo que impide registrar la compra a plazo, cada uno junto a su campo.
 * @param accounts las cuentas de las que se puede pagar; vacía si la persona no lleva cuentas.
 * @param accountId la cuenta elegida, o `null` para ninguna.
 */
data class PurchasePaymentUi(
    val onCredit: Boolean = false,
    val dueDate: BusinessDate? = null,
    val canClearDue: Boolean = false,
    val purchaseDate: BusinessDate? = null,
    val paidNowText: String = "",
    val errors: Set<PurchasePaymentError> = emptySet(),
    val accounts: List<MoneyAccount> = emptyList(),
    val accountId: Long? = null,
)

/** Lo que impide registrar una compra a plazo. La pantalla elige el texto. */
enum class PurchasePaymentError {
    /** Una compra a plazo exige proveedor. */
    SUPPLIER_REQUIRED,

    /** El vencimiento es anterior a la fecha de la compra. */
    DUE_BEFORE_PURCHASE,

    /** Lo pagado ahora no es un número, o trae más decimales que la moneda. */
    PAID_NOT_A_NUMBER,

    /** Lo pagado ahora pasa del total de la compra. */
    PAID_OVER_TOTAL,
}

/** Por qué un campo de la compra no se puede guardar. La pantalla elige el texto. */
enum class PurchaseFieldError {
    /** Falta el valor: se dice solo después de intentar registrar. */
    REQUIRED,

    /** La cantidad es cero o no es positiva. */
    ZERO_QUANTITY,

    /** La unidad no admite fracciones y la cantidad las trae. */
    FRACTION_NOT_ALLOWED,

    /** Lo escrito no es un número, o es negativo. */
    NOT_A_NUMBER,

    /** El valor pasa de lo que la app puede guardar. */
    TOO_HIGH,

    /** Con esta línea, el stock del producto pasaría de su tope. */
    STOCK_OVER_LIMIT,

    /** La unidad elegida ya no se compra: hay que elegir otra. */
    UNIT_UNAVAILABLE,
}

/** Las hojas de la compra. */
sealed interface PurchaseSheet {
    /** Elegir un producto para agregar, con lo que se escribió en la búsqueda. */
    data class Products(val query: String, val options: List<ProductOption>) : PurchaseSheet

    /** Elegir el proveedor, o ninguno. */
    data class Suppliers(val query: String, val options: List<SupplierOption>) : PurchaseSheet
}

/** Un producto que se puede agregar. [archived] ofrece reactivarlo en vez de agregarlo. */
data class ProductOption(val id: Long, val name: String, val archived: Boolean)

/** Un proveedor que se puede elegir; [id] `null` es `Sin proveedor`. */
data class SupplierOption(val id: Long?, val name: String?)

/** Los diálogos de la compra. */
sealed interface PurchaseDialog {
    /** Salir con líneas escritas: se pregunta antes de descartarlas. */
    data object Discard : PurchaseDialog

    /** El producto elegido está archivado: se reactiva para comprarlo. */
    data class Reactivate(val productId: Long, val name: String) : PurchaseDialog
}

/** Los avisos de la compra, que no borran nada de lo escrito. */
sealed interface PurchaseNotice {
    /** No se pudo guardar: se reintenta con lo mismo. [outOfSpace] dice si falta espacio. */
    data class RegisterFailed(val outOfSpace: Boolean) : PurchaseNotice

    /** La moneda del negocio sigue sin confirmar: se confirma y se vuelve. */
    data object CurrencyUnconfirmed : PurchaseNotice

    /** La suma de las líneas pasa de lo que se puede guardar. */
    data object TotalTooLarge : PurchaseNotice

    /** La compra pasa del máximo de líneas. */
    data object TooManyLines : PurchaseNotice

    /** El proveedor elegido se archivó o se eliminó mientras se escribía la compra. */
    data object SupplierUnavailable : PurchaseNotice

    /** La cuenta elegida ya no puede pagar la compra: se elige otra o ninguna. */
    data object AccountUnavailable : PurchaseNotice

    /**
     * Al corregir una compra, [lines] líneas no se trajeron porque su producto está archivado, y con
     * [supplier] tampoco el proveedor, que está archivado.
     */
    data class CorrectionLeftOut(val lines: Int, val supplier: Boolean) : PurchaseNotice
}

/** El resumen tras registrar: el total y cómo quedó el costo de cada producto. */
data class PurchaseSummary(val purchaseId: Long, val total: Money, val costs: List<CostChangeUi>)

/**
 * Cómo quedó el costo de un producto tras la compra. Con [reviewPrice], su margen quedó por debajo
 * del objetivo: se sugiere revisar su precio, sin obligar.
 */
data class CostChangeUi(
    val productId: Long,
    val name: String,
    val before: Money,
    val after: Money,
    val reviewPrice: Boolean = false,
)

/** Lo que la persona hace en la compra nueva. */
sealed interface NewPurchaseAction {
    data object OpenProducts : NewPurchaseAction

    data object OpenSuppliers : NewPurchaseAction

    data class QueryChange(val query: String) : NewPurchaseAction

    data object CloseSheet : NewPurchaseAction

    data class AddProduct(val productId: Long) : NewPurchaseAction

    /** Crear un producto que la búsqueda no encontró, con lo escrito como nombre. */
    data class CreateProduct(val name: String) : NewPurchaseAction

    data class SelectSupplier(val supplierId: Long?) : NewPurchaseAction

    data class DateChange(val date: BusinessDate) : NewPurchaseAction

    data class ReferenceChange(val text: String) : NewPurchaseAction

    /** `Pagada` con `false`, `A plazo` con `true`. */
    data class PaymentModeChange(val onCredit: Boolean) : NewPurchaseAction

    /** El vencimiento de la compra a plazo, o `null` para dejarla sin vencimiento. */
    data class DueDateChange(val date: BusinessDate?) : NewPurchaseAction

    data class PaidNowChange(val text: String) : NewPurchaseAction

    /** Elige la cuenta de la que sale lo pagado, o ninguna con `null`. */
    data class AccountChange(val accountId: Long?) : NewPurchaseAction

    data class QuantityChange(val key: Long, val text: String) : NewPurchaseAction

    data class AmountChange(val key: Long, val text: String) : NewPurchaseAction

    /** Comprar la línea en otra unidad, como la caja: [unitId] `null` es la base. */
    data class UnitChange(val key: Long, val unitId: Long?) : NewPurchaseAction

    /** Escribir el total de la línea, o volver al costo unitario. */
    data class AmountModeChange(val key: Long, val byTotal: Boolean) : NewPurchaseAction

    data class RemoveLine(val key: Long) : NewPurchaseAction

    data object ConfirmReactivate : NewPurchaseAction

    data object Register : NewPurchaseAction

    /** Ir a confirmar la moneda: lo escrito se conserva. */
    data object ConfirmCurrency : NewPurchaseAction

    data object Retry : NewPurchaseAction

    data object DismissNotice : NewPurchaseAction

    data object Close : NewPurchaseAction

    data object ConfirmLeave : NewPurchaseAction

    data object DismissDialog : NewPurchaseAction

    /** Revisar el precio de un producto desde el resumen: lo resuelve la navegación. */
    data class ReviewPrice(val productId: Long) : NewPurchaseAction

    /** El resumen ya se vio: la pantalla termina. */
    data object Finish : NewPurchaseAction
}

/** Los símbolos con que se leen los números que escribe la persona, del formato regional. */
data class PurchaseNumberFormat(
    val moneySymbols: NumberSymbols,
    val currencyDecimals: Int,
    val quantitySymbols: NumberSymbols,
)
