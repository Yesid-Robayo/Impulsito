package io.github.yesidrobayo.shopledger.ui.sale

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.ui.components.ActionState
import io.github.yesidrobayo.shopledger.ui.components.ActionVariant
import io.github.yesidrobayo.shopledger.ui.components.AppBottomSheet
import io.github.yesidrobayo.shopledger.ui.components.AppTextField
import io.github.yesidrobayo.shopledger.ui.components.CustomerPicker
import io.github.yesidrobayo.shopledger.ui.components.CustomerPickerSheet
import io.github.yesidrobayo.shopledger.ui.components.DateField
import io.github.yesidrobayo.shopledger.ui.components.NoticeCard
import io.github.yesidrobayo.shopledger.ui.components.NoticeVariant
import io.github.yesidrobayo.shopledger.ui.components.PrimaryActionButton
import io.github.yesidrobayo.shopledger.ui.theme.AppTheme

/**
 * La hoja de `Guardar cotización`: el cliente, opcional, con la misma lista que el cobro; la
 * vigencia, opcional; y la nota, que es la de la venta. Lo que impide guardar se dice aquí, sin
 * cerrar la hoja ni perder lo escrito.
 */
@Composable
internal fun QuoteSaveSheetView(
    form: QuoteSaveSheet,
    cart: SaleCartState,
    onAction: (NewSaleAction) -> Unit,
) {
    if (form.choosingCustomer) {
        CustomerPickerSheet(
            customers = form.customerChoices,
            query = form.customerQuery,
            onQueryChange = { onAction(NewSaleAction.QuoteCustomerQuery(it)) },
            onPick = { onAction(NewSaleAction.QuotePickCustomer(it)) },
            onCreate = { onAction(NewSaleAction.QuoteNewCustomer(it)) },
            onDismiss = { onAction(NewSaleAction.QuoteCloseCustomers) },
        )
        return
    }
    AppBottomSheet(
        onDismissRequest = { onAction(NewSaleAction.CloseSheet) },
        title = stringResource(R.string.quote_save_action),
        action = {
            PrimaryActionButton(
                text = stringResource(R.string.quote_save_action),
                onClick = { onAction(NewSaleAction.ConfirmSaveQuote) },
                variant = ActionVariant.PRIMARY,
                state = if (form.working) ActionState.WORKING else ActionState.IDLE,
            )
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.s12)) {
            CustomerPicker(
                label = stringResource(R.string.quote_customer_label),
                name = form.customerName,
                balance = form.customerBalance,
                onOpen = { onAction(NewSaleAction.QuoteOpenCustomers) },
                onRemove = { onAction(NewSaleAction.QuoteRemoveCustomer) },
            )
            val today = form.today ?: cart.today
            val weekStart = cart.weekStartDay
            if (today != null && weekStart != null) {
                DateField(
                    label = stringResource(R.string.quote_valid_until_label),
                    date = form.validUntil,
                    onDateChange = { onAction(NewSaleAction.QuoteValidUntilChange(it)) },
                    today = today,
                    weekStartDay = weekStart,
                    // La vigencia mira hacia adelante: no tiene fecha máxima.
                    latest = null,
                    error = if (form.pastDate) {
                        stringResource(
                            R.string.quote_error_past_date,
                        )
                    } else {
                        null
                    },
                )
            }
            AppTextField(
                value = cart.note,
                onValueChange = { onAction(NewSaleAction.QuoteNoteChange(it)) },
                label = stringResource(R.string.quote_note_label),
                singleLine = false,
                imeAction = ImeAction.Default,
                error =
                    if (cart.noteTooLong) {
                        pluralStringResource(
                            R.plurals.error_text_long,
                            NewSaleViewModel.MAX_NOTE_LENGTH,
                            NewSaleViewModel.MAX_NOTE_LENGTH,
                        )
                    } else {
                        null
                    },
            )
            when {
                form.noLines -> Text(
                    text = stringResource(R.string.quote_error_no_lines),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )

                form.extras -> NoticeCard(
                    text = stringResource(R.string.quote_error_extras),
                    variant = NoticeVariant.ERROR,
                )

                form.failed -> NoticeCard(
                    text = stringResource(R.string.quote_error_save),
                    variant = NoticeVariant.ERROR,
                    actionLabel = stringResource(R.string.action_retry),
                    onAction = { onAction(NewSaleAction.ConfirmSaveQuote) },
                )
            }
        }
    }
}
