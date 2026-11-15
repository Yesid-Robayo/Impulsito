package io.github.yesidrobayo.shopledger.ui.customer

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.customer.Customer
import io.github.yesidrobayo.shopledger.ui.components.ActionVariant
import io.github.yesidrobayo.shopledger.ui.components.AppTopBar
import io.github.yesidrobayo.shopledger.ui.components.CompactChipRow
import io.github.yesidrobayo.shopledger.ui.components.CustomerRow
import io.github.yesidrobayo.shopledger.ui.components.CustomerRowData
import io.github.yesidrobayo.shopledger.ui.components.DelayedSkeleton
import io.github.yesidrobayo.shopledger.ui.components.EmptyState
import io.github.yesidrobayo.shopledger.ui.components.OverflowAction
import io.github.yesidrobayo.shopledger.ui.components.OverflowMenu
import io.github.yesidrobayo.shopledger.ui.components.PrimaryActionButton
import io.github.yesidrobayo.shopledger.ui.components.SearchField
import io.github.yesidrobayo.shopledger.ui.components.SkeletonBlock
import io.github.yesidrobayo.shopledger.ui.components.StatTile
import io.github.yesidrobayo.shopledger.ui.components.StatTileVariant
import io.github.yesidrobayo.shopledger.ui.components.TonalFab
import io.github.yesidrobayo.shopledger.ui.components.TonalFabDefaults
import io.github.yesidrobayo.shopledger.ui.components.TopBarNavigation
import io.github.yesidrobayo.shopledger.ui.components.keepsMessagesAbove
import io.github.yesidrobayo.shopledger.ui.help.HelpTopic
import io.github.yesidrobayo.shopledger.ui.help.ScreenHelpLink
import io.github.yesidrobayo.shopledger.ui.theme.AppTheme
import io.github.yesidrobayo.shopledger.ui.theme.PreviewTheme
import io.github.yesidrobayo.shopledger.ui.theme.ThemePreviews

/**
 * La lista de clientes, sin ViewModel: `Por cobrar` si alguien debe, la búsqueda por nombre o
 * teléfono, los filtros y una fila por cliente con su saldo y, con el programa activo, sus puntos.
 * `Nuevo cliente` abre la hoja sin salir de la lista. Con más de 50 clientes a la vista, la lista
 * lleva encabezados por inicial.
 *
 * @param selectedId el cliente abierto en el panel derecho, en una ventana ancha.
 */
@Composable
fun CustomerListScreen(
    uiState: CustomerListUiState,
    onAction: (CustomerListAction) -> Unit,
    modifier: Modifier = Modifier,
    navigation: TopBarNavigation = TopBarNavigation.BACK,
    selectedId: Long? = null,
) {
    val spacing = AppTheme.spacing
    val sizes = AppTheme.sizes
    val pointsLabel = stringResource(R.string.loyalty_title)
    Box(modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        Column(Modifier.fillMaxSize()) {
            AppTopBar(
                // Abierta desde el cobro, la lista sirve para elegir: el título lo dice.
                title = stringResource(
                    if (uiState.pickForSale) {
                        R.string.customer_picker_title
                    } else {
                        R.string.customer_title
                    },
                ),
                navigation = navigation,
                onNavigate = { onAction(CustomerListAction.Back) },
                navigationLabel = stringResource(R.string.a11y_back),
                actions = {
                    ScreenHelpLink(HelpTopic.CUSTOMERS)
                    OverflowMenu(
                        listOf(
                            OverflowAction(pointsLabel, {
                                onAction(CustomerListAction.OpenLoyalty)
                            }),
                        ),
                    )
                },
            )
            Column(
                Modifier
                    .weight(1f)
                    .align(Alignment.CenterHorizontally)
                    .widthIn(max = sizes.maxContentWidth)
                    .fillMaxWidth()
                    .navigationBarsPadding(),
            ) {
                if (hasCustomers(uiState.content)) Controls(uiState, onAction)
                CustomerListBody(
                    uiState,
                    onAction,
                    PaddingValues(bottom = TonalFabDefaults.listEndPadding),
                    selectedId,
                )
            }
        }
        if (hasCustomers(uiState.content)) {
            TonalFab(
                text = stringResource(R.string.customer_new_action),
                onClick = { onAction(CustomerListAction.NewCustomer()) },
                icon = R.drawable.ic_add,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(spacing.s16)
                    .keepsMessagesAbove(),
            )
        }
    }
}

