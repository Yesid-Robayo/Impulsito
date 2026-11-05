package io.github.yesidrobayo.shopledger.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.isSensitiveData
import androidx.compose.ui.text.TextStyle
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.ui.format.LocalDiscreetMode
import io.github.yesidrobayo.shopledger.ui.theme.AppTheme
import io.github.yesidrobayo.shopledger.ui.theme.PreviewTheme
import io.github.yesidrobayo.shopledger.ui.theme.ThemePreviews

/**
 * Envuelve una cifra sensible, como un costo, una ganancia o un saldo. Con el modo discreto
 * encendido dibuja `••••` en el estilo de la cifra y en tono apagado; si no, dibuja [content].
 *
 * Los puntos son siempre cuatro, sea cual sea la cifra, y no se revelan al tocarlos: la cifra vuelve
 * con el icono de la barra. TalkBack dice «Oculto», y el nodo va marcado como dato sensible para que
 * los servicios de accesibilidad no lo copien.
 *
 * @param style el estilo de `AppTheme.amounts` que tendría la cifra, para que los puntos midan lo
 *   mismo en alto y la fila no salte al cambiar de modo.
 */
@Composable
fun DiscreetValue(
    modifier: Modifier = Modifier,
    style: TextStyle = AppTheme.amounts.row,
    content: @Composable () -> Unit,
) {
    if (LocalDiscreetMode.current) {
        val hidden = stringResource(R.string.a11y_discreet_hidden)
        Text(
            text = DISCREET_TEXT,
            style = style,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            softWrap = false,
            modifier =
                modifier.clearAndSetSemantics {
                    contentDescription = hidden
                    isSensitiveData = true
                },
        )
    } else {
        content()
    }
}

@ThemePreviews
@Composable
private fun DiscreetValuePreview() {
    PreviewTheme(discreet = true) {
        Column(
            modifier = Modifier.padding(AppTheme.spacing.s16),
            verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.s8),
        ) {
            for ((_, style) in AppTheme.amounts.all.drop(2)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    DiscreetValue(style = style) {}
                }
            }
        }
    }
}
