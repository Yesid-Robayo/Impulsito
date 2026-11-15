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
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.customer.Customer
import io.github.yesidrobayo.shopledger.domain.model.customer.CustomerContact
import io.github.yesidrobayo.shopledger.ui.theme.AppTheme
import io.github.yesidrobayo.shopledger.ui.theme.PreviewTheme
import io.github.yesidrobayo.shopledger.ui.theme.ThemePreviews
import kotlin.time.Instant

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
            // Sin `mergeDescendants` propio: la fila tocable junta etiqueta, nombre y saldo en su
            // nombre accesible. Un nodo fusionado aquí la dejaba sin texto para TalkBack.
            Column(
                Modifier.weight(1f),
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
            // Región viva educada: al faltar el cliente, TalkBack lo dice sin mover el foco.
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = scheme.error,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
    }
}

/**
 * La lista para elegir un cliente: búsqueda por nombre o teléfono, una [CustomerRow] por cliente y
 * `Crear «…»` con lo buscado, que abre la hoja de un cliente nuevo. [customers] ya viene filtrada.
 *
 * Sin búsqueda, [recent] va arriba, bajo `Recientes`, y la lista completa sigue debajo con
 * `Todos los clientes`: quien vuelve a comprar está a un toque. Con [onViewAll], `Ver clientes`
 * queda fijo bajo la lista y abre la pantalla de clientes, con sus filtros, para elegir desde ahí.
 */
@Composable
fun CustomerPickerSheet(
    customers: List<Customer>,
    query: String,
    onQueryChange: (String) -> Unit,
    onPick: (Long) -> Unit,
    onCreate: (String) -> Unit,
    onDismiss: () -> Unit,
    recent: List<Customer> = emptyList(),
    onViewAll: (() -> Unit)? = null,
) {
    SelectionSheet(
        title = stringResource(R.string.customer_picker_title),
        items = customers,
        key = { it.id },
        query = query,
        onQueryChange = onQueryChange,
        onDismissRequest = onDismiss,
        searchHint = stringResource(R.string.customer_search_hint),
        itemContent = { customer -> PickerRow(customer, onPick) },
        onCreate = onCreate,
        pinned = SelectionPinned(
            title = stringResource(R.string.customer_picker_recent),
            items = recent,
            itemsTitle = stringResource(R.string.customer_picker_all),
        ),
        footer = onViewAll?.let {
            SelectionFooter(
                stringResource(R.string.customer_picker_view_all),
                R.drawable.ic_group,
                it,
            )
        },
    )
}

@Composable
private fun PickerRow(customer: Customer, onPick: (Long) -> Unit) {
    CustomerRow(
        CustomerRowData(customer.contact?.name, customer.balance, customer.contact?.phone),
        onClick = { onPick(customer.id) },
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

/** El contenido de la hoja sin búsqueda: `Recientes` arriba, la lista completa y `Ver clientes`. */
@ThemePreviews
@Composable
private fun CustomerPickerSheetPreview() {
    val recent = previewCustomer(2, PreviewSamples.LONG_NAME, PreviewSamples.LONG_AMOUNT)
    val all = listOf(
        previewCustomer(1, "Ana Ríos", Money(4_000L), "300 000 000"),
        recent,
        previewCustomer(3, "Bruno Paz", Money(-1_500L)),
    )
    PreviewTheme {
        Column(Modifier.padding(AppTheme.spacing.s16)) {
            SelectionSheetContent(
                items = all,
                key = { it.id },
                query = "",
                onQueryChange = {},
                searchHint = stringResource(R.string.customer_search_hint),
                itemContent = { PickerRow(it) {} },
                pinned = SelectionPinned(
                    stringResource(R.string.customer_picker_recent),
                    listOf(recent),
                    stringResource(R.string.customer_picker_all),
                ),
                footer = SelectionFooter(
                    stringResource(R.string.customer_picker_view_all),
                    R.drawable.ic_group,
                ) {},
            )
        }
    }
}

/** Un cliente inventado para las vistas previas. */
private fun previewCustomer(id: Long, name: String, balance: Money, phone: String? = null) =
    Customer(
        id = id,
        contact = CustomerContact(name, name.lowercase(), phone, null, null),
        priceListId = null,
        balance = balance,
        pointsBalance = 0L,
        anonymizedAt = null,
        archivedAt = null,
        createdAt = PREVIEW_AT,
        updatedAt = PREVIEW_AT,
    )

private val PREVIEW_AT = Instant.fromEpochMilliseconds(1_700_000_000_000L)
