package io.github.yesidrobayo.shopledger.ui.onboarding

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.model.BusinessTimeZone
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupCopySummary
import io.github.yesidrobayo.shopledger.domain.model.backup.CopyOrigin
import io.github.yesidrobayo.shopledger.format.DataSizeFormatter
import io.github.yesidrobayo.shopledger.format.NO_VALUE_TEXT
import io.github.yesidrobayo.shopledger.ui.backup.BackupAction
import io.github.yesidrobayo.shopledger.ui.backup.RestoreLayer
import io.github.yesidrobayo.shopledger.ui.backup.RestoreStoppedNotice
import io.github.yesidrobayo.shopledger.ui.backup.RestoreUi
import io.github.yesidrobayo.shopledger.ui.components.ActionState
import io.github.yesidrobayo.shopledger.ui.components.ActionVariant
import io.github.yesidrobayo.shopledger.ui.components.AppBottomSheet
import io.github.yesidrobayo.shopledger.ui.components.BackupCopyRow
import io.github.yesidrobayo.shopledger.ui.components.BackupCopyRowData
import io.github.yesidrobayo.shopledger.ui.components.ConfirmDialog
import io.github.yesidrobayo.shopledger.ui.components.ConfirmVariant
import io.github.yesidrobayo.shopledger.ui.components.InlineTextButton
import io.github.yesidrobayo.shopledger.ui.components.NoticeCard
import io.github.yesidrobayo.shopledger.ui.components.NoticeVariant
import io.github.yesidrobayo.shopledger.ui.components.OnboardingStep
import io.github.yesidrobayo.shopledger.ui.components.PrimaryActionButton
import io.github.yesidrobayo.shopledger.ui.components.SettingsRow
import io.github.yesidrobayo.shopledger.ui.components.SettingsRowAction
import io.github.yesidrobayo.shopledger.ui.components.StatusTag
import io.github.yesidrobayo.shopledger.ui.components.keepsMessagesAbove
import io.github.yesidrobayo.shopledger.ui.format.LocalAppFormats
import io.github.yesidrobayo.shopledger.ui.theme.AppTheme
import kotlin.time.Instant

/** Cuántos pasos tiene el primer uso, y cuál es cada uno de los que dibuja esta pantalla. */
internal const val ONBOARDING_STEPS = 4
private const val WELCOME_STEP = 3
private const val PROTECTION_STEP = 4

/**
 * La bienvenida, paso 3 del primer uso: la promesa en el bloque de marca, lo que hace la app con
 * los datos, lo que se puede perder con cada destino, y abajo, fijos, `Elegir dónde guardar`,
 * `Traer datos de otro teléfono` y `Ahora no`.
 *
 * Es pura: recibe el estado y avisa cada acción. [zone] da la fecha de las copias encontradas.
 */
@Composable
fun WelcomeStep(
    uiState: OnboardingUiState,
    zone: BusinessTimeZone?,
    onAction: (OnboardingAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val app = stringResource(R.string.app_name)
    Surface(
        modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) { WelcomeContent(uiState, app, onAction) }
    if (uiState.bringSheet) BringSheet(onAction)
    (uiState.found as? FoundCopies.Found)?.let { found ->
        if (uiState.restore == RestoreUi.Idle && !uiState.startOver) {
            FoundSheet(found.copies, zone, onAction)
        }
    }
    if (uiState.startOver) StartOverDialog(uiState, zone, onAction)
}

@Composable
private fun WelcomeContent(
    uiState: OnboardingUiState,
    app: String,
    onAction: (OnboardingAction) -> Unit,
) {
    val spacing = AppTheme.spacing
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Hero(app)
            Column(
                Modifier
                    .align(Alignment.CenterHorizontally)
                    .widthIn(max = AppTheme.sizes.maxContentWidth)
                    .fillMaxWidth()
                    .padding(spacing.s16),
                verticalArrangement = Arrangement.spacedBy(spacing.s12),
            ) {
                Point(R.drawable.ic_smartphone, stringResource(R.string.welcome_point_local))
                Point(R.drawable.ic_cloud_upload, stringResource(R.string.welcome_point_backup))
                Point(R.drawable.ic_wifi_off, stringResource(R.string.welcome_point_offline))
                if (uiState.offline) {
                    NoticeCard(
                        stringResource(R.string.permission_destination_no_internet),
                        NoticeVariant.WARNING,
                    )
                }
                if (uiState.found == FoundCopies.Failed) {
                    NoticeCard(
                        stringResource(R.string.permission_destination_search_error),
                        NoticeVariant.ERROR,
                        actionLabel = stringResource(R.string.action_retry),
                        onAction = { onAction(OnboardingAction.RetrySearch) },
                    )
                }
                (uiState.restore as? RestoreUi.Stopped)?.let { stopped ->
                    RestoreStoppedNotice(stopped) { onAction(OnboardingAction.Restore(it)) }
                }
                NoticeCard(
                    stringResource(R.string.permission_destination_rationale, app),
                    NoticeVariant.INFO,
                )
                Small(stringResource(R.string.backup_limits_every_60_min))
                Small(
                    stringResource(
                        R.string.backup_limits_slots,
                        uiState.slotsLabel ?: NO_VALUE_TEXT,
                    ),
                )
                Small(stringResource(R.string.legal_not_accounting, app))
                Small(stringResource(R.string.legal_universal_notice, app))
                InlineTextButton(
                    stringResource(R.string.transparency_title),
                    { onAction(OnboardingAction.OpenDataTransparency) },
                )
            }
        }
        Column(
            Modifier
                .align(Alignment.CenterHorizontally)
                .widthIn(max = AppTheme.sizes.maxContentWidth)
                .fillMaxWidth()
                .keepsMessagesAbove()
                .navigationBarsPadding()
                .padding(horizontal = spacing.s16, vertical = spacing.s12),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val searching = uiState.found == FoundCopies.Searching
            PrimaryActionButton(
                text =
                    stringResource(
                        if (searching) {
                            R.string.backup_searching
                        } else {
                            R.string.backup_action_choose_destination
                        },
                    ),
                onClick = { onAction(OnboardingAction.ChooseDestination) },
                variant = ActionVariant.PRIMARY,
                state = if (searching) ActionState.WORKING else ActionState.IDLE,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(spacing.s8)) {
                InlineTextButton(
                    stringResource(R.string.onboarding_restore_action),
                    { onAction(OnboardingAction.OpenBring) },
                )
                InlineTextButton(
                    stringResource(R.string.action_not_now),
                    { onAction(OnboardingAction.NotNow) },
                )
            }
        }
    }
}

