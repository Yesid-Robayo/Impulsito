package io.github.yesidrobayo.shopledger.ui.money

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalResources
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.model.SeedTable
import io.github.yesidrobayo.shopledger.domain.model.money.AccountCurrency
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyAccount
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyAccountKind
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyMovementType
import io.github.yesidrobayo.shopledger.format.MoneyFormatter
import io.github.yesidrobayo.shopledger.localization.SeedText
import io.github.yesidrobayo.shopledger.ui.format.AppFormats
import io.github.yesidrobayo.shopledger.ui.format.LocalAppFormats

/** El nombre visible de una cuenta: el que escribió la persona, o el traducido de la inicial. */
@Composable
fun accountName(account: MoneyAccount): String = SeedText.displayName(
    LocalResources.current,
    SeedTable.MONEY_ACCOUNTS,
    account.systemKey,
    account.details.name,
)

/** El nombre de la clase de una cuenta. */
@StringRes
fun kindLabel(kind: MoneyAccountKind): Int = when (kind) {
    MoneyAccountKind.CASH -> R.string.money_kind_cash
    MoneyAccountKind.BANK -> R.string.money_kind_bank
    MoneyAccountKind.WALLET -> R.string.money_kind_wallet
    MoneyAccountKind.OTHER -> R.string.money_kind_other
}

/** El nombre de cada tipo de movimiento, como lo lee la persona en el libro. */
@StringRes
fun movementLabel(type: MoneyMovementType): Int = when (type) {
    MoneyMovementType.OPENING_BALANCE -> R.string.money_movement_opening_balance
    MoneyMovementType.SALE_RECEIPT -> R.string.money_movement_sale_receipt
    MoneyMovementType.CUSTOMER_PAYMENT -> R.string.money_movement_customer_payment
    MoneyMovementType.REFUND -> R.string.money_movement_refund
    MoneyMovementType.FEE -> R.string.money_movement_fee
    MoneyMovementType.EXPENSE_PAYMENT -> R.string.money_movement_expense_payment
    MoneyMovementType.PURCHASE_PAYMENT -> R.string.money_movement_purchase_payment
    MoneyMovementType.SUPPLIER_PAYMENT -> R.string.money_movement_supplier_payment
    MoneyMovementType.SUPPLIER_REFUND -> R.string.money_movement_supplier_refund
    MoneyMovementType.OWNER_DRAW -> R.string.money_movement_owner_draw
    MoneyMovementType.OWNER_CONTRIBUTION -> R.string.money_movement_owner_contribution
    MoneyMovementType.OTHER_INCOME -> R.string.money_movement_other_income
    MoneyMovementType.TRANSFER_OUT -> R.string.money_movement_transfer_out
    MoneyMovementType.TRANSFER_IN -> R.string.money_movement_transfer_in
    MoneyMovementType.CASH_DIFFERENCE -> R.string.money_movement_cash_difference
    MoneyMovementType.MOVEMENT_VOID -> R.string.money_movement_void
}

/**
 * Dibuja [content] con los montos en [currency]: la moneda de una cuenta puede no ser la del negocio,
 * y su saldo va en su propia unidad menor. Los números siguen el formato regional del negocio; solo
 * cambian el símbolo, su lugar, los decimales y el nombre que lee TalkBack. Con la moneda del negocio
 * no cambia nada, y el símbolo propio que eligió la persona se conserva.
 */
@Composable
fun ProvideAccountCurrency(currency: AccountCurrency, content: @Composable () -> Unit) {
    val formats = LocalAppFormats.current
    val money = formats.money
    if (money.currencyCode == currency.code && money.currencyDecimals == currency.decimals) {
        content()
        return
    }
    val accountFormats = remember(formats, currency) { formats.withCurrency(currency) }
    CompositionLocalProvider(LocalAppFormats provides accountFormats, content = content)
}

/** Los mismos formatos, con los montos en [currency] y su símbolo de la norma. */
private fun AppFormats.withCurrency(currency: AccountCurrency): AppFormats = AppFormats(
    money = MoneyFormatter(
        formatLocale = money.formatLocale,
        currencyCode = currency.code,
        currencyDecimals = currency.decimals,
        currencySymbol = null,
        uiLocale = money.uiLocale,
    ),
    quantity = quantity,
    percent = percent,
    date = date,
)
