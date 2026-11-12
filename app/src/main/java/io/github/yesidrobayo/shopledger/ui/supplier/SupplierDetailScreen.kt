package io.github.yesidrobayo.shopledger.ui.supplier

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.model.DocumentStatus
import io.github.yesidrobayo.shopledger.domain.model.catalog.SupplierOverview
import io.github.yesidrobayo.shopledger.domain.model.catalog.SupplierPurchase
import io.github.yesidrobayo.shopledger.ui.components.ActionVariant
import io.github.yesidrobayo.shopledger.ui.components.AppCard
import io.github.yesidrobayo.shopledger.ui.components.AppTopBar
import io.github.yesidrobayo.shopledger.ui.components.ConfirmDialog
import io.github.yesidrobayo.shopledger.ui.components.ConfirmVariant
import io.github.yesidrobayo.shopledger.ui.components.DelayedSkeleton
import io.github.yesidrobayo.shopledger.ui.components.EmptyState
import io.github.yesidrobayo.shopledger.ui.components.EmptyStateInline
import io.github.yesidrobayo.shopledger.ui.components.InlineTextButton
import io.github.yesidrobayo.shopledger.ui.components.MoneySize
import io.github.yesidrobayo.shopledger.ui.components.MoneyText
import io.github.yesidrobayo.shopledger.ui.components.MoneyTone
import io.github.yesidrobayo.shopledger.ui.components.OverflowAction
import io.github.yesidrobayo.shopledger.ui.components.OverflowMenu
import io.github.yesidrobayo.shopledger.ui.components.PrimaryActionButton
import io.github.yesidrobayo.shopledger.ui.components.SectionHeader
import io.github.yesidrobayo.shopledger.ui.components.SkeletonBlock
import io.github.yesidrobayo.shopledger.ui.components.StatusTag
import io.github.yesidrobayo.shopledger.ui.components.TopBarAction
import io.github.yesidrobayo.shopledger.ui.components.TopBarNavigation
import io.github.yesidrobayo.shopledger.ui.components.keepsMessagesAbove
import io.github.yesidrobayo.shopledger.ui.format.LocalAppFormats
import io.github.yesidrobayo.shopledger.ui.theme.AppTheme
import io.github.yesidrobayo.shopledger.ui.theme.PreviewTheme
import io.github.yesidrobayo.shopledger.ui.theme.ThemePreviews

/**
 * El detalle de un proveedor, sin ViewModel: sus datos de contacto, el total comprado, los productos
 * que lo tienen como habitual y sus compras, con las anuladas marcadas. Tocar el teléfono abre el
 * marcador del sistema. La acción fija es `Nueva compra`, salvo si está archivado.
 *
 * El total comprado es una cifra del negocio: con el modo discreto se oculta.
 *
 * @param navigation `BACK` cuando ocupa la pantalla; `NONE` cuando va como panel junto a la lista.
 */
@Composable
fun SupplierDetailScreen(
    uiState: SupplierDetailUiState,
    onAction: (SupplierDetailAction) -> Unit,
    modifier: Modifier = Modifier,
    navigation: TopBarNavigation = TopBarNavigation.BACK,
) {
    val ready = uiState.content as? SupplierDetailContent.Ready
    Column(modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        AppTopBar(
            title =
                ready?.overview?.supplier?.details?.name
                    ?: stringResource(R.string.product_tab_suppliers),
            navigation = navigation,
            onNavigate = { onAction(SupplierDetailAction.Back) },
            navigationLabel =
                when (navigation) {
                    TopBarNavigation.NONE -> null
                    TopBarNavigation.BACK -> stringResource(R.string.a11y_back)
                    TopBarNavigation.CLOSE -> stringResource(R.string.a11y_close)
                },
            titleStyle = MaterialTheme.typography.titleLarge,
        ) {
            if (ready != null) TopBarActions(ready.overview, onAction)
        }
        Box(
            Modifier
                .weight(1f)
                .align(Alignment.CenterHorizontally)
                .widthIn(max = AppTheme.sizes.maxContentWidth)
                .fillMaxWidth(),
        ) {
            when (val content = uiState.content) {
                SupplierDetailContent.Loading -> DelayedSkeleton {
                    Column(
                        Modifier.padding(AppTheme.spacing.s16),
                        verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.s12),
                    ) {
                        repeat(SKELETON_ROWS) {
                            SkeletonBlock(
                                Modifier.fillMaxWidth().heightIn(min = AppTheme.sizes.listRow),
                            )
                        }
                    }
                }

                SupplierDetailContent.Missing, SupplierDetailContent.ReadError ->
                    EmptyState(
                        icon = R.drawable.ic_error,
                        title = stringResource(R.string.state_error_read),
                    ) {
                        if (content == SupplierDetailContent.ReadError) {
                            PrimaryActionButton(
                                text = stringResource(R.string.action_retry),
                                onClick = { onAction(SupplierDetailAction.Retry) },
                                variant = ActionVariant.TONAL,
                            )
                        }
                    }

                is SupplierDetailContent.Ready -> DetailBody(content.overview, onAction)
            }
        }
        val overview = ready?.overview
        if (overview != null && !overview.supplier.isArchived) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .keepsMessagesAbove()
                    .navigationBarsPadding()
                    .padding(AppTheme.spacing.s16),
                contentAlignment = Alignment.Center,
            ) {
                PrimaryActionButton(
                    text = stringResource(R.string.purchase_new_title),
                    onClick = { onAction(SupplierDetailAction.NewPurchase) },
                    variant = ActionVariant.PRIMARY,
                    modifier = Modifier.widthIn(
                        max = AppTheme.sizes.maxContentWidth,
                    ).fillMaxWidth(),
                )
            }
        }
    }
    uiState.dialog?.let { SupplierConfirm(it, onAction) }
}

