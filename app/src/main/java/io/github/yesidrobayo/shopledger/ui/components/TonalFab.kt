package io.github.yesidrobayo.shopledger.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.ui.theme.AppTheme
import io.github.yesidrobayo.shopledger.ui.theme.PreviewTheme
import io.github.yesidrobayo.shopledger.ui.theme.ThemePreviews

/**
 * El botón flotante `Tonal` extendido: crear algo nuevo en una lista, como `Nueva tasa` o
 * `Nuevo producto`. Va abajo a la derecha, sobre la lista, al alcance del pulgar.
 *
 * Es una acción secundaria importante, así que usa el relleno tonal y no el de marca: la acción
 * principal de la app es vender. Lleva siempre el texto junto al icono, porque un `+` solo no dice
 * qué crea. Sin sombra: la regla de la app solo se la da al botón de vender, y lo separa de la
 * lista su relleno.
 *
 * Con [outlined] lleva el filo de 1 dp de las tarjetas: separa más el botón de una lista con el mismo
 * papel, a costa de un borde más. Por defecto va sin él.
 *
 * Se aparta solo de la barra de navegación del sistema y del teclado. Quien lo usa lo pone en un
 * `Box` sobre la lista con `Alignment.BottomEnd`, y deja al final de la lista el espacio
 * [TonalFabDefaults.listEndPadding], para que la última fila no quede debajo.
 */
@Composable
fun TonalFab(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    @DrawableRes icon: Int = R.drawable.ic_add,
    outlined: Boolean = false,
) {
    val spacing = AppTheme.spacing
    Surface(
        onClick = onClick,
        modifier =
            modifier
                .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime))
                .padding(spacing.s16)
                .heightIn(min = AppTheme.sizes.primaryButton)
                .semantics { role = Role.Button },
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        // Sin elevación tonal ni sombra: lo separa su relleno.
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
        border = if (outlined) {
            BorderStroke(
                AppTheme.sizes.hairline,
                AppTheme.colors.cardBorder,
            )
        } else {
            null
        },
    ) {
        Row(
            modifier = Modifier.padding(start = spacing.s16, end = spacing.s20),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.s12),
        ) {
            // El texto ya dice qué hace: el icono no se lee aparte.
            Icon(
                painter = painterResource(icon),
                contentDescription = null,
                modifier = Modifier.size(AppTheme.sizes.icon),
            )
            Text(
                text = text,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(vertical = spacing.s16),
            )
        }
    }
}

/** Medidas que acompañan al botón flotante. */
object TonalFabDefaults {
    /**
     * El espacio al final de una lista con botón flotante: su alto y sus márgenes, para que la
     * última fila se pueda ver y tocar por encima de él.
     */
    val listEndPadding = AppTheme.sizes.primaryButton + AppTheme.spacing.s32
}

@ThemePreviews
@Composable
private fun TonalFabPreview() {
    val s = PreviewSamples
    PreviewTheme {
        Box {
            Column(Modifier.fillMaxWidth()) {
                listOf(s.SHORT_NAME, s.SECOND_NAME, s.THIRD_NAME, s.LONG_NAME).forEach {
                    SettingsRow(it, SettingsRowAction.Navigation {})
                }
            }
            TonalFab(
                text = stringResource(R.string.product_new_title),
                onClick = {},
                modifier = Modifier.align(Alignment.BottomEnd),
            )
        }
    }
}
