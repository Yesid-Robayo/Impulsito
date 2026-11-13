package io.github.yesidrobayo.shopledger.ui.cash

import androidx.compose.runtime.Immutable
import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.BusinessTimeZone
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.cash.CashDenomination
import io.github.yesidrobayo.shopledger.domain.model.cash.CashSession
import io.github.yesidrobayo.shopledger.domain.model.cash.DenominationCount
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyAccount
import io.github.yesidrobayo.shopledger.domain.rule.cash.CashSessionFigures

/** La caja abierta de la cuenta: su sesión, sus cifras y si quedó abierta desde otro día. */
@Immutable
data class OpenCash(
    val session: CashSession,
    val figures: CashSessionFigures,
    val fromAnotherDay: Boolean,
)

/**
 * La caja de una cuenta lista para mostrar: la abierta, si hay, y los cierres anteriores, del más
 * reciente al más viejo.
 */
@Immutable
data class CashRegisterView(
    val account: MoneyAccount,
    val open: OpenCash?,
    val closed: List<CashSession>,
) {
    /** Nunca se abrió caja en esta cuenta: la pantalla explica que es opcional. */
    val isEmpty: Boolean get() = open == null && closed.isEmpty()
}

/** Lo que muestra la pantalla de caja. */
@Immutable
sealed interface CashRegisterContent {
    data object Loading : CashRegisterContent

    data object ReadError : CashRegisterContent

    /** Este teléfono no conoce la zona del negocio: no hay cómo saber qué día es hoy. */
    data object UnknownZone : CashRegisterContent

    /** No queda ninguna cuenta de efectivo vigente: no hay qué contar. */
    data object NoCashAccount : CashRegisterContent

    data class Ready(val view: CashRegisterView) : CashRegisterContent
}

/** Lo que impide guardar un monto o un conteo, junto a su campo. */
enum class CashFormError {
    AMOUNT_REQUIRED,
    AMOUNT_NOT_A_NUMBER,
    AMOUNT_DECIMALS,
    AMOUNT_TOO_HIGH,
    NOTE_TOO_LONG,
}

/** Un aviso dentro de una hoja o del cierre, que no borra lo escrito. */
@Immutable
sealed interface CashFormNotice {
    data class SaveFailed(val outOfSpace: Boolean) : CashFormNotice

    /** La cuenta ya tiene una caja abierta. */
    data object AlreadyOpen : CashFormNotice

    /** La moneda sigue sin confirmar, y una diferencia de caja es dinero. */
    data object CurrencyUnconfirmed : CashFormNotice

    /** Una denominación del conteo se desactivó mientras se contaba. */
    data object DenominationChanged : CashFormNotice

    data object UnknownZone : CashFormNotice
}

/**
 * Lo contado, escrito como un monto o pieza por pieza. [counts] lleva el texto escrito por cada
 * denominación, por su id; [counted] es la suma que resulta, o `null` si todavía no se puede leer.
 */
@Immutable
data class CashCountInput(
    val amountText: String = "",
    val byDenomination: Boolean = false,
    val counts: Map<Long, String> = emptyMap(),
    val counted: Money? = null,
)

/** La hoja de abrir caja: la cuenta, lo que hay al abrir y cómo se contó. */
@Immutable
data class OpenCashSheet(
    val accountId: Long,
    val count: CashCountInput,
    val errors: Set<CashFormError> = emptySet(),
    val notice: CashFormNotice? = null,
    val working: Boolean = false,
)

/**
 * El cierre, en pantalla completa: lo contado, lo esperado, la diferencia en vivo y una nota.
 * [confirmDifference] pide confirmar antes de cerrar con una diferencia distinta de cero.
 */