/** `Editar` siempre, y en el menú archivar con historia o eliminar sin ella. */
@Composable
private fun TopBarActions(overview: SupplierOverview, onAction: (SupplierDetailAction) -> Unit) {
    TopBarAction(R.drawable.ic_edit, stringResource(R.string.action_edit)) {
        onAction(SupplierDetailAction.Edit)
    }
    if (overview.supplier.isArchived) return
    val action =
        if (overview.totals.hasHistory) {
            OverflowAction(stringResource(R.string.action_archive), {
                onAction(SupplierDetailAction.AskArchive)
            })
        } else {
            OverflowAction(
                stringResource(R.string.action_delete),
                { onAction(SupplierDetailAction.AskDelete) },
                destructive = true,
            )
        }
    OverflowMenu(listOf(action))
}

@Composable
private fun DetailBody(overview: SupplierOverview, onAction: (SupplierDetailAction) -> Unit) {
    val spacing = AppTheme.spacing
    val details = overview.supplier.details
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = spacing.s16),
    ) {
        if (overview.supplier.isArchived) {
            item(key = "archived") {
                Row(
                    Modifier.fillMaxWidth().padding(
                        horizontal = spacing.s16,
                        vertical = spacing.s8,
                    ),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(spacing.s12),
                ) {
                    StatusTag(stringResource(R.string.status_archived))
                    InlineTextButton(stringResource(R.string.action_reactivate), {
                        onAction(SupplierDetailAction.Reactivate)
                    })
                }
            }
        }
        details.phone?.let { phone ->
            item(key = "phone") { PhoneRow(phone) { onAction(SupplierDetailAction.Dial(phone)) } }
        }
        details.email?.let { email ->
            item(key = "email") { ContactText(stringResource(R.string.form_email), email) }
        }
        details.notes?.let { notes ->
            item(key = "notes") { ContactText(stringResource(R.string.form_notes), notes) }
        }
        item(key = "total") {
            AppCard(
                Modifier.fillMaxWidth().padding(horizontal = spacing.s16, vertical = spacing.s8),
            ) {
                Text(
                    stringResource(R.string.purchase_total_bought),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                MoneyText(overview.totals.activeTotal, MoneySize.TITLE, sensitive = true)
            }
        }
        if (overview.usualProducts.isNotEmpty()) {
            item(key = "products-header") {
                SectionHeader(stringResource(R.string.supplier_usual_products))
            }
            items(overview.usualProducts, key = { "product-${it.id}" }) { product ->
                LinkRow(product.name) { onAction(SupplierDetailAction.OpenProduct(product.id)) }
            }
        }
        item(key = "purchases-header") {
            SectionHeader(stringResource(R.string.records_tab_purchases))
        }
        if (overview.purchases.isEmpty()) {
            item(key = "no-purchases") {
                EmptyStateInline(
                    title = stringResource(R.string.supplier_no_purchases),
                    modifier = Modifier.padding(horizontal = spacing.s16),
                )
            }
        } else {
            items(overview.purchases, key = { "purchase-${it.id}" }) { purchase ->
                PurchaseRow(purchase) { onAction(SupplierDetailAction.OpenPurchase(purchase.id)) }
            }
        }
    }
}

