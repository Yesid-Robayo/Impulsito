package io.github.yesidrobayo.shopledger.ui.settings

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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.settings.TaxMode
import io.github.yesidrobayo.shopledger.domain.usecase.tax.ArchiveTaxRateUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.tax.ChangeTaxSettingsUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.tax.CreateTaxRateUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.tax.DeleteTaxRateUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.tax.EditTaxRateUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.tax.ObserveTaxSetupUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.tax.ReactivateTaxRateUseCase
import io.github.yesidrobayo.shopledger.navigation.ScreenModel
import io.github.yesidrobayo.shopledger.navigation.rememberAppViewModel
import io.github.yesidrobayo.shopledger.ui.components.ActionState
import io.github.yesidrobayo.shopledger.ui.components.ActionVariant
import io.github.yesidrobayo.shopledger.ui.components.AppBottomSheet
import io.github.yesidrobayo.shopledger.ui.components.AppSnackbarHost
import io.github.yesidrobayo.shopledger.ui.components.AppTextField
import io.github.yesidrobayo.shopledger.ui.components.AppTopBar
import io.github.yesidrobayo.shopledger.ui.components.ConfirmDialog
import io.github.yesidrobayo.shopledger.ui.components.ConfirmVariant
import io.github.yesidrobayo.shopledger.ui.components.DelayedSkeleton
import io.github.yesidrobayo.shopledger.ui.components.EmptyState
import io.github.yesidrobayo.shopledger.ui.components.NoticeCard
import io.github.yesidrobayo.shopledger.ui.components.NoticeVariant
import io.github.yesidrobayo.shopledger.ui.components.OverflowAction
import io.github.yesidrobayo.shopledger.ui.components.OverflowMenu
import io.github.yesidrobayo.shopledger.ui.components.PercentField
import io.github.yesidrobayo.shopledger.ui.components.PreviewSamples
import io.github.yesidrobayo.shopledger.ui.components.PrimaryActionButton
import io.github.yesidrobayo.shopledger.ui.components.SectionHeader
import io.github.yesidrobayo.shopledger.ui.components.SegmentedTabs
import io.github.yesidrobayo.shopledger.ui.components.SettingsRow
import io.github.yesidrobayo.shopledger.ui.components.SettingsRowAction
import io.github.yesidrobayo.shopledger.ui.components.SkeletonBlock
import io.github.yesidrobayo.shopledger.ui.components.StatusTag
import io.github.yesidrobayo.shopledger.ui.components.TonalFab
import io.github.yesidrobayo.shopledger.ui.components.TonalFabDefaults
import io.github.yesidrobayo.shopledger.ui.components.TopBarNavigation
import io.github.yesidrobayo.shopledger.ui.components.saveErrorText
import io.github.yesidrobayo.shopledger.ui.format.LocalAppFormats
import io.github.yesidrobayo.shopledger.ui.theme.AppTheme
import io.github.yesidrobayo.shopledger.ui.theme.PreviewTheme
import io.github.yesidrobayo.shopledger.ui.theme.ThemePreviews

/**
 * La pantalla de impuestos: cómo se cobra, las tasas que escribió la persona y la tasa por defecto.
 *
 * Arma su ViewModel con los puertos de la base. Si la base no abre, muestra [unavailable], lo que
 * da la navegación para ese caso.
 */
