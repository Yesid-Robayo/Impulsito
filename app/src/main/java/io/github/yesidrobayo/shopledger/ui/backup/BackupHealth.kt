package io.github.yesidrobayo.shopledger.ui.backup

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupCopyList
import io.github.yesidrobayo.shopledger.domain.model.backup.HealthRow
import io.github.yesidrobayo.shopledger.domain.model.backup.HealthSignal
import io.github.yesidrobayo.shopledger.domain.model.backup.HealthState
import io.github.yesidrobayo.shopledger.format.DataSizeFormatter
import io.github.yesidrobayo.shopledger.format.NO_VALUE_TEXT
import io.github.yesidrobayo.shopledger.ui.components.ActionVariant
import io.github.yesidrobayo.shopledger.ui.components.HealthRow
import io.github.yesidrobayo.shopledger.ui.components.PrimaryActionButton
import io.github.yesidrobayo.shopledger.ui.components.healthStateText
import io.github.yesidrobayo.shopledger.ui.components.healthTint
import io.github.yesidrobayo.shopledger.ui.format.LocalAppFormats
import io.github.yesidrobayo.shopledger.ui.theme.AppTheme

/**
 * `Salud de mis datos`: una fila por señal, con su valor y su estado. Solo lee: ninguna fila
 * escribe nada, y tocar el título solo abre o pliega el panel. Plegado, el título dice el estado
 * que más pesa, para que nada quede escondido sin aviso.
 */
@Composable
internal fun HealthPanel(
    rows: List<HealthRow>,
    open: Boolean,
    wording: BackupWording?,
    offer: FullVerifyOffer?,
    verifying: Boolean,
    onAction: (BackupAction) -> Unit,
) {
    val spacing = AppTheme.spacing
    val worst = worstOf(rows)
    val summary = healthStateText(worst)
    val openLabel =
        stringResource(if (open) R.string.a11y_health_collapse else R.string.a11y_health_expand)
    Column(Modifier.padding(horizontal = spacing.s16)) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = AppTheme.sizes.touchTarget)
                    .clickable(role = Role.Button, onClickLabel = openLabel) {
                        onAction(BackupAction.ToggleHealth)
                    }
                    .semantics(mergeDescendants = true) {
                        heading()
                        stateDescription = summary
                    },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.s8),
        ) {
            Text(
                stringResource(R.string.health_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            Text(summary, style = MaterialTheme.typography.labelLarge, color = healthTint(worst))
            Icon(
                painter = painterResource(R.drawable.ic_expand_more),
                contentDescription = null,
                modifier = Modifier.size(spacing.s24).rotate(if (open) HALF_TURN else 0f),
            )
        }
        if (open) {
            for (row in rows) {
                HealthRow(labelOf(row, wording), valueOf(row, wording), row.state)
                if (row.signal == HealthSignal.FULL_VERIFY && offer != null) {
                    FullVerifyOfferBlock(offer, verifying, onAction)
                }
            }
        }
    }
}

/** El estado que más pesa: atención, luego pendiente; si todo está bien o no aplica, bien. */
private fun worstOf(rows: List<HealthRow>): HealthState = when {
    rows.any { it.state == HealthState.ATTENTION } -> HealthState.ATTENTION
    rows.any { it.state == HealthState.PENDING } -> HealthState.PENDING
    else -> HealthState.OK
}

@Composable
private fun labelOf(row: HealthRow, wording: BackupWording?): String = stringResource(
    when (row.signal) {
        // Con ranuras la app entrega: nunca dice que la copia está guardada.
        HealthSignal.LAST_COPY ->
            if (wording?.slots == true) {
                R.string.health_row_last_handoff
            } else {
                R.string.health_row_last_backup
            }

        HealthSignal.CLOUD_CHECK -> R.string.health_row_cloud_check

        HealthSignal.FULL_VERIFY -> R.string.health_row_full_verify

        HealthSignal.LAST_DRILL -> R.string.health_row_last_drill

        HealthSignal.PINNED -> R.string.health_row_pinned

        HealthSignal.OFFSITE -> R.string.health_row_offsite

        HealthSignal.CODE -> R.string.health_row_code

        HealthSignal.DEVICE_LOCK -> R.string.health_row_device_lock

        HealthSignal.PHONE_SPACE -> R.string.health_row_phone_space

        HealthSignal.DESTINATION_SPACE -> R.string.health_row_destination_space
    },
)

