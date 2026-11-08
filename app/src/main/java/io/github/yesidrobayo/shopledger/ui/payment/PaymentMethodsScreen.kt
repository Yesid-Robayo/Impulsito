package io.github.yesidrobayo.shopledger.ui.payment

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.model.payment.PaymentMethodRecord
import io.github.yesidrobayo.shopledger.ui.components.ActionState
import io.github.yesidrobayo.shopledger.ui.components.ActionVariant
import io.github.yesidrobayo.shopledger.ui.components.AppTopBar
import io.github.yesidrobayo.shopledger.ui.components.DelayedSkeleton
import io.github.yesidrobayo.shopledger.ui.components.EmptyState
import io.github.yesidrobayo.shopledger.ui.components.NoticeCard
import io.github.yesidrobayo.shopledger.ui.components.NoticeVariant
import io.github.yesidrobayo.shopledger.ui.components.OverflowAction
import io.github.yesidrobayo.shopledger.ui.components.OverflowMenu
import io.github.yesidrobayo.shopledger.ui.components.PrimaryActionButton
import io.github.yesidrobayo.shopledger.ui.components.SkeletonBlock
import io.github.yesidrobayo.shopledger.ui.components.TonalFab
import io.github.yesidrobayo.shopledger.ui.components.TonalFabDefaults
import io.github.yesidrobayo.shopledger.ui.components.TopBarNavigation
import io.github.yesidrobayo.shopledger.ui.components.saveErrorText
import io.github.yesidrobayo.shopledger.ui.theme.AppTheme
import kotlin.math.roundToInt

/**
 * La lista de métodos de pago: los vigentes en el orden de cobro, los archivados plegados al final y
 * el modo de ordenar. Es pura: todo llega en [uiState] y sale por [onAction].
 */
@Composable
fun PaymentMethodsScreen(
    uiState: PaymentMethodsUiState,
    onAction: (PaymentMethodsAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val ready = uiState as? PaymentMethodsUiState.Ready
    val sorting = ready?.sorting
    Column(
        modifier =
            modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surface),
    ) {
        AppTopBar(
            title =
                stringResource(
                    if (sorting == null) {
                        R.string.payment_method_title
                    } else {
                        R.string.payment_method_sort_title
                    },
                ),
            navigation = if (sorting == null) TopBarNavigation.BACK else TopBarNavigation.CLOSE,
            onNavigate = {
                onAction(
                    if (sorting == null) {
                        PaymentMethodsAction.Back
                    } else {
                        PaymentMethodsAction.CancelSorting
                    },
                )
            },
            navigationLabel =
                stringResource(if (sorting == null) R.string.a11y_back else R.string.a11y_close),
            titleStyle = MaterialTheme.typography.titleLarge,
            actions = {
                // Ordenar tiene sentido con dos o más vigentes.
                if (ready != null && sorting == null && ready.active.size > 1) {
                    OverflowMenu(
                        listOf(
                            OverflowAction(stringResource(R.string.payment_method_menu_sort), {
                                onAction(PaymentMethodsAction.StartSorting)
                            }),
                        ),
                    )
                }
            },
        )
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
            when (uiState) {
                PaymentMethodsUiState.Loading -> DelayedSkeleton { SkeletonRows() }

                PaymentMethodsUiState.ReadError ->
                    EmptyState(
                        icon = R.drawable.ic_error,
                        title = stringResource(R.string.state_error_read),
                        modifier = Modifier.verticalScroll(rememberScrollState()),
                    ) {
                        PrimaryActionButton(
                            text = stringResource(R.string.action_retry),
                            onClick = { onAction(PaymentMethodsAction.Retry) },
                            variant = ActionVariant.TONAL,
                        )
                    }

                is PaymentMethodsUiState.Ready ->
                    if (uiState.sorting != null) {
                        SortingList(uiState.sorting, onAction)
                    } else {
                        MethodList(uiState, onAction)
                    }
            }
        }
    }
}

