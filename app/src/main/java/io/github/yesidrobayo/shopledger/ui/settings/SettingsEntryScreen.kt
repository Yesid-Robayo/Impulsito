package io.github.yesidrobayo.shopledger.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.navigation.AppNavKey
import io.github.yesidrobayo.shopledger.ui.components.AppTopBar
import io.github.yesidrobayo.shopledger.ui.components.NoticeCard
import io.github.yesidrobayo.shopledger.ui.components.NoticeVariant
import io.github.yesidrobayo.shopledger.ui.components.SectionHeader
import io.github.yesidrobayo.shopledger.ui.components.SettingsRow
import io.github.yesidrobayo.shopledger.ui.components.SettingsRowAction
import io.github.yesidrobayo.shopledger.ui.components.TopBarNavigation
import io.github.yesidrobayo.shopledger.ui.theme.AppTheme
import io.github.yesidrobayo.shopledger.ui.theme.PreviewTheme
import io.github.yesidrobayo.shopledger.ui.theme.ThemePreviews

/**
 * Los ajustes mientras no tengan su pantalla completa: el grupo `Región e idioma`, para llegar a la
 * configuración regional y al idioma, y en `Ventas y cobro` el interruptor `Propinas`, que el cobro
 * necesita para ofrecerlas. La pantalla de ajustes, con todos sus grupos, la reemplaza; aquí no hay
 * nada más.
 *
 * @param tipsEnabled si las propinas están encendidas, o `null` mientras se lee: la fila no se
 *   dibuja hasta saberlo.
 * @param tipsSaveFailed si el último cambio de las propinas no se pudo guardar.
 *
 * @param onOpen abre la pantalla de una fila.
 */
@Composable
fun SettingsEntryScreen(
    onOpen: (AppNavKey) -> Unit,
    onBack: () -> Unit,
    tipsEnabled: Boolean? = null,
    onTipsChange: (Boolean) -> Unit = {},
    tipsSaveFailed: Boolean = false,
) {
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        AppTopBar(
            title = stringResource(R.string.settings_title),
            navigation = TopBarNavigation.BACK,
            onNavigate = onBack,
            navigationLabel = stringResource(R.string.a11y_back),
            titleStyle = MaterialTheme.typography.titleLarge,
        )
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(Modifier.widthIn(max = AppTheme.sizes.maxContentWidth).fillMaxWidth()) {
                SectionHeader(stringResource(R.string.settings_group_region))
                SettingsRow(
                    title = stringResource(R.string.region_title),
                    action = SettingsRowAction.Navigation { onOpen(AppNavKey.RegionalSettings()) },
                )
                SettingsRow(
                    title = stringResource(R.string.language_title),
                    action = SettingsRowAction.Navigation { onOpen(AppNavKey.LanguageSettings) },
                )
                if (tipsEnabled != null) {
                    SectionHeader(stringResource(R.string.settings_group_sales))
                    SettingsRow(
                        title = stringResource(R.string.settings_tips),
                        action = SettingsRowAction.Toggle(tipsEnabled, onTipsChange),
                    )
                    if (tipsSaveFailed) {
                        NoticeCard(
                            text = stringResource(R.string.state_error_save),
                            variant = NoticeVariant.ERROR,
                            modifier = Modifier.padding(horizontal = AppTheme.spacing.s16),
                        )
                    }
                }
            }
        }
    }
}

@ThemePreviews
@Composable
private fun SettingsEntryScreenPreview() {
    PreviewTheme { SettingsEntryScreen(onOpen = {}, onBack = {}, tipsEnabled = true) }
}
