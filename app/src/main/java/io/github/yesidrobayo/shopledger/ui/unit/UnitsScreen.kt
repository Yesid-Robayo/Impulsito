package io.github.yesidrobayo.shopledger.ui.unit

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.unit.dp
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.catalog.ConversionEntry
import io.github.yesidrobayo.shopledger.domain.model.catalog.MeasureUnit
import io.github.yesidrobayo.shopledger.domain.model.catalog.SystemRow
import io.github.yesidrobayo.shopledger.domain.model.catalog.UnitKind
import io.github.yesidrobayo.shopledger.format.SpelledQuantityFormatter
import io.github.yesidrobayo.shopledger.ui.components.ActionVariant
import io.github.yesidrobayo.shopledger.ui.components.AppSnackbarHost
import io.github.yesidrobayo.shopledger.ui.components.AppTopBar
import io.github.yesidrobayo.shopledger.ui.components.ConfirmDialog
import io.github.yesidrobayo.shopledger.ui.components.ConfirmVariant
import io.github.yesidrobayo.shopledger.ui.components.DelayedSkeleton
import io.github.yesidrobayo.shopledger.ui.components.EmptyState
import io.github.yesidrobayo.shopledger.ui.components.InlineTextButton
import io.github.yesidrobayo.shopledger.ui.components.OverflowAction
import io.github.yesidrobayo.shopledger.ui.components.OverflowMenu
import io.github.yesidrobayo.shopledger.ui.components.PrimaryActionButton
import io.github.yesidrobayo.shopledger.ui.components.SectionHeader
import io.github.yesidrobayo.shopledger.ui.components.SegmentedTabs
import io.github.yesidrobayo.shopledger.ui.components.SkeletonBlock
import io.github.yesidrobayo.shopledger.ui.components.TonalFab
import io.github.yesidrobayo.shopledger.ui.components.TonalFabDefaults
import io.github.yesidrobayo.shopledger.ui.components.TopBarNavigation
import io.github.yesidrobayo.shopledger.ui.components.UnitConversionRow
import io.github.yesidrobayo.shopledger.ui.components.conversionPhrase
import io.github.yesidrobayo.shopledger.ui.components.saveErrorText
import io.github.yesidrobayo.shopledger.ui.components.unitKindLabel
import io.github.yesidrobayo.shopledger.ui.components.unitName
import io.github.yesidrobayo.shopledger.ui.components.unitSymbol
import io.github.yesidrobayo.shopledger.ui.format.LocalAppFormats
import io.github.yesidrobayo.shopledger.ui.help.HelpTopic
import io.github.yesidrobayo.shopledger.ui.help.ScreenHelpLink
import io.github.yesidrobayo.shopledger.ui.theme.AppTheme

/** Desde este ancho la ventana es `Expanded`: la lista va a la izquierda y la hoja como panel. */
internal val EXPANDED_WIDTH = 840.dp

/**
 * La pantalla de unidades y conversiones, sin ViewModel: dibuja [uiState] y las hojas abiertas, y
 * avisa cada acción.
 *
 * En una ventana `Expanded`, la lista va a la izquierda y la hoja abierta como panel a la derecha; en
 * las demás, la lista ocupa una columna de lectura y la hoja va encima. El botón flotante cambia con
 * el segmento.
 */
@Composable
fun UnitsScreen(
    uiState: UnitsUiState,
    onAction: (UnitsAction) -> Unit,
    modifier: Modifier = Modifier,
    unitEditor: UnitEditor? = null,
    onUnitEditorAction: (UnitEditorAction) -> Unit = {},
    conversionEditor: ConversionEditor? = null,
    onConversionEditorAction: (ConversionEditorAction) -> Unit = {},
) {
    BoxWithConstraints(modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        val expanded = maxWidth >= EXPANDED_WIDTH
        val panelOpen = expanded && (unitEditor != null || conversionEditor != null)
        Row(Modifier.fillMaxSize()) {
            UnitsList(uiState, onAction, Modifier.weight(1f))
            if (panelOpen) {
                Box(Modifier.width(AppTheme.sizes.sideSheet).fillMaxHeight()) {
                    Editors(
                        uiState,
                        unitEditor,
                        onUnitEditorAction,
                        conversionEditor,
                        onConversionEditorAction,
                        true,
                    )
                }
            }
        }
        if (!expanded) {
            Editors(
                uiState,
                unitEditor,
                onUnitEditorAction,
                conversionEditor,
                onConversionEditorAction,
                false,
            )
        }
    }
    uiState.dialog?.let { UnitDialog(it, onAction) }
}

