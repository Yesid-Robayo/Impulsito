package io.github.yesidrobayo.shopledger.ui.i18n

import android.content.res.Resources
import android.icu.text.DisplayContext
import android.icu.text.LocaleDisplayNames
import android.icu.util.ULocale
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.ShopLedgerApp
import io.github.yesidrobayo.shopledger.localization.InstalledLanguages
import io.github.yesidrobayo.shopledger.ui.components.AppSnackbarHost
import io.github.yesidrobayo.shopledger.ui.components.AppSnackbarVisuals
import io.github.yesidrobayo.shopledger.ui.components.AppTopBar
import io.github.yesidrobayo.shopledger.ui.components.LanguageOption
import io.github.yesidrobayo.shopledger.ui.components.LanguagePicker
import io.github.yesidrobayo.shopledger.ui.components.NoticeCard
import io.github.yesidrobayo.shopledger.ui.components.NoticeVariant
import io.github.yesidrobayo.shopledger.ui.components.SettingsRow
import io.github.yesidrobayo.shopledger.ui.components.SettingsRowAction
import io.github.yesidrobayo.shopledger.ui.components.SnackbarVariant
import io.github.yesidrobayo.shopledger.ui.components.TopBarNavigation
import io.github.yesidrobayo.shopledger.ui.theme.AppTheme
import io.github.yesidrobayo.shopledger.ui.theme.PreviewTheme
import io.github.yesidrobayo.shopledger.ui.theme.ThemePreviews
import java.util.Locale

/**
 * Lo que la pantalla de idioma sabe del teléfono, ya escrito para mostrarse.
 *
 * @property options los idiomas de la app, cada uno con su nombre en su propio idioma.
 * @property phoneLanguage el idioma del teléfono, en el de la interfaz.
 * @property phoneLanguageInstalled si la app trae el idioma del teléfono. Si no, siguiendo al
 *   teléfono se ve en inglés, y la pantalla lo dice.
 * @property androidSettingsHelp si Android tiene su propio ajuste de idioma por app, desde Android 13.
 */
data class LanguageScreenFacts(
    val options: List<LanguageOption>,
    val phoneLanguage: String,
    val phoneLanguageInstalled: Boolean,
    val androidSettingsHelp: Boolean,
)

/**
 * La pantalla de idioma: elegir el de la interfaz sin tocar la moneda ni los formatos. Tocar un
 * idioma lo aplica al instante, sin botón de guardar.
 *
 * Es pura: recibe el estado y avisa cada acción.
 *
 * @param onOpenFormat abre la configuración regional, o `null` donde no se puede, como en el
 *   rescate, que funciona sin la base.
 */
@Composable
fun LanguageSettingsScreen(
    uiState: LanguageSettingsUiState,
    facts: LanguageScreenFacts,
    onAction: (LanguageSettingsAction) -> Unit,
    onBack: () -> Unit,
    onOpenFormat: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val spacing = AppTheme.spacing
    val snackbar = remember { SnackbarHostState() }
    val updated = stringResource(R.string.language_updated)
    val selectedName =
        uiState.selected?.let { tag -> facts.options.firstOrNull { it.tag == tag }?.name }
            ?: stringResource(R.string.language_system)
    val notSaved = stringResource(R.string.language_error_save, selectedName)
    val retry = stringResource(R.string.action_retry)
    // La actividad no se recrea al cambiar de idioma: si el aviso llega antes que los textos
    // nuevos, vuelve a mostrarse con ellos, y nunca queda en el idioma de antes.
    LaunchedEffect(uiState.notice, updated, notSaved, retry) {
        when (uiState.notice) {
            null -> Unit

            LanguageNotice.UPDATED -> {
                snackbar.showSnackbar(AppSnackbarVisuals(updated, SnackbarVariant.SUCCESS))
                onAction(LanguageSettingsAction.NoticeShown)
            }

            LanguageNotice.NOT_SAVED -> {
                val result =
                    snackbar.showSnackbar(
                        AppSnackbarVisuals(notSaved, SnackbarVariant.ERROR, retry),
                    )
                onAction(LanguageSettingsAction.NoticeShown)
                if (result == SnackbarResult.ActionPerformed) onAction(LanguageSettingsAction.Retry)
            }
        }
    }
    Box(modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        Column(Modifier.fillMaxSize()) {
            AppTopBar(
                title = stringResource(R.string.language_title),
                navigation = TopBarNavigation.BACK,
                onNavigate = onBack,
                navigationLabel = stringResource(R.string.a11y_back),
                titleStyle = MaterialTheme.typography.titleLarge,
            )
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .navigationBarsPadding(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Column(
                    Modifier
                        .widthIn(max = AppTheme.sizes.maxContentWidth)
                        .fillMaxWidth()
                        .padding(vertical = spacing.s16),
                    verticalArrangement = Arrangement.spacedBy(spacing.s8),
                ) {
                    NoticeCard(
                        stringResource(R.string.language_notice),
                        NoticeVariant.INFO,
                        Modifier.padding(horizontal = spacing.s16),
                    )
                    LanguagePicker(
                        options = facts.options,
                        selected = uiState.selected,
                        phoneLanguage = facts.phoneLanguage,
                        onSelect = { onAction(LanguageSettingsAction.Choose(it)) },
                    )
                    if (!facts.phoneLanguageInstalled) {
                        Help(stringResource(R.string.language_fallback_help))
                    }
                    if (onOpenFormat != null) {
                        SettingsRow(
                            title = stringResource(R.string.language_format_action),
                            action = SettingsRowAction.Navigation(onOpenFormat),
                        )
                    }
                    if (facts.androidSettingsHelp) {
                        Help(stringResource(R.string.language_system_settings_help))
                    }
                }
            }
        }
        AppSnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding())
    }
}

