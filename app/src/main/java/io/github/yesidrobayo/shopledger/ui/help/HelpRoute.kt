package io.github.yesidrobayo.shopledger.ui.help

import android.content.res.Resources
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.ShopLedgerApp
import io.github.yesidrobayo.shopledger.domain.rule.region.BusinessCalendarResult
import io.github.yesidrobayo.shopledger.domain.usecase.backup.BackupDestinationReader
import io.github.yesidrobayo.shopledger.domain.usecase.backup.ObserveBackupOverviewUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.region.BusinessCalendarSource
import io.github.yesidrobayo.shopledger.navigation.AppNavKey
import io.github.yesidrobayo.shopledger.navigation.AppNavigator
import io.github.yesidrobayo.shopledger.navigation.ScreenModel
import io.github.yesidrobayo.shopledger.navigation.rememberAppViewModel
import io.github.yesidrobayo.shopledger.ui.format.LocalAppFormats
import io.github.yesidrobayo.shopledger.ui.rescue.SupportFacts
import io.github.yesidrobayo.shopledger.ui.rescue.SupportInfoSheet
import io.github.yesidrobayo.shopledger.ui.rescue.SupportLine
import io.github.yesidrobayo.shopledger.ui.rescue.copyToClipboard
import io.github.yesidrobayo.shopledger.ui.rescue.supportText
import io.github.yesidrobayo.shopledger.ui.settings.SettingsOverviewViewModel
import java.io.IOException
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * `Ayuda` en la navegación. No espera a la base: los artículos están en el APK, y se leen en el
 * idioma de la interfaz, con `raw/` en inglés como respaldo. Solo `Información para soporte` mira
 * la base, para la última copia; si no abre, lo dice como en el rescate.
 *
 * Un artículo abierto desde la lista, en un teléfono, es otra entrada de la pila: atrás vuelve a
 * la lista. El tema de la pantalla de origen viaja con él.
 */
@Composable
fun HelpRoute(key: AppNavKey.Help, navigator: AppNavigator) {
    val context = LocalContext.current
    val container = (context.applicationContext as ShopLedgerApp).container
    val io = container.dispatchers.io
    val resources = LocalResources.current
    val appName = stringResource(R.string.app_name)
    val titles = HelpArticleId.entries.associateWith { helpTitle(it) }
    // Con otro idioma, otro ViewModel: los títulos y los artículos ya leídos son de un idioma.
    val language = resources.configuration.locales.toLanguageTags()
    val viewModel: HelpViewModel =
        viewModel(
            key = "help-$language",
            factory =
                viewModelFactory {
                    initializer {
                        HelpViewModel(
                            titles = titles,
                            read = { id ->
                                readArticle(resources, id)?.let { HelpMarkdown.parse(it, appName) }
                            },
                            articleKey = key.articleKey,
                            topicKey = key.topicKey,
                            computation = io,
                        )
                    }
                },
        )
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var supportOpen by rememberSaveable { mutableStateOf(false) }
    HelpScreen(uiState, titles) { action ->
        when (action) {
            HelpAction.Back -> navigator.back()

            is HelpAction.Open ->
                navigator.open(AppNavKey.Help(action.article.key, key.topicKey))

            is HelpAction.OpenTarget -> navigator.open(action.target.key)

            HelpAction.ShowSupport -> supportOpen = true

            is HelpAction.Search,
            HelpAction.ClearSearch,
            is HelpAction.Select,
            -> viewModel.onAction(action)
        }
    }
    if (supportOpen) SupportSheet(onDismiss = { supportOpen = false })
}

/**
 * `Información para soporte`, igual que en Ajustes: versión, Android, teléfono, última copia y
 * último código de error. Sin la base, la última copia dice que no está disponible. Nunca un dato
 * del negocio.
 */
@Composable
private fun SupportSheet(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val io = (context.applicationContext as ShopLedgerApp).container.dispatchers.io
    val overviewModel =
        rememberAppViewModel { dependencies ->
            val ports = dependencies.ports
            val app = dependencies.container
            SettingsOverviewViewModel(
                backupOverview = { refreshes ->
                    ObserveBackupOverviewUseCase(ports.backupStatus, app.clock)(refreshes)
                },
                describeDestination = {
                    BackupDestinationReader(app.data.backupDestination).describe()
                },
                zones = BusinessCalendarSource(ports.settings).observe().map {
                    (it as? BusinessCalendarResult.Ready)?.calendar?.zone
                },
                computation = app.dispatchers.io,
            )
        }
    val overviewViewModel = (overviewModel as? ScreenModel.Ready)?.viewModel
    val overview = overviewViewModel?.uiState?.collectAsStateWithLifecycle()?.value
    val backup = overview?.backup
    val lastBackup =
        backup?.overview?.lastSuccessAt?.let { at ->
            backup.zone?.let { LocalAppFormats.current.date.dayMonthTime(at, it) }
        }
    val lastError = backup?.overview?.failureCode?.name
    val handoff = backup?.slots == true
    val lines by produceState(emptyList<SupportLine>(), lastBackup, lastError, handoff) {
        value = withContext(io) {
            SupportFacts.ofDevice(context, lastError, lastBackup, handoff).lines(context)
        }
    }
    val label = stringResource(R.string.settings_support)
    val labels = LocalResources.current
    SupportInfoSheet(
        lines,
        onCopy = {
            copyToClipboard(
                context,
                label,
                supportText(lines) { name, value ->
                    labels.getString(R.string.support_line, name, value)
                },
            )
        },
        onDismiss = onDismiss,
    )
}

/**
 * El Markdown de [id] en el idioma de [resources], o `null` si no se pudo leer. Lee del APK: va
 * fuera del hilo principal.
 */
private fun readArticle(resources: Resources, id: HelpArticleId): String? = try {
    resources.openRawResource(id.body).bufferedReader(Charsets.UTF_8).use { it.readText() }
} catch (_: Resources.NotFoundException) {
    null
} catch (_: IOException) {
    null
}
