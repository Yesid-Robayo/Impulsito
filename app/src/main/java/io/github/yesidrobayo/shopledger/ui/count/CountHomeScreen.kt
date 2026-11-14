package io.github.yesidrobayo.shopledger.ui.count

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.input.ImeAction
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.model.stock.CountSessionStatus
import io.github.yesidrobayo.shopledger.domain.usecase.stock.CountOverview
import io.github.yesidrobayo.shopledger.domain.usecase.stock.CountSessionSummary
import io.github.yesidrobayo.shopledger.ui.components.ActionState
import io.github.yesidrobayo.shopledger.ui.components.ActionVariant
import io.github.yesidrobayo.shopledger.ui.components.AppBottomSheet
import io.github.yesidrobayo.shopledger.ui.components.AppCard
import io.github.yesidrobayo.shopledger.ui.components.AppTextField
import io.github.yesidrobayo.shopledger.ui.components.AppTopBar
import io.github.yesidrobayo.shopledger.ui.components.CheckboxRow
import io.github.yesidrobayo.shopledger.ui.components.DelayedSkeleton
import io.github.yesidrobayo.shopledger.ui.components.EmptyState
import io.github.yesidrobayo.shopledger.ui.components.InlineTextButton
import io.github.yesidrobayo.shopledger.ui.components.LocationSwitcherOptions
import io.github.yesidrobayo.shopledger.ui.components.NoticeCard
import io.github.yesidrobayo.shopledger.ui.components.NoticeVariant
import io.github.yesidrobayo.shopledger.ui.components.PickerField
import io.github.yesidrobayo.shopledger.ui.components.PrimaryActionButton
import io.github.yesidrobayo.shopledger.ui.components.SectionHeader
import io.github.yesidrobayo.shopledger.ui.components.SkeletonBlock
import io.github.yesidrobayo.shopledger.ui.components.StatusTag
import io.github.yesidrobayo.shopledger.ui.components.TonalFab
import io.github.yesidrobayo.shopledger.ui.components.TonalFabDefaults
import io.github.yesidrobayo.shopledger.ui.components.TopBarNavigation
import io.github.yesidrobayo.shopledger.ui.components.keepsMessagesAbove
import io.github.yesidrobayo.shopledger.ui.components.saveErrorText
import io.github.yesidrobayo.shopledger.ui.format.LocalAppFormats
import io.github.yesidrobayo.shopledger.ui.stock.locationChoices
import io.github.yesidrobayo.shopledger.ui.stock.locationName
import io.github.yesidrobayo.shopledger.ui.theme.AppTheme

/**
 * El inicio del conteo físico: el conteo abierto de cada ubicación con `Seguir contando`, los
 * anteriores con sus diferencias y `Nuevo conteo`. Un conteo abierto vive en la base: aparece aquí
 * aunque la app se haya cerrado a mitad.
 */
@Composable
fun CountHomeScreen(
    uiState: CountHomeUiState,
    onAction: (CountHomeAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        Column(Modifier.fillMaxSize()) {
            AppTopBar(
                title = stringResource(R.string.count_title),
                navigation = TopBarNavigation.BACK,
                onNavigate = { onAction(CountHomeAction.Back) },
                navigationLabel = stringResource(R.string.a11y_back),
                titleStyle = MaterialTheme.typography.titleLarge,
            )
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                Box(Modifier.widthIn(max = AppTheme.sizes.maxContentWidth).fillMaxSize()) {
                    when (val content = uiState.content) {
                        CountHomeContent.Loading -> SkeletonRows()

                        CountHomeContent.ReadError -> EmptyState(
                            icon = R.drawable.ic_error,
                            title = stringResource(R.string.state_error_read),
                        ) {
                            InlineTextButton(stringResource(R.string.action_retry), {
                                onAction(CountHomeAction.Retry)
                            })
                        }

                        is CountHomeContent.Ready -> HomeContent(content.overview, onAction)
                    }
                    if (uiState.content is CountHomeContent.Ready) {
                        TonalFab(
                            text = stringResource(R.string.count_new_action),
                            onClick = { onAction(CountHomeAction.NewCount) },
                            modifier = Modifier.align(Alignment.BottomEnd).keepsMessagesAbove(),
                        )
                    }
                }
            }
        }
    }
    val overview = (uiState.content as? CountHomeContent.Ready)?.overview
    if (overview != null) uiState.sheet?.let { NewCountSheetView(it, overview, onAction) }
}

@Composable
private fun SkeletonRows() {
    DelayedSkeleton {
        Column(
            Modifier.padding(AppTheme.spacing.s16),
            verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.s12),
        ) {
            repeat(SKELETON_ROWS) {
                SkeletonBlock(Modifier.fillMaxWidth().heightIn(min = AppTheme.sizes.listRow))
            }
        }
    }
}

