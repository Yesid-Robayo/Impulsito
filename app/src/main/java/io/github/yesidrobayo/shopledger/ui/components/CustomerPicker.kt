package io.github.yesidrobayo.shopledger.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.customer.Customer
import io.github.yesidrobayo.shopledger.ui.theme.AppTheme
import io.github.yesidrobayo.shopledger.ui.theme.PreviewTheme
import io.github.yesidrobayo.shopledger.ui.theme.ThemePreviews

/**
 * El cliente de un documento: una fila que abre la lista de clientes. Sin cliente dice
 * `Sin cliente`; con uno, su nombre y su saldo con [BalanceBadge], y `close` para quitarlo. Con
 * [error], el cliente falta y es obligatorio, como en una venta a crédito: lo dice debajo.
 *
 * La lista que abre es [CustomerPickerSheet].
 */
@Composable
fun CustomerPicker(
    label: String,
    name: String?,
    balance: Money,
    onOpen: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
    error: String? = null,
) {
    val spacing = AppTheme.spacing
    val scheme = MaterialTheme.colorScheme
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = AppTheme.sizes.listRow)
                .clickable(role = Role.Button, onClick = onOpen)
                .padding(vertical = spacing.s8),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.s12),
        ) {
            Column(
                Modifier.weight(1f).semantics(mergeDescendants = true) {},
                verticalArrangement = Arrangement.spacedBy(spacing.s2),
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (error != null) scheme.error else scheme.onSurfaceVariant,
                )
                Text(
                    name ?: stringResource(R.string.customer_picker_none),
                    style = MaterialTheme.typography.titleSmall,
                    color = if (name == null) scheme.onSurfaceVariant else scheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (name != null) {
                    BalanceBadge(
                        BalanceBadgeVariant.forCustomer(balance),
                        balance,
                        Modifier.wrapContentWidth(),
                    )
                }
            }
            if (name != null) {
                IconButton(onClick = onRemove) {
                    Icon(
                        painterResource(R.drawable.ic_close),
                        contentDescription = stringResource(R.string.a11y_remove_customer),
                        tint = scheme.onSurfaceVariant,
                        modifier = Modifier.size(AppTheme.sizes.icon),
                    )
                }
            } else {
                Icon(
                    painterResource(R.drawable.ic_chevron_right),
                    contentDescription = null,
                    tint = scheme.onSurfaceVariant,
                )
            }
        }
        error?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = scheme.error)
        }
    }
}

/**
 * La lista para elegir un cliente: búsqueda por nombre o teléfono, una [CustomerRow] por cliente y
 * `Crear «…»` con lo buscado, que abre la hoja de un cliente nuevo. [customers] ya viene filtrada.
 */
@Composable
fun CustomerPickerSheet(
    customers: List<Customer>,
    query: String,
    onQueryChange: (String) -> Unit,
    onPick: (Long) -> Unit,
    onCreate: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    SelectionSheet(
        title = stringResource(R.string.customer_picker_title),
        items = customers,
        key = { it.id },
        query = query,
        onQueryChange = onQueryChange,
        onDismissRequest = onDismiss,
        searchHint = stringResource(R.string.customer_search_hint),
        itemContent = { customer ->
            CustomerRow(
                CustomerRowData(customer.contact?.name, customer.balance, customer.contact?.phone),
                onClick = { onPick(customer.id) },
            )
        },
        onCreate = onCreate,
    )
}

@ThemePreviews
@Composable
private fun CustomerPickerPreview() {
    PreviewTheme {
        Column(Modifier.padding(AppTheme.spacing.s16)) {
            CustomerPicker(
                stringResource(R.string.checkout_customer),
                null,
                Money.ZERO,
                {},
                {},
                error = stringResource(R.string.checkout_error_no_customer),
            )
            CustomerPicker(
                stringResource(R.string.checkout_customer),
                PreviewSamples.LONG_NAME,
                PreviewSamples.LONG_AMOUNT,
                {},
                {},
            )
        }
    }
}