/** Si ya hay clientes: sin ninguno, la pantalla solo invita a crear el primero. */
private fun hasCustomers(content: CustomerListContent): Boolean = when (content) {
    CustomerListContent.Loading, CustomerListContent.Empty, CustomerListContent.ReadError -> false

    is CustomerListContent.NoResults, CustomerListContent.FilterEmpty,
    is CustomerListContent.Customers,
    -> true
}

/** `Por cobrar`, la búsqueda y los filtros. */
@Composable
private fun Controls(uiState: CustomerListUiState, onAction: (CustomerListAction) -> Unit) {
    val spacing = AppTheme.spacing
    Column(
        Modifier.padding(horizontal = spacing.s16, vertical = spacing.s8),
        verticalArrangement = Arrangement.spacedBy(spacing.s8),
    ) {
        uiState.receivable?.let {
            StatTile(
                label = stringResource(R.string.customer_receivable),
                amount = it,
                variant = StatTileVariant.CARD,
                modifier = Modifier.fillMaxWidth(),
                sensitive = true,
            )
        }
        SearchField(
            query = uiState.query,
            onQueryChange = { onAction(CustomerListAction.QueryChange(it)) },
            placeholder = stringResource(R.string.customer_search_hint),
        )
        CompactChipRow(
            options = CustomerFilter.entries,
            label = { stringResource(filterLabel(it)) },
            selected = setOf(uiState.filter),
            onToggle = { onAction(CustomerListAction.FilterChange(it)) },
            multiple = false,
        )
    }
}

private fun filterLabel(filter: CustomerFilter): Int = when (filter) {
    CustomerFilter.ALL -> R.string.customer_filter_all
    CustomerFilter.RECEIVABLE -> R.string.customer_filter_receivable
    CustomerFilter.CREDIT -> R.string.customer_filter_credit
    CustomerFilter.ARCHIVED -> R.string.customer_filter_archived
}

@Composable
private fun CustomerListBody(
    uiState: CustomerListUiState,
    onAction: (CustomerListAction) -> Unit,
    bottomClearance: PaddingValues,
    selectedId: Long?,
) {
    when (val content = uiState.content) {
        CustomerListContent.Loading -> DelayedSkeleton {
            Column(
                Modifier.padding(AppTheme.spacing.s16),
                verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.s12),
            ) {
                repeat(SKELETON_ROWS) {
                    SkeletonBlock(Modifier.fillMaxWidth().heightIn(min = AppTheme.sizes.listRow))
                }
            }
        }

        CustomerListContent.Empty -> CenteredState {
            EmptyState(
                icon = R.drawable.ic_group,
                title = stringResource(R.string.customer_empty_title),
                body = stringResource(R.string.customer_empty_body),
            ) {
                PrimaryActionButton(
                    text = stringResource(R.string.customer_empty_action),
                    onClick = { onAction(CustomerListAction.NewCustomer()) },
                    variant = ActionVariant.TONAL,
                )
            }
        }

        is CustomerListContent.NoResults -> CenteredState {
            EmptyState(
                icon = R.drawable.ic_search,
                title = stringResource(R.string.customer_search_no_results, content.query),
            ) {
                PrimaryActionButton(
                    text = stringResource(R.string.customer_create_action),
                    onClick = { onAction(CustomerListAction.NewCustomer(content.query)) },
                    variant = ActionVariant.TONAL,
                )
            }
        }

        CustomerListContent.FilterEmpty -> CenteredState {
            EmptyState(
                icon = R.drawable.ic_group,
                title = stringResource(R.string.customer_filter_empty),
            ) {
                PrimaryActionButton(
                    text = stringResource(R.string.action_clear_filters),
                    onClick = { onAction(CustomerListAction.ClearFilters) },
                    variant = ActionVariant.TONAL,
                )
            }
        }

        is CustomerListContent.Customers ->
            CustomerRows(
                content.customers,
                uiState.showPoints,
                bottomClearance,
                selectedId,
                onAction,
            )

        CustomerListContent.ReadError -> CenteredState {
            EmptyState(
                icon = R.drawable.ic_error,
                title = stringResource(R.string.state_error_read),
            ) {
                PrimaryActionButton(
                    text = stringResource(R.string.action_retry),
                    onClick = { onAction(CustomerListAction.Retry) },
                    variant = ActionVariant.TONAL,
                )
            }
        }
    }
}