@Composable
fun TaxSettingsRoute(onBack: () -> Unit, unavailable: @Composable () -> Unit) {
    val model =
        rememberAppViewModel { deps ->
            val ports = deps.ports
            val clock = deps.container.clock
            val tx = ports.transactions
            TaxSettingsViewModel(
                TaxSettingsUseCases(
                    observe = ObserveTaxSetupUseCase(ports.taxRates, ports.taxSettings),
                    create = CreateTaxRateUseCase(tx, ports.taxRates, ports.taxSettings, clock),
                    edit = EditTaxRateUseCase(tx, ports.taxRates, ports.taxSettings, clock),
                    archive = ArchiveTaxRateUseCase(tx, ports.taxRates, ports.taxSettings, clock),
                    reactivate = ReactivateTaxRateUseCase(tx, ports.taxRates, clock),
                    delete = DeleteTaxRateUseCase(tx, ports.taxRates, ports.taxSettings),
                    change = ChangeTaxSettingsUseCase(tx, ports.taxSettings, clock),
                ),
            )
        }
    when (model) {
        ScreenModel.Opening -> Unit

        ScreenModel.Unavailable -> unavailable()

        is ScreenModel.Ready -> {
            val viewModel = model.viewModel
            val uiState by viewModel.uiState.collectAsStateWithLifecycle()
            TaxSettingsScreen(
                uiState = uiState,
                onAction = { action ->
                    if (action == TaxSettingsAction.Back) onBack() else viewModel.onAction(action)
                },
            )
        }
    }
}

/** La pantalla de impuestos, sin ViewModel: dibuja [uiState] y avisa cada acción. */
@Composable
fun TaxSettingsScreen(
    uiState: TaxSettingsUiState,
    onAction: (TaxSettingsAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val snackbar = remember { SnackbarHostState() }
    val ready = uiState as? TaxSettingsUiState.Ready
    val showsFab = ready != null && ready.mode != TaxMode.NONE
    Box(modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).statusBarsPadding()) {
        Column(Modifier.fillMaxSize()) {
            AppTopBar(
                title = stringResource(R.string.tax_title),
                navigation = TopBarNavigation.BACK,
                onNavigate = { onAction(TaxSettingsAction.Back) },
                navigationLabel = stringResource(R.string.a11y_back),
            )
            when (uiState) {
                TaxSettingsUiState.Loading -> LoadingRows()
                is TaxSettingsUiState.Ready -> TaxContent(uiState, onAction)
            }
        }
        if (showsFab) {
            TonalFab(
                text = stringResource(R.string.tax_rate_new_action),
                onClick = { onAction(TaxSettingsAction.NewRate) },
                modifier = Modifier.align(Alignment.BottomEnd),
            )
        }
        // El aviso sale por encima del botón flotante, como en cualquier lista: tapado, el botón
        // no se podría tocar mientras se ve el aviso.
        val fabSpace = if (showsFab) TonalFabDefaults.listEndPadding else AppTheme.spacing.s16
        AppSnackbarHost(
            snackbar,
            Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = fabSpace),
        )
    }
    if (ready != null) {
        ready.editor?.let { TaxRateSheet(it, onAction) }
        ready.dialog?.let { TaxConfirmDialog(it, onAction) }
        val message = ready.message?.let { stringResource(messageText(it)) }
        LaunchedEffect(message) {
            if (message != null) {
                snackbar.showSnackbar(message)
                onAction(TaxSettingsAction.MessageShown)
            }
        }
    }
}

@Composable
private fun LoadingRows() {
    // El modo es local y llega enseguida: el esqueleto solo aparece si la lectura se demora.
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
private fun TaxContent(state: TaxSettingsUiState.Ready, onAction: (TaxSettingsAction) -> Unit) {
    val symbols = LocalAppFormats.current.percent.inputSymbols
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().widthIn(max = AppTheme.sizes.maxContentWidth),
            contentPadding = PaddingValues(bottom = TonalFabDefaults.listEndPadding),
        ) {
            modeSection(state.mode, onAction)
            if (state.mode != TaxMode.NONE) {
                ratesSection(state, onAction) { id ->
                    onAction(TaxSettingsAction.EditRate(id, symbols))
                }
            }
        }
    }
}