@Composable
private fun MethodList(
    state: PaymentMethodsUiState.Ready,
    onAction: (PaymentMethodsAction) -> Unit,
) {
    val sizes = AppTheme.sizes
    Box(Modifier.fillMaxSize()) {
        // El ancho máximo va antes de llenar: en una tableta la lista queda centrada a 600 dp.
        LazyColumn(
            modifier =
                Modifier
                    .align(Alignment.TopCenter)
                    .widthIn(max = sizes.maxContentWidth)
                    .fillMaxSize(),
        ) {
            if (state.active.isEmpty()) {
                item(key = "empty") {
                    EmptyState(
                        icon = R.drawable.ic_sell,
                        title = stringResource(R.string.payment_method_empty_title),
                        body = stringResource(R.string.payment_method_empty_body),
                    )
                }
            }
            items(state.active, key = { it.id }) { method ->
                MethodRow(method, onClick = {
                    onAction(PaymentMethodsAction.OpenMethod(method.id))
                })
            }
            if (state.archived.isNotEmpty()) {
                item(key = "archived") {
                    ArchivedHeader(
                        count = state.archived.size,
                        expanded = state.archivedExpanded,
                        onToggle = { onAction(PaymentMethodsAction.ToggleArchived) },
                    )
                }
                if (state.archivedExpanded) {
                    items(state.archived, key = { it.id }) { method ->
                        MethodRow(
                            method,
                            onClick = { onAction(PaymentMethodsAction.OpenMethod(method.id)) },
                        )
                    }
                }
            }
            // El botón flotante no tapa la última fila.
            item(key = "end") { Spacer(Modifier.height(TonalFabDefaults.listEndPadding)) }
        }
        TonalFab(
            text = stringResource(R.string.payment_method_new_action),
            onClick = { onAction(PaymentMethodsAction.NewMethod) },
            modifier = Modifier.align(Alignment.BottomEnd),
        )
    }
}

/** Una fila de libreta: nombre arriba, tipo y comisión debajo, y acreditación si la hay. */
@Composable
private fun MethodRow(method: PaymentMethodRecord, onClick: () -> Unit) {
    val spacing = AppTheme.spacing
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = AppTheme.sizes.listRow)
                    .clickable(role = Role.Button, onClick = onClick)
                    .padding(horizontal = spacing.s16, vertical = spacing.s12),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.s12),
        ) {
            MethodText(method, Modifier.weight(1f))
        }
        HorizontalDivider(
            thickness = AppTheme.sizes.hairline,
            color = AppTheme.colors.cardBorder,
            modifier = Modifier.padding(start = spacing.s16),
        )
    }
}

@Composable
private fun MethodText(method: PaymentMethodRecord, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.s2)) {
        Text(
            paymentMethodName(method),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            paymentMethodSummary(method.details),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        settlementText(method.details)?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ArchivedHeader(count: Int, expanded: Boolean, onToggle: () -> Unit) {
    val spacing = AppTheme.spacing
    val state =
        stringResource(
            if (expanded) {
                R.string.payment_method_a11y_archived_expanded
            } else {
                R.string.payment_method_a11y_archived_collapsed
            },
        )
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = AppTheme.sizes.listRowSingle)
                .clickable(role = Role.Button, onClick = onToggle)
                .semantics {
                    heading()
                    stateDescription = state
                }.padding(horizontal = spacing.s16, vertical = spacing.s12),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(R.string.payment_method_group_archived, count),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Icon(
            painter = painterResource(R.drawable.ic_expand_more),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(AppTheme.sizes.icon).rotate(if (expanded) 180f else 0f),
        )
    }
}

/**
 * El modo de ordenar: cada fila se arrastra por su asa, y también se mueve con sus botones o con las
 * acciones de TalkBack, para quien no arrastra. La lista es corta, así que no es perezosa: el
 * arrastre necesita medir todas las filas.
 */
@Composable
private fun SortingList(sorting: SortingState, onAction: (PaymentMethodsAction) -> Unit) {
    val spacing = AppTheme.spacing
    val order = sorting.order
    var dragged by remember { mutableIntStateOf(-1) }
    var offset by remember { mutableFloatStateOf(0f) }
    var rowHeight by remember { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            when (val status = sorting.status) {
                SortingStatus.ListChanged ->
                    NoticeCard(
                        stringResource(R.string.payment_method_sort_list_changed),
                        NoticeVariant.WARNING,
                        Modifier.padding(spacing.s16),
                    )

                is SortingStatus.Failed ->
                    NoticeCard(
                        saveProblemText(status.problem),
                        NoticeVariant.ERROR,
                        Modifier.padding(spacing.s16),
                    )

                SortingStatus.Editing, SortingStatus.Saving -> Unit
            }
            val currentOrder by rememberUpdatedState(order)
            order.forEachIndexed { index, method ->
                key(method.id) {
                    // El arrastre sigue vivo mientras la fila cambia de lugar: lee su lugar de hoy.
                    val currentIndex by rememberUpdatedState(index)
                    val isDragged = index == dragged
                    SortRow(
                        method = method,
                        canMoveUp = index > 0,
                        canMoveDown = index < order.lastIndex,
                        onMove = { to -> onAction(PaymentMethodsAction.Move(index, to)) },
                        index = index,
                        handle =
                            Modifier.pointerDrag(
                                onStart = {
                                    dragged = currentIndex
                                    offset = 0f
                                },
                                onDrag = { delta ->
                                    offset += delta
                                    val step = rowHeight.coerceAtLeast(1)
                                    val shift = (offset / step).roundToInt()
                                    val target = (dragged + shift).coerceIn(
                                        0,
                                        currentOrder.lastIndex,
                                    )
                                    if (target != dragged) {
                                        onAction(PaymentMethodsAction.Move(dragged, target))
                                        offset -= (target - dragged) * step
                                        dragged = target
                                    }
                                },
                                onEnd = {
                                    dragged = -1
                                    offset = 0f
                                },
                            ),
                        modifier =
                            Modifier
                                .widthIn(max = AppTheme.sizes.maxContentWidth)
                                .onSizeChanged { rowHeight = it.height }
                                .graphicsLayer { translationY = if (isDragged) offset else 0f },
                    )
                }
            }
        }
        PrimaryActionButton(
            text = stringResource(R.string.payment_method_sort_save_action),
            onClick = { onAction(PaymentMethodsAction.SaveOrder) },
            variant = ActionVariant.PRIMARY,
            state = if (sorting.status ==
                SortingStatus.Saving
            ) {
                ActionState.WORKING
            } else {
                ActionState.IDLE
            },
            modifier =
                Modifier
                    .widthIn(max = AppTheme.sizes.maxContentWidth)
                    .align(Alignment.CenterHorizontally)
                    .navigationBarsPadding()
                    .imePadding()
                    .padding(spacing.s16),
        )
    }
}

