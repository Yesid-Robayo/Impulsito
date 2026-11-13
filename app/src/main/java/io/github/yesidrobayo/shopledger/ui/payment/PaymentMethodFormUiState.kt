package io.github.yesidrobayo.shopledger.ui.payment

import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.catalog.StoredImage
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyAccount
import io.github.yesidrobayo.shopledger.domain.model.payment.PaymentMethodKind
import io.github.yesidrobayo.shopledger.domain.model.payment.PaymentMethodOptions

/**
 * Lo que la persona escribe en el formulario, tal como lo escribe. Los números van como texto limpio
 * y se leen con el formato regional solo al guardar: así un error nunca borra lo escrito.
 *
 * Un método inicial muestra su nombre traducido hasta que la persona toca el campo: mientras
 * [nameEdited] sea falso, [name] no cuenta y el método conserva su nombre traducido.
 *
 * [moneyAccountId] es la cuenta de dinero donde entra lo cobrado con el método, o `null` si no mueve
 * ninguna. Una cuenta que ya no se ofrece se conserva tal como estaba hasta que la persona elija otra.
 */
data class PaymentMethodForm(
    val name: String = "",
    val nameEdited: Boolean = false,
    val kind: PaymentMethodKind = PaymentMethodKind.OTHER,
    val feeRate: String = "",
    val feeFixed: String = "",
    val settlementDays: String = "",
    val priceAdjustment: String = "",
    val instructions: String = "",
    val image: FormImage = FormImage.None,
    val moneyAccountId: Long? = null,
)

/** La imagen de cobro en el formulario, como un código QR. */
sealed interface FormImage {
    /** Sin imagen: nunca la tuvo, o la persona la quitó. */
    data object None : FormImage

    /** La que ya tenía guardada el método. */
    data class Saved(val image: StoredImage) : FormImage

    /** Una recién elegida, ya recodificada y en su archivo, que se asigna al guardar. */
    data class Picked(val image: StoredImage) : FormImage
}

/** Por qué no se pudo traer la imagen elegida. Nada cambió, y se puede elegir otra. */
enum class ImageProblem {
    UNREADABLE,
    OUT_OF_SPACE,
}

/** Los campos que pueden tener un error propio. */
enum class FormField {
    NAME,
    KIND,
    FEE_RATE,
    FEE_FIXED,
    SETTLEMENT_DAYS,
    PRICE_ADJUSTMENT,
    INSTRUCTIONS,
}

/** El error de un campo, que la pantalla convierte en un texto que dice cómo arreglarlo. */
enum class FieldProblem {
    NAME_BLANK,
    NAME_TOO_LONG,
    NAME_TAKEN,
    KIND_LOCKED,
    FEE_RATE_RANGE,
    FEE_FIXED_NEGATIVE,
    FEE_FIXED_TOO_HIGH,
    SETTLEMENT_TOO_HIGH,
    PRICE_ADJUSTMENT_RANGE,
    INSTRUCTIONS_TOO_LONG,
}

/** Un aviso del formulario, arriba del botón, que no es de un solo campo. */
sealed interface FormNotice {
    /** No se guardó. Lo escrito sigue aquí. */
    data class SaveFailed(val problem: SaveProblem) : FormNotice

    /** La cuenta de dinero que tenía el método ya no está disponible. */
    data object AccountUnavailable : FormNotice

    /** Es el único vigente: no se archiva ni se elimina. */
    data object LastActive : FormNotice

    /** Mientras estuvo archivado, otro vigente tomó su nombre. */
    data object ReactivateNameTaken : FormNotice

    /** Tiene ventas, que entraron mientras se decidía eliminarlo: solo se archiva. */
    data object HasSales : FormNotice
}

/** Lo que pide confirmación antes de hacerse. */
enum class FormDialog {
    ARCHIVE,
    DELETE,
}

/** El formulario de un método de pago. */
sealed interface PaymentMethodFormUiState {
    data object Loading : PaymentMethodFormUiState

    /** El método ya no existe, por ejemplo porque se eliminó en otra pantalla. */
    data object NotFound : PaymentMethodFormUiState

    /** La lectura falló: se dice, con `Reintentar`. */
    data object ReadFailed : PaymentMethodFormUiState

    /**
     * El formulario de [methodId], o de uno nuevo si es `null`.
     *
     * - [systemKey] es la clave de un método inicial, cuyo nombre traducido se muestra mientras no
     *   se edite.
     * - [options] dice qué se puede hacer con un método guardado, como archivarlo; es `null` en uno
     *   nuevo.
     * - [initialMethods] son los métodos iniciales vigentes sin nombre propio, por su clave y su id:
     *   si el nombre escrito coincide con el texto traducido de uno, la pantalla avisa y ofrece
     *   abrirlo, sin impedir guardar.
     * - [finished] se vuelve verdadero al guardar, archivar o eliminar: la ruta vuelve a la lista.
     * - [cashRounding] es el redondeo de efectivo de los ajustes regionales, que se muestra con el
     *   tipo `Efectivo`. Cero es sin redondeo.
     * - [accounts] son las cuentas de dinero que se ofrecen para el método: las vigentes en la moneda
     *   del negocio, y solo si la persona lleva cuentas. Vacía, la fila no aparece.
     */
    data class Editing(
        val methodId: Long?,
        val systemKey: String?,
        val form: PaymentMethodForm,
        val options: PaymentMethodOptions?,
        val initialMethods: Map<String, Long> = emptyMap(),
        val errors: Map<FormField, FieldProblem> = emptyMap(),
        val notice: FormNotice? = null,
        val dialog: FormDialog? = null,
        val working: Boolean = false,
        val finished: Boolean = false,
        val cashRounding: Money = Money.ZERO,
        val importingImage: Boolean = false,
        val imageProblem: ImageProblem? = null,
        val accounts: List<MoneyAccount> = emptyList(),
    ) : PaymentMethodFormUiState
}

/** Lo que la persona hace en el formulario. */
sealed interface PaymentMethodFormAction {
    data object Back : PaymentMethodFormAction

    /** Vuelve a leer el método tras una falla de lectura. */
    data object Retry : PaymentMethodFormAction

    data class NameChanged(val value: String) : PaymentMethodFormAction

    data class KindChanged(val kind: PaymentMethodKind) : PaymentMethodFormAction

    data class FeeRateChanged(val value: String) : PaymentMethodFormAction

    data class FeeFixedChanged(val value: String) : PaymentMethodFormAction

    data class SettlementDaysChanged(val value: String) : PaymentMethodFormAction

    data class PriceAdjustmentChanged(val value: String) : PaymentMethodFormAction

    data class InstructionsChanged(val value: String) : PaymentMethodFormAction

    /** La cuenta de dinero elegida para el método, o `null` para que no mueva ninguna. */
    data class AccountChanged(val accountId: Long?) : PaymentMethodFormAction

    data object Save : PaymentMethodFormAction

    data object AskArchive : PaymentMethodFormAction

    data object AskDelete : PaymentMethodFormAction

    data object DismissDialog : PaymentMethodFormAction

    data object ConfirmDialog : PaymentMethodFormAction

    data object Reactivate : PaymentMethodFormAction

    /** La persona eligió una imagen en el selector del sistema; [source] es su documento. */
    data class PickImage(val source: String) : PaymentMethodFormAction

    data object RemoveImage : PaymentMethodFormAction

    /** Abre el método inicial cuyo nombre coincide con el escrito. */
    data class OpenMethod(val id: Long) : PaymentMethodFormAction
}