private fun LazyListScope.modeSection(mode: TaxMode, onAction: (TaxSettingsAction) -> Unit) {
    item(key = "mode-header") { SectionHeader(stringResource(R.string.tax_section_mode)) }
    item(key = "mode") {
        Column(
            Modifier.padding(horizontal = AppTheme.spacing.s16),
            verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.s8),
        ) {
            SegmentedTabs(
                options = TaxMode.entries.map { stringResource(modeLabel(it)) },
                selectedIndex = TaxMode.entries.indexOf(mode),
                onSelect = { onAction(TaxSettingsAction.SelectMode(TaxMode.entries[it])) },
            )
            Text(
                text = stringResource(modeHelp(mode)),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun LazyListScope.ratesSection(
    state: TaxSettingsUiState.Ready,
    onAction: (TaxSettingsAction) -> Unit,
    onEdit: (Long) -> Unit,
) {
    if (state.missingDefaultRate) {
        item(key = "missing-default") {
            NoticeCard(
                text = stringResource(R.string.tax_missing_rate_warning),
                variant = NoticeVariant.WARNING,
                icon = R.drawable.ic_warning,
                modifier = Modifier.padding(AppTheme.spacing.s16),
            )
        }
    }
    item(key = "rates-header") { SectionHeader(stringResource(R.string.tax_section_rates)) }
    if (state.active.isEmpty()) {
        item(key = "empty") {
            EmptyState(
                icon = R.drawable.ic_receipt_long,
                title = stringResource(R.string.tax_empty_title),
                body = stringResource(R.string.tax_empty_body, stringResource(R.string.app_name)),
            ) {
                PrimaryActionButton(
                    text = stringResource(R.string.tax_empty_action),
                    onClick = { onAction(TaxSettingsAction.NewRate) },
                    variant = ActionVariant.TONAL,
                )
            }
        }
    }
    items(state.active, key = { "rate-${it.id}" }) { row ->
        TaxRateRowView(
            row = row,
            onClick = { onEdit(row.id) },
            actions =
                listOf(
                    OverflowAction(stringResource(R.string.action_edit), { onEdit(row.id) }),
                    if (row.inUse) {
                        OverflowAction(
                            stringResource(R.string.action_archive),
                            { onAction(TaxSettingsAction.AskArchive(row.id)) },
                        )
                    } else {
                        OverflowAction(
                            stringResource(R.string.action_delete),
                            { onAction(TaxSettingsAction.AskDelete(row.id)) },
                            destructive = true,
                        )
                    },
                ),
        )
    }
    item(key = "recoverable") {
        SettingsRow(
            title = stringResource(R.string.tax_recoverable),
            action =
                SettingsRowAction.Toggle(state.purchaseTaxRecoverable) {
                    onAction(TaxSettingsAction.SetPurchaseTaxRecoverable(it))
                },
            summary = stringResource(R.string.tax_recoverable_help),
        )
    }
    item(key = "estimate") {
        NoticeCard(
            text = stringResource(R.string.tax_estimate_notice),
            variant = NoticeVariant.INFO,
            icon = R.drawable.ic_info,
            modifier = Modifier.padding(AppTheme.spacing.s16),
        )
    }
    if (state.archived.isNotEmpty()) {
        item(key = "archived-header") { SectionHeader(stringResource(R.string.tax_group_archived)) }
        items(state.archived, key = { "archived-${it.id}" }) { row ->
            TaxRateRowView(
                row = row,
                onClick = null,
                actions =
                    listOf(
                        OverflowAction(
                            stringResource(R.string.action_reactivate),
                            { onAction(TaxSettingsAction.Reactivate(row.id)) },
                        ),
                    ),
            )
        }
    }
}

/**
 * Una tasa: nombre, porcentaje y la marca de predeterminada, en un solo foco que se lee entero,
 * como «Tasa general, 21 %, Predeterminada», y su menú aparte. Tocar una vigente abre su hoja.
 */
@Composable
private fun TaxRateRowView(row: TaxRateRow, onClick: (() -> Unit)?, actions: List<OverflowAction>) {
    val spacing = AppTheme.spacing
    val percent = LocalAppFormats.current.percent.formatRate(row.rate)
    val defaultTag = stringResource(R.string.tax_rate_default_tag)
    val reading = listOfNotNull(
        row.name,
        percent,
        defaultTag.takeIf {
            row.isDefault
        },
    ).joinToString()
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = AppTheme.sizes.listRowSingle),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val content =
            if (onClick != null) {
                Modifier.clickable(role = Role.Button, onClick = onClick)
            } else {
                Modifier
            }
        Row(
            modifier =
                Modifier
                    .weight(1f)
                    .heightIn(min = AppTheme.sizes.listRowSingle)
                    .then(content)
                    .clearAndSetSemantics { contentDescription = reading }
                    .padding(start = spacing.s16, top = spacing.s8, bottom = spacing.s8),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.s12),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(spacing.s4)) {
                Text(
                    row.name,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (row.isDefault) StatusTag(defaultTag)
            }
            Text(percent, style = AppTheme.amounts.row, color = MaterialTheme.colorScheme.onSurface)
        }
        OverflowMenu(actions, Modifier.padding(end = spacing.s4))
    }
}

