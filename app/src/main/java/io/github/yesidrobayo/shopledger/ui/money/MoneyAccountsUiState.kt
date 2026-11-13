package io.github.yesidrobayo.shopledger.ui.money

import androidx.compose.runtime.Immutable
import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.BusinessTimeZone
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.money.AccountCurrency
import io.github.yesidrobayo.shopledger.domain.model.money.ManualMoneyKind
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyAccount
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyAccountKind
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyDocument
import io.github.yesidrobayo.shopledger.domain.model.settings.CurrencyCode
import io.github.yesidrobayo.shopledger.domain.rule.money.MoneyStatementDay
import io.github.yesidrobayo.shopledger.domain.rule.money.MoneyStatementLine
import io.github.yesidrobayo.shopledger.ui.region.RegionalOption

/**
 * Una cuenta en la lista, con lo que le falta por llegar: [pending] es `null` si no espera nada.
 */
@Immutable
data class AccountRow(val account: MoneyAccount, val pending: Money?)

/**
 * La lista de cuentas lista para mostrar.
 *
 * - [available] es la suma de las cuentas en la moneda del negocio, o `null` si no cabe.
 * - [others] son las cuentas en otra moneda: nunca entran en [available].
 * - [inUse] es falso mientras solo exista la cuenta inicial sin movimientos: la pantalla explica
 *   para qué sirve, y las acciones siguen a la mano.
 * - [archived] son las archivadas, que se ven desde el menú.
 */
@Immutable
data class MoneyAccountsView(
    val bookCurrency: AccountCurrency,
    val available: Money?,
    val accounts: List<AccountRow>,
    val others: List<AccountRow>,
    val archived: List<AccountRow>,
    val inUse: Boolean,
) {
    /** Las cuentas vigentes, de la moneda del negocio primero: las que reciben dinero nuevo. */
    val active: List<MoneyAccount> get() = (accounts + others).map { it.account }

    /** Cualquier cuenta, vigente o archivada, por su id. */
    fun find(id: Long): AccountRow? = accounts.firstOrNull { it.account.id == id }
        ?: others.firstOrNull { it.account.id == id }
        ?: archived.firstOrNull { it.account.id == id }
}

/** Lo que muestra la lista. */
@Immutable
sealed interface MoneyAccountsContent {
    data object Loading : MoneyAccountsContent

    data object ReadError : MoneyAccountsContent

    data class Ready(val view: MoneyAccountsView) : MoneyAccountsContent
}

/** El libro de la cuenta abierta. */
@Immutable
sealed interface AccountDetailContent {
    data object Loading : AccountDetailContent

    /** La cuenta ya no existe. */
    data object Missing : AccountDetailContent

    data class Ready(
        val row: AccountRow,
        val days: List<MoneyStatementDay>,
        /** La zona del negocio, para decir a qué hora entró cada movimiento. */
        val zone: BusinessTimeZone?,
    ) : AccountDetailContent
}

/** Lo que impide guardar una hoja, junto a su campo. */
enum class MoneyFormError {
    AMOUNT_ZERO,
    AMOUNT_NOT_A_NUMBER,
    AMOUNT_DECIMALS,
    AMOUNT_TOO_HIGH,
    NOTE_TOO_LONG,
    SAME_ACCOUNT,
    RATE_REQUIRED,
    RATE_NOT_A_NUMBER,
    RATE_TOO_HIGH,
    NAME_EMPTY,
    NAME_TOO_LONG,
    NAME_TAKEN,
    OPENING_NOT_A_NUMBER,
    OPENING_DECIMALS,
    OPENING_TOO_HIGH,
}

/** Un aviso dentro de una hoja, que no borra lo escrito. */
@Immutable
sealed interface MoneyFormNotice {
    data class SaveFailed(val outOfSpace: Boolean) : MoneyFormNotice

    /** La moneda del negocio sigue sin confirmar: ningún monto se guarda antes. */
    data object CurrencyUnconfirmed : MoneyFormNotice

    /** Este teléfono no conoce la zona del negocio: no hay cómo fechar el movimiento. */
    data object UnknownZone : MoneyFormNotice
}

/**
 * La hoja de un retiro, un aporte u otro ingreso: la cuenta, el monto en su moneda y una nota.
 * [negativeAfter] es el saldo negativo en que quedaría la cuenta, que se avisa sin bloquear.
 */
@Immutable
data class ManualSheet(
    val kind: ManualMoneyKind,
    val accountId: Long?,
    val amountText: String = "",
    val note: String = "",
    val errors: Set<MoneyFormError> = emptySet(),
    val notice: MoneyFormNotice? = null,
    val negativeAfter: Money? = null,
    val working: Boolean = false,
)

/**
 * La hoja de transferir: desde, hacia, el monto en la moneda de origen y, entre monedas distintas,
 * la cotización escrita a mano y lo que llega, [arrives], en la moneda de destino.
 */
@Immutable
data class TransferSheet(
    val fromId: Long?,
    val toId: Long?,
    val amountText: String = "",
    val rateText: String = "",
    val note: String = "",
    val errors: Set<MoneyFormError> = emptySet(),
    val notice: MoneyFormNotice? = null,
    val negativeAfter: Money? = null,
    val arrives: Money? = null,
    val working: Boolean = false,
)

