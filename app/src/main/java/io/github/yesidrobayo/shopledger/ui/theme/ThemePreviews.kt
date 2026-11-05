package io.github.yesidrobayo.shopledger.ui.theme

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import io.github.yesidrobayo.shopledger.domain.model.settings.ColorPalette

/** Las tres vistas previas que pide todo composable no trivial: claro, oscuro y letra al 200 %. */
@Preview(name = "Claro")
@Preview(
    name = "Oscuro",
    uiMode =
        Configuration.UI_MODE_NIGHT_YES or Configuration.UI_MODE_TYPE_NORMAL,
)
@Preview(name = "Letra al 200 %", fontScale = 2f)
annotation class ThemePreviews

/** El tema de una vista previa: la paleta pedida, en el modo de la vista previa, sobre el papel. */
@Composable
fun PreviewTheme(palette: ColorPalette = DefaultColorPalette, content: @Composable () -> Unit) {
    AppTheme(palette = palette, darkTheme = isSystemInDarkTheme()) {
        Box(Modifier.background(MaterialTheme.colorScheme.surface)) { content() }
    }
}
