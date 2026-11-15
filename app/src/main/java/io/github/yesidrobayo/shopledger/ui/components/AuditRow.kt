package io.github.yesidrobayo.shopledger.ui.components

import androidx.annotation.StringRes
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.model.audit.AuditEventType
import io.github.yesidrobayo.shopledger.domain.model.audit.AuditResult
import io.github.yesidrobayo.shopledger.ui.theme.AppTheme
import io.github.yesidrobayo.shopledger.ui.theme.PreviewTheme
import io.github.yesidrobayo.shopledger.ui.theme.ThemePreviews

/**
 * Un evento del registro de auditoría: la hora, qué pasó y cómo terminó, con icono y palabra,
 * nunca solo color. Sin montos ni nombres: el evento no los tiene. Con letra grande, la hora va
 * encima del evento. TalkBack lo lee como una sola fila.
 */
@Composable
fun AuditRow(
    time: String,
    type: AuditEventType,
    result: AuditResult,
    modifier: Modifier = Modifier,
) {
    val spacing = AppTheme.spacing
    val stacked = LocalDensity.current.fontScale >= STACKED_FONT_SCALE
    val failed = result == AuditResult.FAILURE
    val tint =
        if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .heightIn(min = AppTheme.sizes.listRowSingle)
                .padding(horizontal = spacing.s16, vertical = spacing.s8)
                .semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.s12),
    ) {
        if (stacked) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(spacing.s2)) {
                AuditTime(time)
                AuditEventText(type)
            }
        } else {
            AuditTime(time)
            Column(Modifier.weight(1f)) { AuditEventText(type) }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.s4),
        ) {
            val icon = if (failed) R.drawable.ic_error else R.drawable.ic_check_circle
            Icon(
                painter = painterResource(icon),
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(AppTheme.sizes.iconTag),
            )
            Text(
                stringResource(
                    if (failed) R.string.audit_result_failure else R.string.audit_result_success,
                ),
                style = MaterialTheme.typography.labelLarge,
                color = tint,
            )
        }
    }
}

@Composable
private fun AuditTime(time: String) {
    Text(
        time,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun AuditEventText(type: AuditEventType) {
    Text(stringResource(auditEventLabel(type)), style = MaterialTheme.typography.bodyLarge)
}

/** El nombre de cada evento en pantalla. El nombre guardado nunca se muestra. */
@StringRes
fun auditEventLabel(type: AuditEventType): Int = when (type) {
    AuditEventType.EXPORT_CSV -> R.string.audit_event_export_csv
    AuditEventType.EXPORT_FULL_COPY -> R.string.audit_event_export_full_copy
    AuditEventType.RESTORE -> R.string.audit_event_restore
    AuditEventType.RESTORE_REJECTED -> R.string.audit_event_restore_rejected
    AuditEventType.DESTINATION_DISCONNECTED -> R.string.audit_event_destination_disconnected
    AuditEventType.DESTINATION_CHANGED -> R.string.audit_event_destination_changed
    AuditEventType.APP_LOCK_CHANGED -> R.string.audit_event_app_lock_changed
    AuditEventType.ENCRYPTION_CHANGED -> R.string.audit_event_encryption_changed
    AuditEventType.RECOVERY_CODE_VIEWED -> R.string.audit_event_recovery_code_viewed
    AuditEventType.DELETE_MY_DATA -> R.string.audit_event_delete_my_data
    AuditEventType.AUTH_FAILED -> R.string.audit_event_auth_failed
    AuditEventType.CURRENCY_CORRECTED -> R.string.audit_event_currency_corrected
}

private const val STACKED_FONT_SCALE = 1.5f

@ThemePreviews
@Composable
private fun AuditRowPreview() {
    PreviewTheme {
        Column {
            AuditRow("10:42", AuditEventType.EXPORT_CSV, AuditResult.SUCCESS)
            AuditRow("09:15", AuditEventType.AUTH_FAILED, AuditResult.FAILURE)
        }
    }
}