/** El bloque de marca a sangre, bajo la barra de estado: el paso, el saludo y la promesa. */
@Composable
private fun Hero(app: String) {
    val colors = AppTheme.colors
    val spacing = AppTheme.spacing
    Box(
        Modifier
            .fillMaxWidth()
            .background(colors.heroContainer)
            .statusBarsPadding(),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            Modifier
                .widthIn(max = AppTheme.sizes.maxContentWidth)
                .fillMaxWidth()
                .padding(horizontal = spacing.s24, vertical = spacing.s32),
            verticalArrangement = Arrangement.spacedBy(spacing.s12),
        ) {
            Text(
                stringResource(R.string.onboarding_step_progress, WELCOME_STEP, ONBOARDING_STEPS),
                style = MaterialTheme.typography.labelMedium,
                color = colors.onHeroVariant,
            )
            Text(
                stringResource(R.string.onboarding_welcome_title, app),
                style = MaterialTheme.typography.displaySmall,
                color = colors.onHero,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                stringResource(R.string.welcome_promise),
                style = MaterialTheme.typography.bodyLarge,
                color = colors.onHeroVariant,
            )
        }
    }
}

@Composable
private fun Point(@DrawableRes icon: Int, text: String) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = AppTheme.sizes.touchTarget),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.s16),
    ) {
        Icon(
            painterResource(icon),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(AppTheme.sizes.icon),
        )
        Text(text, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun Small(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** `¿Dónde están las copias?`: un servicio con WebDAV, o un archivo de la nube del teléfono. */
@Composable
private fun BringSheet(onAction: (OnboardingAction) -> Unit) {
    AppBottomSheet(
        onDismissRequest = { onAction(OnboardingAction.CloseBring) },
        title = stringResource(R.string.onboarding_restore_title),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.s4)) {
            SettingsRow(
                title = stringResource(R.string.destination_webdav_title),
                action = SettingsRowAction.Navigation {
                    onAction(OnboardingAction.BringWithWebDav)
                },
            )
            SettingsRow(
                title = stringResource(R.string.backup_action_find_file),
                action = SettingsRowAction.Navigation { onAction(OnboardingAction.FindFile) },
                summary = stringResource(R.string.onboarding_restore_slots_help),
            )
        }
    }
}

/** `Copias encontradas`: la más nueva arriba, cada una con su fila, `Restaurar` o `Empezar de cero`. */
@Composable
private fun FoundSheet(
    copies: List<BackupCopySummary>,
    zone: BusinessTimeZone?,
    onAction: (OnboardingAction) -> Unit,
) {
    val newest = copies.firstOrNull { it.restorable } ?: return
    AppBottomSheet(
        onDismissRequest = { onAction(OnboardingAction.AskStartOver) },
        title = null,
        action = {
            PrimaryActionButton(
                text = stringResource(R.string.backup_action_restore),
                onClick = {
                    onAction(OnboardingAction.Restore(BackupAction.ConfirmCopy(newest.key)))
                },
                variant = ActionVariant.PRIMARY,
            )
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.s8)) {
            Text(
                stringResource(
                    R.string.permission_destination_copy_found,
                    dateOf(newest.createdAt, zone),
                    originOf(newest),
                    recordsOf(newest) ?: NO_VALUE_TEXT,
                ),
                style = MaterialTheme.typography.bodyLarge,
            )
            for (copy in copies) {
                BackupCopyRow(
                    rowOf(copy, zone),
                    onClick =
                        if (copy.restorable) {
                            {
                                onAction(
                                    OnboardingAction.Restore(BackupAction.ConfirmCopy(copy.key)),
                                )
                            }
                        } else {
                            null
                        },
                )
            }
            InlineTextButton(
                stringResource(R.string.dialog_start_over_action),
                { onAction(OnboardingAction.AskStartOver) },
            )
        }
    }
}