@Composable
private fun Editors(
    uiState: UnitsUiState,
    unitEditor: UnitEditor?,
    onUnitEditorAction: (UnitEditorAction) -> Unit,
    conversionEditor: ConversionEditor?,
    onConversionEditorAction: (ConversionEditorAction) -> Unit,
    asPanel: Boolean,
) {
    unitEditor?.let { UnitEditorSheet(it, onUnitEditorAction, asPanel) }
    conversionEditor?.let {
        ConversionEditorSheet(
            it,
            uiState.products,
            uiState.allUnits,
            onConversionEditorAction,
            asPanel,
        )
    }
}

@Composable
private fun UnitsList(uiState: UnitsUiState, onAction: (UnitsAction) -> Unit, modifier: Modifier) {
    val snackbar = remember { SnackbarHostState() }
    Box(modifier.fillMaxHeight()) {
        Column(Modifier.fillMaxSize()) {
            AppTopBar(
                title = stringResource(R.string.unit_title),
                navigation = TopBarNavigation.BACK,
                onNavigate = { onAction(UnitsAction.Back) },
                navigationLabel = stringResource(R.string.a11y_back),
                titleStyle = MaterialTheme.typography.titleLarge,
                actions = { ScreenHelpLink(HelpTopic.UNITS) },
            )
            Column(
                Modifier
                    .align(Alignment.CenterHorizontally)
                    .widthIn(max = AppTheme.sizes.maxContentWidth)
                    .fillMaxWidth(),
            ) {
                SegmentedTabs(
                    options = UnitsTab.entries.map { stringResource(tabLabel(it)) },
                    selectedIndex = UnitsTab.entries.indexOf(uiState.tab),
                    onSelect = { onAction(UnitsAction.SelectTab(UnitsTab.entries[it])) },
                    modifier = Modifier.padding(
                        horizontal = AppTheme.spacing.s16,
                        vertical = AppTheme.spacing.s8,
                    ),
                )
                when {
                    uiState.loading -> LoadingRows()
                    uiState.readFailed -> ReadFailed(onAction)
                    uiState.tab == UnitsTab.UNITS -> UnitsTabContent(uiState, onAction)
                    else -> ConversionsTabContent(uiState, onAction)
                }
            }
        }
        if (!uiState.loading && !uiState.readFailed) {
            val (text, action) =
                when (uiState.tab) {
                    UnitsTab.UNITS -> R.string.unit_new_action to UnitsAction.NewUnit

                    UnitsTab.CONVERSIONS ->
                        R.string.unit_add_conversion_action to
                            UnitsAction.NewConversion
                }
            TonalFab(
                text = stringResource(text),
                onClick = { onAction(action) },
                modifier = Modifier.align(Alignment.BottomEnd),
            )
        }
        AppSnackbarHost(
            snackbar,
            Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = TonalFabDefaults.listEndPadding),
        )
    }
    val message = uiState.message?.let { messageText(it) }
    LaunchedEffect(uiState.message) {
        if (message != null) {
            snackbar.showSnackbar(message)
            onAction(UnitsAction.MessageShown)
        }
    }
}

@Composable
private fun LoadingRows() {
    DelayedSkeleton {
        Column(
            Modifier.padding(AppTheme.spacing.s16),
            verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.s12),
        ) {
            repeat(SKELETON_ROWS) {
                SkeletonBlock(Modifier.fillMaxWidth().heightIn(min = AppTheme.sizes.listRowSingle))
            }
        }
    }
}

@Composable
private fun UnitsTabContent(uiState: UnitsUiState, onAction: (UnitsAction) -> Unit) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = TonalFabDefaults.listEndPadding),
    ) {
        for (kind in UnitKind.entries) {
            val ofKind = uiState.active.filter { it.kind == kind }
            if (ofKind.isEmpty()) continue
            item(key = "kind-${kind.name}") { SectionHeader(stringResource(unitKindLabel(kind))) }
            unitRows(ofKind, archived = false, uiState.inUse, onAction)
        }
        if (uiState.archived.isNotEmpty()) {
            item(key = "archived-header") {
                SectionHeader(stringResource(R.string.unit_group_archived))
            }
            unitRows(uiState.archived, archived = true, uiState.inUse, onAction)
        }
    }
}

