package io.github.yesidrobayo.shopledger.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
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
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.ui.theme.AppTheme
import io.github.yesidrobayo.shopledger.ui.theme.PreviewTheme
import io.github.yesidrobayo.shopledger.ui.theme.ThemePreviews

/** El tipo de un aviso, que decide su fondo y su icono por defecto. */
enum class NoticeVariant {
    /** Información neutra o una ayuda. */
    INFO,

    /** Algo que conviene revisar, sin urgencia. */
    WARNING,

    /** Algo que falló y se puede arreglar. */
    ERROR,
}

/**
 * Un aviso que explica algo sin bloquear: un icono, una o dos frases y, si hace falta, una acción
 * de texto. El tipo se lee en el icono y en la frase, no solo en el fondo.
 *
 * @param icon otro icono que el del tipo, cuando el aviso habla de algo concreto.
 */
@Composable
fun NoticeCard(
    text: String,
    variant: NoticeVariant,
    modifier: Modifier = Modifier,
    @DrawableRes icon: Int? = null,
    actionLabel: String? = null,
    onAction: () -> Unit = {},
) {
    val (container, content) = noticeColors(variant)
    val shownIcon =
        icon ?: when (variant) {
            NoticeVariant.INFO -> R.drawable.ic_info
            NoticeVariant.WARNING -> R.drawable.ic_warning
            NoticeVariant.ERROR -> R.drawable.ic_error
        }
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .background(container, MaterialTheme.shapes.medium)
                .padding(AppTheme.spacing.s12),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.s12)) {
            Icon(
                painter = painterResource(shownIcon),
                contentDescription = null,
                tint = content,
                modifier = Modifier.size(AppTheme.sizes.iconInline),
            )
            Text(text = text, style = MaterialTheme.typography.bodyMedium, color = content)
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

/** El fondo y el contenido de cada tipo, de la paleta vigente. */
@Composable
private fun noticeColors(variant: NoticeVariant): Pair<Color, Color> = when (variant) {
    NoticeVariant.INFO ->
        MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer

    NoticeVariant.WARNING -> AppTheme.colors.warningContainer to AppTheme.colors.onWarningContainer

    NoticeVariant.ERROR ->
        MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
}

@ThemePreviews
@Composable
private fun NoticeCardPreview() {
    PreviewTheme {
        Column(
            modifier = Modifier.padding(AppTheme.spacing.s16),
            verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.s12),
        ) {
            NoticeCard(PreviewSamples.NOTICE_TEXT, NoticeVariant.INFO)
            NoticeCard(
                stringResource(R.string.notice_negative_margin),
                NoticeVariant.WARNING,
                icon = R.drawable.ic_trending_down,
            )
            NoticeCard(
                stringResource(R.string.state_error_read),
                NoticeVariant.ERROR,
                actionLabel = stringResource(R.string.action_retry),
            )
        }
    }
}
