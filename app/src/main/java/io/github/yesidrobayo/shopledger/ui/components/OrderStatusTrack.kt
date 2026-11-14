package io.github.yesidrobayo.shopledger.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.ui.theme.AppTheme
import io.github.yesidrobayo.shopledger.ui.theme.PreviewTheme
import io.github.yesidrobayo.shopledger.ui.theme.ThemePreviews

/**
 * El avance de un pedido en tres pasos, en una fila: `Pendiente`, `Listo` y `Entregado`. El paso
 * actual lleva el punto relleno con `primary`, los pasados un check y los que faltan solo el borde:
 * el estado no depende solo del color.
 *
 * Es un solo foco para TalkBack, que lee el estado y en qué paso va. Un pedido cancelado no usa
 * este indicador: lleva su etiqueta.
 *
 * @param labels los nombres de los tres pasos, en orden.
 * @param current el paso actual, de 0 a 2.
 */
@Composable
fun OrderStatusTrack(labels: List<String>, current: Int, modifier: Modifier = Modifier) {
    require(labels.size == STEPS) { "El avance de un pedido tiene tres pasos" }
    val step = current.coerceIn(0, STEPS - 1)
    val description = stringResource(R.string.a11y_order_track, labels[step], step + 1, STEPS)
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = AppTheme.spacing.s16)
            .clearAndSetSemantics { contentDescription = description },
        verticalAlignment = Alignment.Top,
    ) {
        labels.forEachIndexed { index, label ->
            Step(label, done = index < step, now = index == step, Modifier.weight(1f))
            if (index < STEPS - 1) {
                HorizontalDivider(
                    modifier = Modifier
                        .weight(CONNECTOR_WEIGHT)
                        .padding(top = DOT / 2),
                    thickness = AppTheme.sizes.hairline,
                    color = if (index < step) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.outline
                    },
                )
            }
        }
    }
}

@Composable
private fun Step(label: String, done: Boolean, now: Boolean, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Column(
        modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.s4),
    ) {
        val dot = Modifier.size(DOT).let {
            when {
                now || done -> it.background(colors.primary, CircleShape)
                else -> it.border(AppTheme.sizes.hairline, colors.outline, CircleShape)
            }
        }
        Box(dot, contentAlignment = Alignment.Center) {
            if (done) {
                Icon(
                    painter = painterResource(R.drawable.ic_check),
                    contentDescription = null,
                    tint = colors.onPrimary,
                    modifier = Modifier.size(DOT),
                )
            }
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = if (now) colors.onSurface else colors.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

private const val STEPS = 3
private const val CONNECTOR_WEIGHT = 0.5f

// El punto de cada paso, del sistema de diseño.
private val DOT = 12.dp

// ---------------------------------------------------------------- vistas previas

@ThemePreviews
@Composable
private fun OrderStatusTrackPreview() {
    PreviewTheme {
        Column(
            Modifier.padding(vertical = AppTheme.spacing.s16),
            verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.s24),
        ) {
            val labels = listOf(
                stringResource(R.string.order_status_pending),
                stringResource(R.string.order_status_ready),
                stringResource(R.string.order_status_delivered),
            )
            OrderStatusTrack(labels, current = 0)
            OrderStatusTrack(labels, current = 1)
            OrderStatusTrack(labels, current = 2)
        }
    }
}