private fun LazyListScope.unitRows(
    units: List<MeasureUnit>,
    archived: Boolean,
    inUse: Set<Long>,
    onAction: (UnitsAction) -> Unit,
) {
    items(units, key = { "unit-${it.id}" }) { unit ->
        val actions =
            when {
                archived ->
                    listOf(
                        OverflowAction(stringResource(R.string.action_reactivate), {
                            onAction(UnitsAction.Reactivate(unit.id))
                        }),
                    )

                else -> unitActions(unit, unit.id in inUse, onAction)
            }
        UnitRow(
            unit,
            onClick = if (archived) {
                null
            } else {
                (
                    {
                        onAction(UnitsAction.EditUnit(unit.id))
                    }
                    )
            },
            actions,
        )
    }
}

/** Editar siempre; archivar con uso o eliminar sin él. La de conteo inicial solo se edita. */
@Composable
private fun unitActions(
    unit: MeasureUnit,
    inUse: Boolean,
    onAction: (UnitsAction) -> Unit,
): List<OverflowAction> {
    val edit =
        OverflowAction(stringResource(R.string.action_edit), {
            onAction(UnitsAction.EditUnit(unit.id))
        })
    val archive =
        OverflowAction(stringResource(R.string.action_archive), {
            onAction(UnitsAction.AskArchive(unit.id))
        })
    val delete =
        OverflowAction(
            stringResource(R.string.action_delete),
            { onAction(UnitsAction.AskDelete(unit.id)) },
            destructive = true,
        )
    return when {
        unit.systemKey == SystemRow.DEFAULT_UNIT.systemKey -> listOf(edit)
        inUse -> listOf(edit, archive)
        else -> listOf(edit, archive, delete)
    }
}

/**
 * Una unidad: su nombre y, debajo, si admite decimales; su símbolo a la derecha. Un solo foco, que
 * se lee entero, y su menú aparte. Tocar una vigente abre su hoja.
 */
