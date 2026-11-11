package io.github.yesidrobayo.shopledger.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.ui.theme.AppTheme
import io.github.yesidrobayo.shopledger.ui.theme.PreviewTheme
import io.github.yesidrobayo.shopledger.ui.theme.ThemePreviews

/**
 * El tono de un estado del respaldo, que decide sus colores. Ninguno se lee solo por el color: el
 * texto y el icono dicen lo mismo.
 */
enum class BackupTone {
    /** Con WebDAV, todo comprobado. El verde se reserva para esto: con ranuras nunca aparece. */
    OK,

    /** Nada que respaldar todavía: sin color. */
    NEUTRAL,

    /** El funcionamiento normal: pendiente, subiendo, esperando red o entregada. No es un error. */
    PENDING,

    /** Algo pide atención sin estar roto: sin destino, en pausa, un día sin copia. */
    WARNING,

    /** Algo falló o se perdió el acceso. */
    ERROR,
}

/**
 * Cómo se ve un estado del respaldo en el chip y en el banner: su texto ya escrito, su tono y su
 * icono, o el indicador circular mientras sube o restaura.
 */
@Immutable
data class BackupStatusLook(
    val text: String,
    val tone: BackupTone,
    @param:DrawableRes val icon: Int,
    val busy: Boolean = false,
)

/** El color fuerte de cada tono, de la paleta vigente. */
@Composable
fun backupToneColor(tone: BackupTone): Color = when (tone) {
    BackupTone.OK -> AppTheme.colors.backupOk
    BackupTone.NEUTRAL -> MaterialTheme.colorScheme.onSurfaceVariant
    BackupTone.PENDING -> AppTheme.colors.backupPending
    BackupTone.WARNING -> AppTheme.colors.warning
    BackupTone.ERROR -> AppTheme.colors.backupError
}

/** El fondo y el contenido del contenedor de cada tono. */
@Composable
fun backupToneContainer(tone: BackupTone): Pair<Color, Color> = when (tone) {
    BackupTone.OK -> AppTheme.colors.backupOkContainer to AppTheme.colors.onBackupOkContainer

    BackupTone.NEUTRAL ->
        MaterialTheme.colorScheme.surfaceContainerHigh to MaterialTheme.colorScheme.onSurface

    BackupTone.PENDING ->
        AppTheme.colors.backupPendingContainer to AppTheme.colors.onBackupPendingContainer

    BackupTone.WARNING -> AppTheme.colors.warningContainer to AppTheme.colors.onWarningContainer

    BackupTone.ERROR ->
        AppTheme.colors.backupErrorContainer to
            AppTheme.colors.onBackupErrorContainer
}

/**
 * El estado del respaldo en la barra de Inicio: un punto del color del estado, o el indicador
 * mientras sube, y una frase corta. Mide 32 dp a la vista dentro de 48 dp táctiles, y abre la
 * pantalla del respaldo. TalkBack lo lee como `Respaldo: {estado}`.
 *
 * Si la frase no cabe, se parte en otra línea: nunca se corta, porque un estado a medias engaña.
 */
@Composable
fun BackupStatusChip(look: BackupStatusLook, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val spacing = AppTheme.spacing
    val spoken = stringResource(R.string.a11y_backup_chip, look.text)
    Box(
        modifier
            .heightIn(min = AppTheme.sizes.touchTarget)
            .clearAndSetSemantics {
                contentDescription = spoken
                role = Role.Button
            }
            .clickable(onClick = onClick),
        contentAlignment = Alignment.CenterStart,
    ) {
        Row(
            Modifier
                .heightIn(min = AppTheme.spacing.s32)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape)
                .padding(horizontal = spacing.s12, vertical = spacing.s4),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.s8),
        ) {
            val color = backupToneColor(look.tone)
            if (look.busy) {
                CircularProgressIndicator(
                    modifier = Modifier.size(AppTheme.spacing.s12),
                    color = color,
                    strokeWidth = AppTheme.sizes.progressStroke,
                )
            } else {
                Box(Modifier.size(AppTheme.spacing.s8).background(color, CircleShape))
            }
            Text(
                look.text,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

/**
 * Un estado del respaldo que pide atención, bajo la barra: su icono, una frase y una acción. Con
 * tono de advertencia usa el contenedor de advertencia, y con error el de error del respaldo. Nunca
 * aparece en la venta.
 */
@Composable
fun BackupStatusBanner(
    look: BackupStatusLook,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: () -> Unit = {},
) {
    val (container, content) = backupToneContainer(look.tone)
    Column(
        modifier
            .fillMaxWidth()
            .background(container, MaterialTheme.shapes.medium)
            .padding(AppTheme.spacing.s12),
    ) {
        Row(
            Modifier.semantics(mergeDescendants = true) {},
            horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.s12),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painter = painterResource(look.icon),
                contentDescription = null,
                tint = content,
                modifier = Modifier.size(AppTheme.sizes.iconInline),
            )
            Text(look.text, style = MaterialTheme.typography.bodyMedium, color = content)
        }
        if (actionLabel != null) {
            InlineTextButton(
                text = actionLabel,
                onClick = onAction,
                color = content,
                modifier = Modifier.align(Alignment.End),
            )
        }
    }
}

@ThemePreviews
@Composable
private fun BackupStatusPreview() {
    PreviewTheme {
        Column(
            Modifier.padding(AppTheme.spacing.s16),
            verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.s8),
        ) {
            val upToDate =
                BackupStatusLook(
                    stringResource(R.string.backup_last_copy_moment),
                    BackupTone.OK,
                    R.drawable.ic_cloud_done,
                )
            BackupStatusChip(upToDate, onClick = {})
            BackupStatusChip(
                BackupStatusLook(
                    stringResource(R.string.backup_state_uploading),
                    BackupTone.PENDING,
                    R.drawable.ic_cloud_upload,
                    busy = true,
                ),
                onClick = {},
            )
            BackupStatusBanner(
                BackupStatusLook(
                    stringResource(R.string.backup_state_stale),
                    BackupTone.WARNING,
                    R.drawable.ic_history,
                ),
                actionLabel = stringResource(R.string.backup_action_review),
            )
            BackupStatusBanner(
                BackupStatusLook(
                    stringResource(R.string.backup_state_failed),
                    BackupTone.ERROR,
                    R.drawable.ic_error,
                ),
                actionLabel = stringResource(R.string.backup_action_review),
            )
        }
    }
}