@Composable
private fun TaxConfirmDialog(dialog: TaxDialog, onAction: (TaxSettingsAction) -> Unit) {
    val confirm = { onAction(TaxSettingsAction.ConfirmDialog) }
    val dismiss = { onAction(TaxSettingsAction.DismissDialog) }
    when (dialog) {
        is TaxDialog.ChangeMode -> ConfirmDialog(
            title = stringResource(R.string.tax_mode_confirm_title),
            body = stringResource(R.string.tax_mode_confirm_body),
            confirmLabel = stringResource(R.string.tax_mode_confirm_action),
            onConfirm = confirm,
            onDismiss = dismiss,
        )

        is TaxDialog.Archive -> ConfirmDialog(
            title = stringResource(R.string.tax_rate_archive_confirm_title, dialog.name),
            body = stringResource(R.string.tax_rate_archive_confirm_body),
            confirmLabel = stringResource(R.string.tax_rate_archive_confirm_action),
            onConfirm = confirm,
            onDismiss = dismiss,
        )

        is TaxDialog.Delete -> ConfirmDialog(
            title = stringResource(R.string.tax_rate_delete_confirm_title, dialog.name),
            body = stringResource(R.string.tax_rate_delete_confirm_body),
            confirmLabel = stringResource(R.string.tax_rate_delete_confirm_action),
            onConfirm = confirm,
            onDismiss = dismiss,
            variant = ConfirmVariant.DESTRUCTIVE,
        )
    }
}

private fun modeLabel(mode: TaxMode): Int = when (mode) {
    TaxMode.NONE -> R.string.tax_mode_none
    TaxMode.INCLUDED -> R.string.tax_mode_included
    TaxMode.ADDED -> R.string.tax_mode_added
}

private fun modeHelp(mode: TaxMode): Int = when (mode) {
    TaxMode.NONE -> R.string.tax_mode_none_help
    TaxMode.INCLUDED -> R.string.tax_mode_included_help
    TaxMode.ADDED -> R.string.tax_mode_added_help
}

private fun messageText(message: TaxMessage): Int = when (message) {
    TaxMessage.RateSaved -> R.string.tax_rate_saved
    TaxMessage.NameTaken -> R.string.tax_error_name_duplicate
    is TaxMessage.Failed -> saveErrorText(message.failure.outOfSpace)
}

private const val SKELETON_ROWS = 3

/**
 * La hoja de crear o editar una tasa: nombre, porcentaje y si es la tasa por defecto. Los errores
 * van en su campo, y un guardado fallido se avisa arriba del botón sin borrar nada.
 */