@Composable
private fun Help(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = AppTheme.spacing.s16),
    )
}

/**
 * La pantalla de idioma en la navegación. No espera a la base: el idioma vive fuera de ella.
 *
 * @param onOpenFormat abre la configuración regional, o `null` si no hay base.
 */
@Composable
fun LanguageSettingsRoute(onBack: () -> Unit, onOpenFormat: (() -> Unit)?) {
    val context = LocalContext.current
    val container = (context.applicationContext as ShopLedgerApp).container
    val resources = context.resources
    val viewModel: LanguageSettingsViewModel =
        viewModel(
            factory =
                viewModelFactory {
                    initializer {
                        LanguageSettingsViewModel(
                            language = container.language.language,
                            choose = container.language::choose,
                            installed = InstalledLanguages.tags(resources),
                        )
                    }
                },
        )
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val uiTag = stringResource(R.string.resources_language)
    val facts = remember(uiState.installed, uiTag) { languageFacts(uiState.installed, uiTag) }
    LanguageSettingsScreen(uiState, facts, viewModel::onAction, onBack, onOpenFormat)
}

/**
 * Los nombres de los idiomas: cada uno en su propio idioma, y el del teléfono en el de la interfaz
 * [uiTag]. El idioma del teléfono sale de la configuración del sistema, nunca del idioma por
 * defecto del proceso, que cambia con el elegido en la app.
 */
internal fun languageFacts(installed: List<String>, uiTag: String): LanguageScreenFacts {
    val phone = Resources.getSystem().configuration.locales.get(0) ?: Locale.ROOT
    val ui = ULocale.forLanguageTag(uiTag)
    val phoneName =
        LocaleDisplayNames
            .getInstance(ui, DisplayContext.CAPITALIZATION_FOR_MIDDLE_OF_SENTENCE)
            .localeDisplayName(ULocale.forLocale(phone))
    return LanguageScreenFacts(
        options = installed.map { tag -> LanguageOption(tag, ownName(tag)) },
        phoneLanguage = phoneName,
        phoneLanguageInstalled =
            installed.any { Locale.forLanguageTag(it).language == phone.language },
        androidSettingsHelp = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU,
    )
}

/** El nombre de [tag] escrito en ese mismo idioma, con mayúscula para una lista. */
internal fun ownName(tag: String): String {
    val locale = ULocale.forLanguageTag(tag)
    return LocaleDisplayNames
        .getInstance(locale, DisplayContext.CAPITALIZATION_FOR_UI_LIST_OR_MENU)
        .localeDisplayName(locale)
}

@ThemePreviews
@Composable
private fun LanguageSettingsScreenPreview() {
    PreviewTheme {
        LanguageSettingsScreen(
            uiState = LanguageSettingsUiState(
                selected = "es",
                installed = listOf("en", "es", "pt"),
            ),
            facts =
                LanguageScreenFacts(
                    options =
                        listOf(
                            LanguageOption("en", "English"),
                            LanguageOption("es", "Español"),
                            LanguageOption("pt", "Português"),
                        ),
                    phoneLanguage = "francés",
                    phoneLanguageInstalled = false,
                    androidSettingsHelp = true,
                ),
            onAction = {},
            onBack = {},
            onOpenFormat = {},
        )
    }
}
