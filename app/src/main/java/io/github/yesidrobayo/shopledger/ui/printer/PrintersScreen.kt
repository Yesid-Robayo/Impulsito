package io.github.yesidrobayo.shopledger.ui.printer

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.model.print.PaperWidth
import io.github.yesidrobayo.shopledger.domain.model.print.PrintFailure
import io.github.yesidrobayo.shopledger.domain.model.print.SavedPrinter
import io.github.yesidrobayo.shopledger.ui.components.ActionVariant
import io.github.yesidrobayo.shopledger.ui.components.AppBottomSheet
import io.github.yesidrobayo.shopledger.ui.components.AppTopBar
import io.github.yesidrobayo.shopledger.ui.components.EmptyStateInline
import io.github.yesidrobayo.shopledger.ui.components.NoticeCard
import io.github.yesidrobayo.shopledger.ui.components.NoticeVariant
import io.github.yesidrobayo.shopledger.ui.components.OverflowAction
import io.github.yesidrobayo.shopledger.ui.components.PrimaryActionButton
import io.github.yesidrobayo.shopledger.ui.components.PrinterRow
import io.github.yesidrobayo.shopledger.ui.components.SectionHeader
import io.github.yesidrobayo.shopledger.ui.components.SettingsRow
import io.github.yesidrobayo.shopledger.ui.components.SettingsRowAction
import io.github.yesidrobayo.shopledger.ui.components.TopBarNavigation
import io.github.yesidrobayo.shopledger.ui.help.HelpTopic
import io.github.yesidrobayo.shopledger.ui.help.ScreenHelpLink
import io.github.yesidrobayo.shopledger.ui.theme.AppTheme

/**
 * Las impresoras: el texto de que solo se usan las emparejadas, la sección Bluetooth con sus
 * impresoras guardadas y `Agregar impresora`, y lo que el sistema impida, como el permiso negado o
 * el Bluetooth apagado, con la salida que corresponde. Sin Bluetooth en el teléfono, la sección lo
 * dice y no ofrece agregar.
 *
 * [systemSection] es lo que va debajo, el servicio de impresión del sistema, que no depende de
 * Bluetooth. Es pura: todo llega en [uiState] y sale por [onAction].
 */
@Composable
fun PrintersScreen(
    uiState: PrintersUiState,
    onAction: (PrintersAction) -> Unit,
    modifier: Modifier = Modifier,
    systemSection: LazyListScope.() -> Unit = {},
) {
    Column(modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        AppTopBar(
            title = stringResource(R.string.printer_title),
            navigation = TopBarNavigation.BACK,
            onNavigate = { onAction(PrintersAction.Back) },
            navigationLabel = stringResource(R.string.a11y_back),
            actions = { ScreenHelpLink(HelpTopic.RECEIPTS) },
        )
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
            LazyColumn(
                Modifier.widthIn(max = AppTheme.sizes.maxContentWidth).fillMaxSize(),
                contentPadding = PaddingValues(bottom = AppTheme.spacing.s32),
            ) {
                item(key = INTRO) {
                    Text(
                        stringResource(R.string.printer_intro),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(AppTheme.spacing.s16),
                    )
                }
                bluetoothSection(uiState, onAction)
                systemSection()
            }
        }
    }
    uiState.sheet?.let { PrinterSheetContent(it, onAction) }
}

private fun LazyListScope.bluetoothSection(
    uiState: PrintersUiState,
    onAction: (PrintersAction) -> Unit,
) {
    if (uiState.blocked == PrintFailure.NO_ADAPTER) {
        item(key = BLUETOOTH) { SectionHeader(stringResource(R.string.printer_section_bluetooth)) }
        item(key = NO_ADAPTER) {
            Text(
                stringResource(R.string.printer_error_no_bluetooth),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = AppTheme.spacing.s16),
            )
        }
        return
    }
    item(key = BLUETOOTH) {
        SectionHeader(
            stringResource(R.string.printer_section_bluetooth),
            actionLabel = stringResource(R.string.printer_add_action).takeIf {
                uiState.setup.printers.isNotEmpty()
            },
            onAction = { onAction(PrintersAction.Add) },
        )
    }
    uiState.blocked?.let { blocked ->
        item(key = BLOCKED) { BlockedNotice(blocked, onAction) }
    }
    if (uiState.setup.printers.isEmpty() && !uiState.loading) {
        item(key = EMPTY) {
            EmptyStateInline(
                title = stringResource(R.string.printer_empty_title),
                body = stringResource(R.string.printer_empty_body),
                modifier = Modifier.padding(AppTheme.spacing.s16),
            ) {
                PrimaryActionButton(
                    stringResource(R.string.printer_add_action),
                    { onAction(PrintersAction.Add) },
                    ActionVariant.TONAL,
                )
            }
        }
    }
    items(uiState.setup.printers, key = { "printer-${it.address}" }) { printer ->
        SavedPrinterRow(printer, uiState, onAction)
    }
}

