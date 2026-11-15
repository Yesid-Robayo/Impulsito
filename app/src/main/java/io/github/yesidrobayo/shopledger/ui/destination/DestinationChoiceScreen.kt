package io.github.yesidrobayo.shopledger.ui.destination

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.ui.components.ActionVariant
import io.github.yesidrobayo.shopledger.ui.components.AppBottomSheet
import io.github.yesidrobayo.shopledger.ui.components.AppTopBar
import io.github.yesidrobayo.shopledger.ui.components.DestinationOption
import io.github.yesidrobayo.shopledger.ui.components.InlineTextButton
import io.github.yesidrobayo.shopledger.ui.components.PrimaryActionButton
import io.github.yesidrobayo.shopledger.ui.components.TopBarNavigation
import io.github.yesidrobayo.shopledger.ui.help.HelpTopic
import io.github.yesidrobayo.shopledger.ui.help.ScreenHelpLink
import io.github.yesidrobayo.shopledger.ui.theme.AppTheme

/** El paso de esta pantalla en el primer uso, de cuatro. */
internal const val FIRST_RUN_STEP = 3
internal const val FIRST_RUN_STEPS = 4

/**
 * `Dónde guardar las copias`: la promesa de las copias, las dos opciones con lo que exige cada una,
 * la salida para otras nubes, `Ahora no` sin destino conectado, y lo que lleva cada copia.
 *
 * - No hay acción principal: las dos opciones pesan lo mismo y nada viene preseleccionado.
 * - La opción conectada lleva `Actual`.
 * - En el primer uso, bajo la barra va en qué paso se está.
 * - Columna centrada de 600 dp; en una ventana ancha, las dos opciones lado a lado y con el mismo
 *   ancho y alto.
 *
 * Es pura: recibe el estado y avisa cada acción.
 */
