package io.github.yesidrobayo.shopledger.ui.onboarding

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.ShopLedgerApp
import io.github.yesidrobayo.shopledger.domain.usecase.backup.BackupDestinationReader
import io.github.yesidrobayo.shopledger.domain.usecase.backup.PrepareSlotFilesUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.backup.RestoreBackupUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.onboarding.CompleteOnboardingUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.onboarding.ResumeOnboardingUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.region.BusinessCalendarSource
import io.github.yesidrobayo.shopledger.localization.InstalledLanguages
import io.github.yesidrobayo.shopledger.navigation.AppNavKey
import io.github.yesidrobayo.shopledger.navigation.AppNavigator
import io.github.yesidrobayo.shopledger.navigation.PlaceholderRoute
import io.github.yesidrobayo.shopledger.navigation.ScreenModel
import io.github.yesidrobayo.shopledger.navigation.rememberAppViewModelWithState
import io.github.yesidrobayo.shopledger.ui.backup.BackupAction
import io.github.yesidrobayo.shopledger.ui.backup.RestoreUi
import io.github.yesidrobayo.shopledger.ui.backup.restartForRestore
import io.github.yesidrobayo.shopledger.ui.components.ActionVariant
import io.github.yesidrobayo.shopledger.ui.components.LanguagePicker
import io.github.yesidrobayo.shopledger.ui.components.OnboardingStep
import io.github.yesidrobayo.shopledger.ui.components.PrimaryActionButton
import io.github.yesidrobayo.shopledger.ui.i18n.LanguageSettingsAction
import io.github.yesidrobayo.shopledger.ui.i18n.LanguageSettingsViewModel
import io.github.yesidrobayo.shopledger.ui.i18n.languageFacts
import io.github.yesidrobayo.shopledger.ui.region.RegionalSettingsRoute

/**
 * El primer uso en la navegación: cuatro pasos encima de Inicio, que se cierran al terminar.
 *
 * Atrás vuelve al paso anterior; en el primero, sale de la app, como en cualquier inicio. Elegir
 * dónde guardar y traer datos de otro teléfono siguen el orden para conectar, con la política
 * antes; al volver, la pantalla busca las copias del servicio conectado.
 */
@Composable
fun OnboardingRoute(navigator: AppNavigator) {
    val model =
        rememberAppViewModelWithState { dependencies, savedState ->
            val ports = dependencies.ports
            val container = dependencies.container
            OnboardingViewModel(
                resume = ResumeOnboardingUseCase(ports.settings)::invoke,
                complete =
                    CompleteOnboardingUseCase(
                        ports.transactions,
                        ports.settings,
                        container.clock,
                    )::invoke,
                destination = BackupDestinationReader(container.data.backupDestination),
                slotsLabel = {
                    PrepareSlotFilesUseCase(container.data.slotFilesSetup).look()?.providerLabel
                },
                calendar = BusinessCalendarSource(ports.settings).observe(),
                restore = RestoreBackupUseCase(container.data.backupRestore),
                savedState = savedState,
                computation = container.dispatchers.io,
            )
        }
    when (model) {
        ScreenModel.Opening -> Box(Modifier.fillMaxSize())

        ScreenModel.Unavailable -> PlaceholderRoute(AppNavKey.Onboarding, navigator)

        is ScreenModel.Ready -> {
            val viewModel = model.viewModel
            val uiState by viewModel.uiState.collectAsStateWithLifecycle()
            val context = LocalContext.current
            val onAction: (OnboardingAction) -> Unit = viewModel::onAction
            val picker =
                rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                    uri?.let {
                        onAction(OnboardingAction.Restore(BackupAction.FilePicked(it.toString())))
                    }
                }
            LaunchedEffect(uiState.done) {
                if (uiState.done && navigator.current == AppNavKey.Onboarding) navigator.back()
            }
            LaunchedEffect(uiState.restore) {
                if (uiState.restore == RestoreUi.Ready) restartForRestore(context)
            }
            LifecycleResumeEffect(viewModel) {
                viewModel.onAction(OnboardingAction.Resumed)
                onPauseOrDispose {}
            }
            // Atrás: el paso anterior, o fuera de la app desde el primero.
            BackHandler {
                if (uiState.step == OnboardingStepId.LANGUAGE) {
                    context.findActivity()?.finish()
                } else {
                    onAction(OnboardingAction.Back)
                }
            }
            val route: (OnboardingAction) -> Unit = { action ->
                onAction(action)
                when (action) {
                    OnboardingAction.ChooseDestination, OnboardingAction.BringWithWebDav ->
                        navigator.open(AppNavKey.BackupDestinationChoice(firstRun = true))

                    OnboardingAction.FindFile -> picker.launch(arrayOf(ANY_FILE))

                    OnboardingAction.OpenDataTransparency -> navigator.open(
                        AppNavKey.DataTransparency,
                    )

                    is OnboardingAction.Restore ->
                        if (action.action == BackupAction.FindFile) picker.launch(arrayOf(ANY_FILE))

                    OnboardingAction.LanguageDone,
                    OnboardingAction.RegionDone,
                    OnboardingAction.Back,
                    OnboardingAction.OpenBring,
                    OnboardingAction.CloseBring,
                    OnboardingAction.NotNow,
                    OnboardingAction.Resumed,
                    OnboardingAction.RetrySearch,
                    OnboardingAction.AskStartOver,
                    OnboardingAction.ConfirmStartOver,
                    OnboardingAction.DismissStartOver,
                    OnboardingAction.Finish,
                    -> Unit
                }
            }
            Box(Modifier.fillMaxSize()) {
                when (uiState.step) {
                    null -> Unit

                    OnboardingStepId.LANGUAGE -> LanguageStep {
                        route(OnboardingAction.LanguageDone)
                    }

                    OnboardingStepId.REGION ->
                        RegionalSettingsRoute(
                            firstRun = true,
                            onBack = { route(OnboardingAction.Back) },
                            onConfirmed = { route(OnboardingAction.RegionDone) },
                            visit = uiState.regionVisit,
                        )

                    OnboardingStepId.WELCOME -> WelcomeStep(uiState, uiState.zone, route)

                    OnboardingStepId.PROTECTION -> ProtectionStep(uiState, route)
                }
                OnboardingRestoreLayer(uiState, uiState.zone, route)
            }
        }
    }
}

/**
 * El idioma, paso 1: el del teléfono preseleccionado, o inglés si no está. Tocar uno lo aplica al
 * instante; `Continuar` sigue a la región.
 */
@Composable
private fun LanguageStep(onContinue: () -> Unit) {
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
    OnboardingStep(
        step = 1,
        totalSteps = ONBOARDING_STEPS,
        title = stringResource(R.string.onboarding_step_language_title),
        action = {
            PrimaryActionButton(
                text = stringResource(R.string.action_continue),
                onClick = onContinue,
                variant = ActionVariant.PRIMARY,
            )
        },
    ) {
        LanguagePicker(
            options = facts.options,
            selected = uiState.selected,
            phoneLanguage = facts.phoneLanguage,
            onSelect = { viewModel.onAction(LanguageSettingsAction.Choose(it)) },
        )
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/** Cualquier tipo de archivo en el selector: una copia puede llegar sin un tipo conocido. */
private const val ANY_FILE = "*/*"