@Composable
private fun HomeContent(overview: CountOverview, onAction: (CountHomeAction) -> Unit) {
    if (overview.open.isEmpty() && overview.finished.isEmpty()) {
        EmptyState(
            icon = R.drawable.ic_fact_check,
            title = stringResource(R.string.count_empty_title),
            body = stringResource(R.string.count_empty_body),
        ) {
            PrimaryActionButton(
                text = stringResource(R.string.count_new_action),
                onClick = { onAction(CountHomeAction.NewCount) },
                variant = ActionVariant.TONAL,
            )
        }
        return
    }
    LazyColumn(
        Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.s8),
        contentPadding = PaddingValues(bottom = TonalFabDefaults.listEndPadding),
    ) {
        items(overview.open, key = { "open-${it.session.id}" }) { summary ->
            OpenCard(summary, overview.showsLocations, onAction)
        }
        if (overview.finished.isNotEmpty()) {
            item(key = "previous") { SectionHeader(stringResource(R.string.count_previous_title)) }
            items(overview.finished, key = { "done-${it.session.id}" }) { summary ->
                FinishedRow(summary, overview.showsLocations, onAction)
            }
        }
    }
}

/** El alcance de un conteo: la ubicación si hay varias, y la categoría si se eligió. */
@Composable
private fun scopeText(summary: CountSessionSummary, showsLocations: Boolean): String? = joined(
    listOfNotNull(
        summary.location?.takeIf { showsLocations }?.let { locationName(it) },
        summary.category?.details?.name,
    ),
)

/** Las partes unidas como `Almacén · Lácteos`, con el separador de la redacción. */
@Composable
internal fun joined(parts: List<String>): String? =
    parts.reduceOrNull { left, right -> stringResource(R.string.count_scope, left, right) }

