package io.github.yesidrobayo.shopledger.ui.money

import androidx.compose.runtime.Composable
import io.github.yesidrobayo.shopledger.ui.components.TopBarNavigation
import io.github.yesidrobayo.shopledger.ui.theme.PreviewTheme
import io.github.yesidrobayo.shopledger.ui.theme.ThemePreviews

// Vistas previas de las cuentas de dinero en claro, oscuro y letra al 200 %, con un nombre de 80
// caracteres y un monto largo en negativo. Las hojas se ven en las capturas del emulador.

@ThemePreviews
@Composable
private fun MoneyAccountsListPreview() {
    PreviewTheme { MoneyAccountsScreen(SAMPLE_LIST_STATE, {}) }
}

@ThemePreviews
@Composable
private fun MoneyAccountsEmptyPreview() {
    PreviewTheme {
        MoneyAccountsScreen(
            SAMPLE_LIST_STATE.copy(content = MoneyAccountsContent.Ready(SAMPLE_EMPTY_VIEW)),
            {},
        )
    }
}

@ThemePreviews
@Composable
private fun MoneyAccountsDiscreetPreview() {
    PreviewTheme(discreet = true) { MoneyAccountsScreen(SAMPLE_LIST_STATE, {}) }
}

@ThemePreviews
@Composable
private fun AccountDetailPreview() {
    PreviewTheme { AccountDetailScreen(SAMPLE_DETAIL_STATE, {}, TopBarNavigation.BACK) }
}

@ThemePreviews
@Composable
private fun AccountDetailEmptyPreview() {
    PreviewTheme {
        val ready = SAMPLE_DETAIL_STATE.detail as AccountDetailContent.Ready
        AccountDetailScreen(
            SAMPLE_DETAIL_STATE.copy(detail = ready.copy(days = emptyList())),
            {},
            TopBarNavigation.BACK,
        )
    }
}
