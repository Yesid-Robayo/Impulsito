package io.github.yesidrobayo.shopledger.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.ui.theme.AppTheme
import io.github.yesidrobayo.shopledger.ui.theme.PreviewTheme
import io.github.yesidrobayo.shopledger.ui.theme.ThemePreviews

/** Cómo se sale de una pantalla desde su barra superior. */
enum class TopBarNavigation {
    /** Una raíz de pestaña: no hay a dónde volver desde la barra. */
    NONE,

    /** Un detalle o un formulario: vuelve a la pantalla anterior. */
    BACK,

    /** Una acción que se abre encima, como una venta: se cierra. */
    CLOSE,
}

/**
 * La barra superior: el título a la izquierda y hasta dos acciones a la derecha, sobre el papel y
 * sin sombra. Dibuja bajo la barra de estado y deja su espacio.
 *
 * El título crece con la letra del sistema y pasa a dos líneas antes de cortarse: la barra mide 64
 * dp como mínimo, no como máximo.
 *
 * @param titleStyle `headlineSmall` en una raíz de pestaña, `titleLarge` en un detalle.
 */
@Composable
fun AppTopBar(
    title: String,
    navigation: TopBarNavigation,
    onNavigate: () -> Unit,
    navigationLabel: String?,
    modifier: Modifier = Modifier,
    titleStyle: TextStyle = MaterialTheme.typography.headlineSmall,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val spacing = AppTheme.spacing
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .statusBarsPadding()
                .heightIn(min = AppTheme.sizes.topBar)
                .padding(horizontal = spacing.s4),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.s4),
    ) {
        val icon =
            when (navigation) {
                TopBarNavigation.NONE -> null
                TopBarNavigation.BACK -> R.drawable.ic_arrow_back
                TopBarNavigation.CLOSE -> R.drawable.ic_close
            }
        if (icon != null) {
            TopBarAction(icon = icon, label = navigationLabel, onClick = onNavigate)
        }
        Text(
            text = title,
            style = titleStyle,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier =
                Modifier
                    .weight(1f)
                    // Sin icono, el título queda en el margen lateral de la pantalla.
                    .then(if (icon == null) Modifier.padding(start = spacing.s12) else Modifier)
                    .padding(end = spacing.s4)
                    .padding(vertical = spacing.s8)
                    .semantics { heading() },
        )
        actions()
    }
}

/** Una acción de la barra superior: un icono con su nombre para TalkBack, en un área de 48 dp. */
@Composable
fun TopBarAction(@DrawableRes icon: Int, label: String?, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(AppTheme.sizes.touchTarget)) {
        Icon(
            painter = painterResource(icon),
            contentDescription = label,
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(AppTheme.sizes.icon),
        )
    }
}

@ThemePreviews
@Composable
private fun AppTopBarPreview() {
    PreviewTheme {
        Column {
            AppTopBar(
                title = stringResource(R.string.nav_products),
                navigation = TopBarNavigation.NONE,
                onNavigate = {},
                navigationLabel = null,
                actions = {
                    TopBarAction(R.drawable.ic_settings, stringResource(R.string.a11y_settings)) {}
                },
            )
            // El título más largo de la app, que a letra grande pasa a dos líneas.
            AppTopBar(
                title = stringResource(R.string.transparency_title),
                navigation = TopBarNavigation.BACK,
                onNavigate = {},
                navigationLabel = stringResource(R.string.a11y_back),
                titleStyle = MaterialTheme.typography.titleLarge,
            )
        }
    }
}