@Composable
private fun SortRow(
    method: PaymentMethodRecord,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onMove: (Int) -> Unit,
    index: Int,
    handle: Modifier,
    modifier: Modifier = Modifier,
) {
    val spacing = AppTheme.spacing
    val upLabel = stringResource(R.string.payment_method_move_up)
    val downLabel = stringResource(R.string.payment_method_move_down)
    val actions =
        buildList {
            if (canMoveUp) {
                add(
                    CustomAccessibilityAction(upLabel) {
                        onMove(index - 1)
                        true
                    },
                )
            }
            if (canMoveDown) {
                add(
                    CustomAccessibilityAction(downLabel) {
                        onMove(index + 1)
                        true
                    },
                )
            }
        }
    Column(modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = AppTheme.sizes.listRow)
                    .semantics(mergeDescendants = true) { customActions = actions }
                    .padding(start = spacing.s4, end = spacing.s4),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                handle.size(AppTheme.sizes.touchTarget),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_drag_handle),
                    contentDescription = stringResource(R.string.payment_method_a11y_drag_handle),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(AppTheme.sizes.icon),
                )
            }
            MethodText(method, Modifier.weight(1f).padding(vertical = spacing.s12))
            MoveButton(R.drawable.ic_keyboard_arrow_up, upLabel, canMoveUp) { onMove(index - 1) }
            MoveButton(R.drawable.ic_keyboard_arrow_down, downLabel, canMoveDown) {
                onMove(index + 1)
            }
        }
        HorizontalDivider(
            thickness = AppTheme.sizes.hairline,
            color = AppTheme.colors.cardBorder,
            modifier = Modifier.padding(start = spacing.s16),
        )
    }
}

@Composable
private fun MoveButton(icon: Int, label: String, enabled: Boolean, onClick: () -> Unit) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(AppTheme.sizes.touchTarget),
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = label,
            modifier = Modifier.size(AppTheme.sizes.icon),
        )
    }
}

/** El arrastre vertical de un asa, en píxeles. */
private fun Modifier.pointerDrag(
    onStart: () -> Unit,
    onDrag: (Float) -> Unit,
    onEnd: () -> Unit,
): Modifier = pointerInput(Unit) {
    detectDragGestures(
        onDragStart = { onStart() },
        onDragEnd = onEnd,
        onDragCancel = onEnd,
        onDrag = { change, amount ->
            change.consume()
            onDrag(amount.y)
        },
    )
}

/** Seis filas con la forma de un método, mientras la lista tarda en llegar. */
@Composable
private fun SkeletonRows() {
    val spacing = AppTheme.spacing
    Column(
        Modifier
            .widthIn(max = AppTheme.sizes.maxContentWidth)
            .fillMaxWidth()
            .padding(spacing.s16),
        verticalArrangement = Arrangement.spacedBy(spacing.s24),
    ) {
        repeat(SKELETON_ROWS) {
            Column(verticalArrangement = Arrangement.spacedBy(spacing.s8)) {
                SkeletonBlock(Modifier.fillMaxWidth(0.5f).height(spacing.s16))
                SkeletonBlock(Modifier.fillMaxWidth(0.7f).height(spacing.s12))
            }
        }
    }
}

/** El texto de una falla al guardar: disco lleno, o cualquier otra. */
@Composable
fun saveProblemText(problem: SaveProblem): String =
    stringResource(saveErrorText(outOfSpace = problem == SaveProblem.OUT_OF_SPACE))

private const val SKELETON_ROWS = 6
