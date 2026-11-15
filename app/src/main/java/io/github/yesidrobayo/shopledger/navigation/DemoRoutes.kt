package io.github.yesidrobayo.shopledger.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.ui.components.TopBarNavigation
import io.github.yesidrobayo.shopledger.ui.placeholder.EmptyContent
import io.github.yesidrobayo.shopledger.ui.placeholder.PlaceholderAction
import io.github.yesidrobayo.shopledger.ui.placeholder.PlaceholderContent
import io.github.yesidrobayo.shopledger.ui.placeholder.PlaceholderScreen
import io.github.yesidrobayo.shopledger.ui.placeholder.PlaceholderUiState

/**
 * Las pantallas que dependen de la nube: el respaldo, el cifrado, las copias locales, la cuenta de
 * la nube y conectar un destino. Con datos de ejemplo no hay copias: se muestran desactivadas, con
 * la razón, y ninguna llega a la base del negocio ni a un destino.
 */
internal fun AppNavKey.needsCloud(): Boolean = this in CLOUD_SCREENS ||
    this is AppNavKey.BackupDestinationChoice ||
    this is AppNavKey.WebDavConnect

private val CLOUD_SCREENS: Set<AppNavKey> =
    setOf(
        AppNavKey.Backup,
        AppNavKey.BackupEncryption,
        AppNavKey.LocalCopies,
        AppNavKey.CloudAccountSecurity,
        AppNavKey.DocumentSlotsSetup,
    )

/**
 * La pantalla de [key] en la demostración, cuando depende de la nube: su barra, y en lugar de su
 * contenido, que los datos de ejemplo no se respaldan. Atrás vuelve, como siempre.
 */
@Composable
internal fun DemoUnavailableRoute(key: AppNavKey, navigator: AppNavigator) {
    val spec = screenSpecOf(key)
    val navigationLabel =
        when (spec.navigation) {
            TopBarNavigation.NONE -> null
            TopBarNavigation.BACK -> stringResource(R.string.a11y_back)
            TopBarNavigation.CLOSE -> stringResource(R.string.a11y_close)
        }
    PlaceholderScreen(
        content =
            PlaceholderContent(
                title = stringResource(spec.title),
                navigation = spec.navigation,
                navigationLabel = navigationLabel,
                icon = R.drawable.ic_science,
                empty =
                    EmptyContent(
                        title = stringResource(R.string.demo_backup_title),
                        body = stringResource(R.string.demo_backup_unavailable),
                    ),
                withBottomBar = false,
            ),
        uiState = PlaceholderUiState.Empty,
        onAction = { action ->
            when (action) {
                PlaceholderAction.Navigate -> navigator.back()

                PlaceholderAction.OpenSettings -> navigator.open(AppNavKey.Settings)

                PlaceholderAction.OpenBusiness -> navigator.open(AppNavKey.BusinessHub)

                // Sin acción en el estado vacío ni nada que reintentar.
                PlaceholderAction.EmptyAction, PlaceholderAction.Retry -> Unit
            }
        },
    )
}