/**
 * La hoja de una cuenta nueva, si [editingId] es `null`, o de una que se edita. La cuenta inicial
 * muestra su nombre traducido mientras [nameEdited] sea falso. La moneda solo se elige al crear,
 * con [choosingCurrency] abierta sobre la hoja; [currencyName] es su nombre en el idioma de la
 * interfaz, si el teléfono la conoce.
 */
@Immutable
data class AccountEditor(
    val editingId: Long?,
    val systemKey: String? = null,
    val name: String = "",
    val nameEdited: Boolean = true,
    val kind: MoneyAccountKind = MoneyAccountKind.BANK,
    val currency: AccountCurrency,
    val currencyName: String? = null,
    val openingText: String = "",
    val errors: Set<MoneyFormError> = emptySet(),
    val notice: MoneyFormNotice? = null,
    val working: Boolean = false,
    val choosingCurrency: Boolean = false,
    val currencyQuery: String = "",
    val currencyOptions: List<RegionalOption> = emptyList(),
)

/**
 * Un movimiento abierto desde el libro: lo que dice y, si viene de un documento, adónde lleva.
 * [document] es `null` mientras se busca o si no tiene.
 */
@Immutable
data class MovementSheet(val line: MoneyStatementLine, val document: MoneyDocument? = null)

/** Lo que pide confirmación antes de hacerse. */
@Immutable
sealed interface MoneyDialog {
    /** Anular un movimiento registrado a mano; una transferencia se anula entera. */
    data class Void(val movementId: Long, val isTransfer: Boolean) : MoneyDialog

    /** Archivar una cuenta: con saldo, sugiere transferirlo antes. */
    data class Archive(val account: MoneyAccount) : MoneyDialog
}

/** Un aviso breve, abajo, tras una acción. */
sealed interface MoneyMessage {
    data object MovementSaved : MoneyMessage

    data object AccountSaved : MoneyMessage

    /** Otra cuenta vigente ya usa el nombre de la que se quería reactivar. */
    data object NameTaken : MoneyMessage

    data class Failed(val outOfSpace: Boolean) : MoneyMessage
}

/** El estado completo de la pantalla de cuentas de dinero. */
@Immutable
data class MoneyAccountsUiState(
    val content: MoneyAccountsContent = MoneyAccountsContent.Loading,
    val showArchived: Boolean = false,
    val selectedId: Long? = null,
    val detail: AccountDetailContent = AccountDetailContent.Loading,
    /** La zona del negocio, para la hora de cada movimiento, o `null` si el teléfono no la conoce. */
    val zone: BusinessTimeZone? = null,
    /** La fecha de negocio de hoy, para decir qué falta por acreditar. */
    val today: BusinessDate? = null,
    val manual: ManualSheet? = null,
    val transfer: TransferSheet? = null,
    val editor: AccountEditor? = null,
    val movement: MovementSheet? = null,
    val dialog: MoneyDialog? = null,
    val message: MoneyMessage? = null,
) {
    /** Si hay una hoja o una confirmación abierta: atrás la cierra antes que la pantalla. */
    val overlayOpen: Boolean
        get() = manual != null || transfer != null || editor != null || movement != null ||
            dialog != null
}

/** Lo que la persona hace en las cuentas de dinero. */
sealed interface MoneyAccountsAction {
    data object Back : MoneyAccountsAction

    data object Retry : MoneyAccountsAction

    data object ChooseZone : MoneyAccountsAction

    data object ConfirmCurrency : MoneyAccountsAction

    data class ShowArchived(val show: Boolean) : MoneyAccountsAction

    /** Abre el libro de una cuenta, o vuelve a la lista con `null`. */
    data class Select(val accountId: Long?) : MoneyAccountsAction

    data class StartManual(val kind: ManualMoneyKind) : MoneyAccountsAction

    data object StartTransfer : MoneyAccountsAction

    data object NewAccount : MoneyAccountsAction

    data object EditAccount : MoneyAccountsAction

    data class ChooseAccount(val accountId: Long) : MoneyAccountsAction

    data class ChooseFrom(val accountId: Long) : MoneyAccountsAction

    data class ChooseTo(val accountId: Long) : MoneyAccountsAction

    data class Amount(val text: String) : MoneyAccountsAction

    data class Rate(val text: String) : MoneyAccountsAction

    data class Note(val text: String) : MoneyAccountsAction

    data class Name(val text: String) : MoneyAccountsAction

    data class Kind(val kind: MoneyAccountKind) : MoneyAccountsAction

    data class OpeningBalance(val text: String) : MoneyAccountsAction

    data object OpenCurrencies : MoneyAccountsAction

    data class CurrencyQuery(val text: String) : MoneyAccountsAction

    data class ChooseCurrency(val code: CurrencyCode) : MoneyAccountsAction

    data object CloseCurrencies : MoneyAccountsAction

    data object Save : MoneyAccountsAction

    data object DismissSheet : MoneyAccountsAction

    data class OpenMovement(val movementId: Long) : MoneyAccountsAction

    data class OpenDocument(val document: MoneyDocument) : MoneyAccountsAction

    data object AskVoid : MoneyAccountsAction

    data object AskArchive : MoneyAccountsAction

    data object Reactivate : MoneyAccountsAction

    data object ConfirmDialog : MoneyAccountsAction

    data object DismissDialog : MoneyAccountsAction

    data object MessageShown : MoneyAccountsAction
}
