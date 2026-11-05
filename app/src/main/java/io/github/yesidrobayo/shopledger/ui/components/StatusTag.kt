package io.github.yesidrobayo.shopledger.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
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
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.ui.theme.AppTheme
import io.github.yesidrobayo.shopledger.ui.theme.PreviewTheme
import io.github.yesidrobayo.shopledger.ui.theme.ThemePreviews

/** El color de una etiqueta: neutro, o el contenedor de un estado del stock o del dinero. */
enum class StatusTone {
    /** Datos que faltan o están archivados: `Sin costo`, `Anulada`, `Archivado`. */
    NEUTRAL,

    /** Por agotarse, o algo que conviene revisar. */
    WARNING,

    /** Agotado, stock negativo o vencido. */
    LOSS,

    /** Un estado favorable. */
    PROFIT,

    /** Información, sin juicio. */
    INFO,
}

/**
 * Una etiqueta de estado: texto corto en una caja de 24 dp, con icono opcional. Nunca depende solo
 * del color: el texto dice el estado, y el icono lo refuerza.
 */
@Composable
fun StatusTag(
    text: String,
    modifier: Modifier = Modifier,
    @DrawableRes icon: Int? = null,
    tone: StatusTone = StatusTone.NEUTRAL,
) {
    val (container, content) = statusColors(tone)
    Row(
        modifier =
            modifier
                .heightIn(min = AppTheme.sizes.statusTag)
                .background(container, MaterialTheme.shapes.extraSmall)
                .padding(horizontal = AppTheme.spacing.s8, vertical = AppTheme.spacing.s2),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.s4),
    ) {
        if (icon != null) {
            Icon(
                painter = painterResource(icon),
                contentDescription = null,
                tint = content,
                modifier = Modifier.size(AppTheme.sizes.iconTag),
            )
        }
        Text(text = text, style = MaterialTheme.typography.labelSmall, color = content)
    }
}

/** El fondo y el contenido de cada tono, de la paleta vigente. */
@Composable
private fun statusColors(tone: StatusTone): Pair<Color, Color> {
    val scheme = MaterialTheme.colorScheme
    val app = AppTheme.colors
    return when (tone) {
        StatusTone.NEUTRAL -> scheme.surfaceContainerHigh to scheme.onSurfaceVariant
        StatusTone.WARNING -> app.warningContainer to app.onWarningContainer
        StatusTone.LOSS -> app.lossContainer to app.onLossContainer
        StatusTone.PROFIT -> app.profitContainer to app.onProfitContainer
        StatusTone.INFO -> scheme.tertiaryContainer to scheme.onTertiaryContainer
    }
}

@ThemePreviews
@Composable
private fun StatusTagPreview() {
    PreviewTheme {
        FlowRow(
            modifier = Modifier.padding(AppTheme.spacing.s16),
            horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.s8),
            verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.s8),
        ) {
            StatusTag(stringResource(R.string.status_no_cost))
            StatusTag(stringResource(R.string.notice_no_price))
            StatusTag(stringResource(R.string.status_archived))
            StatusTag(stringResource(R.string.status_voided))
            StatusTag(
                stringResource(R.string.term_low_stock),
                icon = R.drawable.ic_warning,
                tone = StatusTone.WARNING,
            )
            StatusTag(
                stringResource(R.string.term_out_of_stock),
                icon = R.drawable.ic_block,
                tone = StatusTone.LOSS,
            )
            StatusTag(stringResource(R.string.a11y_today), tone = StatusTone.INFO)
        }
    }
}
