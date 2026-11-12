package io.github.yesidrobayo.shopledger.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.model.backup.HealthState
import io.github.yesidrobayo.shopledger.ui.theme.AppTheme
import io.github.yesidrobayo.shopledger.ui.theme.PreviewTheme
import io.github.yesidrobayo.shopledger.ui.theme.ThemePreviews

/**
 * Una fila de `Salud de mis datos`: la señal, su valor y su estado, con icono y texto. El estado
 * nunca va solo en color: el texto lo dice, y TalkBack lee la fila entera de una vez.
 *
 * @param label la señal, como `Último simulacro`.
 * @param value lo que se sabe de ella, como una fecha, o nulo si no hay nada que decir.
 */
@Composable
fun HealthRow(label: String, value: String?, state: HealthState, modifier: Modifier = Modifier) {
    val spacing = AppTheme.spacing
    val tint = healthTint(state)
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .heightIn(min = AppTheme.sizes.touchTarget)
                .padding(vertical = spacing.s8)
                .semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.s12),
    ) {
        Icon(
            painter = painterResource(healthIcon(state)),
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(spacing.s24),
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(spacing.s2)) {
            Text(label, style = MaterialTheme.typography.titleSmall)
            value?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Text(healthStateText(state), style = MaterialTheme.typography.labelLarge, color = tint)
    }
}

/** El texto de cada estado, el que acompaña al icono. */
@Composable
fun healthStateText(state: HealthState): String = stringResource(
    when (state) {
        HealthState.OK -> R.string.health_state_ok
        HealthState.PENDING -> R.string.health_state_pending
        HealthState.ATTENTION -> R.string.health_state_attention
        HealthState.NOT_APPLICABLE -> R.string.health_state_na
    },
)

private fun healthIcon(state: HealthState): Int = when (state) {
    HealthState.OK -> R.drawable.ic_check_circle
    HealthState.PENDING -> R.drawable.ic_schedule
    HealthState.ATTENTION -> R.drawable.ic_error
    HealthState.NOT_APPLICABLE -> R.drawable.ic_remove
}

/** El token de cada estado, como lo fija el sistema de diseño. */
@Composable
fun healthTint(state: HealthState): Color = when (state) {
    HealthState.OK -> AppTheme.colors.backupOk
    HealthState.PENDING -> AppTheme.colors.warning
    HealthState.ATTENTION -> AppTheme.colors.backupError
    HealthState.NOT_APPLICABLE -> MaterialTheme.colorScheme.onSurfaceVariant
}

@ThemePreviews
@Composable
private fun HealthRowPreview() {
    PreviewTheme {
        Column(Modifier.padding(AppTheme.spacing.s16)) {
            HealthRow(stringResource(R.string.health_row_last_backup), "Hoy, 10:43", HealthState.OK)
            HealthRow(stringResource(R.string.health_row_last_drill), null, HealthState.PENDING)
            HealthRow(PreviewSamples.LONG_NAME, "Lun 26 oct, 18:02", HealthState.ATTENTION)
            HealthRow(stringResource(R.string.health_row_code), null, HealthState.NOT_APPLICABLE)
        }
    }
}
