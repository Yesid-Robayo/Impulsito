package io.github.yesidrobayo.shopledger.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
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
import io.github.yesidrobayo.shopledger.ui.components.SectionHeader
import io.github.yesidrobayo.shopledger.ui.components.SettingsRow
import io.github.yesidrobayo.shopledger.ui.components.SettingsRowAction
import io.github.yesidrobayo.shopledger.ui.components.TopBarNavigation
import io.github.yesidrobayo.shopledger.ui.theme.AppTheme
import io.github.yesidrobayo.shopledger.ui.theme.PreviewTheme
import io.github.yesidrobayo.shopledger.ui.theme.ThemePreviews

/**
 * Los ajustes mientras no tengan su pantalla completa: solo el grupo `Región e idioma`, para llegar
 * a la configuración regional y al idioma. La pantalla de ajustes, con todos sus grupos, la
 * reemplaza; aquí no hay nada más.
 *
 * @param onOpen abre la pantalla de una fila.
 */
@Composable
fun SettingsEntryScreen(onOpen: (AppNavKey) -> Unit, onBack: () -> Unit) {
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
            }
        }
    }
}

@ThemePreviews
@Composable
private fun SettingsEntryScreenPreview() {
    PreviewTheme { SettingsEntryScreen(onOpen = {}, onBack = {}) }
}
