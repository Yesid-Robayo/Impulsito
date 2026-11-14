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
import io.github.yesidrobayo.shopledger.domain.usecase.document.SaveOrderUseCase
import io.github.yesidrobayo.shopledger.ui.components.ActionState
import io.github.yesidrobayo.shopledger.ui.components.ActionVariant
import io.github.yesidrobayo.shopledger.ui.components.AppBottomSheet
import io.github.yesidrobayo.shopledger.ui.components.AppTextField
import io.github.yesidrobayo.shopledger.ui.components.CustomerPicker
import io.github.yesidrobayo.shopledger.ui.components.CustomerPickerSheet
import io.github.yesidrobayo.shopledger.ui.components.DateField
import io.github.yesidrobayo.shopledger.ui.components.MoneyField
import io.github.yesidrobayo.shopledger.ui.components.NoticeCard
import io.github.yesidrobayo.shopledger.ui.components.NoticeVariant
import io.github.yesidrobayo.shopledger.ui.components.PrimaryActionButton
import io.github.yesidrobayo.shopledger.ui.theme.AppTheme

/**
 * La hoja de `Guardar pedido`: el cliente, con la misma lista que el cobro; la fecha de entrega, la
 * dirección, la nota de entrega y el cobro por envío. Todo es opcional. Lo que impide guardar se
 * dice aquí, sin cerrar la hoja ni perder lo escrito.
 */
@Composable
internal fun OrderSaveSheetView(
    form: OrderSaveSheet,
    cart: SaleCartState,
    onAction: (NewSaleAction) -> Unit,
) {
    if (form.choosingCustomer) {
        CustomerPickerSheet(
            customers = form.customerChoices,
            query = form.customerQuery,
            onQueryChange = { onAction(NewSaleAction.OrderCustomerQuery(it)) },
            onPick = { onAction(NewSaleAction.OrderPickCustomer(it)) },
            onCreate = { onAction(NewSaleAction.OrderNewCustomer(it)) },
            onDismiss = { onAction(NewSaleAction.OrderCloseCustomers) },
        )
        return
    }
    AppBottomSheet(
        onDismissRequest = { onAction(NewSaleAction.CloseSheet) },
        title = stringResource(R.string.order_save_action),
        action = {
            PrimaryActionButton(
                text = stringResource(R.string.order_save_action),
                onClick = { onAction(NewSaleAction.ConfirmSaveOrder) },
                variant = ActionVariant.PRIMARY,
                state = if (form.working) ActionState.WORKING else ActionState.IDLE,
            )
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.s12)) {
            CustomerPicker(
                label = stringResource(R.string.order_customer_label),
                name = form.customerName,
                balance = form.customerBalance,
                onOpen = { onAction(NewSaleAction.OrderOpenCustomers) },
                onRemove = { onAction(NewSaleAction.OrderRemoveCustomer) },
            )
            val today = form.today ?: cart.today
            val weekStart = cart.weekStartDay
            if (today != null && weekStart != null) {
                DateField(
                    label = stringResource(R.string.order_delivery_date_label),
                    date = form.dueDate,
                    onDateChange = { onAction(NewSaleAction.OrderDueDateChange(it)) },
                    today = today,
                    weekStartDay = weekStart,
                    // La entrega mira hacia adelante: no tiene fecha máxima.
                    latest = null,
                )
            }
            val tooLong = pluralStringResource(
                R.plurals.error_text_long,
                SaveOrderUseCase.MAX_TEXT_LENGTH,
                SaveOrderUseCase.MAX_TEXT_LENGTH,
            )
            AppTextField(
                value = form.address,
                onValueChange = {
                    onAction(NewSaleAction.OrderTextChange(OrderTextField.ADDRESS, it))
                },
                label = stringResource(R.string.order_delivery_address_label),
                singleLine = false,
                error = tooLong.takeIf {
                    form.address.trim().length > SaveOrderUseCase.MAX_TEXT_LENGTH
                },
            )
            AppTextField(
                value = form.deliveryNote,
                onValueChange = {
                    onAction(NewSaleAction.OrderTextChange(OrderTextField.NOTE, it))
                },
                label = stringResource(R.string.order_delivery_note_label),
                singleLine = false,
                error = tooLong.takeIf {
                    form.deliveryNote.trim().length > SaveOrderUseCase.MAX_TEXT_LENGTH
                },
            )
            MoneyField(
                value = form.shippingText,
                onValueChange = {
                    onAction(NewSaleAction.OrderTextChange(OrderTextField.SHIPPING, it))
                },
                label = stringResource(R.string.order_shipping_charge_label),
                imeAction = ImeAction.Done,
                error = stringResource(R.string.error_value_high).takeIf { form.shippingError },
            )
            when {
                form.noLines -> Text(
                    text = stringResource(R.string.order_error_no_lines),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )

                form.extras -> NoticeCard(
                    text = stringResource(R.string.order_error_extras),
                    variant = NoticeVariant.ERROR,
                )

                form.failed -> NoticeCard(
                    text = stringResource(R.string.order_error_save),
                    variant = NoticeVariant.ERROR,
                    actionLabel = stringResource(R.string.action_retry),
                    onAction = { onAction(NewSaleAction.ConfirmSaveOrder) },
                )
            }
        }
    }
}