/** Las filas, perezosas y con su id como clave, y con encabezados por inicial si son muchas. */
@Composable
private fun CustomerRows(
    customers: List<Customer>,
    showPoints: Boolean,
    bottomClearance: PaddingValues,
    selectedId: Long?,
    onAction: (CustomerListAction) -> Unit,
) {
    val anonymous = stringResource(R.string.customer_anonymous)
    val withHeaders = customers.size > INITIALS_FROM
    LazyColumn(Modifier.fillMaxSize(), contentPadding = bottomClearance) {
        customers.forEachIndexed { index, customer ->
            if (withHeaders) {
                val initial = initialOf(customer, anonymous)
                if (index == 0 || initialOf(customers[index - 1], anonymous) != initial) {
                    item(key = "initial-$index", contentType = HEADER) { InitialHeader(initial) }
                }
            }
            item(key = customer.id, contentType = ROW) {
                CustomerRow(
                    data = rowData(customer, showPoints),
                    onClick = { onAction(CustomerListAction.OpenCustomer(customer.id)) },
                    onReactivate = { onAction(CustomerListAction.Reactivate(customer.id)) },
                    selected = customer.id == selectedId,
                )
            }
        }
    }
}

/** La inicial de su nombre, o el texto de anónimo para los que ya no tienen nombre. */
private fun initialOf(customer: Customer, anonymous: String): String =
    customer.contact?.name?.trim()?.firstOrNull()?.uppercase() ?: anonymous

@Composable
private fun InitialHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = AppTheme.spacing.s16, vertical = AppTheme.spacing.s8)
            .semantics { heading() },
    )
}

/** Lo que muestra la fila de [customer]. */
internal fun rowData(customer: Customer, showPoints: Boolean): CustomerRowData = CustomerRowData(
    name = customer.contact?.name,
    balance = customer.balance,
    phone = customer.contact?.phone,
    points = customer.pointsBalance.takeIf { showPoints },
    archived = customer.isArchived,
)

/** Un estado que ocupa la pantalla: centrado y desplazable con letra grande. */
@Composable
private fun CenteredState(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) { content() }
}

/** Desde cuántos clientes a la vista la lista lleva encabezados por inicial. */
private const val INITIALS_FROM = 50
private const val SKELETON_ROWS = 8
private const val HEADER = "header"
private const val ROW = "row"

// ---------------------------------------------------------------- vistas previas

@ThemePreviews
@Composable
private fun CustomerListPreview() {
    PreviewTheme {
        CustomerListScreen(
            CustomerListUiState(
                content = CustomerListContent.Customers(CustomerSamples.list),
                receivable = Money(128_400),
                showPoints = true,
            ),
            onAction = {},
        )
    }
}

@ThemePreviews
@Composable
private fun CustomerListEmptyPreview() {
    PreviewTheme {
        CustomerListScreen(CustomerListUiState(content = CustomerListContent.Empty), {})
    }
}

@ThemePreviews
@Composable
private fun CustomerListDiscreetPreview() {
    PreviewTheme(discreet = true) {
        CustomerListScreen(
            CustomerListUiState(
                content = CustomerListContent.Customers(CustomerSamples.list),
                receivable = Money(128_400),
            ),
            onAction = {},
        )
    }
}