@Immutable
data class CloseCashForm(
    val sessionId: Long,
    val expected: Money,
    val count: CashCountInput = CashCountInput(),
    val note: String = "",
    val errors: Set<CashFormError> = emptySet(),
    val notice: CashFormNotice? = null,
    val working: Boolean = false,
    val confirmDifference: Boolean = false,
) {
    /** Lo contado menos lo esperado, si ya se puede leer lo contado y la resta cabe. */
    val difference: Money?
        get() = count.counted?.let { counted ->
            try {
                counted - expected
            } catch (_: ArithmeticException) {
                null
            }
        }
}

/** Un cierre anterior abierto en su detalle, con lo contado por denominación si lo hubo. */
@Immutable
data class ClosedCashDetail(val session: CashSession, val counts: List<DenominationCount>)

/** La hoja de una denominación nueva o que se cambia: su valor, tal como se escribe. */
@Immutable
data class DenominationEditor(
    val id: Long?,
    val valueText: String = "",
    val isActive: Boolean = true,
    val error: DenominationError? = null,
    val failed: CashFormNotice? = null,
    val working: Boolean = false,
)

/** Por qué no se guarda una denominación. */
enum class DenominationError { VALUE_ZERO, NOT_A_NUMBER, TOO_HIGH, DUPLICATE }

/** Un aviso breve, abajo, tras una acción de la caja. */
sealed interface CashMessage {
    data object Opened : CashMessage

    data object Closed : CashMessage

    data class Failed(val outOfSpace: Boolean) : CashMessage
}

/** El estado completo de la pantalla de caja. */
@Immutable
data class CashRegisterUiState(
    val content: CashRegisterContent = CashRegisterContent.Loading,
    val accounts: List<MoneyAccount> = emptyList(),
    val denominations: List<CashDenomination> = emptyList(),
    val today: BusinessDate? = null,
    /** La zona del negocio, para decir a qué hora se abrió la caja. */
    val zone: BusinessTimeZone? = null,
    val openSheet: OpenCashSheet? = null,
    val closeForm: CloseCashForm? = null,
    val detail: ClosedCashDetail? = null,
    val showDenominations: Boolean = false,
    val denominationEditor: DenominationEditor? = null,
    val message: CashMessage? = null,
) {
    /** Las denominaciones que entran en un conteo nuevo: las activas. */
    val activeDenominations: List<CashDenomination>
        get() = denominations.filter { it.isActive }
}

/** Lo que la persona hace en la caja. */
sealed interface CashRegisterAction {
    data object Back : CashRegisterAction

    data object Retry : CashRegisterAction

    data object ChooseZone : CashRegisterAction

    data object ConfirmCurrency : CashRegisterAction

    /** Ver la caja de otra cuenta de efectivo, si hay más de una. */
    data class SelectAccount(val accountId: Long) : CashRegisterAction

    data object StartOpen : CashRegisterAction

    data class OpenAccount(val accountId: Long) : CashRegisterAction

    data object StartClose : CashRegisterAction

    /** El monto escrito, en la hoja de abrir o en el cierre, el que esté a la vista. */
    data class CountAmount(val text: String) : CashRegisterAction

    /** Contar pieza por pieza, o volver al monto. */
    data class CountByDenomination(val enabled: Boolean) : CashRegisterAction

    data class CountPieces(val denominationId: Long, val text: String) : CashRegisterAction

    data class CloseNote(val text: String) : CashRegisterAction

    data object ConfirmOpen : CashRegisterAction

    data object ConfirmClose : CashRegisterAction

    data object ConfirmDifference : CashRegisterAction

    data object DismissDifference : CashRegisterAction

    data object DismissSheet : CashRegisterAction

    data class OpenClosed(val sessionId: Long) : CashRegisterAction

    data object OpenDenominations : CashRegisterAction

    data object CloseDenominations : CashRegisterAction

    data object NewDenomination : CashRegisterAction

    data class EditDenomination(val id: Long) : CashRegisterAction

    data class DenominationValue(val text: String) : CashRegisterAction

    data class DenominationActive(val active: Boolean) : CashRegisterAction

    data object SaveDenomination : CashRegisterAction

    data object MessageShown : CashRegisterAction
}