@Composable
private fun StartOverDialog(
    uiState: OnboardingUiState,
    zone: BusinessTimeZone?,
    onAction: (OnboardingAction) -> Unit,
) {
    val newest = (uiState.found as? FoundCopies.Found)?.copies?.firstOrNull()
    ConfirmDialog(
        title = stringResource(R.string.dialog_start_over_title),
        body = stringResource(R.string.dialog_start_over_body, dateOf(newest?.createdAt, zone)),
        confirmLabel = stringResource(R.string.dialog_start_over_action),
        onConfirm = { onAction(OnboardingAction.ConfirmStartOver) },
        onDismiss = { onAction(OnboardingAction.DismissStartOver) },
        variant = ConfirmVariant.DESTRUCTIVE,
        dismissLabel = stringResource(R.string.action_cancel),
    )
}

@Composable
internal fun dateOf(instant: Instant?, zone: BusinessTimeZone?): String {
    if (instant == null || zone == null) return NO_VALUE_TEXT
    return LocalAppFormats.current.date.dayMonthTime(instant, zone)
}

@Composable
private fun originOf(copy: BackupCopySummary): String = when (copy.origin) {
    CopyOrigin.THIS_PHONE -> stringResource(R.string.backup_device_this)
    CopyOrigin.OTHER_PHONE, CopyOrigin.UNKNOWN -> stringResource(R.string.backup_device_other)
}

@Composable
private fun recordsOf(copy: BackupCopySummary): String? = copy.records?.let {
    val count = it.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
    pluralStringResource(R.plurals.backup_records_count, count, count)
}

@Composable
private fun rowOf(copy: BackupCopySummary, zone: BusinessTimeZone?): BackupCopyRowData {
    val formats = LocalAppFormats.current
    return BackupCopyRowData(
        whenText = dateOf(copy.createdAt, zone),
        sizeText = DataSizeFormatter(formats.date.formatLocale, formats.uiLocale)
            .megabytes(copy.sizeBytes),
        originText = originOf(copy),
        mostRecent = copy.newest,
        recordsText = recordsOf(copy),
        tags =
            if (copy.restorable) {
                emptyList()
            } else {
                listOf(stringResource(R.string.backup_copy_not_restorable))
            },
    )
}

/**
 * La protección, paso 4: proteger el negocio. El cifrado de las copias es opcional, va apagado, y
 * se elige en su pantalla; sin destino conectado, dice que primero hay que elegir dónde guardar.
 */
@Composable
fun ProtectionStep(uiState: OnboardingUiState, onAction: (OnboardingAction) -> Unit) {
    OnboardingStep(
        step = PROTECTION_STEP,
        totalSteps = ONBOARDING_STEPS,
        title = stringResource(R.string.onboarding_protection_title),
        action = {
            PrimaryActionButton(
                text = stringResource(R.string.action_continue),
                onClick = { onAction(OnboardingAction.Finish) },
                variant = ActionVariant.PRIMARY,
            )
        },
    ) {
        Column(
            Modifier.fillMaxWidth().semantics(mergeDescendants = true) {},
            verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.s4),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.s8),
            ) {
                Text(
                    stringResource(R.string.onboarding_encrypt_row),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                StatusTag(stringResource(R.string.onboarding_encrypt_optional))
            }
            Small(
                stringResource(
                    if (uiState.connected) {
                        R.string.onboarding_encrypt_help
                    } else {
                        R.string.onboarding_encrypt_needs_destination
                    },
                ),
            )
        }
        if (uiState.saveFailed) {
            NoticeCard(stringResource(R.string.state_error_save), NoticeVariant.ERROR)
        }
    }
}

/** La restauración del primer uso, sobre la pantalla: la misma de la pantalla del respaldo. */
@Composable
internal fun OnboardingRestoreLayer(
    uiState: OnboardingUiState,
    zone: BusinessTimeZone?,
    onAction: (OnboardingAction) -> Unit,
) {
    RestoreLayer(
        restore = uiState.restore,
        rowOf = { rowOf(it, zone) },
        dateOf = { dateOf(it, zone) },
        onAction = { onAction(OnboardingAction.Restore(it)) },
    )
}