/** El teléfono, que al tocarlo abre el marcador del sistema. Se lee como un botón. */
@Composable
private fun PhoneRow(phone: String, onDial: () -> Unit) {
    val spacing = AppTheme.spacing
    val reading = stringResource(R.string.supplier_dial_a11y, phone)
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onDial)
            .heightIn(min = AppTheme.sizes.listRowSingle)
            .padding(horizontal = spacing.s16, vertical = spacing.s8)
            .clearAndSetSemantics {
                contentDescription = reading
                role = Role.Button
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.s12),
    ) {
        Icon(
            painterResource(R.drawable.ic_call),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(AppTheme.sizes.icon),
        )
        Column {
            Text(
                stringResource(R.string.form_phone),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                phone,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

/** Un dato de contacto con su rótulo encima. Las notas se ven enteras. */
@Composable
private fun ContactText(label: String, value: String) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = AppTheme.spacing.s16, vertical = AppTheme.spacing.s8)
            .semantics(mergeDescendants = true) {},
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** Una fila que lleva a otra pantalla: su texto y la flecha. */
@Composable
private fun LinkRow(text: String, onClick: () -> Unit) {
    val spacing = AppTheme.spacing
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .heightIn(min = AppTheme.sizes.listRowSingle)
            .padding(horizontal = spacing.s16, vertical = spacing.s8),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Icon(
            painterResource(R.drawable.ic_chevron_right),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Una compra: su fecha, su referencia si la tiene, y el total a la derecha. Anulada, va apagada, y
 * el monto tachado lleva su propia etiqueta: no suma al total comprado.
 */
@Composable
private fun PurchaseRow(purchase: SupplierPurchase, onClick: () -> Unit) {
    val spacing = AppTheme.spacing
    val formats = LocalAppFormats.current
    val voided = purchase.status == DocumentStatus.VOIDED
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(Modifier.clickable(role = Role.Button, onClick = onClick)) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = AppTheme.sizes.listRow)
                .padding(horizontal = spacing.s16, vertical = spacing.s12),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.s12),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(spacing.s2)) {
                Text(
                    formats.date.dayMonthYear(purchase.businessDate),
                    style = MaterialTheme.typography.titleSmall,
                    color = if (voided) muted else MaterialTheme.colorScheme.onSurface,
                )
                purchase.invoiceReference?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = muted)
                }
            }
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(spacing.s4),
            ) {
                // Lo comprado a un proveedor dice cuánto cuesta lo que se vende: se oculta.
                MoneyText(
                    purchase.total,
                    MoneySize.ROW,
                    tone = if (voided) MoneyTone.MUTED else MoneyTone.DEFAULT,
                    voided = voided,
                    sensitive = true,
                )
            }
        }
        HorizontalDivider(
            modifier = Modifier.padding(start = spacing.s16),
            thickness = AppTheme.sizes.hairline,
            color = MaterialTheme.colorScheme.outlineVariant,
        )
    }
}

/** La confirmación de archivar o eliminar, con el nombre del proveedor en el título. */
@Composable
private fun SupplierConfirm(dialog: SupplierDialog, onAction: (SupplierDetailAction) -> Unit) {
    when (dialog) {
        is SupplierDialog.Archive ->
            ConfirmDialog(
                title = stringResource(R.string.dialog_archive_supplier_title, dialog.name),
                body = stringResource(R.string.dialog_archive_supplier_body),
                confirmLabel = stringResource(R.string.dialog_archive_supplier_action),
                onConfirm = { onAction(SupplierDetailAction.ConfirmDialog) },
                onDismiss = { onAction(SupplierDetailAction.DismissDialog) },
            )

        is SupplierDialog.Delete ->
            ConfirmDialog(
                title = stringResource(R.string.dialog_delete_supplier_title, dialog.name),
                body = stringResource(R.string.dialog_delete_supplier_body),
                confirmLabel = stringResource(R.string.dialog_delete_supplier_action),
                onConfirm = { onAction(SupplierDetailAction.ConfirmDialog) },
                onDismiss = { onAction(SupplierDetailAction.DismissDialog) },
                variant = ConfirmVariant.DESTRUCTIVE,
            )
    }
}

private const val SKELETON_ROWS = 6

// ---------------------------------------------------------------- vistas previas

@ThemePreviews
@Composable
private fun SupplierDetailPreview() {
    PreviewTheme {
        SupplierDetailScreen(
            SupplierDetailUiState(SupplierDetailContent.Ready(SupplierSamples.overview())),
            {},
        )
    }
}

@ThemePreviews
@Composable
private fun SupplierDetailArchivedPreview() {
    PreviewTheme(discreet = true) {
        SupplierDetailScreen(
            SupplierDetailUiState(
                SupplierDetailContent.Ready(
                    SupplierSamples.overview(archived = true, purchases = false),
                ),
            ),
            {},
        )
    }
}

@Preview(name = "Cargando")
@Composable
private fun SupplierDetailLoadingPreview() {
    PreviewTheme { SupplierDetailScreen(SupplierDetailUiState(), {}) }
}