@Composable
private fun TaxRateSheet(editor: TaxRateEditor, onAction: (TaxSettingsAction) -> Unit) {
    val symbols = LocalAppFormats.current.percent.inputSymbols
    val title =
        if (editor.editingId == null) R.string.tax_rate_new_action else R.string.tax_rate_edit_title
    AppBottomSheet(
        onDismissRequest = { onAction(TaxSettingsAction.DismissEditor) },
        title = stringResource(title),
        action = {
            Column(verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.s12)) {
                editor.saveFailure?.let {
                    NoticeCard(
                        text = stringResource(saveErrorText(it.outOfSpace)),
                        variant = NoticeVariant.ERROR,
                        icon = R.drawable.ic_error,
                    )
                }
                PrimaryActionButton(
                    text = stringResource(R.string.tax_rate_save_action),
                    onClick = { onAction(TaxSettingsAction.SaveRate(symbols)) },
                    variant = ActionVariant.PRIMARY,
                    state = if (editor.saving) ActionState.WORKING else ActionState.IDLE,
                )
            }
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.s12)) {
            AppTextField(
                value = editor.name,
                onValueChange = { onAction(TaxSettingsAction.EditorName(it)) },
                label = stringResource(R.string.tax_rate_name),
                error = editor.nameError?.let { stringResource(nameErrorText(it)) },
            )
            PercentField(
                value = editor.percent,
                onValueChange = { onAction(TaxSettingsAction.EditorPercent(it)) },
                label = stringResource(R.string.tax_rate_percent),
                error = editor.percentError?.let { stringResource(percentErrorText(it)) },
                imeAction = ImeAction.Done,
            )
        }
        SettingsRow(
            title = stringResource(R.string.tax_rate_use_default),
            action =
                SettingsRowAction.Toggle(editor.useAsDefault) {
                    onAction(TaxSettingsAction.EditorDefault(it))
                },
        )
    }
}

private fun nameErrorText(error: TaxNameError): Int = when (error) {
    TaxNameError.EMPTY -> R.string.tax_error_name_empty
    TaxNameError.TOO_LONG -> R.string.tax_error_name_long
    TaxNameError.DUPLICATE -> R.string.tax_error_name_duplicate
}

private fun percentErrorText(error: TaxPercentError): Int = when (error) {
    TaxPercentError.RANGE -> R.string.tax_error_percent_range
    TaxPercentError.DECIMALS -> R.string.tax_error_percent_decimals
}

/** Tasas de muestra para las vistas previas: nombres inventados, uno de 80 caracteres. */
private val previewRates =
    listOf(
        TaxRateRow(1, "Tasa general", BasisPoints(2_100), isDefault = true, inUse = true),
        TaxRateRow(2, "Tasa reducida", BasisPoints(825), isDefault = false, inUse = true),
        TaxRateRow(3, "Exenta", BasisPoints.ZERO, isDefault = false, inUse = false),
        TaxRateRow(4, PreviewSamples.LONG_NAME.take(80), BasisPoints(1_050), false, false),
    )

@ThemePreviews
@Composable
private fun TaxSettingsWithRatesPreview() {
    PreviewTheme {
        TaxSettingsScreen(
            TaxSettingsUiState.Ready(
                mode = TaxMode.INCLUDED,
                purchaseTaxRecoverable = true,
                missingDefaultRate = false,
                active = previewRates,
                archived = listOf(previewRates[2].copy(id = 9, name = "Tasa anterior")),
            ),
            onAction = {},
        )
    }
}

@ThemePreviews
@Composable
private fun TaxSettingsEmptyPreview() {
    PreviewTheme {
        TaxSettingsScreen(
            TaxSettingsUiState.Ready(
                mode = TaxMode.ADDED,
                purchaseTaxRecoverable = false,
                missingDefaultRate = true,
                active = emptyList(),
                archived = emptyList(),
            ),
            onAction = {},
        )
    }
}

@ThemePreviews
@Composable
private fun TaxSettingsNoTaxPreview() {
    PreviewTheme {
        TaxSettingsScreen(
            TaxSettingsUiState.Ready(
                mode = TaxMode.NONE,
                purchaseTaxRecoverable = false,
                missingDefaultRate = false,
                active = previewRates,
                archived = emptyList(),
            ),
            onAction = {},
        )
    }
}