@Composable
private fun SavedPrinterRow(
    printer: SavedPrinter,
    uiState: PrintersUiState,
    onAction: (PrintersAction) -> Unit,
) {
    val isDefault = uiState.setup.default?.address == printer.address
    val actions = buildList {
        add(
            OverflowAction(stringResource(R.string.printer_test_action), {
                onAction(PrintersAction.Test(printer))
            }),
        )
        if (!isDefault) {
            add(
                OverflowAction(stringResource(R.string.printer_set_default_action), {
                    onAction(PrintersAction.MakeDefault(printer))
                }),
            )
        }
        val other = when (printer.paper) {
            PaperWidth.MM_58 -> PaperWidth.MM_80
            PaperWidth.MM_80 -> PaperWidth.MM_58
        }
        add(
            OverflowAction(paperLabel(other), {
                onAction(PrintersAction.ChangePaper(printer, other))
            }),
        )
        add(
            OverflowAction(
                stringResource(R.string.printer_remove_action),
                { onAction(PrintersAction.Remove(printer)) },
                destructive = true,
            ),
        )
    }
    PrinterRow(
        name = printerName(printer.name),
        paper = paperLabel(printer.paper),
        actions = actions,
        defaultLabel = stringResource(R.string.printer_status_default).takeIf { isDefault },
        status = stringResource(R.string.printer_status_printing).takeIf {
            uiState.printing == printer.address
        },
    )
}

/** Lo que el sistema impide, con la salida que tiene: los ajustes de la app o los de Bluetooth. */
@Composable
private fun BlockedNotice(blocked: PrintFailure, onAction: (PrintersAction) -> Unit) {
    val side = Modifier.padding(horizontal = AppTheme.spacing.s16, vertical = AppTheme.spacing.s8)
    when (blocked) {
        PrintFailure.PERMISSION_MISSING -> NoticeCard(
            text = stringResource(R.string.printer_error_permission),
            variant = NoticeVariant.WARNING,
            actionLabel = stringResource(R.string.action_open_settings),
            onAction = { onAction(PrintersAction.OpenAppSettings) },
            modifier = side,
        )

        PrintFailure.BLUETOOTH_OFF, PrintFailure.NOT_BONDED -> NoticeCard(
            text = stringResource(R.string.printer_error_bluetooth_off),
            variant = NoticeVariant.WARNING,
            actionLabel = stringResource(R.string.printer_open_bluetooth_action),
            onAction = { onAction(PrintersAction.OpenBluetoothSettings) },
            modifier = side,
        )

        // Sin Bluetooth, la sección entera lo dice; las demás no impiden agregar.
        PrintFailure.NO_ADAPTER, PrintFailure.CONNECT_FAILED, PrintFailure.WRITE_FAILED -> Unit
    }
}

/** Agregar: primero la impresora emparejada, después el ancho de su papel. */
@Composable
private fun PrinterSheetContent(sheet: PrinterSheet, onAction: (PrintersAction) -> Unit) {
    val close = { onAction(PrintersAction.DismissSheet) }
    when (sheet) {
        is PrinterSheet.Paired -> AppBottomSheet(
            onDismissRequest = close,
            title = stringResource(R.string.printer_choose_paired_title),
        ) {
            if (sheet.printers.isEmpty()) {
                Text(
                    stringResource(R.string.printer_paired_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = AppTheme.spacing.s16),
                )
            }
            sheet.printers.forEach { printer ->
                SettingsRow(
                    printerName(printer.name),
                    SettingsRowAction.Navigation {
                        onAction(PrintersAction.ChoosePaired(printer))
                    },
                )
            }
            SettingsRow(
                stringResource(R.string.printer_open_bluetooth_action),
                SettingsRowAction.Navigation { onAction(PrintersAction.OpenBluetoothSettings) },
            )
        }

        is PrinterSheet.Paper -> AppBottomSheet(
            onDismissRequest = close,
            title = stringResource(R.string.printer_paper_width),
        ) {
            PaperWidth.entries.forEach { paper ->
                SettingsRow(
                    paperLabel(paper),
                    SettingsRowAction.Navigation { onAction(PrintersAction.ChoosePaper(paper)) },
                )
            }
        }
    }
}

/**
 * El servicio de impresión del sistema, para impresoras Wi-Fi o USB, con su ayuda y la prueba.
 * Sigue disponible sin Bluetooth y con el permiso negado: no lo necesita.
 */
internal fun LazyListScope.systemPrintSection(onTest: () -> Unit) {
    item(key = SYSTEM) { SectionHeader(stringResource(R.string.printer_section_system)) }
    item(key = SYSTEM_HELP) {
        Text(
            stringResource(R.string.printer_system_help),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = AppTheme.spacing.s16),
        )
    }
    item(key = SYSTEM_TEST) {
        PrimaryActionButton(
            stringResource(R.string.printer_system_test_action),
            onTest,
            ActionVariant.OUTLINED,
            modifier = Modifier.padding(AppTheme.spacing.s16),
        )
    }
}

/** El nombre que da el sistema, o `Bluetooth` si no da ninguno. Nunca la dirección. */
@Composable
internal fun printerName(name: String?): String =
    name ?: stringResource(R.string.printer_section_bluetooth)

@Composable
internal fun paperLabel(paper: PaperWidth): String = when (paper) {
    PaperWidth.MM_58 -> stringResource(R.string.printer_paper_58)
    PaperWidth.MM_80 -> stringResource(R.string.printer_paper_80)
}

private const val INTRO = "intro"
private const val BLUETOOTH = "bluetooth"
private const val NO_ADAPTER = "no-adapter"
private const val BLOCKED = "blocked"
private const val EMPTY = "empty"
private const val SYSTEM = "system"
private const val SYSTEM_HELP = "system-help"
private const val SYSTEM_TEST = "system-test"
