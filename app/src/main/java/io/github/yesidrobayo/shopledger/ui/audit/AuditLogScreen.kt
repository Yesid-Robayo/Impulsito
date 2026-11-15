package io.github.yesidrobayo.shopledger.ui.audit

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.BusinessTimeZone
import io.github.yesidrobayo.shopledger.domain.model.audit.AuditEvent
import io.github.yesidrobayo.shopledger.domain.model.audit.AuditEventType
import io.github.yesidrobayo.shopledger.domain.model.audit.AuditResult
import io.github.yesidrobayo.shopledger.domain.rule.region.BusinessCalendarResult
import io.github.yesidrobayo.shopledger.domain.usecase.audit.ObserveAuditLogUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.region.BusinessCalendarSource
import io.github.yesidrobayo.shopledger.navigation.AppNavKey
import io.github.yesidrobayo.shopledger.navigation.AppNavigator
import io.github.yesidrobayo.shopledger.navigation.PlaceholderRoute
import io.github.yesidrobayo.shopledger.navigation.ScreenModel
import io.github.yesidrobayo.shopledger.navigation.rememberAppViewModel
import io.github.yesidrobayo.shopledger.ui.components.ActionVariant
import io.github.yesidrobayo.shopledger.ui.components.AppTopBar
import io.github.yesidrobayo.shopledger.ui.components.AuditRow
import io.github.yesidrobayo.shopledger.ui.components.CompactChipRow
import io.github.yesidrobayo.shopledger.ui.components.DelayedSkeleton
import io.github.yesidrobayo.shopledger.ui.components.EmptyState
import io.github.yesidrobayo.shopledger.ui.components.PrimaryActionButton
import io.github.yesidrobayo.shopledger.ui.components.SkeletonBlock
import io.github.yesidrobayo.shopledger.ui.components.TopBarNavigation
import io.github.yesidrobayo.shopledger.ui.format.LocalAppFormats
import io.github.yesidrobayo.shopledger.ui.theme.AppTheme
import io.github.yesidrobayo.shopledger.ui.theme.PreviewTheme
import io.github.yesidrobayo.shopledger.ui.theme.ThemePreviews
import kotlin.time.Instant
import kotlinx.coroutines.flow.map

/**
 * `Registro de auditoría` en la navegación. Es de solo lectura: ningún evento se edita, se borra a
 * mano ni se exporta. Si la base no abre, muestra su barra y el estado vacío de siempre.
 */
@Composable
fun AuditLogRoute(navigator: AppNavigator) {
    val model =
        rememberAppViewModel(key = "audit-log") { dependencies ->
            val ports = dependencies.ports
            val observe = ObserveAuditLogUseCase(ports.auditLog)
            AuditLogViewModel(
                observe = { limit, failuresOnly -> observe(limit, failuresOnly) },
                zone =
                    BusinessCalendarSource(ports.settings).observe().map { result ->
                        (result as? BusinessCalendarResult.Ready)?.calendar?.zone
                    },
                computation = dependencies.container.dispatchers.default,
            )
        }
    when (model) {
        ScreenModel.Opening -> AuditLogScreen(AuditLogUiState.Loading) {}

        ScreenModel.Unavailable -> PlaceholderRoute(AppNavKey.AuditLog, navigator)

        is ScreenModel.Ready -> {
            val viewModel = model.viewModel
            val uiState by viewModel.uiState.collectAsStateWithLifecycle()
            AuditLogScreen(uiState) { action ->
                when (action) {
                    AuditLogAction.Back -> navigator.back()
                    else -> viewModel.onAction(action)
                }
            }
        }
    }
}

/**
 * `Registro de auditoría`, sin ViewModel: qué muestra y qué no, `Todos` y `Solo fallos`, la lista
 * por día con su encabezado fijo, y el pie con cuántos eventos se conservan. Sin acción principal.
 * Es local: sin red ni respaldo fallido no cambia nada, y no lleva banner.
 */
@Composable
fun AuditLogScreen(
    uiState: AuditLogUiState,
    modifier: Modifier = Modifier,
    onAction: (AuditLogAction) -> Unit,
) {
    Surface(modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxSize()) {
            AppTopBar(
                title = stringResource(R.string.audit_title),
                navigation = TopBarNavigation.BACK,
                onNavigate = { onAction(AuditLogAction.Back) },
                navigationLabel = stringResource(R.string.a11y_back),
            )
            Column(
                Modifier
                    .weight(1f)
                    .align(Alignment.CenterHorizontally)
                    .widthIn(max = AppTheme.sizes.maxContentWidth)
                    .fillMaxWidth(),
            ) {
                when (uiState) {
                    AuditLogUiState.Loading -> LoadingRows()

                    AuditLogUiState.ReadFailed ->
                        EmptyState(
                            icon = R.drawable.ic_error,
                            title = stringResource(R.string.state_error_read),
                        ) {
                            PrimaryActionButton(
                                text = stringResource(R.string.action_retry),
                                onClick = { onAction(AuditLogAction.Retry) },
                                variant = ActionVariant.TONAL,
                            )
                        }

                    is AuditLogUiState.Ready -> EventList(uiState, onAction)
                }
            }
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
                SkeletonBlock(
                    Modifier.fillMaxWidth().heightIn(min = AppTheme.sizes.listRowSingle),
                )
            }
        }
    }
}