/**
 * Lo que se sabe de la señal: la fecha, con la revisión como lo que dijo la persona, o cuántas
 * copias están fijadas. Sin dato, nada: el estado lo dice.
 */
@Composable
private fun valueOf(row: HealthRow, wording: BackupWording?): String? = when (row.signal) {
    HealthSignal.CLOUD_CHECK ->
        row.at?.let { stringResource(R.string.health_cloud_check_value, instantText(it, wording)) }

    HealthSignal.PINNED -> row.count?.toString()

    HealthSignal.LAST_COPY, HealthSignal.FULL_VERIFY, HealthSignal.LAST_DRILL ->
        row.at?.let { instantText(it, wording) }

    HealthSignal.OFFSITE,
    HealthSignal.CODE,
    HealthSignal.DEVICE_LOCK,
    HealthSignal.PHONE_SPACE,
    HealthSignal.DESTINATION_SPACE,
    -> null
}?.takeIf { it != NO_VALUE_TEXT }

/** Media vuelta del icono, para el panel abierto. */
private const val HALF_TURN = 180f

/**
 * Lo que `Comprobar ahora` muestra antes de correr: los días sin comprobar y el tamaño que se baja.
 *
 * @property sizeBytes el tamaño de la copia más reciente de la lista, o nulo si no se pudo ver.
 */
internal data class FullVerifyOffer(val days: Long, val sizeBytes: Long?)

/**
 * Con WebDAV y la comprobación completa atrasada, la oferta de hacerla ahora, también con datos
 * móviles: solo si la lectura completa está pendiente. Nunca corre sola.
 */
internal fun fullVerifyOffer(uiState: BackupUiState): FullVerifyOffer? {
    val overview = uiState.wording?.overview ?: return null
    val pending =
        uiState.health?.any {
            it.signal == HealthSignal.FULL_VERIFY && it.state == HealthState.PENDING
        } == true
    if (!pending) return null
    val since = overview.lastFullReadAt ?: return null
    val newest =
        (uiState.copies as? BackupCopyList.Listed)?.copies?.firstOrNull { it.newest }
    return FullVerifyOffer((overview.computedAt - since).inWholeDays, newest?.sizeBytes)
}

@Composable
private fun FullVerifyOfferBlock(
    offer: FullVerifyOffer,
    verifying: Boolean,
    onAction: (BackupAction) -> Unit,
) {
    val spacing = AppTheme.spacing
    val formats = LocalAppFormats.current
    val size =
        offer.sizeBytes?.let {
            DataSizeFormatter(formats.date.formatLocale, formats.uiLocale).megabytes(it)
        } ?: NO_VALUE_TEXT
    Column(
        Modifier.padding(start = spacing.s24 + spacing.s12, bottom = spacing.s8),
        verticalArrangement = Arrangement.spacedBy(spacing.s8),
    ) {
        Text(
            stringResource(R.string.health_full_verify_offer, offer.days.toString(), size),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (verifying) {
            Text(
                stringResource(R.string.health_full_verify_running),
                style = MaterialTheme.typography.bodyMedium,
            )
            LinearProgressIndicator(Modifier.fillMaxWidth())
        } else {
            PrimaryActionButton(
                text = stringResource(R.string.health_full_verify_action),
                onClick = { onAction(BackupAction.CheckFullCopy) },
                variant = ActionVariant.TONAL,
            )
        }
    }
}
