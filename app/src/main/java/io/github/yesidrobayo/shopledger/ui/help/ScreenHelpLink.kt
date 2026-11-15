package io.github.yesidrobayo.shopledger.ui.help

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.res.stringResource
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.ui.components.AppTopBar
import io.github.yesidrobayo.shopledger.ui.components.HelpLink
import io.github.yesidrobayo.shopledger.ui.components.TopBarNavigation
import io.github.yesidrobayo.shopledger.ui.theme.PreviewTheme
import io.github.yesidrobayo.shopledger.ui.theme.ThemePreviews

/**
 * Abre la ayuda de un tema. Lo provee la navegación, que la abre encima de la pantalla visible.
 * Fuera de ella, en vistas previas y pruebas de una pantalla suelta, no hace nada.
 */
val LocalHelpOpener = staticCompositionLocalOf<(HelpTopic) -> Unit> { {} }

/**
 * El enlace de ayuda de una pantalla, en su barra superior: lleva directo a su artículo, o a la
 * lista con `Para esta pantalla` si tiene varios. TalkBack lo nombra con el título del primero.
 *
 * Va antes del engranaje de los ajustes, que siempre queda último.
 */
@Composable
fun ScreenHelpLink(topic: HelpTopic) {
    val open = LocalHelpOpener.current
    HelpLink(helpTitle(topic.primary)) { open(topic) }
}

/**
 * El título de [article] en el idioma de la interfaz. Un título puede nombrar la app, como `Lo que
 * {app} no cubre`: el nombre solo vive en sus recursos.
 */
@Composable
fun helpTitle(article: HelpArticleId): String =
    stringResource(article.title, stringResource(R.string.app_name))

@ThemePreviews
@Composable
private fun ScreenHelpLinkPreview() {
    PreviewTheme {
        AppTopBar(
            title = stringResource(R.string.backup_title),
            navigation = TopBarNavigation.BACK,
            onNavigate = {},
            navigationLabel = stringResource(R.string.a11y_back),
            actions = { ScreenHelpLink(HelpTopic.BACKUP) },
        )
    }
}
