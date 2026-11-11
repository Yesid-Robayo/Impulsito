package io.github.yesidrobayo.shopledger.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.ui.theme.PreviewTheme
import io.github.yesidrobayo.shopledger.ui.theme.ThemePreviews

/**
 * La ayuda de una pantalla: el icono `help` de 48 dp en la barra superior, que abre su artículo.
 * TalkBack dice `Ayuda:` y el tema, para que no sean varios botones iguales sin nombre.
 *
 * @param topic el tema del artículo, ya escrito.
 */
@Composable
fun HelpLink(topic: String, onClick: () -> Unit) {
    TopBarAction(R.drawable.ic_help, stringResource(R.string.a11y_help_link, topic), onClick)
}

@ThemePreviews
@Composable
private fun HelpLinkPreview() {
    PreviewTheme {
        AppTopBar(
            title = stringResource(R.string.slots_title),
            navigation = TopBarNavigation.BACK,
            onNavigate = {},
            navigationLabel = stringResource(R.string.a11y_back),
            actions = { HelpLink(stringResource(R.string.destination_title)) {} },
        )
    }
}