@Composable
private fun UnitRow(unit: MeasureUnit, onClick: (() -> Unit)?, actions: List<OverflowAction>) {
    val spacing = AppTheme.spacing
    val name = unitName(unit)
    val symbol = unitSymbol(unit)
    val fraction =
        stringResource(
            if (unit.allowsFraction) R.string.unit_allows_fraction else R.string.unit_whole_only,
        )
    val reading = listOfNotNull(name, symbol?.takeIf { it != name }, fraction).joinToString()
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = AppTheme.sizes.listRowSingle),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val content =
            if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier
        Row(
            modifier =
                Modifier
                    .weight(1f)
                    .heightIn(min = AppTheme.sizes.listRowSingle)
                    .then(content)
                    .clearAndSetSemantics {
                        contentDescription = reading
                        if (onClick != null) role = Role.Button
                    }
                    .padding(start = spacing.s16, top = spacing.s8, bottom = spacing.s8),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.s12),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(spacing.s2)) {
                Text(
                    name,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    fraction,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (symbol != null && symbol != name) {
                Text(
                    symbol,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        OverflowMenu(actions, Modifier.padding(end = spacing.s4))
    }
}

@Composable
private fun ConversionsTabContent(uiState: UnitsUiState, onAction: (UnitsAction) -> Unit) {
    if (uiState.conversions.isEmpty()) {
        EmptyState(
            icon = R.drawable.ic_inventory_2,
            title = stringResource(R.string.unit_empty_title),
            // Con letra grande, el texto y el botón no se cortan.
            modifier = Modifier.verticalScroll(rememberScrollState()),
            body = stringResource(R.string.unit_empty_body),
        ) {
            PrimaryActionButton(
                text = stringResource(R.string.unit_add_conversion_action),
                onClick = { onAction(UnitsAction.NewConversion) },
                variant = ActionVariant.TONAL,
            )
        }
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = TonalFabDefaults.listEndPadding),
    ) {
        items(uiState.conversions, key = { "conversion-${it.conversion.id}" }) { entry ->
            ConversionRow(
                entry = entry,
                units = uiState.allUnits,
                withProduct = true,
                onClick = { onAction(UnitsAction.EditConversion(entry.conversion.id)) },
            )
        }
    }
}

/**
 * Una conversión de la lista, como frase y con dónde se ofrece. [withProduct] dice el producto en la
 * primera línea, en la lista de todos; dentro del producto mismo no hace falta.
 */
@Composable
internal fun ConversionRow(
    entry: ConversionEntry,
    units: List<MeasureUnit>,
    withProduct: Boolean,
    onClick: () -> Unit,
    inset: Boolean = true,
) {
    val details = entry.conversion.details
    val unit = units.firstOrNull { it.id == details.unitId } ?: return
    val base = units.firstOrNull { it.id == entry.product.details.baseUnitId } ?: return
    val factor = QuantityMilli(details.factorMilli)
    val uses =
        listOfNotNull(
            stringResource(R.string.unit_for_purchase).takeIf { details.forPurchase },
            stringResource(R.string.unit_for_sale).takeIf { details.forSale },
        )
    // Sin artículo, para no darle género a la unidad: «Arroz: 24 unidades por caja». El nombre va en
    // minúsculas del idioma de la interfaz, como dentro de una frase.
    val uiLocale = LocalAppFormats.current.uiLocale
    val reading =
        stringResource(
            R.string.a11y_unit_conversion,
            entry.product.details.name,
            spokenQuantity(factor, base),
            unitName(unit).lowercase(uiLocale),
        ) + uses.joinToString(prefix = if (uses.isEmpty()) "" else ", ")
    UnitConversionRow(
        phrase = conversionPhrase(unit, base, factor),
        uses = uses,
        reading = reading,
        onClick = onClick,
        title = if (withProduct) entry.product.details.name else null,
        inset = inset,
    )
}

/** Una cantidad para TalkBack: con el plural de una unidad inicial, o con su nombre sin declinar. */
@Composable
internal fun spokenQuantity(quantity: QuantityMilli, unit: MeasureUnit): String {
    val resources = LocalResources.current
    val formats = LocalAppFormats.current
    val spelled =
        remember(formats) { SpelledQuantityFormatter(formats.quantity.formatLocale) }
            .format(
                resources,
                unit.systemKey,
                unit.name,
                quantity,
                unit.allowsFraction || !quantity.isWholeUnits,
            )
    return spelled
        ?: stringResource(
            R.string.format_custom_unit_quantity,
            formats.quantity.format(quantity, unit.allowsFraction || !quantity.isWholeUnits),
            unitName(unit),
        )
}

@Composable
private fun UnitDialog(dialog: UnitsDialog, onAction: (UnitsAction) -> Unit) {
    val confirm = { onAction(UnitsAction.ConfirmDialog) }
    val dismiss = { onAction(UnitsAction.DismissDialog) }
    when (dialog) {
        is UnitsDialog.Archive ->
            ConfirmDialog(
                title = stringResource(R.string.unit_archive_confirm_title, dialog.name),
                body = stringResource(R.string.unit_archive_confirm_body),
                confirmLabel = stringResource(R.string.unit_archive_confirm_action),
                onConfirm = confirm,
                onDismiss = dismiss,
            )

        is UnitsDialog.Delete ->
            ConfirmDialog(
                title = stringResource(R.string.unit_delete_confirm_title, dialog.name),
                body = stringResource(R.string.unit_delete_confirm_body),
                confirmLabel = stringResource(R.string.unit_delete_confirm_action),
                onConfirm = confirm,
                onDismiss = dismiss,
                variant = ConfirmVariant.DESTRUCTIVE,
            )
    }
}

private fun tabLabel(tab: UnitsTab): Int = when (tab) {
    UnitsTab.UNITS -> R.string.unit_tab_units
    UnitsTab.CONVERSIONS -> R.string.unit_tab_conversions
}

@Composable
private fun messageText(message: UnitsMessage): String = when (message) {
    UnitsMessage.UnitSaved -> stringResource(R.string.unit_saved)
    UnitsMessage.ConversionSaved -> stringResource(R.string.unit_conversion_saved)
    UnitsMessage.NameTaken -> stringResource(R.string.unit_error_name_repeated)
    is UnitsMessage.Failed -> stringResource(saveErrorText(message.outOfSpace))
}

private const val SKELETON_ROWS = 6

/** La lectura falló: se dice, y `Reintentar` vuelve a leer. Nada de lo guardado se tocó. */
@Composable
private fun ReadFailed(onAction: (UnitsAction) -> Unit) {
    EmptyState(
        icon = R.drawable.ic_error,
        title = stringResource(R.string.state_error_read),
        modifier = Modifier.verticalScroll(rememberScrollState()),
    ) {
        InlineTextButton(stringResource(R.string.action_retry), { onAction(UnitsAction.Retry) })
    }
}