/** El conteo abierto: su alcance, cuánto va y `Seguir contando`. */
@Composable
private fun OpenCard(
    summary: CountSessionSummary,
    showsLocations: Boolean,
    onAction: (CountHomeAction) -> Unit,
) {
    val spacing = AppTheme.spacing
    AppCard(Modifier.padding(horizontal = spacing.s16, vertical = spacing.s8)) {
        Column(verticalArrangement = Arrangement.spacedBy(spacing.s8)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    scopeText(summary, showsLocations) ?: stringResource(R.string.count_title),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                StatusTag(stringResource(R.string.count_status_open))
            }
            Text(
                stringResource(R.string.count_progress, summary.counted, summary.total),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            LinearProgressIndicator(
                progress = { progressOf(summary.counted, summary.total) },
                modifier = Modifier.fillMaxWidth(),
            )
            PrimaryActionButton(
                text = stringResource(R.string.count_continue_action),
                onClick = { onAction(CountHomeAction.OpenSession(summary.session.id)) },
                variant = ActionVariant.TONAL,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** Un conteo anterior: fecha, alcance, diferencias y si se aplicó o se canceló. Un solo foco. */
@Composable
private fun FinishedRow(
    summary: CountSessionSummary,
    showsLocations: Boolean,
    onAction: (CountHomeAction) -> Unit,
) {
    val spacing = AppTheme.spacing
    val date = LocalAppFormats.current.date.dayMonth(summary.session.businessDate)
    val scope = scopeText(summary, showsLocations)
    val differences = pluralStringResource(
        R.plurals.count_differences,
        summary.differences,
        summary.differences,
    )
    val status = stringResource(
        when (summary.session.status) {
            CountSessionStatus.APPLIED -> R.string.count_status_applied
            CountSessionStatus.CANCELLED -> R.string.count_status_cancelled
            CountSessionStatus.OPEN -> R.string.count_status_open
        },
    )
    val first = joined(listOfNotNull(date, scope)).orEmpty()
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = AppTheme.sizes.listRow)
                .clickable { onAction(CountHomeAction.OpenSession(summary.session.id)) }
                .clearAndSetSemantics {
                    contentDescription = listOf(first, differences, status).joinToString()
                    role = Role.Button
                }
                .padding(horizontal = spacing.s16, vertical = spacing.s12),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.s12),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(spacing.s2)) {
                Text(first, style = MaterialTheme.typography.bodyLarge)
                Text(
                    differences,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            StatusTag(status)
        }
        HorizontalDivider(
            modifier = Modifier.padding(start = spacing.s16),
            thickness = AppTheme.sizes.hairline,
            color = MaterialTheme.colorScheme.outlineVariant,
        )
    }
}

/**
 * La hoja de un conteo nuevo: `Ubicación` solo si hay más de una, `Categoría` opcional, a ciegas
 * marcado por defecto, `Nota` y `Empezar conteo`.
 */
@Composable
private fun NewCountSheetView(
    sheet: NewCountSheet,
    overview: CountOverview,
    onAction: (CountHomeAction) -> Unit,
) {
    val spacing = AppTheme.spacing
    if (sheet.choosingCategory) {
        CategorySheet(sheet, onAction)
        return
    }
    AppBottomSheet(
        onDismissRequest = { onAction(CountHomeAction.DismissSheet) },
        title = stringResource(R.string.count_new_action),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(spacing.s12)) {
            if (overview.showsLocations) {
                Text(
                    stringResource(R.string.count_location_label),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                LocationSwitcherOptions(
                    locations = locationChoices(overview.activeLocations),
                    selectedId = sheet.locationId,
                    allowAll = false,
                    onSelect = { id -> id?.let { onAction(CountHomeAction.SheetLocation(it)) } },
                    onManage = null,
                )
            }
            PickerField(
                label = stringResource(R.string.count_category_label),
                value = sheet.categories.firstOrNull { it.id == sheet.categoryId }?.details?.name
                    ?: stringResource(R.string.count_category_all),
                onClick = { onAction(CountHomeAction.OpenCategories) },
            )
            CheckboxRow(
                text = stringResource(R.string.count_blind_label),
                checked = sheet.blind,
                onCheckedChange = { onAction(CountHomeAction.SheetBlind(it)) },
            )
            Text(
                stringResource(R.string.count_blind_help),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            AppTextField(
                value = sheet.note,
                onValueChange = { onAction(CountHomeAction.SheetNote(it)) },
                label = stringResource(R.string.count_note_label),
                modifier = Modifier.fillMaxWidth(),
                error = if (sheet.error == NewCountError.NOTE_TOO_LONG) {
                    pluralStringResource(R.plurals.error_text_long, NOTE_MAX, NOTE_MAX)
                } else {
                    null
                },
                imeAction = ImeAction.Done,
            )
            sheet.error?.takeIf { it != NewCountError.NOTE_TOO_LONG }?.let { error ->
                NoticeCard(
                    text = stringResource(
                        when (error) {
                            NewCountError.SCOPE_EMPTY -> R.string.count_scope_empty
                            NewCountError.LOCATION_UNAVAILABLE -> R.string.location_error_same
                            NewCountError.UNKNOWN_ZONE -> R.string.stats_zone_unknown_title
                            NewCountError.NOTE_TOO_LONG -> R.string.state_error_save
                        },
                    ),
                    variant = NoticeVariant.WARNING,
                    actionLabel = stringResource(R.string.stats_zone_unknown_action)
                        .takeIf { error == NewCountError.UNKNOWN_ZONE },
                    onAction = { onAction(CountHomeAction.ChooseZone) },
                )
            }
            sheet.failedOutOfSpace?.let {
                NoticeCard(text = stringResource(saveErrorText(it)), variant = NoticeVariant.ERROR)
            }
            PrimaryActionButton(
                text = stringResource(R.string.count_start_action),
                onClick = { onAction(CountHomeAction.Start) },
                variant = ActionVariant.PRIMARY,
                modifier = Modifier.fillMaxWidth(),
                state = if (sheet.starting) ActionState.WORKING else ActionState.IDLE,
            )
        }
    }
}

/** Las categorías para elegir el alcance, con `Todas` para contar sin categoría. */
@Composable
private fun CategorySheet(sheet: NewCountSheet, onAction: (CountHomeAction) -> Unit) {
    AppBottomSheet(
        onDismissRequest = { onAction(CountHomeAction.SheetCategory(sheet.categoryId)) },
        title = stringResource(R.string.count_category_label),
    ) {
        Column {
            CategoryOption(stringResource(R.string.count_category_all)) {
                onAction(CountHomeAction.SheetCategory(null))
            }
            sheet.categories.forEach { category ->
                CategoryOption(category.details.name) {
                    onAction(CountHomeAction.SheetCategory(category.id))
                }
            }
        }
    }
}

@Composable
private fun CategoryOption(text: String, onClick: () -> Unit) {
    Text(
        text,
        style = MaterialTheme.typography.bodyLarge,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = AppTheme.sizes.touchTarget)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = AppTheme.spacing.s12),
    )
}

/** Cuánto va de un conteo, de 0 a 1. Sin líneas, nada. */
internal fun progressOf(counted: Int, total: Int): Float =
    if (total <= 0) 0f else counted.toFloat() / total

private const val SKELETON_ROWS = 6

/** El largo máximo de la nota de un conteo, el mismo que revisa el caso de uso. */
private const val NOTE_MAX = 200
