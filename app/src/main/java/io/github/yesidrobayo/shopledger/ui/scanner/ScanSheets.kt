package io.github.yesidrobayo.shopledger.ui.scanner

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.rule.TextNormalizer
import io.github.yesidrobayo.shopledger.ui.components.ActionVariant
import io.github.yesidrobayo.shopledger.ui.components.AppBottomSheet
import io.github.yesidrobayo.shopledger.ui.components.NoticeCard
import io.github.yesidrobayo.shopledger.ui.components.NoticeVariant
import io.github.yesidrobayo.shopledger.ui.components.PrimaryActionButton
import io.github.yesidrobayo.shopledger.ui.components.SelectionSheet
import io.github.yesidrobayo.shopledger.ui.components.SettingsRow
import io.github.yesidrobayo.shopledger.ui.components.SettingsRowAction
import io.github.yesidrobayo.shopledger.ui.theme.AppTheme

/**
 * La hoja de un código que no se pudo tomar. Cerrarla deja seguir leyendo; mientras está abierta,
 * la cámara sigue a la vista pero no toma otro código.
 */
@Composable
internal fun CodeSheet(sheet: ScanSheet, onAction: (ScannerAction) -> Unit) {
    when (sheet) {
        is ScanSheet.Unknown -> UnknownCodeSheet(sheet, onAction)
        is ScanSheet.Archived -> ArchivedSheet(sheet, onAction)
        is ScanSheet.Assign -> AssignSheet(sheet, onAction)
    }
}

/** `Código sin producto`: crear uno con el código puesto, o dárselo a uno que no tiene. */
@Composable
private fun UnknownCodeSheet(sheet: ScanSheet.Unknown, onAction: (ScannerAction) -> Unit) {
    AppBottomSheet(
        onDismissRequest = { onAction(ScannerAction.DismissSheet) },
        title = stringResource(R.string.scanner_unknown_title),
        action = {
            Column(verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.s8)) {
                PrimaryActionButton(
                    text = stringResource(R.string.scanner_create_product_action),
                    onClick = { onAction(ScannerAction.CreateProduct(sheet.code)) },
                    variant = ActionVariant.PRIMARY,
                )
                PrimaryActionButton(
                    text = stringResource(R.string.scanner_assign_action),
                    onClick = { onAction(ScannerAction.StartAssign) },
                    variant = ActionVariant.OUTLINED,
                )
            }
        },
    ) {
        Text(
            stringResource(R.string.scanner_unknown_code, sheet.code),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        sheet.problem?.let { ProblemNotice(it) }
    }
}

/** `{product} está archivado`, con `Reactivar`. */
@Composable
private fun ArchivedSheet(sheet: ScanSheet.Archived, onAction: (ScannerAction) -> Unit) {
    AppBottomSheet(
        onDismissRequest = { onAction(ScannerAction.DismissSheet) },
        title = stringResource(R.string.scanner_archived_title, sheet.name),
        action = {
            PrimaryActionButton(
                text = stringResource(R.string.action_reactivate),
                onClick = { onAction(ScannerAction.Reactivate(sheet.productId)) },
                variant = ActionVariant.PRIMARY,
            )
        },
    ) {
        Text(
            stringResource(R.string.scanner_unknown_code, sheet.code),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        sheet.problem?.let { ProblemNotice(it) }
    }
}

/** Elegir a qué producto sin código se le da el leído. La búsqueda compara sin tildes. */
@Composable
private fun AssignSheet(sheet: ScanSheet.Assign, onAction: (ScannerAction) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    val wanted = TextNormalizer.normalize(query.trim())
    SelectionSheet(
        title = stringResource(R.string.scanner_assign_action),
        items = sheet.candidates.filter { it.normalizedName.contains(wanted) },
        key = { it.id },
        query = query,
        onQueryChange = { query = it },
        onDismissRequest = { onAction(ScannerAction.DismissSheet) },
        searchHint = stringResource(R.string.sale_search_hint),
        itemContent = { candidate ->
            SettingsRow(
                candidate.name,
                SettingsRowAction.Navigation { onAction(ScannerAction.AssignTo(candidate.id)) },
            )
        },
        resultsCount = R.plurals.a11y_search_results,
    )
}

@Composable
private fun ProblemNotice(problem: ScanProblem) {
    val text =
        when (problem) {
            is ScanProblem.CodeTaken ->
                stringResource(R.string.error_barcode_duplicate, problem.name)

            ScanProblem.Duplicate -> stringResource(R.string.error_product_reactivate_duplicate)

            ScanProblem.SaveFailed -> stringResource(R.string.state_error_save)
        }
    NoticeCard(text = text, variant = NoticeVariant.ERROR)
}