@Composable
private fun EventList(state: AuditLogUiState.Ready, onAction: (AuditLogAction) -> Unit) {
    val spacing = AppTheme.spacing
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = spacing.s24),
    ) {
        item(key = "intro", contentType = TEXT) {
            Text(
                stringResource(R.string.audit_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = spacing.s16, vertical = spacing.s8),
            )
        }
        item(key = "filters", contentType = FILTERS) {
            CompactChipRow(
                options = AuditFilter.entries,
                label = { filter ->
                    stringResource(
                        when (filter) {
                            AuditFilter.ALL -> R.string.audit_filter_all
                            AuditFilter.FAILURES -> R.string.audit_filter_failures
                        },
                    )
                },
                selected = setOf(state.filter),
                onToggle = { onAction(AuditLogAction.Filter(it)) },
                multiple = false,
                modifier = Modifier.padding(horizontal = spacing.s16, vertical = spacing.s4),
            )
        }
        if (state.days.isEmpty()) {
            item(key = "empty", contentType = EMPTY) { Empty(state.filter, onAction) }
        } else {
            days(state, onAction)
        }
        item(key = "retention", contentType = TEXT) {
            val cap = ObserveAuditLogUseCase.MAX_EVENTS.toLong()
            Text(
                stringResource(
                    R.string.audit_retention_note,
                    LocalAppFormats.current.quantity.formatCount(cap),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = spacing.s16, vertical = spacing.s16),
            )
        }
    }
}

/** Los días con su encabezado fijo, y al final el tramo siguiente si lo hay. */
private fun LazyListScope.days(
    state: AuditLogUiState.Ready,
    onAction: (AuditLogAction) -> Unit,
) {
    for (day in state.days) {
        stickyHeader(key = "day-${day.date}", contentType = DAY) { DayHeader(day.date) }
        items(day.events, key = { it.id }, contentType = { ROW }) { event ->
            AuditRow(
                time = LocalAppFormats.current.date.time(event.occurredAt, state.zone),
                type = event.type,
                result = event.result,
            )
        }
    }
    if (state.canLoadMore) {
        item(key = "more", contentType = MORE) {
            // Llegar aquí trae el tramo siguiente, sin botón: la lista crece sola hasta el tope.
            LaunchedEffect(state.days.sumOf { it.events.size }) {
                onAction(AuditLogAction.LoadMore)
            }
            SkeletonBlock(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = AppTheme.spacing.s16)
                    .heightIn(min = AppTheme.sizes.listRowSingle),
            )
        }
    }
}

/** El encabezado fijo de un día, que TalkBack anuncia como título. */
@Composable
private fun DayHeader(date: BusinessDate) {
    Column(Modifier.fillMaxWidth()) {
        Surface(color = MaterialTheme.colorScheme.surface) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = AppTheme.sizes.touchTarget)
                    .padding(horizontal = AppTheme.spacing.s16, vertical = AppTheme.spacing.s8)
                    .semantics(mergeDescendants = true) { heading() },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    LocalAppFormats.current.date.weekdayDayMonth(date),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        HorizontalDivider(
            thickness = AppTheme.sizes.hairline,
            color = MaterialTheme.colorScheme.outlineVariant,
        )
    }
}

/** Sin eventos todavía, o ningún fallo con `Solo fallos`, que se quita desde aquí. */
@Composable
private fun Empty(filter: AuditFilter, onAction: (AuditLogAction) -> Unit) {
    when (filter) {
        AuditFilter.ALL ->
            EmptyState(
                icon = R.drawable.ic_history,
                title = stringResource(R.string.audit_empty_title),
                body = stringResource(R.string.audit_empty_body),
            )

        AuditFilter.FAILURES ->
            EmptyState(
                icon = R.drawable.ic_check_circle,
                title = stringResource(R.string.audit_empty_failures),
            ) {
                PrimaryActionButton(
                    text = stringResource(R.string.action_clear_filters),
                    onClick = { onAction(AuditLogAction.Filter(AuditFilter.ALL)) },
                    variant = ActionVariant.TONAL,
                )
            }
    }
}

private const val SKELETON_ROWS = 8
private const val TEXT = "text"
private const val FILTERS = "filters"
private const val EMPTY = "empty"
private const val DAY = "day"
private const val ROW = "row"
private const val MORE = "more"

@ThemePreviews
@Composable
private fun AuditLogScreenPreview() {
    val zone = checkNotNull(BusinessTimeZone.of("UTC"))
    val at = Instant.fromEpochMilliseconds(PREVIEW_AT)
    val events =
        listOf(
            AuditEvent(2, at, AuditEventType.AUTH_FAILED, AuditResult.FAILURE),
            AuditEvent(1, at, AuditEventType.EXPORT_CSV, AuditResult.SUCCESS),
        )
    PreviewTheme {
        AuditLogScreen(
            AuditLogUiState.Ready(
                filter = AuditFilter.ALL,
                days = listOf(AuditDay(BusinessDate.of(at, zone), events)),
                zone = zone,
                canLoadMore = false,
            ),
        ) {}
    }
}

private const val PREVIEW_AT = 1_767_225_600_000L