@Composable
fun DestinationChoiceScreen(
    uiState: DestinationChoiceUiState,
    onAction: (DestinationChoiceAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (uiState.slotsWarning) SlotsWarningSheet(onAction)
    val spacing = AppTheme.spacing
    Surface(
        modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Column(Modifier.fillMaxSize()) {
            AppTopBar(
                title = stringResource(R.string.destination_title),
                navigation = TopBarNavigation.BACK,
                onNavigate = { onAction(DestinationChoiceAction.Back) },
                navigationLabel = stringResource(R.string.a11y_back),
                titleStyle = MaterialTheme.typography.titleLarge,
                actions = { ScreenHelpLink(HelpTopic.DESTINATIONS) },
            )
            BoxWithConstraints(
                Modifier.weight(1f).fillMaxWidth(),
                contentAlignment = Alignment.TopCenter,
            ) {
                val expanded = maxWidth >= EXPANDED_WIDTH
                val width =
                    if (expanded) {
                        AppTheme.sizes.maxContentWidth * 3 / 2
                    } else {
                        AppTheme.sizes.maxContentWidth
                    }
                Column(
                    Modifier
                        .widthIn(max = width)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .navigationBarsPadding()
                        .padding(horizontal = spacing.s16)
                        .padding(bottom = spacing.s24),
                    verticalArrangement = Arrangement.spacedBy(spacing.s12),
                ) {
                    if (uiState.firstRun) {
                        Text(
                            stringResource(
                                R.string.onboarding_step_progress,
                                FIRST_RUN_STEP,
                                FIRST_RUN_STEPS,
                            ),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        stringResource(
                            R.string.destination_intro,
                            stringResource(R.string.app_name),
                        ),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Options(uiState, expanded, onAction)
                    Notes(uiState, onAction)
                }
            }
        }
    }
}

/** Las dos opciones: una sobre otra, o lado a lado con el mismo ancho en una ventana ancha. */
@Composable
private fun Options(
    uiState: DestinationChoiceUiState,
    expanded: Boolean,
    onAction: (DestinationChoiceAction) -> Unit,
) {
    val spacing = AppTheme.spacing
    if (expanded) {
        Row(
            Modifier.fillMaxWidth().height(IntrinsicSize.Min),
            horizontalArrangement = Arrangement.spacedBy(spacing.s12),
        ) {
            SlotsOption(uiState, onAction, Modifier.weight(1f).fillMaxHeight())
            WebDavOption(uiState, onAction, Modifier.weight(1f).fillMaxHeight())
        }
    } else {
        SlotsOption(uiState, onAction)
        WebDavOption(uiState, onAction)
    }
}

@Composable
private fun SlotsOption(
    uiState: DestinationChoiceUiState,
    onAction: (DestinationChoiceAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val app = stringResource(R.string.app_name)
    val body =
        when (uiState.slotsHandoff) {
            SlotsHandoff.AUTOMATIC -> stringResource(R.string.destination_slots_body, app)
            SlotsHandoff.MANUAL -> stringResource(R.string.destination_slots_manual_body, app)
        }
    DestinationOption(
        title = stringResource(R.string.destination_slots_title),
        tag = stringResource(R.string.destination_slots_tag),
        body = body,
        note = stringResource(R.string.destination_slots_note),
        onClick = { onAction(DestinationChoiceAction.ChooseSlots) },
        modifier = modifier,
        currentLabel = currentLabel(uiState, ConnectedDestination.DOCUMENT_SLOTS),
    )
}

@Composable
private fun WebDavOption(
    uiState: DestinationChoiceUiState,
    onAction: (DestinationChoiceAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    DestinationOption(
        title = stringResource(R.string.destination_webdav_title),
        tag = stringResource(R.string.destination_webdav_tag),
        body = stringResource(R.string.destination_webdav_body, stringResource(R.string.app_name)),
        note = stringResource(R.string.destination_webdav_note),
        onClick = { onAction(DestinationChoiceAction.ChooseWebDav) },
        modifier = modifier,
        currentLabel = currentLabel(uiState, ConnectedDestination.WEBDAV),
    )
}

@Composable
private fun currentLabel(uiState: DestinationChoiceUiState, option: ConnectedDestination): String? =
    if (uiState.connected == option) stringResource(R.string.destination_current_tag) else null

/** Otras nubes, `Ahora no`, lo que lleva cada copia y `Qué datos hay y adónde van`. */
@Composable
private fun Notes(uiState: DestinationChoiceUiState, onAction: (DestinationChoiceAction) -> Unit) {
    val small = MaterialTheme.typography.bodySmall
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Text(stringResource(R.string.destination_other_cloud_note), style = small, color = muted)
    if (uiState.showsNotNow) {
        InlineTextButton(
            stringResource(R.string.action_not_now),
            { onAction(DestinationChoiceAction.NotNow) },
        )
    }
    Text(
        stringResource(R.string.destination_privacy_note, stringResource(R.string.app_name)),
        style = small,
        color = muted,
    )
    InlineTextButton(
        stringResource(R.string.transparency_title),
        { onAction(DestinationChoiceAction.OpenDataTransparency) },
    )
}

/**
 * `Antes de elegir`: lo que se puede perder con la nube del teléfono, dicho sin suavizar, y que hay
 * que revisar esa nube cada semana. Solo `Entendido` sigue; cerrarla vuelve sin cambiar nada.
 */
@Composable
private fun SlotsWarningSheet(onAction: (DestinationChoiceAction) -> Unit) {
    val app = stringResource(R.string.app_name)
    AppBottomSheet(
        onDismissRequest = { onAction(DestinationChoiceAction.DismissSlotsWarning) },
        title = stringResource(R.string.destination_slots_gate_title),
        action = {
            PrimaryActionButton(
                text = stringResource(R.string.destination_slots_gate_action),
                onClick = { onAction(DestinationChoiceAction.ConfirmSlots) },
                variant = ActionVariant.PRIMARY,
            )
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.s12)) {
            Text(
                stringResource(R.string.destination_slots_gate_body, app),
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                stringResource(R.string.destination_slots_gate_check, app),
                style = MaterialTheme.typography.bodyLarge,
            )
        }
    }
}

// Desde aquí la ventana es Expanded y las opciones van lado a lado.
private val EXPANDED_WIDTH = 840.dp
