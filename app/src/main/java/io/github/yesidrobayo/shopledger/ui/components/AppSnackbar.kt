package io.github.yesidrobayo.shopledger.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarData
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarVisuals
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.ui.theme.AppTheme
import io.github.yesidrobayo.shopledger.ui.theme.PreviewTheme
import io.github.yesidrobayo.shopledger.ui.theme.ThemePreviews

/** El tipo de un mensaje breve, que decide su icono y cuánto dura. */
enum class SnackbarVariant {
    /** Algo salió bien: 4 s, o 10 s si lleva acción, como `Deshacer`. */
    SUCCESS,

    /** Información: 4 s, sin icono. */
    INFO,

    /** Algo falló: queda hasta que la persona actúe. */
    ERROR,
}

/**
 * Un mensaje breve de la app. Se muestra con [SnackbarHostState.showSnackbar] y [AppSnackbarHost].
 * Uno a la vez, sin acciones destructivas, y nada importante vive solo aquí. Con TalkBack activo,
 * Material alarga la duración según la preferencia del sistema.
 */
class AppSnackbarVisuals(
    override val message: String,
    val variant: SnackbarVariant,
    override val actionLabel: String? = null,
) : SnackbarVisuals {
    override val withDismissAction: Boolean = false

    override val duration: SnackbarDuration =
        when (variant) {
            SnackbarVariant.SUCCESS -> if (actionLabel !=
                null
            ) {
                SnackbarDuration.Long
            } else {
                SnackbarDuration.Short
            }

            SnackbarVariant.INFO -> SnackbarDuration.Short

            SnackbarVariant.ERROR -> SnackbarDuration.Indefinite
        }
}

/**
 * El lugar de los mensajes breves: sobre la barra inferior o sobre el panel de acción, nunca encima
 * del botón principal.
 */
@Composable
fun AppSnackbarHost(hostState: SnackbarHostState, modifier: Modifier = Modifier) {
    SnackbarHost(hostState = hostState, modifier = modifier) { data -> AppSnackbar(data) }
}

/** Un mensaje del anfitrión, con el tipo que traiga, o informativo si no trae ninguno. */
@Composable
fun AppSnackbar(data: SnackbarData, modifier: Modifier = Modifier) {
    val visuals = data.visuals
    AppSnackbarContent(
        message = visuals.message,
        variant = (visuals as? AppSnackbarVisuals)?.variant ?: SnackbarVariant.INFO,
        actionLabel = visuals.actionLabel,
        onAction = data::performAction,
        modifier = modifier,
    )
}

/**
 * El dibujo del mensaje: la superficie inversa, un icono si lo pide el tipo, el texto y la acción en
 * el color de acción inverso. El tipo se lee en el icono y en la frase, no solo en el color.
 */
@Composable
fun AppSnackbarContent(
    message: String,
    variant: SnackbarVariant,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: () -> Unit = {},
) {
    val scheme = MaterialTheme.colorScheme
    val icon =
        when (variant) {
            SnackbarVariant.SUCCESS -> R.drawable.ic_check
            SnackbarVariant.INFO -> null
            SnackbarVariant.ERROR -> R.drawable.ic_error
        }
    Surface(
        modifier = modifier.fillMaxWidth().padding(AppTheme.spacing.s16),
        shape = MaterialTheme.shapes.medium,
        color = scheme.inverseSurface,
        contentColor = scheme.inverseOnSurface,
    ) {
        Row(
            modifier = Modifier.padding(start = AppTheme.spacing.s16, end = AppTheme.spacing.s4),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.s12),
        ) {
            if (icon != null) {
                Icon(
                    painter = painterResource(icon),
                    contentDescription = null,
                    modifier = Modifier.size(AppTheme.sizes.iconInline),
                )
            }
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f).padding(vertical = AppTheme.spacing.s12),
            )
            if (actionLabel != null) {
                InlineTextButton(actionLabel, onAction, color = scheme.inversePrimary)
            }
        }
    }
}

@ThemePreviews
@Composable
private fun AppSnackbarPreview() {
    PreviewTheme {
        Column {
            AppSnackbarContent(
                stringResource(R.string.sale_register),
                SnackbarVariant.SUCCESS,
                actionLabel = stringResource(R.string.action_cancel),
            )
            AppSnackbarContent(PreviewSamples.NOTICE_TEXT, SnackbarVariant.INFO)
            AppSnackbarContent(
                stringResource(R.string.state_error_read),
                SnackbarVariant.ERROR,
                actionLabel = stringResource(R.string.action_retry),
            )
        }
    }
}
